package dev.mnaoumov.asc

import android.graphics.PointF
import android.os.Handler

/** A press on the pad: move the active edge, by this much, in this direction. */
enum class PadCommand(val unit: Unit, val toRight: Boolean) {
  CHAR_LEFT(Unit.CHARACTER, false),
  CHAR_RIGHT(Unit.CHARACTER, true),
  WORD_LEFT(Unit.WORD, false),
  WORD_RIGHT(Unit.WORD, true),
  PAGE_UP(Unit.PAGE, false),
  PAGE_DOWN(Unit.PAGE, true),
  DOC_START(Unit.DOCUMENT, false),
  DOC_END(Unit.DOCUMENT, true);

  enum class Unit { CHARACTER, WORD, PAGE, DOCUMENT }
}

/** What a press achieved, for the pad to show and for the loop to reason about. */
sealed interface Outcome {
  data class Moved(val fromOffset: Int, val toOffset: Int) : Outcome
  data object NoSelection : Outcome
  data object HandleLost : Outcome
  data class Degraded(val reason: String) : Outcome
}

/** The gestures the driver needs, kept behind an interface so it does not depend on the service. */
interface GestureDispatcher {
  fun drag(from: PointF, to: PointF, durationMs: Long, onFinished: (Boolean) -> kotlin.Unit)
  fun dragAndHold(from: PointF, to: PointF, dragMs: Long, holdMs: Long, onFinished: (Boolean) -> kotlin.Unit)
  fun screenWidth(): Int
  fun screenHeight(): Int

  /**
   * Press the handle at [from], travel to [to], and **do not lift** (the held-pointer fix).
   *
   * The whole reason this exists: a released drag's landed offset is a function of the final pixel
   * AND the lift — the app snaps the handle somewhere of its own choosing when the finger comes up,
   * which is what the released loop spends two to seven gestures undoing. Held, it does not snap
   * (measured: three links landed `10..12` and it was still `10..12` after the lift and 900 ms), so
   * a correction made while down is the last correction needed.
   */
  fun grabAndHold(from: PointF, to: PointF, onGrabbed: (Boolean) -> kotlin.Unit)
  // [onGrabbed] reports when the grab has PLAYED, not when it was accepted — a caller that starts
  // waiting for an announcement before the stroke has run always times out.

  /**
   * Move the pointer that [grabAndHold] pressed. False when nothing is held, which is a real answer
   * and not a formality — a caller that ignores it goes on issuing moves to a chain that has died.
   *
   * Silent about the result: read that from the selection stream, as everything else here does.
   */
  fun moveHeld(to: PointF): Boolean

  /** Lift, at the end of the link in flight. Harmless when nothing is held. */
  fun releaseHeld()

  /** Where the held pointer is, or null when nothing is down. */
  fun heldAt(): PointF?
}

/**
 * The closed loop: one press moves the selection one unit, reading the result and correcting.
 *
 * The granularity rule this is built on was measured, not assumed, and it is **directional**:
 * growing a selection moves it a whole **word**, shrinking it moves one **character**. Every command
 * below is a consequence of that asymmetry rather than a separate mechanism.
 */
