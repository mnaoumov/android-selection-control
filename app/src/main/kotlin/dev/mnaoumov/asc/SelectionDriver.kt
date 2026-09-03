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
      PadCommand.Unit.CHARACTER -> if (growing) growOneCharacter(command, report) else growOneUnit(command, onDone = report)
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
  private fun growOneCharacter(command: PadCommand, onDone: (Outcome) -> Unit) {
    val start = observer.latest?.high() ?: run {
      onDone(Outcome.NoSelection)
      return
    }
    val target = if (command.toRight) start + 1 else start - 1

    growOneUnit(command) { outcome ->
      if (outcome !is Outcome.Moved) {
        onDone(outcome)
        return@growOneUnit
      }
      // A grow that happened to move exactly one character (a lone space, say) is already the answer.
      if (outcome.toOffset == target) {
        onDone(Outcome.Moved(start, target))
        return@growOneUnit
      }
      walkBackTo(target, command, start, onDone)
    }
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
      // We are already past the target and cannot come back: moving this edge outward again is a
      // GROW, which snaps a whole word. Trying to correct upward is what made the loop oscillate
      // 31 → 29 → 31 → 29 until it destroyed the selection. Stop one character out instead.
      Diag.log("  shrink: overshot to $current past $target; stopping rather than oscillating")
      onDone(Outcome.Moved(origin, current))
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
     * Aim SHORT, never past. The per-character width is a line average, and in proportional text a
     * narrow run ("with") is far tighter than it, so a reach computed for N characters can cross
     * N+1. Undershooting costs one more small gesture; overshooting cannot be undone, because the
     * only way back is a grow and a grow snaps a word.
     */
    val reach = direction * when {
      toLose >= 2 -> (toLose - 1) * perChar
      // Grows with each retry, because a creep that moved nothing was simply too short.
      else -> (perChar * FINAL_STEP_FRACTION * (guard + 1)).coerceAtLeast(MIN_SHRINK_PX)
    }

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
              walkBackTo(target, command, origin, onDone, guard + 1)
            } else {
              onDone(Outcome.Moved(origin, current))
            }
          !locator.grabbedAHandle(before, after) -> onDone(Outcome.HandleLost)
          else -> walkBackTo(target, command, origin, onDone, guard + 1)
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
  ) {
    val now = observer.latest
    if (now != null && now.atMs != last.atMs) {
      awaitQuiet(now, 0, onResult)
      return
    }
    if (quietFor >= QUIET_MS) {
      onResult(last)
      return
    }
    handler.postDelayed({ awaitQuiet(last, quietFor + POLL_MS, onResult) }, POLL_MS)
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

    /** Must exceed the widest word on screen once multiplied by [STEP_PX]. */
    const val MAX_ATTEMPTS = 12

    /**
     * The walk back is one estimated drag plus corrections, so this bounds the corrections, not the
     * characters. It should rarely go past the first.
     */
    const val MAX_CORRECTIONS = 8

    /**
     * The last character is crept, not stepped: a fraction of the average width, so a narrow glyph
     * cannot be jumped clean over. Shrinking is character-granular, so a short drag moves exactly
     * one character or none — and none simply costs another try.
     */
    const val FINAL_STEP_FRACTION = 0.55f
    const val MIN_SHRINK_PX = 8f

    /** Sanity bounds on the per-character width derived from a node's geometry. */
    const val MIN_CHAR_PX = 6f
    const val MAX_CHAR_PX = 60f

    const val EDGE_INSET = 24
    const val PAGE_HOLD_MS = 1200L
    const val DOCUMENT_HOLD_MS = 6000L
  }
}