class SelectionDriver(
  private val gestures: GestureDispatcher,
  private val observer: SelectionObserver,
  private val locator: HandleLocator,
  private val handler: Handler,
  private val toolbarCentre: () -> Float?,
) {

  /**
   * Whether a selection still appears to exist on screen, independently of anything announced.
   *
   * This is a **safety** check, not an optimisation, and it exists because of damage done during
   * testing: a drag that misses the handle does not fail quietly — it lands on the page as a tap,
   * and on a link it NAVIGATES, losing the page the user was reading. A miss produces no selection
   * event, so silence alone cannot distinguish "not far enough" from "I just clicked something",
   * and the escalation would keep poking. The floating toolbar disappearing is the signal that the
   * selection is gone and there is nothing left to reach for.
   */
  private fun selectionStillOnScreen(): Boolean = toolbarCentre() != null

  /** The edge the pad is moving. The other one is the anchor and stays put. */
  var activeEdge: Edge = Edge.END

  /**
   * Offsets the selection has been seen to land on while GROWING, which are exactly word boundaries,
   * because a grow snaps to one. This is how `word ←` finds the previous boundary **without reading
   * any text**: retrace to the largest remembered boundary below where we are.
   *
   * Cleared whenever the source node changes, since offsets are local to it and mean nothing across
   * a boundary.
   */
  private val knownBoundaries = sortedSetOf<Int>()
  private var boundariesNodeKey: String? = null

  /** Gestures spent on the press in flight — the number that says whether a press feels slow. */
  private var gestureCount = 0

  fun perform(command: PadCommand, onDone: (Outcome) -> Unit) {
    val startedAt = android.os.SystemClock.uptimeMillis()
    gestureCount = 0

    val snapshot = observer.latest
    if (snapshot == null || snapshot.isEmpty()) {
      Diag.log("$command: nothing announced — the pad cannot see a selection")
      onDone(Outcome.NoSelection)
      return
    }
    rememberBoundaryContext(snapshot)
    Diag.log(
      "$command edge=$activeEdge at ${snapshot.low()}..${snapshot.high()} " +
        "srcLen=${snapshot.sourceLength} oneLine=${snapshot.sourceIsOneLine()} " +
        "boundaries=$knownBoundaries"
    )

    val report: (Outcome) -> Unit = { outcome ->
      Diag.log(
        "$command -> $outcome in ${android.os.SystemClock.uptimeMillis() - startedAt}ms, " +
          "$gestureCount gesture(s)"
      )
      onDone(outcome)
    }

    val growing = (activeEdge == Edge.END) == command.toRight
    when (command.unit) {
      PadCommand.Unit.WORD -> if (growing) growOneUnit(command, onDone = report) else shrinkByWord(command, report)
      // Both directions go through the held chain now (the held-pointer fix): it is exact where the released path
      // has to undo a snap, and it falls back to the released path by itself when a chain will not
      // run. [releasedCharacterStep] is what it falls back to.
      PadCommand.Unit.CHARACTER -> heldCharacterStep(command, report)
      PadCommand.Unit.PAGE -> sweepToEdge(command, PAGE_HOLD_MS, report)
      PadCommand.Unit.DOCUMENT -> sweepToEdge(command, DOCUMENT_HOLD_MS, report)
    }
  }

  /**
   * One drag of the active handle, escalating the reach until the announced range changes.
   *
   * The escalation is not a retry loop dressed up: **the distance the finger must travel is the
   * width of the next word**, because the app snaps. A four-character word moved at 48 px while a
   * nine-character one needed 108, so a fixed step cannot work and the cap has to exceed the widest
   * word on screen.
   */
  private fun growOneUnit(command: PadCommand, attempt: Int = 0, onDone: (Outcome) -> Unit) {
    val before = observer.latestWithFreshBounds()
    if (before == null) {
      onDone(Outcome.NoSelection)
      return
    }
    val handle = locator.locate(before, activeEdge, toolbarCentre())
    if (handle == null) {
      acquireThenRetry(command, onDone)
      return
    }

    // Escalate in characters rather than in a fixed pixel count: the distance that matters is the
    // width of the next word, and the node's own geometry says how wide a character is here. A
    // constant step wastes attempts on wide text and overshoots on narrow.
    val step = (pixelsPerCharacter(before) * CHARS_PER_ATTEMPT).coerceAtLeast(STEP_PX)
    val reach = step * (attempt + 1) * (if (command.toRight) 1 else -1)
    gestureCount++
    gestures.drag(handle, PointF(handle.x + reach, handle.y), DRAG_MS) { completed ->
      if (!completed) {
        onDone(Outcome.HandleLost)
        return@drag
      }
      awaitChange(before) { after ->
        Diag.log(
          "  grow try ${attempt + 1}: handle=(${handle.x}, ${handle.y}) reach=$reach -> " +
            if (after == null) "nothing" else "${after.low()}..${after.high()} bounds=${after.bounds}"
        )
        when {
          after != null && locator.grabbedAHandle(before, after) -> {
            recordBoundary(after)
            locator.rememberAnchor(handle.x - reach.coerceAtLeast(0f))
            onDone(Outcome.Moved(before.high(), after.high()))
          }
          after != null -> onDone(Outcome.HandleLost)
          // Silence is ambiguous: the reach may be too short, or that drag may have landed on the
          // page and taken the selection (or the whole page) with it. Never escalate past the point
          // where a selection still visibly exists.
          !selectionStillOnScreen() -> {
            Diag.log("  grow: the selection is gone from screen — stopping rather than poking the page")
            onDone(Outcome.HandleLost)
          }
          attempt + 1 < MAX_ATTEMPTS -> growOneUnit(command, attempt + 1, onDone)
          else -> onDone(Outcome.HandleLost)
        }
      }
    }
  }

  /**
   * A character step in the growing direction, which the target app will not do: growing always
   * snaps a whole word. So overshoot by one word and walk back — shrinking IS character-granular —
   * until the offset is one past where we started. Several gestures for one press, but exact, and
   * self-correcting because every step is read back.
   */
  private fun growOneCharacter(
    command: PadCommand,
    onDone: (Outcome) -> Unit,
    retriesLeft: Int = MAX_STEP_RETRIES,
  ) {
    val beforeGrow = observer.latest ?: run {
      onDone(Outcome.NoSelection)
      return
    }
    val start = beforeGrow.high()

    growOneUnit(command) { outcome ->
      if (outcome !is Outcome.Moved) {
        onDone(outcome)
        return@growOneUnit
      }
      val afterGrow = observer.latest ?: run {
        onDone(Outcome.NoSelection)
        return@growOneUnit
      }

      /*
       * Offsets are local to the source node, and a grow can carry the end into the NEXT node —
       * after which `start + 1` is a number in a frame of reference that no longer exists. When the
       * node has changed, one character past the old end is simply one character into the new one.
       */
      val crossed = beforeGrow.source != null && afterGrow.source != null &&
        beforeGrow.source != afterGrow.source
      val target = when {
        crossed -> if (command.toRight) 1 else afterGrow.sourceLength - 1
        command.toRight -> start + 1
        else -> start - 1
      }

      // A grow that happened to move exactly one character (a lone space, say) is already the answer.
      if (afterGrow.high() == target) {
        onDone(Outcome.Moved(start, target))
        return@growOneUnit
      }
      walkBackTo(target, command, start, onDone, retriesLeft = retriesLeft)
    }
  }

  /**
   * Where one character past [start] actually is, which is not always [start] + 1.
   *
   * Offsets are local to the source node, and a step can carry the edge into the NEXT node — after
   * which `start + 1` is a number in a frame of reference that no longer exists. When the node has
   * changed, one character past the old end is simply one character into the new one.
   */
  private fun targetOffset(
    before: SelectionObserver.Snapshot,
    after: SelectionObserver.Snapshot,
    command: PadCommand,
    start: Int,
  ): Int {
    val crossed = before.source != null && after.source != null && before.source != after.source
    return when {
      crossed -> if (command.toRight) 1 else after.sourceLength - 1
      command.toRight -> start + 1
      else -> start - 1
    }
  }

  /**
   * A character step whose finger never lifts — the fast path, and the point of the held-pointer fix.
   *
   * **Why this is faster, measured rather than hoped.** A released drag's landed offset is a
   * function of the final pixel AND the lift: the app snaps the handle to a boundary of its own
   * choosing when the finger comes up, and undoing that snap is what costs the released path two to
   * seven gestures per character. Held, there is no snap — three links landed 10..12 and it was
   * still 10..12 after the lift and 900 ms of settle — so a correction made while down is the last
   * correction needed. Five whole grab-move-lift presses measured 318–329 ms against 0.7–2.3 s.
   *
   * **And the pointer IS the handle.** Once grabbed, its position is known rather than interpolated
   * from node geometry, so every correction after the first is exact and needs no re-derivation.
   * That is what removes the escalation, not a shorter timeout.
   *
   * The first travel differs by direction because the target app's granularity does:
   * - **Shrinking** is character-granular everywhere measured, so aim at the target directly and one
   *   link is usually the whole step.
   * - **Growing** snaps to a word in Chrome even while held (measured: 8 to 10 to 18, straight
   *   across a word boundary), though a plain `TextView` steps by character in both directions. So
   *   overshoot as the released path does and let [heldCorrect] walk back — held, and therefore once.
   *
   * Falls back to the released path rather than failing whenever the chain will not start or does
   * not survive: it is slower, it works, and a held pointer is not worth a regression.
   */
  private fun heldCharacterStep(command: PadCommand, onDone: (Outcome) -> Unit) {
    val before = observer.latestWithFreshBounds()
    if (before == null) {
      onDone(Outcome.NoSelection)
      return
    }
    val handle = locator.locate(before, activeEdge, toolbarCentre())
    if (handle == null) {
      acquireThenRetry(command, onDone)
      return
    }
    val start = before.high()
    val perChar = pixelsPerCharacter(before)
    val growing = (activeEdge == Edge.END) == command.toRight

    /*
     * Aim at ONE character, in either direction — not at [CHARS_PER_ATTEMPT].
     *
     * The released path overshoots by half a character on purpose, because on a word-snapping target
     * the distance that matters is the width of the next WORD and a short reach simply fails. Held,
     * that costs accuracy on the targets where growing is character-granular: 1.5 characters from
     * mid-character lands on +2, and the press then needs a correction it should never have needed.
     * Measured, repeatedly, as the one press in eight that moved two characters.
     *
     * Aiming at one is safe because failure is cheap here: a word-snapping target announces nothing
     * for a reach this short, and [heldCharacterStep] falls back to the released path, which
     * escalates properly.
     *
     * **A whole character in BOTH directions**, with no [BOUNDARY_BIAS] backed off the first travel.
     * Shrinking was tried at `1 - BOUNDARY_BIAS`, on the released path's reasoning that a floor
     * rounds down at a boundary — and it landed too close to that boundary to hold: every press
     * announced `21 -> 20` and the selection was back at 21 by the next press, ten times running,
     * while the growing direction with a full character was exact ten times running. The bias still
     * applies to [heldCorrect], which aims from a pointer whose position is known rather than
     * interpolated.
     */
    val characters = 1f
    val reach = (if (command.toRight) 1f else -1f) * (perChar * characters).coerceAtLeast(STEP_PX)

    gestureCount++
    gestures.grabAndHold(handle, PointF(handle.x + reach, handle.y)) { grabbed ->
      if (!grabbed) {
        Diag.log("  held: the grab was refused — falling back to the released path")
        releasedCharacterStep(command, onDone)
        return@grabAndHold
      }
      awaitChange(before) { after ->
        Diag.log(
          "  held grab: handle=(${handle.x}, ${handle.y}) reach=$reach -> " +
            if (after == null) "nothing" else "${after.low()}..${after.high()}"
        )
        when {
          /*
           * Nothing announced, so the grab may have missed the handle and be resting on the page.
           * Let go BEFORE deciding anything: a held pointer sitting on a page is worse than no
           * pointer, and the released path re-derives the handle from scratch anyway.
           */
          after == null -> {
            gestures.releaseHeld()
            if (selectionStillOnScreen()) {
              releasedCharacterStep(command, onDone)
            } else {
              Diag.log("  held: the selection is gone from screen — stopping rather than poking the page")
              onDone(Outcome.HandleLost)
            }
          }
          !locator.grabbedAHandle(before, after) -> {
            gestures.releaseHeld()
            onDone(Outcome.HandleLost)
          }
          else -> {
            recordBoundary(after)
            locator.rememberAnchor(handle.x - reach.coerceAtLeast(0f))
            heldCorrect(targetOffset(before, after, command, start), start, command, onDone)
          }
        }
      }
    }
  }

  /**
   * Walk the HELD pointer onto [target], and lift only once it is there.
   *
   * The released [walkBackTo] has to re-locate the handle for every correction, because the last
   * drag lifted and the app moved the handle afterwards. Here the pointer never lifted, so
   * [GestureDispatcher.heldAt] IS the handle, and each correction is measured from a pixel that is
   * known rather than inferred. That is why this is bounded at [MAX_HELD_CORRECTIONS] where the
   * released path needs [MAX_CORRECTIONS].
   *
   * Every exit lifts. A chain left down would keep the target app in a drag for ever, and the next
   * press would find a pointer the app has already stopped following.
   */
  private fun heldCorrect(
    target: Int,
    origin: Int,
    command: PadCommand,
    onDone: (Outcome) -> Unit,
    guard: Int = 0,
  ) {
    val before = observer.latestWithFreshBounds()
    // Mirrors [walkBackTo]: `high()` is read for both edges. That is wrong for the START edge and is
    // tracked as the swap-edge fix; matching it here keeps one bug rather than two different ones.
    val current = before?.high()
    if (before == null || current == null) {
      gestures.releaseHeld()
      onDone(Outcome.NoSelection)
      return
    }
    if (current == target) {
      gestures.releaseHeld()
      onDone(Outcome.Moved(origin, current))
      return
    }
    if (guard >= MAX_HELD_CORRECTIONS) {
      Diag.log("  held: out of corrections at $current, wanted $target")
      gestures.releaseHeld()
      onDone(Outcome.Moved(origin, current))
      return
    }

    val at = gestures.heldAt()
    if (at == null) {
      // The chain died under us — which it now SAYS, under the same held: prefix as everything else.
      // The released path can still finish from wherever the selection actually is.
      Diag.log("  held: nothing is held any more — finishing on the released path")
      walkBackTo(target, command, origin, onDone)
      return
    }

    val perChar = pixelsPerCharacter(before)
    // Shrinking the END edge means moving left; the START edge, right.
    val direction = if (activeEdge == Edge.END) -1f else 1f
    val toLose = current - target
    /*
     * Aim AT the target and land INSIDE its cell — the offset comes out as a floor, so a finger on
     * the boundary rounds down and the bias backs off into the target's own cell.
     *
     * The bias has to point TOWARDS the target, which means following the sign of [toLose]. Written
     * as a bare `toLose - BOUNDARY_BIAS` it is right when shrinking and wrong when correcting an
     * overshoot: at `toLose = -1` it asks for 1.35 characters instead of 0.65 and sails straight
     * past the target again. Measured, on the press that ended two characters out.
     */
    val bias = if (toLose >= 0) BOUNDARY_BIAS else -BOUNDARY_BIAS
    val reach = direction * (toLose - bias) * perChar

    gestureCount++
    if (!gestures.moveHeld(PointF(at.x + reach, at.y))) {
      Diag.log("  held: the move was refused — finishing on the released path")
      walkBackTo(target, command, origin, onDone)
      return
    }
    awaitChange(before) { after ->
      Diag.log(
        "  held correct ${guard + 1}: $current -> target $target, lose $toLose x ${perChar}px, " +
          "at=${at.x} reach=$reach -> " +
          if (after == null) "nothing" else "${after.low()}..${after.high()}"
      )
      if (after != null && !locator.grabbedAHandle(before, after)) {
        gestures.releaseHeld()
        onDone(Outcome.HandleLost)
        return@awaitChange
      }
      if (after == null) {
        /*
         * The move was made and the news has not arrived. **Wait; do not move again.**
         *
         * This is where the held path differs from the released one, and getting it wrong cost a
         * character. Released, a silent gesture really did nothing, so trying again is right. Held,
         * the pointer HAS travelled — silence only means the announcement is late — and correcting
         * again recomputes the same reach from the same stale offset and applies it to a pointer
         * that already moved. Measured: two corrections of -18 px on one reading of `18`, landing
         * at 16 instead of 17, and the press finished two characters out.
         */
        Diag.log("  held: no news yet — waiting rather than correcting a second time")
        awaitChange(before) { later ->
          if (later == null) {
            gestures.releaseHeld()
            onDone(Outcome.Moved(origin, current))
          } else {
            heldCorrect(target, origin, command, onDone, guard + 1)
          }
        }
        return@awaitChange
      }
      heldCorrect(target, origin, command, onDone, guard + 1)
    }
  }

  /** The pre-the held-pointer fix character step, kept as the fallback for whenever a chain will not run. */
  private fun releasedCharacterStep(command: PadCommand, onDone: (Outcome) -> Unit) {
    val growing = (activeEdge == Edge.END) == command.toRight
    if (growing) growOneCharacter(command, onDone) else growOneUnit(command, onDone = onDone)
  }

  /**
   * Shrink until the offset is [target].
   *
   * **In as few drags as possible, not one per character.** Shrinking tracks the finger, so the
   * distance to travel is (characters to lose) × (pixels per character), and the node gives the
   * second factor directly: its width over its length. One drag usually lands it; the loop exists to
   * correct the estimate, not to walk.
   *
   * Doing this one character at a time is what made a single `char →` press take about eight
   * seconds — every step paying a dispatch plus a settle to move one character.
   */
  private fun walkBackTo(
    target: Int,
    command: PadCommand,
    origin: Int,
    onDone: (Outcome) -> Unit,
    guard: Int = 0,
    retriesLeft: Int = MAX_STEP_RETRIES,
  ) {
    val before = observer.latestWithFreshBounds()
    val current = before?.high()
    if (before == null || current == null) {
      onDone(Outcome.NoSelection)
      return
    }
    if (current == target || guard >= MAX_CORRECTIONS) {
      onDone(Outcome.Moved(origin, current))
      return
    }

    val toLose = current - target
    if (toLose < 0) {
      /*
       * Past the target, and this edge cannot creep back: outward is a GROW and a grow snaps a whole
       * word. Correcting upward by hand is what made the loop oscillate 31 → 29 → 31 → 29 until it
       * destroyed the selection, so that is still not an option.
       *
       * What IS an option is starting the press over from here. The overshoot left the selection at
       * a perfectly good offset, one short of where it was headed, so another grow-and-walk-back
       * aims at the same place from a character closer — and it converges, because each retry aims
       * absolutely rather than stepping. Bounded, because a press that keeps missing must end.
       */
      if (retriesLeft > 0 && selectionStillOnScreen()) {
        Diag.log("  shrink: overshot to $current past $target; starting the step over from here")
        growOneCharacter(command, onDone, retriesLeft - 1)
      } else {
        Diag.log("  shrink: overshot to $current past $target and out of retries")
        onDone(Outcome.Moved(origin, current))
      }
      return
    }

    val perChar = pixelsPerCharacter(before)
    // Shrinking the END edge means moving left; the START edge, right.
    val direction = if (activeEdge == Edge.END) -1f else 1f
    val handle = locator.locate(before, activeEdge, toolbarCentre())
    if (handle == null) {
      onDone(Outcome.HandleLost)
      return
    }

    /*
     * Aim AT the target, not short of it.
     *
     * The old version deliberately undershot by a character and then crept, on the theory that a
     * line-average width could not be trusted in proportional text. The measured behaviour says
     * otherwise, and says it precisely: the handle lands on the character under the finger, and the
     * offset comes out as `floor((x − left) / perChar)` to within a character. Two traces from the
     * same node, both one drag:
     *
     *     aim at 16: x = 712.0 → landed 16      aim at 17: x = 733.875 → landed 17
     *
     * What the creep did instead was step a *fixed* 0.55 × perChar from wherever it happened to be
     * — 24 px where a character was 21.9 — and floor-rounding turned that into two characters. The
     * loop then refused to correct upward (a grow snaps a whole word) and stopped, so `char →` on a
     * one-line node reported `Moved(16, 16)`: a press that cost 1.36 s, four gestures, and moved
     * nothing. Pressed again it did exactly the same thing, for ever. That is the whole of "still
     * cannot select char by char".
     *
     * Aiming at the target keeps every probe absolute, so an error in `perChar` shows up as landing
     * on a neighbouring offset and the next iteration corrects from the new position rather than
     * compounding a relative step.
     */
    /*
     * ...and land INSIDE the target character, not on its boundary.
     *
     * The offset comes out as a floor, so a finger placed exactly on the boundary between characters
     * 19 and 20 can round either way, and measured it rounded down: aiming at x = 799.5 for offset
     * 20 returned 19, twice in a row, and the press burned seven gestures getting nowhere. Backing
     * off a fraction of a character puts the finger clearly within the target's own cell.
     */
    val reach = direction * (toLose - BOUNDARY_BIAS) * perChar +
      // A probe that moved nothing was too short to leave the character it started in; lengthen it
      // rather than repeat it.
      direction * guard * perChar * FINAL_STEP_FRACTION

    gestureCount++

    gestures.drag(handle, PointF(handle.x + reach, handle.y), DRAG_MS) { completed ->
      if (!completed) {
        onDone(Outcome.HandleLost)
        return@drag
      }
      awaitChange(before) { after ->
        Diag.log(
          "  shrink ${guard + 1}: $current -> target $target, lose $toLose x ${perChar}px, " +
            "handle=(${handle.x}, ${handle.y}) reach=$reach -> " +
            if (after == null) "nothing" else "${after.low()}..${after.high()}"
        )
        when {
          // Nothing moved: the creep was too short. Try again slightly longer rather than give up
          // one character out.
          after == null ->
            if (guard + 1 < MAX_CORRECTIONS && selectionStillOnScreen()) {
              walkBackTo(target, command, origin, onDone, guard + 1, retriesLeft)
            } else {
              onDone(Outcome.Moved(origin, current))
            }
          !locator.grabbedAHandle(before, after) -> onDone(Outcome.HandleLost)
          else -> walkBackTo(target, command, origin, onDone, guard + 1, retriesLeft)
        }
      }
    }
  }

  /**
   * How wide one character is, from the node's own geometry — a width divided by a length, never a
   * look at the characters. Only meaningful where the box is a single line; elsewhere fall back to
   * the probe step and let the correction loop do the work.
   */
  private fun pixelsPerCharacter(snapshot: SelectionObserver.Snapshot): Float {
    val bounds = snapshot.bounds
    if (!snapshot.sourceIsOneLine() || bounds == null || snapshot.sourceLength <= 0) return STEP_PX
    return (bounds.width().toFloat() / snapshot.sourceLength).coerceIn(MIN_CHAR_PX, MAX_CHAR_PX)
  }

  /**
   * Wait for the next announcement rather than sleeping a fixed span.
   *
   * The event usually lands well inside the old fixed settle, and paying that settle on every one of
   * a dozen gestures is most of why a press felt slow. Polling costs nothing and stops as soon as the
   * answer arrives; the deadline is what distinguishes "not yet" from "never".
   */
  private fun awaitChange(
    before: SelectionObserver.Snapshot?,
    waited: Long = 0,
    onResult: (SelectionObserver.Snapshot?) -> Unit,
  ) {
    val after = observer.latest
    if (after != null && after.atMs != before?.atMs) {
      // A change is not the answer — the LAST change is. See awaitQuiet.
      awaitQuiet(after, 0, onResult)
      return
    }
    if (waited >= MAX_SETTLE_MS) {
      onResult(null)
      return
    }
    handler.postDelayed({ awaitChange(before, waited + POLL_MS, onResult) }, POLL_MS)
  }

  /**
   * Wait for the announcements to stop, and take the last one.
   *
   * A drag emits a selection event **while the finger is still down**, and the target app finalises
   * on lift by snapping the handle to the nearest character — which is often not where the
   * mid-gesture event said it was. Acting on the first event therefore leaves the loop believing
   * something the app has since revised.
   *
   * Measured, and it is not subtle: a shrink announced `20..30`, the loop reported `29 → 30`, and
   * the very next press opened at `20..29`. Every press after that repeated the same step forever,
   * because the pad kept acting on a number the app had already taken back.
   */
  private fun awaitQuiet(
    last: SelectionObserver.Snapshot,
    quietFor: Long,
    onResult: (SelectionObserver.Snapshot?) -> Unit,
    totalMs: Long = 0,
  ) {
    val now = observer.latest
    if (now != null && now.atMs != last.atMs) {
      awaitQuiet(now, 0, onResult, totalMs)
      return
    }
    /*
     * A total cap as well as a quiet one, because "wait for silence" has no end if the silence never
     * comes. A selection that flaps — a held pointer idling right on a character boundary will do it
     * — announces a change every tick, resets [quietFor] every time, and the press never finishes:
     * measured once at 3.2 s for a step that should have cost 330 ms. The flapping itself is fixed
     * elsewhere; this is the guard that stops the next cause of it costing a press instead of a
     * measurement.
     */
    if (quietFor >= QUIET_MS || totalMs >= MAX_QUIET_WAIT_MS) {
      if (totalMs >= MAX_QUIET_WAIT_MS) Diag.log("  settle: never went quiet in ${totalMs}ms; taking the last")
      onResult(last)
      return
    }
    handler.postDelayed({ awaitQuiet(last, quietFor + POLL_MS, onResult, totalMs + POLL_MS) }, POLL_MS)
  }

  /**
   * A word step in the shrinking direction. The app offers no such step — shrinking moves one
   * character — and finding the previous boundary in the text would break the promise this project
   * is built on. So use the boundaries we have already been shown: every grow landed on one.
   */
  private fun shrinkByWord(command: PadCommand, onDone: (Outcome) -> Unit) {
    val current = observer.latest?.high() ?: run {
      onDone(Outcome.NoSelection)
      return
    }
    val target = knownBoundaries.filter { it < current }.maxOrNull()
    if (target == null) {
      // Honest degradation: one character, and say so rather than pretend it was a word.
      growOneUnit(command) { outcome ->
        onDone(if (outcome is Outcome.Moved) Outcome.Degraded("no known word boundary yet") else outcome)
      }
      return
    }
    walkBackTo(target, command, current, onDone)
  }

  /**
   * Page and document steps: drag the handle to the screen edge and HOLD, which is what triggers the
   * target app's own auto-scroll. A plain drag lifts on arrival and can never trigger one.
   *
   * This is the least-measured part of the pad. The mechanism is proven — a held drag fired
   * `TYPE_VIEW_SCROLLED` and the selection kept extending — but it was never driven to a document's
   * end, and the hold time here is a guess at "one screen" rather than a measurement.
   */
  private fun sweepToEdge(command: PadCommand, holdMs: Long, onDone: (Outcome) -> Unit) {
    val before = observer.latestWithFreshBounds() ?: run {
      onDone(Outcome.NoSelection)
      return
    }
    val handle = locator.locate(before, activeEdge, toolbarCentre()) ?: run {
      acquireThenRetry(command, onDone)
      return
    }
    val target = PointF(
      if (command.toRight) (gestures.screenWidth() - EDGE_INSET).toFloat() else EDGE_INSET.toFloat(),
      if (command.toRight) (gestures.screenHeight() - EDGE_INSET).toFloat() else EDGE_INSET.toFloat(),
    )
    gestureCount++
    gestures.dragAndHold(handle, target, DRAG_MS, holdMs) { completed ->
      awaitChange(before) { after ->
        when {
          !completed -> onDone(Outcome.HandleLost)
          after == null -> onDone(Outcome.Moved(before.high(), before.high()))
          after.isEmpty() -> onDone(Outcome.HandleLost)
          else -> onDone(Outcome.Moved(before.high(), after.high()))
        }
      }
    }
  }

  /**
   * Last resort: hunt for the handle by probing outward from the toolbar centre.
   *
   * Deliberately narrow. A probe that misses lands on the page and **destroys the selection**, so
   * this gets few attempts and is only reached when no arithmetic applies.
   */
  private fun acquireThenRetry(command: PadCommand, onDone: (Outcome) -> Unit, probe: Int = 1) {
    val centre = toolbarCentre()
    val snapshot = observer.latestWithFreshBounds()
    if (centre == null || snapshot?.bounds == null || probe > HandleLocator.SCAN_MAX_PROBES) {
      onDone(Outcome.HandleLost)
      return
    }
    val ring = (probe + 1) / 2
    val x = if (probe % 2 == 1) centre + ring * HandleLocator.SCAN_STEP else centre - ring * HandleLocator.SCAN_STEP
    val y = snapshot.bounds.bottom + HandleLocator.HANDLE_DROP
    val from = PointF(x, y)

    gestureCount++

    gestures.drag(from, PointF(x + HandleLocator.SCAN_NUDGE, y), DRAG_MS) { completed ->
      if (!completed) {
        onDone(Outcome.HandleLost)
        return@drag
      }
      awaitChange(snapshot) { after ->
        when {
          after != null && locator.grabbedAHandle(snapshot, after) -> {
            locator.rememberAnchor(2 * centre - x)
            onDone(Outcome.Moved(snapshot.high(), after.high()))
          }
          // The probe wrecked the selection. Stop; there is nothing left to hunt for.
          after != null -> onDone(Outcome.HandleLost)
          else -> acquireThenRetry(command, onDone, probe + 1)
        }
      }
    }
  }

  private fun recordBoundary(snapshot: SelectionObserver.Snapshot) {
    knownBoundaries += snapshot.high()
  }

  /** Offsets are local to the source node, so a change of node invalidates every remembered one. */
  private fun rememberBoundaryContext(snapshot: SelectionObserver.Snapshot) {
    val key = "${snapshot.packageName}|${snapshot.bounds}|${snapshot.sourceLength}"
    if (key != boundariesNodeKey) {
      knownBoundaries.clear()
      boundariesNodeKey = key
      locator.forgetAnchor()
    }
  }

  private companion object {
    /** Floor for one escalation step, for when the node's geometry gives nothing usable. */
    const val STEP_PX = 12f

    /**
     * How many characters an escalation step is worth. Under one and short words take several
     * attempts; much over one and a step can jump clean past a short word to the one after.
     */
    const val CHARS_PER_ATTEMPT = 1.5f
    const val DRAG_MS = 150L
    /**
     * The settle is polled, not slept: the announcement usually lands well inside this, and paying a
     * fixed wait on every gesture of a multi-step press is most of why one felt slow.
     */
    const val POLL_MS = 25L

    /**
     * Measured: a successful step completes in ~170 ms *including* its 150 ms drag, so the
     * announcement lands within a few tens of milliseconds. The cap only has to outlast that, and
     * every millisecond of slack is paid again on each silent attempt of an escalation — which is
     * where a slow press actually spends its time (four silent attempts cost 1.9 s of a 2.1 s press).
     */
    const val MAX_SETTLE_MS = 220L

    /**
     * How long the announcements must stay quiet before the last one is believed. Long enough to
     * outlast the revision an app makes when the finger lifts, short enough not to be felt.
     */
    const val QUIET_MS = 130L

    /**
     * The longest a settle may take even if the announcements never go quiet. Several times
     * [QUIET_MS], so an ordinarily chatty settle still completes on its own terms.
     */
    const val MAX_QUIET_WAIT_MS = 600L

    /** Must exceed the widest word on screen once multiplied by [STEP_PX]. */
    const val MAX_ATTEMPTS = 12

    /**
     * The walk back is one estimated drag plus corrections, so this bounds the corrections, not the
     * characters. It should rarely go past the first.
     */
    const val MAX_CORRECTIONS = 8

    /**
     * The same bound for the HELD path, and much smaller on purpose.
     *
     * The released loop needs eight because every one of its corrections starts by guessing where
     * the handle went after the last lift. A held correction starts from the pointer itself, so it
     * is aiming from a pixel it knows; if three of those in a row have not landed on the target,
     * something is wrong that a fourth will not fix, and each one costs a settle.
     */
    const val MAX_HELD_CORRECTIONS = 3

    /**
     * The last character is crept, not stepped: a fraction of the average width, so a narrow glyph
     * cannot be jumped clean over. Shrinking is character-granular, so a short drag moves exactly
     * one character or none — and none simply costs another try.
     */
    const val FINAL_STEP_FRACTION = 0.55f
    const val MIN_SHRINK_PX = 8f

    /**
     * How far inside the target character to aim, as a fraction of its width. Enough to be clear of
     * the boundary that floor-rounding is ambiguous at, small enough not to reach the next one.
     */
    const val BOUNDARY_BIAS = 0.35f

    /**
     * How many times one press may restart its walk back after overshooting the target.
     *
     * Each retry costs a grow plus a probe, so this trades about a second for a press that actually
     * moves. Zero was the old behaviour, and it is what made the pad stick: the press reported
     * success while leaving the selection exactly where it found it.
     */
    const val MAX_STEP_RETRIES = 2

    /** Sanity bounds on the per-character width derived from a node's geometry. */
    const val MIN_CHAR_PX = 6f
    const val MAX_CHAR_PX = 60f

    const val EDGE_INSET = 24
    const val PAGE_HOLD_MS = 1200L
    const val DOCUMENT_HOLD_MS = 6000L
  }
}
