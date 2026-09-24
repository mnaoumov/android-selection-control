package dev.mnaoumov.asc

import android.graphics.PointF
import android.graphics.Rect
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

  /**
   * The selection is intact and visible, and its row still cannot be established — so nothing was
   * touched at all.
   *
   * Distinct from [HandleLost] because the two need opposite things said to the user and one of them
   * is a lie here: nothing was lost, the selection is exactly where it was, and "reselect" is advice
   * that cannot work — the same long-press on the same wrapped paragraph produces the same refusal.
   *
   * It is the honest ending of a measured dead end rather than a failure to try. On a paragraph that
   * wraps, Chrome refuses a per-character rectangle (it answers with the node's own box) and exposes
   * no child finer than the paragraph (the node is a leaf), so there is no signal left that says
   * which LINE the moving edge is on — and a drag at the wrong line lands on the page, collapsing
   * the selection and, on a link, navigating. Both refusals are measured; see `AGENTS.md`.
   */
  data object RowUnknown : Outcome

  /**
   * The target app's floating toolbar sits over the handle, so nothing was touched.
   *
   * Chrome puts the toolbar BELOW a selection near the top of the page, and a down there presses a
   * toolbar button rather than grabbing the handle. The selection is intact; scrolling it lower on
   * screen moves the toolbar above it.
   */
  data object HandleCovered : Outcome

  /**
   * A shrink was asked for with one character left, so nothing was touched.
   *
   * A dragged handle cannot take the selection below one character: a `TextView` keeps it, and
   * Chrome carries the handle across the anchor and flips the selection onto the other side of it.
   * So the pad stops one character short of the anchor and says so, rather than spend gestures on a
   * floor that is already known.
   */
  data object AtFloor : Outcome
  data class Degraded(val reason: String) : Outcome
}

/** The gestures the driver needs, kept behind an interface so it does not depend on the service. */
interface GestureDispatcher {
  fun drag(
    from: PointF,
    to: PointF,
    durationMs: Long,
    pastTarget: Boolean = true,
    onFinished: (Boolean) -> kotlin.Unit,
  )
  // [pastTarget] false keeps the slop detour from travelling beyond [to]: a drag that already
  // crosses the slop goes straight there. See `SelectionDriver.growOneUnit`.
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
  fun grabAndHold(
    from: PointF,
    to: PointF,
    detourBack: Boolean = false,
    pastTarget: Boolean = true,
    onGrabbed: (Boolean) -> kotlin.Unit,
  )
  // [onGrabbed] reports when the grab has PLAYED, not when it was accepted — a caller that starts
  // waiting for an announcement before the stroke has run always times out. [detourBack] sends the
  // grab's slop detour away from [to] instead of past it, for a caller whose first travel must not
  // carry the handle into the next word on its way (see `growToWordEnd`). [pastTarget] false keeps
  // the detour on [to]'s side but no further than one pixel past the touch slop, as [drag]'s does
  // (see `heldCharacterStep`).

  /**
   * Move the pointer that [grabAndHold] pressed. False when nothing is held, which is a real answer
   * and not a formality — a caller that ignores it goes on issuing moves to a chain that has died.
   *
   * Silent about the result: read that from the selection stream, as everything else here does.
   */
  fun moveHeld(to: PointF): Boolean

  /**
   * Lift, at the end of the link in flight. Harmless when nothing is held.
   *
   * [onLifted] fires once the lift has PLAYED — the only moment from which the target app's own
   * finalisation of the drag can be read. It fires on every path, including the ones with nothing to
   * lift and the ones whose chain died first, so a caller may wait on it unconditionally.
   */
  fun releaseHeld(onLifted: () -> kotlin.Unit = {})

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
  private val toolbarBounds: () -> Rect?,
) {

  /** The floating toolbar's horizontal centre, which tracks the selection's. */
  private fun toolbarCentre(): Float? = toolbarBounds()?.exactCenterX()

  /**
   * [handle], or an aim moved off the floating toolbar, or null — having ended the press through
   * [onRefused] — when the toolbar covers every part of the handle in reach.
   *
   * A down on the toolbar presses a toolbar button, and that is worse than a miss on the page: it
   * ACTS. Measured on the rig as Select all, which the pad then read as a successful move. See
   * [HandleLocator.clearOfToolbar].
   */
  private fun uncovered(handle: PointF, onRefused: (Outcome) -> Unit): PointF? {
    val toolbar = toolbarBounds()
    val aim = locator.clearOfToolbar(handle, toolbar)
    when {
      aim == null -> {
        Diag.log(
          "  grab: the toolbar at $toolbar covers the handle at (${handle.x}, ${handle.y}) " +
            "and leaves none of it in reach — refusing to touch"
        )
        onRefused(Outcome.HandleCovered)
      }
      aim !== handle -> Diag.log(
        "  grab: the toolbar at $toolbar covers (${handle.x}, ${handle.y}) — aiming at y ${aim.y} instead"
      )
    }
    return aim
  }

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

  /**
   * [selectionStillOnScreen], asked only once the target app has had time to put its toolbar back.
   *
   * The app takes its toolbar down for the whole of any handle drag and puts it back after, so read
   * straight after a drag the toolbar is absent whether or not the selection survived. Measured
   * 2026-09-24 on Chrome's `ERR_INVALID_URL` page: a 12 px `word →` reach from the end of the first
   * node announced nothing, the check ran 6 ms later, found no toolbar, and the press ended
   * `HandleLost` in 430 ms with the selection intact and the toolbar back seconds later — twice, so
   * the escalation that would have passed the next word's middle never ran.
   *
   * So a missing toolbar is re-read every [POLL_MS] for up to [TOOLBAR_RETURN_MS] before it is
   * believed. A surviving selection costs only as long as its toolbar takes to come back; a
   * destroyed one costs the whole bound, once, at the end of a press that is failing anyway.
   */
  private fun awaitSelectionOnScreen(waited: Long = 0, onResult: (Boolean) -> Unit) {
    if (selectionStillOnScreen()) {
      if (waited > 0) Diag.log("  toolbar back after ${waited}ms")
      onResult(true)
      return
    }
    if (waited >= TOOLBAR_RETURN_MS) {
      onResult(false)
      return
    }
    handler.postDelayed({ awaitSelectionOnScreen(waited + POLL_MS, onResult) }, POLL_MS)
  }

  /** The edge the pad is moving. The other one is the anchor and stays put. */
  var activeEdge: Edge = Edge.END
    set(value) {
      // After a swap the anchor is the edge that was moving, which is in the announcing node only
      // when the whole selection is. Otherwise nothing says where it is.
      if (value != field && observer.latest?.let(::nodeKey) != anchorNodeKey) anchorNodeKey = null
      field = value
    }

  /**
   * The node the ANCHOR is known to be in, as [nodeKey] reads it, or null when that is not known.
   *
   * Set by a fresh selection, whose two edges are in the node it was announced from. The anchor's
   * offset ([anchorOffset]) means something only while the announcing node is this one: a grow
   * that carries the moving edge into the next node is announced in THAT node's frame, as `0..1`,
   * and the `0` there is where the node starts rather than where the selection does. A shrink from
   * there crosses the seam back legitimately (see [crossedTarget]), so the one-character floor in
   * [shrinkByWord] must not apply to it.
   */
  private var anchorNodeKey: String? = null

  /**
   * The offset of the edge being moved — `low()` for START, `high()` for END.
   *
   * **Read this, never `high()`, wherever the loop means "where the moving edge is now".** Every
   * path once took `high()` unconditionally, which is right for the END edge and off by the whole
   * width of the selection for the START one. [HandleLocator.locate] already picked the correct
   * handle, so the gesture landed on the real handle, the selection really moved, and the loop then
   * corrected against a number belonging to the other end — measured 2026-09-03 with `⇄ swap` on
   * `12..19`: the grab moved the start to 11, three corrections aimed the other way put it back at
   * 12, and the press reported `Moved(19, 19)`. A `char →` in the same state moved the start two
   * characters the wrong way. That is why one reading serves every path rather than each path
   * remembering.
   */
  private fun SelectionObserver.Snapshot.movingOffset(): Int =
    if (activeEdge == Edge.START) low() else high()

  /**
   * The sign a SHRINK adds to the moving edge's offset, and the direction it travels in x — the same
   * number, because offsets increase rightward.
   *
   * Shrinking the END edge moves LEFT and lowers the offset; shrinking the START edge moves RIGHT
   * and raises it. Every "how far still to go" below is measured in this frame, so a step and an
   * overshoot keep their signs on both edges.
   */
  private val towardAnchor: Int get() = if (activeEdge == Edge.END) -1 else 1

  /** The edge that is NOT being moved, in the source node's frame. A shrink stops here at the latest. */
  private fun SelectionObserver.Snapshot.anchorOffset(): Int =
    if (activeEdge == Edge.START) high() else low()

  /**
   * Whether [command] makes the selection BIGGER on the edge currently being moved — the one fact
   * that decides whether a landing is evidence of a word boundary, because the measured granularity
   * rule is directional: growing snaps a whole word, shrinking moves one character.
   *
   * Computed from the command and the active edge rather than remembered per call site, because the
   * call sites do not divide along that line: [growOneUnit] is reached by `word →` (a grow), by
   * `shrinkByWord`'s degradation (a shrink) and by [releasedCharacterStep]'s shrinking branch, and
   * [heldCharacterStep] serves `char ←` and `char →` alike. Asking which button was pressed is
   * therefore not the same question as asking which way the selection travelled, and answering the
   * first is what filled [knownBoundaries] with mid-word offsets.
   */
  private fun growsSelection(command: PadCommand): Boolean = (activeEdge == Edge.END) == command.toRight

  /**
   * Offsets that something has SHOWN to be word boundaries. This is how `word ←` finds the previous
   * boundary **without reading any text**: retrace to the nearest remembered one in the shrinking
   * direction.
   *
   * Two sources feed it, and between them they are the whole of what this mechanism can ever know
   * about where words begin and end — see [recordBoundary] and [noteSelectionEvent]:
   *
   *  - an offset a GROW landed on, because growing snaps a whole word;
   *  - both edges of a selection the user has just made, because a long-press snaps a whole word too.
   *
   * There is no third source, and in particular **a shrink reveals nothing**. Shrinking is
   * character-granular, so where it stops says only where the finger was; the target app's word
   * knowledge is expressed exclusively by SNAPPING, and a snap reports a destination rather than
   * classifying an origin. Every offset in here is therefore a landing of one of the two kinds
   * above, never merely an offset the selection has been seen at — a distinction this used to lose,
   * with the result below.
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
    syncBoundaryContext(snapshot)
    Diag.log(
      "$command edge=$activeEdge at ${snapshot.low()}..${snapshot.high()} " +
        "moving=${snapshot.movingOffset()} " +
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

    val growing = growsSelection(command)
    when (command.unit) {
      PadCommand.Unit.WORD -> when {
        !growing -> shrinkByWord(command, report)
        // Only a grow from a boundary snaps to a word; from anywhere else it follows the finger one
        // character at a time and stops wherever the reach ran out. See [growToWordEnd].
        snapshot.movingOffset() in knownBoundaries -> growOneUnit(command, onDone = report)
        else -> growToWordEnd(command, report)
      }
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
  private fun growOneUnit(
    command: PadCommand,
    attempt: Int = 0,
    // Where the press began, which a retry after a backwards landing has to keep reporting from.
    first: SelectionObserver.Snapshot? = null,
    // Set once Chrome has parked the edge on the next word's start: see [landedOnNextWordStart].
    resume: ResumedReach? = null,
    onDone: (Outcome) -> Unit,
  ) {
    val before = observer.latestWithFreshBounds()
    if (before == null) {
      onDone(Outcome.NoSelection)
      return
    }
    val origin = first ?: before
    val located = locator.locate(before, activeEdge, toolbarCentre())
    if (located == null) {
      acquireThenRetry(command, onDone)
      return
    }
    val handle = uncovered(located, onDone) ?: return

    // Escalate in characters rather than in a fixed pixel count: the distance that matters is the
    // width of the next word, and the node's own geometry says how wide a character is here. A
    // constant step wastes attempts on wide text and overshoots on narrow.
    val step = resume?.step
      ?: (pixelsPerCharacter(before, crossingOf(command)) * CHARS_PER_ATTEMPT).coerceAtLeast(STEP_PX)
    val sign = if (command.toRight) 1 else -1
    val reach = step * (attempt + 1) * sign

    /*
     * From the last character of a line the next one is on the next row, and from the first
     * character of a line the previous one is on the row above, so the reach starts from THAT caret
     * rather than running sideways off the line's end. See [crossRowDelta].
     */
    val next = before.movingOffset() + sign
    val crossing = crossRowDelta(before, before.movingOffset(), next, 0f)
    val wrap = crossing ?: PointF(0f, 0f)
    val base = PointF(handle.x + wrap.x, handle.y + wrap.y)
    if (crossing != null && first == null && command.unit == PadCommand.Unit.WORD && growsSelection(command)) {
      changeRowThenGrow(command, before, handle, base, next, onDone)
      return
    }

    /*
     * And never past the screen's edge. A reach that already left it last time will find nothing
     * further on; measured with the toolbar over a line-end handle, the escalation ran to 360 px.
     */
    val width = gestures.screenWidth().toFloat()
    val onScreen = { x: Float -> x >= 0f && x <= width - 1 }
    // Measured from where the press began once Chrome has moved the handle a character on, so the
    // escalation keeps the schedule a silent try would have had rather than stacking on that character.
    val from = if (resume != null && crossing == null) resume.x else base.x
    if (attempt > 0 && !onScreen(from + step * attempt * sign)) {
      Diag.log("  grow: the last reach already left the screen at x ${from + step * attempt * sign} — stopping")
      onDone(heldOrLost(origin, before, resume))
      return
    }
    val to = PointF((from + reach).coerceIn(0f, width - 1), base.y)

    /*
     * A word grow's drag must not detour PAST where it is aimed. The usual slop detour travels 32 px
     * beyond the handle before coming back, and on Chrome that visit passes the middle of a short next
     * word, snaps to its end, and the return then shrinks one character at a time to the finger:
     * measured 2026-09-24 on the served seven-line paragraph, `word →` from 40, the end of
     * `frustration`, landed on 42 inside `of` (41..43) three cold runs of three, and 42 went into
     * [knownBoundaries] as a snap. Aimed straight at the reach, the same press lands on 41, the hold
     * on the next word's start, and [landedOnNextWordStart] escalates it to 43: nine presses of nine
     * over `of`, `selecting` and `a`, in 0.7 s instead of 1.0-1.2 s for the first. A detour toward
     * the anchor was measured and refused: on a `TextView` it puts the handle back inside the word it
     * started at the end of, and from mid-word the grow is character-granular (11 -> 12 -> 14).
     */
    val wordGrow = command.unit == PadCommand.Unit.WORD && growsSelection(command)
    gestureCount++
    gestures.drag(
      handle,
      to,
      DRAG_MS,
      pastTarget = !wordGrow,
    ) { completed ->
      if (!completed) {
        onDone(Outcome.HandleLost)
        return@drag
      }
      awaitChange(before) { after ->
        Diag.log(
          "  grow try ${attempt + 1}: handle=(${handle.x}, ${handle.y}) reach=$reach to=(${to.x}, ${to.y}) -> " +
            if (after == null) "nothing" else "${after.low()}..${after.high()} bounds=${after.bounds}"
        )
        when {
          /*
           * The handle moved, but BACK: the drag's first move event reaches the platform's shrinking
           * branch, which can pull the edge a character against the drag (measured 2026-09-24: a
           * 13.5 px grow from 11 announced 10). That is not the step, and reporting it as one sent a
           * `char →` press backwards. The handle is known to be under the finger, so escalate from
           * where it is now, as for a reach that was too short.
           */
          after != null && locator.grabbedAHandle(before, after) && !progressed(origin, after, command) &&
            attempt + 1 < MAX_ATTEMPTS -> {
            Diag.log("  grow: landed on ${after.movingOffset()}, not past ${origin.movingOffset()} — reaching further")
            growOneUnit(command, attempt + 1, origin, resume, onDone)
          }
          after != null && locator.grabbedAHandle(before, after) &&
            landedOnNextWordStart(command, origin, before, after) && attempt + 1 < MAX_ATTEMPTS -> {
            Diag.log(
              "  grow: landed on ${after.movingOffset()}, one past the boundary ${origin.movingOffset()} — " +
                "the next word's start, reaching further"
            )
            knownBoundaries += after.movingOffset()
            growOneUnit(command, attempt + 1, origin, resume ?: ResumedReach(handle.x, step), onDone)
          }
          after != null && locator.grabbedAHandle(before, after) -> {
            /*
             * Only a grow from where the press began is evidence of a boundary. One that restarted
             * from a backwards landing started MID-WORD, and a `TextView` grows from mid-word one
             * character at a time rather than snapping. Measured 2026-09-24: 10 -> 14, from inside
             * `bravo` to inside `charlie`, went into the set, and the next `word ←` walked to 14.
             */
            // Nor is a grow that changed row, which is character-granular: see [changeRowThenGrow].
            // A resumed one grew from the hold on the next word's start, where Chrome is snapping by
            // word, so its landing is that word's end even one character on: see [landedOnNextWordStart].
            val resumedInNode = resume != null && before.source == after.source &&
              grownBy(before.movingOffset(), after.movingOffset()) > 0
            when {
              crossing != null -> {}
              resumedInNode -> knownBoundaries += after.movingOffset()
              before.movingOffset() == origin.movingOffset() || resume != null -> recordBoundary(before, after, command)
            }
            locator.rememberAnchor(handle.x - reach.coerceAtLeast(0f))
            onDone(Outcome.Moved(origin.movingOffset(), after.movingOffset()))
          }
          after != null -> onDone(Outcome.HandleLost)
          // Silence is ambiguous: the reach may be too short, or that drag may have landed on the
          // page and taken the selection (or the whole page) with it. Never escalate past the point
          // where a selection still visibly exists.
          attempt + 1 < MAX_ATTEMPTS -> awaitSelectionOnScreen { onScreen ->
            if (onScreen) {
              growOneUnit(command, attempt + 1, origin, resume, onDone)
            } else {
              Diag.log("  grow: the selection is gone from screen — stopping rather than poking the page")
              onDone(Outcome.HandleLost)
            }
          }
          else -> onDone(heldOrLost(origin, before, resume))
        }
      }
    }
  }

  /** Where a resumed grow measures its reach from, and the step it escalates by. */
  private class ResumedReach(val x: Float, val step: Float)

  /**
   * How a grow that ran out of reach ends. A resumed one has already moved the edge onto the next
   * word's start, so it reports that move rather than a lost handle: the selection did change.
   */
  private fun heldOrLost(
    origin: SelectionObserver.Snapshot,
    before: SelectionObserver.Snapshot,
    resume: ResumedReach?,
  ): Outcome = if (resume != null) Outcome.Moved(origin.movingOffset(), before.movingOffset()) else Outcome.HandleLost

  /**
   * Whether a word grow from a known boundary stopped exactly one character on, which on Chrome is
   * not the step: it is the next word's START.
   *
   * Chrome's grow from a word's end steps through the space and then holds at the next word's start
   * until the finger passes that word's middle, the same hold the word walk found. A reach short of
   * the middle therefore lands one past the boundary: measured 2026-09-24 as 43 -> 44, from the end
   * of `of` to the start of `selecting`. A `TextView` does not land there, since from a boundary it
   * announces nothing until the middle is passed, and then the word's end. So the landing is
   * escalated through, as a silent try would be.
   *
   * The hold is a word's start, so it goes into [knownBoundaries], and so does where the resumed try
   * lands: from the hold Chrome is snapping by word, so the landing is that word's end, even a
   * one-letter word's one character on (measured: 53 -> 54 -> 55 over ` a`). The next `word →` then
   * starts on a known boundary instead of falling into the walk, which on Chrome has no stop.
   *
   * The one case this gets wrong is a ONE-LETTER word grown from its own start, where one character
   * on is the word's end: the press escalates on and ends at the next word's start, a space too far.
   * No geometry tells the two apart, since a space is as wide as a narrow letter.
   *
   * Only while the grow still stands where the press began, so a resumed try is taken as it lands.
   */
  private fun landedOnNextWordStart(
    command: PadCommand,
    origin: SelectionObserver.Snapshot,
    before: SelectionObserver.Snapshot,
    after: SelectionObserver.Snapshot,
  ): Boolean =
    command.unit == PadCommand.Unit.WORD && growsSelection(command) &&
      before.movingOffset() == origin.movingOffset() && origin.movingOffset() in knownBoundaries &&
      before.source == after.source && grownBy(origin.movingOffset(), after.movingOffset()) == 1

  /**
   * A word grow whose next character is on another row: move the handle onto that row's caret first,
   * then grow along it as from any word boundary.
   *
   * Two stages because Chrome's granularity changes with the row. Measured 2026-09-24 on the rig, the
   * seven-line paragraph, `small` selected at the end of line 1: one drag from the line-1 handle to
   * 18 px into line 2 landed on 32, inside `frustration` (29..40), not on its end. A grow that changes
   * line is character-granular there, so the landing is where the finger was, and taking it as a
   * word both reported half a word and put 32 into [knownBoundaries], which the next two presses then
   * grew from. On the same row, a grow from a word's start snaps as it always has.
   *
   * The row change lands on [next], the next row's first character. That is recorded as a boundary
   * only when the character between the two rows has no box, i.e. the line wraps at a space, so
   * [next] starts a word: a line broken inside a word (a hyphen, an overlong URL) has a box there.
   * Anything else it lands on is reported as the step and recorded nowhere.
   */
  private fun changeRowThenGrow(
    command: PadCommand,
    before: SelectionObserver.Snapshot,
    handle: PointF,
    base: PointF,
    next: Int,
    onDone: (Outcome) -> Unit,
  ) {
    val origin = before.movingOffset()
    val wrapsAtSpace = !locator.hasOwnBox(before, minOf(origin, next))
    gestureCount++
    gestures.drag(handle, base, DRAG_MS) { completed ->
      if (!completed) {
        onDone(Outcome.HandleLost)
        return@drag
      }
      awaitChange(before) { after ->
        Diag.log(
          "  wrap: row change to (${base.x}, ${base.y}) -> " +
            if (after == null) "nothing" else "${after.low()}..${after.high()}"
        )
        val growFromNext = {
          syncBoundaryContext(after!!)
          knownBoundaries += next
          growOneUnit(command) { outcome ->
            onDone(if (outcome is Outcome.Moved) Outcome.Moved(origin, outcome.toOffset) else outcome)
          }
        }
        when {
          after == null || !locator.grabbedAHandle(before, after) -> onDone(Outcome.HandleLost)
          after.movingOffset() == next && wrapsAtSpace -> growFromNext()
          /*
           * Past the row's first character, which is the usual case: aimed at the next row's caret,
           * the released drag landed on 31 rather than 29, measured. Shrinking along one row is
           * exact, so walk back onto [next] and grow from there rather than from mid-word, where
           * Chrome's grow does not stop at the word's end.
           */
          wrapsAtSpace && before.source == after.source && grownBy(next, after.movingOffset()) > 0 ->
            walkBackTo(next, command, origin, { outcome ->
              if (outcome is Outcome.Moved && outcome.toOffset == next) {
                growFromNext()
              } else {
                onDone(outcome)
              }
            })
          else -> {
            Diag.log("  wrap: landed on ${after.movingOffset()}, not a known word start — taking it as the step")
            onDone(Outcome.Moved(origin, after.movingOffset()))
          }
        }
      }
    }
  }

  /**
   * Whether [after] is past where the press began, in the direction [command] drags — or in another
   * node, which a drag only reaches by going that way. Offsets increase rightward, so the drag's
   * direction is [PadCommand.toRight] on both edges.
   */
  private fun progressed(
    first: SelectionObserver.Snapshot,
    after: SelectionObserver.Snapshot,
    command: PadCommand,
  ): Boolean {
    if (first.source != null && after.source != null && first.source != after.source) return true
    val moved = after.movingOffset() - first.movingOffset()
    return if (command.toRight) moved > 0 else moved < 0
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
    // A retry after an overshoot is the SAME press, so it reports from where the press began and
    // aims where the press was aiming. Recomputing both from the overshoot reported a press that
    // started at 11 and ended at 11 as `Moved(10, 11)`, measured 2026-09-24, and aimed one
    // character short of the original target whenever the overshoot was more than one.
    pressOrigin: Int? = null,
    pressTarget: Int? = null,
  ) {
    val beforeGrow = observer.latest ?: run {
      onDone(Outcome.NoSelection)
      return
    }
    val start = beforeGrow.movingOffset()
    val origin = pressOrigin ?: start

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
       * node has changed, the target is reckoned across the seam ([crossedTarget]). A grow jumps a
       * word, so its landing is never the step, and where the seam arithmetic says nothing the old
       * guess stands: one character into the new node.
       */
      val target = when {
        crossedNodes(beforeGrow, afterGrow) ->
          crossedTarget(beforeGrow, afterGrow, command, start)
            ?: if (command.toRight) 1 else afterGrow.sourceLength - 1
        pressTarget != null -> pressTarget
        command.toRight -> start + 1
        else -> start - 1
      }

      // A grow that happened to move exactly one character (a lone space, say) is already the answer.
      if (afterGrow.movingOffset() == target) {
        onDone(Outcome.Moved(origin, target))
        return@growOneUnit
      }
      walkBackTo(target, command, origin, onDone, retriesLeft = retriesLeft)
    }
  }

  /**
   * Where one character past [start] actually is, which is not always [start] + 1.
   *
   * Offsets are local to the source node, and a step can carry the edge into the NEXT node — after
   * which `start + 1` is a number in a frame of reference that no longer exists. When the node has
   * changed, the target is reckoned across the seam as if the two nodes were adjacent: see
   * [crossedTarget]. Where that arithmetic has nothing to say, the landing itself is the step, since
   * a held grab is aimed at one character and [HandleLocator.grabbedAHandle] has already ruled out a
   * collapse.
   */
  private fun targetOffset(
    before: SelectionObserver.Snapshot,
    after: SelectionObserver.Snapshot,
    command: PadCommand,
    start: Int,
  ): Int = when {
    crossedNodes(before, after) -> crossedTarget(before, after, command, start) ?: after.movingOffset()
    command.toRight -> start + 1
    else -> start - 1
  }

  private fun crossedNodes(before: SelectionObserver.Snapshot, after: SelectionObserver.Snapshot): Boolean =
    before.source != null && after.source != null && before.source != after.source

  /**
   * Whether [landed] sits on the seam that [target], counted in [frame]'s node, is: the end of the
   * node on its left or the start of the node on its right, which are the same caret. Chrome
   * announces that caret in either frame. Same adjacency assumption as [crossedTarget], and false
   * where a length is unknown or the target is not the seam: nothing else of one node has a place
   * in the other.
   */
  private fun isSeamOf(frame: SelectionObserver.Snapshot, landed: SelectionObserver.Snapshot, target: Int): Boolean {
    if (frame.sourceLength < 0 || landed.sourceLength < 0) return false
    val current = landed.movingOffset()
    return (target == 0 && current == landed.sourceLength) || (target == frame.sourceLength && current == 0)
  }

  /**
   * One character past [start], counted into the node [after] landed in, assuming the two nodes are
   * adjacent: the end of the left one is the start of the right one, the same caret. Null when the
   * answer falls outside the landing node, which means the assumption does not hold or its length is
   * unknown.
   *
   * It used to be `sourceLength - 1` leftward and `1` rightward. That is right only for a step that
   * starts exactly ON the seam. From offset 1, one character left lands ON the seam, and Chrome
   * announces the seam in the previous node's frame, as its length. Measured 2026-09-24 on
   * `ERR_INVALID_URL`: `char ←` from `0..1` of the paragraph grabbed onto `9..15` of
   * `"chrome://terms/"`, the paragraph's offset 0 and exactly one step. The old target of 14 read
   * that as an overshoot, and the held correction took one more character off.
   */
  private fun crossedTarget(
    before: SelectionObserver.Snapshot,
    after: SelectionObserver.Snapshot,
    command: PadCommand,
    start: Int,
  ): Int? {
    if (before.sourceLength < 0 || after.sourceLength < 0) return null
    val target = if (command.toRight) start + 1 - before.sourceLength else after.sourceLength + start - 1
    return target.takeIf { it in 0..after.sourceLength }
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
   *   across a word boundary). A plain `TextView` steps by character only from INSIDE a word: from
   *   a boundary it snaps too, to the next word's end once the finger passes that word's middle,
   *   and holds still until then (see [heldSnapThrough]). So overshoot as the released path does
   *   and let [heldCorrect] walk back.
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
    val located = locator.locate(before, activeEdge, toolbarCentre())
    if (located == null) {
      acquireThenRetry(command, onDone)
      return
    }
    val handle = uncovered(located, onDone) ?: return
    val start = before.movingOffset()
    val perChar = pixelsPerCharacter(before, crossingOf(command))

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
     *
     * **And no [STEP_PX] floor.** [pixelsPerCharacter] already bounds its answer, and a floor of
     * 12 px over a glyph the platform measured narrower aims past it: measured 2026-09-24 on Chrome
     * across `oil`, whose `i` and `l` are 8 px each, 6 -> 8 in one grab, and the held correction back
     * to 7 announced nothing, so the press ended two characters on. Without the floor the same run
     * of ten presses each moved exactly one.
     */
    val characters = 1f
    val reach = (if (command.toRight) 1f else -1f) * perChar * characters
    /*
     * Across a line wrap the one character is the row change itself — the space the line wraps at
     * has no width on either row — so the first travel goes to the other row's caret and no further.
     * See [crossRowDelta].
     */
    val next = start + if (command.toRight) 1 else -1
    val to = crossRowDelta(before, start, next, 0f)?.let { PointF(handle.x + it.x, handle.y + it.y) }
      ?: PointF(handle.x + reach, handle.y)

    /*
     * On Blink the grab's detour stops just past the touch slop, not 60 px out.
     *
     * The 60 px detour grows the selection far past the destination first, often past the next
     * word's middle, where Chrome snaps to its end. The pointer's return is then a shrink, and Chrome
     * keeps the lead that the snap built up: the edge stops one character past the finger. Measured
     * 2026-09-24 on the rig, logging every announcement of the grab: `char →` from 9 of `Chrome is made
     * by Google` announced 10, 14, 13, 12, 11 and stopped on 11 over a pointer at 10's caret, and from
     * 17 it snapped to 23, the end of `Google`, and came back only to 20. A detour that stayed short
     * of a word's middle (from 8: 10, then 9) came back exactly. Every +2 landing that the
     * measured-caret aim left in place was the first shape, which is why moving the grab did not
     * move them.
     *
     * Detouring toward the anchor instead was measured the same day and is worse: the return is then
     * a grow, which Chrome ends one character SHORT as often as the outward detour ended long.
     *
     * Not on a `TextView`, which snaps a grow by word from a boundary and rounds the first move as a
     * shrink ([heldSnapThrough]'s note); its outward detour is what moves it one character there.
     */
    gestureCount++
    gestures.grabAndHold(handle, to, pastTarget = !before.isBlink) { grabbed ->
      if (!grabbed) {
        Diag.log("  held: the grab was refused — falling back to the released path")
        releasedCharacterStep(command, onDone)
        return@grabAndHold
      }
      awaitChange(before) { after ->
        Diag.log(
          "  held grab: handle=(${handle.x}, ${handle.y}) to=(${to.x}, ${to.y}) -> " +
            if (after == null) "nothing" else "${after.low()}..${after.high()}"
        )
        when {
          /*
           * Nothing announced, so the grab may have missed the handle and be resting on the page.
           * Let go BEFORE deciding anything: a held pointer sitting on a page is worse than no
           * pointer, and the released path re-derives the handle from scratch anyway.
           *
           * And wait for the lift to have PLAYED before dispatching anything else. A release is only
           * a request that the chain honours at the end of the link in flight, and a drag
           * dispatched while that lift is still playing cancels the one gesture or the other.
           * Measured 2026-09-24 from a word's end: the fallback's first drag came back not completed
           * every time, so the press reported `HandleLost` in about 500 ms with the selection
           * untouched and the released path never having run.
           *
           * Except a Blink GROW, where silence after the short detour is Chrome holding the edge at a
           * word's START until the finger passes that word's middle: push the held pointer on
           * through the snap, as a `TextView` correction does, and walk back from there. Measured
           * 2026-09-24: every such press (from 10 and 18 of `Chrome is made by Google`, 12 and 20 of
           * `alpha bravo charlie delta`, 9 of `mint oil ink lilt wax`, and 17 and 14 leftward on the
           * START edge) landed exactly, in 3-4 gestures and 1.35-1.75 s. Releasing instead fell to the released grow, which took 3-7
           * gestures and once ended where it started (`Moved(9, 9)`, 4.1 s). A grab that really
           * missed is already past the slop, so a push reads to the page as a scroll, and
           * [heldSnapThrough] gives up after [MAX_SNAP_THROUGH_ATTEMPTS].
           */
          after == null && before.isBlink && growsSelection(command) -> {
            Diag.log("  held: silent from $start on Blink — pushing through the word's middle")
            heldSnapThrough(next, start, command, onDone, guard = 1, frame = before)
          }
          after == null -> gestures.releaseHeld {
            awaitSelectionOnScreen { onScreen ->
              if (onScreen) {
                releasedCharacterStep(command, onDone)
              } else {
                Diag.log("  held: the selection is gone from screen — stopping rather than poking the page")
                onDone(Outcome.HandleLost)
              }
            }
          }
          !locator.grabbedAHandle(before, after) -> {
            gestures.releaseHeld()
            onDone(Outcome.HandleLost)
          }
          else -> {
            /*
             * The FIRST landing is NOT recorded as a boundary, although a `TextView` snap from a
             * boundary would land on one (8 -> 10 -> 18, once). On Chrome it is where the grab's
             * outward detour left the handle: measured 2026-09-24 on the error page, `char →` from
             * 21, the end of `temporarily`, landed on 23, inside `down` (22..26), and 23 went into
             * the set. The press after it read 23 as a known boundary and took 24 as the next word's
             * start. Nothing tells that landing from a snap. And with the outward detour a
             * `TextView` does not snap here at all: six presses of six from `bravo`'s end moved
             * 11 -> 12 in one gesture. The snaps that do happen are recorded by [heldSnapThrough]
             * and [growOneUnit].
             *
             * Detouring toward the anchor, as [growToWordEnd] does, was measured and refused. Chrome
             * then landed on 22 four times of four, but the `TextView` grab from a word's end then
             * announced nothing 2 times of 3. It fell back to the released path, at 4-7 gestures and
             * 2-9 s a press against 1 gesture and about 1 s.
             */
            // Still re-key: a landing across a node makes the old node's offsets meaningless.
            syncBoundaryContext(after)
            locator.rememberAnchor(handle.x - reach.coerceAtLeast(0f))
            heldCorrect(targetOffset(before, after, command, start), start, command, onDone, frame = after)
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
  /**
   * Lift, let the target app finish, and report the offset that SURVIVED the lift.
   *
   * Every held exit that has an offset to report goes through here, and the reason is the one thing
   * the held path had no way to see. A release is asynchronous — the chain notices it at the end of
   * the link in flight and then plays a lift — so `releaseHeld(); onDone(...)` answers before the
   * app has finalised its own drag. Held, the app follows the pointer; on the lift it revises. Five
   * presses in a row announced `24 → 23`, each one true when it was said, and the selection was back
   * at 24 every time. To the user that is a dead button, and the pad could not tell, because it had
   * already stopped looking.
   *
   * So the lift is now part of the step that is read back. What comes out is what the user can see,
   * which costs a settle and buys three things: the announcement matches the screen; `madeProgress`
   * can see a press that achieved nothing and stop a repeat run on it, as it does for every other
   * kind of failure; and a revision that nothing yet explains shows up in the log as itself rather
   * than as a step that mysteriously has to be repeated.
   *
   * A lift that collapses the selection is [Outcome.HandleLost], not a move: there is no longer an
   * edge to have moved, and the run must stop rather than press on against a caret.
   */
  private fun releaseThenReport(origin: Int, landed: Int, onDone: (Outcome) -> Unit) {
    gestures.releaseHeld {
      val atLift = observer.latest
      if (atLift == null) {
        onDone(Outcome.Moved(origin, landed))
        return@releaseHeld
      }
      awaitQuiet(
        atLift,
        0,
        onResult = { settled ->
          when {
            settled == null -> onDone(Outcome.Moved(origin, landed))
            settled.isEmpty() -> {
              Diag.log("  held: the lift collapsed the selection — a lost handle, not a move")
              onDone(Outcome.HandleLost)
            }
            else -> {
              val after = settled.movingOffset()
              if (after != landed) Diag.log("  held: the app revised $landed -> $after after the lift")
              onDone(Outcome.Moved(origin, after))
            }
          }
        },
      )
    }
  }

  /**
   * [releaseThenReport] for a GROW that is on its target: move the held pointer back towards the
   * anchor by [LIFT_PULLBACK_PX] first, so the last move before the lift is not a growing one.
   *
   * Chrome finalises a handle drag on the lift, and a drag whose last move GREW the selection is
   * finalised one character further on. Measured 2026-09-24 on the rig against a centred 13 px
   * `Google LLC`, the `chrome://version` shape, where one `char →` from `0..6` held at 7 with the
   * pointer 2 px into the `L`: seven presses of seven announced `7`, and the lift turned every one
   * into `8`. Nearest-boundary rounding cannot explain it, since 2 px into a 16 px glyph rounds to
   * 7. The direction does, and three controls separate the two:
   *
   * - A press whose held correction ended with a SHRINKING move (grab to 8, correct back to 7) was
   *   never revised, with the pointer in the same cell.
   * - A 1 px push FORWARD before the lift, with the same wait, was revised to 8 three times in three.
   * - A 1 px pull BACK before the lift was never revised: 4 of 4, then 25 of 26 Chrome presses over
   *   three nodes, the one miss being a correction that stalled rather than a revision.
   *
   * A `TextView` needs none of this, and is not hurt by it: 16 of 16 presses over `bravo`, crossing
   * the word snap, landed exactly one character on. Shrinks keep the plain release, since nothing
   * revises them.
   */
  private fun pullBackThenRelease(origin: Int, landed: Int, onDone: (Outcome) -> Unit) {
    val at = gestures.heldAt()
    if (at == null || !gestures.moveHeld(PointF(at.x + towardAnchor * LIFT_PULLBACK_PX, at.y))) {
      releaseThenReport(origin, landed, onDone)
      return
    }
    Diag.log("  held: pulling back ${LIFT_PULLBACK_PX}px before the lift")
    handler.postDelayed({ releaseThenReport(origin, landed, onDone) }, LIFT_PULLBACK_MS)
  }

  private fun heldCorrect(
    target: Int,
    origin: Int,
    command: PadCommand,
    onDone: (Outcome) -> Unit,
    guard: Int = 0,
    // The snapshot whose node [target] is counted in, as for [walkBackTo].
    frame: SelectionObserver.Snapshot? = null,
  ) {
    val before = observer.latestWithFreshBounds()
    // Mirrors [walkBackTo], down to the frame the arithmetic below is written in: [movingOffset] for
    // where we are, [towardAnchor] for which way a shrink travels. The two paths hand work to each
    // other whenever a chain dies, so a difference between them would be a difference in the middle
    // of one press.
    val current = before?.movingOffset()
    if (before == null || current == null) {
      gestures.releaseHeld()
      onDone(Outcome.NoSelection)
      return
    }
    val targetFrame = frame ?: before
    val onSeam = crossedNodes(targetFrame, before) && isSeamOf(targetFrame, before, target)
    if (crossedNodes(targetFrame, before) && !onSeam) {
      // Past the seam into another node, where [target] means nothing: see [walkBackTo].
      Diag.log("  held: landed on $current in another node, past $target — stopping there")
      releaseThenReport(origin, current, onDone)
      return
    }
    if (current == target || onSeam) {
      if (onSeam) Diag.log("  held: $current in the next node is the seam, which is $target")
      when {
        // Asked of the command, not of `grownBy(origin, target)`: after a node crossing the two are
        // offsets in different nodes, and 1 -> 15 on a `char ←` read as a grow of 14.
        growsSelection(command) -> pullBackThenRelease(origin, current, onDone)
        else -> releaseThenReport(origin, current, onDone)
      }
      return
    }
    if (guard >= MAX_HELD_CORRECTIONS) {
      Diag.log("  held: out of corrections at $current, wanted $target")
      releaseThenReport(origin, current, onDone)
      return
    }

    val at = gestures.heldAt()
    if (at == null) {
      // The chain died under us — which it now SAYS, under the same held: prefix as everything else.
      // The released path can still finish from wherever the selection actually is.
      Diag.log("  held: nothing is held any more — finishing on the released path")
      walkBackTo(target, command, origin, onDone, frame = targetFrame)
      return
    }

    val perChar = pixelsPerCharacter(before, crossingToward(current, target))
    val direction = towardAnchor.toFloat()
    // Characters still to travel, counted in the SHRINKING direction — `current - target` on the END
    // edge, its mirror on the START one. Written the old way round it came out negative on every
    // ordinary START step, and each of the signs below then read a normal step as an overshoot.
    val toLose = towardAnchor * (target - current)
    /*
     * More than one character to shrink means the grow before it jumped — a word SNAP, or a held
     * grow that ran on (10 -> 14 on a one-character correction). Held steps are aimed at one
     * character, so nothing else puts the edge two or more past its target. And a held shrink of
     * more than one character is measured to overshoot, three times in three on 2026-09-24: 19 -> 13
     * aimed at the six measured glyphs landed on 11, 19 -> 14 landed on 12, and 14 -> 12 landed on
     * 11. The released walk-back with the identical measured reach landed exactly, 19 -> 13 and
     * 19 -> 12. So lift and walk back released. The lift is safe from the platform's touch-up
     * filter, which only reverts an offset that changed within 150 ms of the lift: this runs after a
     * quiet settle and a link.
     */
    if (toLose > 1) {
      Diag.log("  held: $toLose characters back to $target — lifting to walk back released")
      gestures.releaseHeld { walkBackTo(target, command, origin, onDone, frame = targetFrame) }
      return
    }
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
    val reach = measuredReach(before, current, target) ?: (direction * (toLose - bias) * perChar)
    val to = crossRowDelta(before, current, target, BOUNDARY_BIAS)?.let { PointF(at.x + it.x, at.y + it.y) }
      ?: PointF(at.x + reach, at.y)

    gestureCount++
    if (!gestures.moveHeld(to)) {
      Diag.log("  held: the move was refused — finishing on the released path")
      walkBackTo(target, command, origin, onDone, frame = targetFrame)
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
          when {
            later != null -> heldCorrect(target, origin, command, onDone, guard + 1, targetFrame)
            // Still silent on a GROWING correction: that is the target app's word snap, not a
            // late announcement. See [heldSnapThrough].
            toLose < 0 -> heldSnapThrough(target, origin, command, onDone, guard + 1, frame = targetFrame)
            else -> releaseThenWalkBack(target, command, origin, onDone, targetFrame)
          }
        }
        return@awaitChange
      }
      heldCorrect(target, origin, command, onDone, guard + 1, targetFrame)
    }
  }

  /**
   * A held SHRINK that is still silent after the wait: lift, let the app settle, and finish on the
   * released [walkBackTo].
   *
   * Chrome ignores a short held shrink. Measured 2026-09-24 on the rig, on a served one-line
   * `Chrome is made by Google` at 32 px: held corrections of -11.7 px (12 -> 11, back across an
   * 18 px `a`) and -5.2 px (8 -> 7, back across an 8 px `i`) announced nothing, even after the wait.
   * A -19.5 px correction in the same run moved. Releasing there left the press two characters
   * on, as `Moved(10, 12)`. The released walk-back re-locates the handle from the announced offset,
   * and it is exact on a one-character shrink.
   *
   * The wait before [walkBackTo] matters. The lift can be revised, and a revision that arrived in
   * the middle of the walk-back's own drag would read as that drag's answer.
   */
  private fun releaseThenWalkBack(
    target: Int,
    command: PadCommand,
    origin: Int,
    onDone: (Outcome) -> Unit,
    frame: SelectionObserver.Snapshot?,
  ) {
    Diag.log("  held: the shrink to $target was ignored — lifting to walk back released")
    gestures.releaseHeld {
      val atLift = observer.latest
      if (atLift == null) {
        walkBackTo(target, command, origin, onDone, frame = frame)
        return@releaseHeld
      }
      awaitQuiet(atLift, 0, onResult = { walkBackTo(target, command, origin, onDone, frame = frame) })
    }
  }

  /**
   * Push the HELD pointer on past the target app's word snap, then let [heldCorrect] shrink back
   * onto [target].
   *
   * A growing correction that announces nothing, even after the wait, was not ignored. It ran into
   * the word snap in the platform's `Editor.SelectionHandleView.updatePosition`: while it grows from
   * a word boundary, the handle keeps its previous offset until the finger passes the MIDDLE of the
   * word it is entering, and then it jumps to that word's end. So from the end of `bravo` no reach
   * short of half of `charlie` moves anything, and a character-sized correction waits for news that
   * cannot come. Shrinking is character-granular from wherever the snap lands (the platform keeps
   * the finger's offset from the snapped handle as `mTouchWordDelta`), so overshooting to the word's
   * end and walking back is exact. This is [growOneCharacter]'s strategy, done while the pointer is
   * still down, which is where [heldCorrect] can walk back in one link.
   *
   * It is what turns the backwards press into a forward one. The grab's FIRST move event always
   * takes the platform's shrinking branch, because it has no previous x to compare against, and
   * that can pull the edge one character back. Measured 2026-09-24: `12` became `11`, the
   * correction towards `13` was snapped away, and the press reported `Moved(12, 11)`.
   *
   * Only reached after a grab that [HandleLocator.grabbedAHandle] has confirmed, so the pointer is
   * on the handle and a longer reach cannot land on the page. The toolbar is down for the whole
   * drag, so [selectionStillOnScreen] means nothing here and is deliberately not asked.
   */
  private fun heldSnapThrough(
    target: Int,
    origin: Int,
    command: PadCommand,
    onDone: (Outcome) -> Unit,
    guard: Int,
    attempt: Int = 1,
    frame: SelectionObserver.Snapshot? = null,
  ) {
    val before = observer.latestWithFreshBounds()
    val current = before?.movingOffset()
    if (before == null || current == null) {
      gestures.releaseHeld()
      onDone(Outcome.NoSelection)
      return
    }
    val at = gestures.heldAt()
    if (at == null) {
      Diag.log("  held: nothing is held any more — finishing on the released path")
      walkBackTo(target, command, origin, onDone, frame = frame)
      return
    }
    if (attempt > MAX_SNAP_THROUGH_ATTEMPTS) {
      Diag.log("  held: no snap after $MAX_SNAP_THROUGH_ATTEMPTS pushes, at $current, wanted $target")
      releaseThenReport(origin, current, onDone)
      return
    }

    // The width that matters is the next WORD's, which nothing here can measure, so step by the
    // node's AVERAGE character, as [growOneUnit] does, rather than by the glyph in front of the
    // handle — which on this path is usually the narrow space the snap is refusing to cross.
    val step = (averageCharacterWidth(before) * CHARS_PER_ATTEMPT).coerceAtLeast(STEP_PX)
    val x = (at.x - towardAnchor * step).coerceIn(0f, (gestures.screenWidth() - 1).toFloat())
    gestureCount++
    if (!gestures.moveHeld(PointF(x, at.y))) {
      Diag.log("  held: the move was refused — finishing on the released path")
      walkBackTo(target, command, origin, onDone, frame = frame)
      return
    }
    awaitChange(before) { after ->
      Diag.log(
        "  held snap-through $attempt: at $current, want $target, at=${at.x} -> x=$x -> " +
          if (after == null) "nothing" else "${after.low()}..${after.high()}"
      )
      when {
        after == null -> heldSnapThrough(target, origin, command, onDone, guard, attempt + 1, frame)
        !locator.grabbedAHandle(before, after) -> {
          gestures.releaseHeld()
          onDone(Outcome.HandleLost)
        }
        else -> {
          // A snap lands on a real word boundary whichever button asked for it — a `char ←` that
          // overshot can need a grow back — so this does not go through [recordBoundary], which
          // asks the button. The one-character test is the same one it applies.
          syncBoundaryContext(after)
          if (grownBy(current, after.movingOffset()) > 1) knownBoundaries += after.movingOffset()
          heldCorrect(target, origin, command, onDone, guard, frame)
        }
      }
    }
  }

  /**
   * `word →` from an edge that is not KNOWN to sit on a word boundary: walk the held pointer one
   * character at a time until the target app holds the handle still.
   *
   * The platform's `Editor.SelectionHandleView.updatePosition` snaps a grow to a word only when
   * `mInWord` is false, and `mInWord` is recomputed from the handle's current offset. So a grow from
   * inside a word does not snap: it follows the finger one character at a time, and [growOneUnit]'s
   * fixed reach simply stops wherever it ran out. Measured 2026-09-24: from 21, inside `delta`, one
   * `word →` landed on 23. Once the handle reaches the word's end, `mInWord` turns false and the
   * handle HOLDS there until the finger passes the middle of the next word. That hold is the one
   * signal the walk needs, and it reads no text:
   *
   *  - **+1 onto a boundary the set already knows** — the word's end, so stop there. On Chrome this
   *    is the ONLY stop: it does not hold at a word's end but steps on through the space and holds at
   *    the next word's START (see below).
   *  - **+1** — still inside the word; step again.
   *  - **nothing, after a push of one MEASURED glyph** — the hold. A push the platform sized cannot
   *    stay inside its own cell, so one is enough. On a `TextView` the hold is the word's end; on
   *    Chrome, with no known boundary to stop on, it is the next word's START, after a step through
   *    the space. Pushing again there jumped to the end of THAT word, two words reported as one:
   *    measured 2026-09-24 on a served paragraph, with the known-boundary stop switched off since
   *    nothing on the pad reaches such an edge, 29 inside `temporarily` walked 30, 31, 32, 33, was
   *    silent once at 33 and jumped to 37. Stopping on the first silent push lands on 33 in 5
   *    gestures, three runs of three, and the next `word →` grows from it to 37 in 2.
   *  - **nothing, twice** — the same hold, where the push was sized by an average: one silent push
   *    could be a step that stayed inside a wide glyph's cell, and two characters of travel from
   *    inside a word always move the handle.
   *  - **a jump of more than one** — taken as the step and reported, and recorded NOWHERE. On a
   *    `TextView` it is a snap to the end of a word short enough that one push passed its middle, so
   *    the walk has gone a short word too far. A first version walked back to the offset before the
   *    jump and recorded both, which put mid-word offsets into the set and was the very poisoning
   *    this exists to stop.
   *
   * So only the hold is recorded. Measured on the debug target: 21 -> 25 through `delta` and 13 -> 19
   * through `charlie`, each ending on two silent pushes.
   *
   * **Every travel is one MEASURED glyph, with no [STEP_PX] floor, and the grab detours toward the
   * anchor rather than away from it.** Both were measured on Chrome's error page, 2026-09-24, from
   * 19 inside `temporarily` (10..21), where the `l` it crosses is 8 px and the `y` 14 px:
   *
   *  - With the floor, the grab reached 12 px and left the pointer 4 px into the `y`; the next push,
   *    one `y` wide, then ended 4 px into the space, and the walk read `20 -> 22` as a jump. That was
   *    the whole of the "one past the word end" landing, not a Chrome granularity.
   *  - Without the floor but with the usual outward detour, four grabs of four landed on 23, inside
   *    the next word: the 60 px detour carries the handle into `down`, and Chrome keeps that history
   *    when the pointer comes back.
   *  - Detouring toward the anchor, the walk went 19 -> 20 -> 21 -> 22, one character a push, then held
   *    at 22 and jumped to 26 on the next push. That is Chrome's real shape, and why the known
   *    boundary is the stop: with `[10, 21]` seeded by the long-press, three presses of three landed
   *    on 21 in 2 gestures. The debug target's walk is unchanged by either: 21 -> 25 through `delta`.
   *
   * Falls back to [growOneUnit] whenever the chain will not start: slower and less exact, but the
   * old behaviour rather than a regression.
   */
  private fun growToWordEnd(command: PadCommand, onDone: (Outcome) -> Unit) {
    val before = observer.latestWithFreshBounds()
    if (before == null) {
      onDone(Outcome.NoSelection)
      return
    }
    val located = locator.locate(before, activeEdge, toolbarCentre())
    if (located == null) {
      acquireThenRetry(command, onDone)
      return
    }
    val handle = uncovered(located, onDone) ?: return
    val origin = before.movingOffset()
    val reach = (if (command.toRight) 1f else -1f) * pixelsPerCharacter(before, crossingOf(command))

    gestureCount++
    gestures.grabAndHold(handle, PointF(handle.x + reach, handle.y), detourBack = true) { grabbed ->
      if (!grabbed) {
        Diag.log("  word walk: the grab was refused — falling back to a plain grow")
        growOneUnit(command, onDone = onDone)
        return@grabAndHold
      }
      awaitChange(before) { after ->
        Diag.log(
          "  word walk grab: handle=(${handle.x}, ${handle.y}) reach=$reach -> " +
            if (after == null) "nothing" else "${after.low()}..${after.high()}"
        )
        when {
          after != null && !locator.grabbedAHandle(before, after) -> {
            gestures.releaseHeld()
            onDone(Outcome.HandleLost)
          }
          else -> {
            if (after != null) locator.rememberAnchor(handle.x - reach.coerceAtLeast(0f))
            walkHeldToWordEnd(command, origin, before, after, onDone)
          }
        }
      }
    }
  }

  /**
   * One decision of [growToWordEnd], on the landing [after] of a push made from [before].
   * [silentPushes] counts the pushes since the handle last moved; [stepped] says whether any push
   * has moved the edge by exactly one character, which is what makes a later jump a snap past a
   * boundary rather than the step itself.
   */
  private fun walkHeldToWordEnd(
    command: PadCommand,
    origin: Int,
    before: SelectionObserver.Snapshot,
    after: SelectionObserver.Snapshot?,
    onDone: (Outcome) -> Unit,
    silentPushes: Int = 0,
    stepped: Boolean = false,
    pushes: Int = 1,
    // Whether the push that produced [after] was sized by a glyph the platform measured.
    pushWasMeasured: Boolean = false,
  ) {
    val current = before.movingOffset()
    val crossed = after != null && before.source != null && after.source != null && before.source != after.source
    val moved = if (after == null) 0 else grownBy(current, after.movingOffset())
    when {
      // Into the next node: taken as the word step, and recorded only if [recordBoundary] reads it
      // as a snap from a known boundary.
      after != null && crossed -> {
        recordBoundary(before, after, command)
        releaseThenReport(origin, after.movingOffset(), onDone)
        return
      }
      // A push of one MEASURED glyph cannot stay inside its own cell, so one silent push after a step
      // is already the hold. On Chrome that hold is the next word's start: a second push jumps on to
      // that word's end, two words' worth of step reported as one.
      (after == null || moved == 0) && stepped && pushWasMeasured -> {
        knownBoundaries += current
        Diag.log("  word walk: held at $current after a measured push — a word boundary")
        pullBackThenRelease(origin, current, onDone)
      }
      after == null || moved == 0 -> {
        if (silentPushes + 1 >= WORD_WALK_SILENT_PUSHES) {
          if (stepped) {
            // Held still with the finger past it: the snap hold, so this is a word's end.
            knownBoundaries += current
            Diag.log("  word walk: held at $current — a word's end")
            releaseThenReport(origin, current, onDone)
          } else {
            // Nothing moved at all, so there is no evidence the pointer is on the handle. Let go and
            // hand the press to the plain grow, which escalates its reach properly — unless the lift
            // shows a push did move the edge after all. See [releaseThenGrow].
            Diag.log("  word walk: nothing moved from $current — falling back to a plain grow")
            releaseThenGrow(command, origin, before, onDone)
          }
          return
        }
        pushHeld(command, origin, before, onDone, silentPushes + 1, stepped, pushes)
      }
      moved < 0 -> {
        // The grab's first move event takes the platform's shrinking branch and can pull the edge
        // back a character (see [heldSnapThrough]). Keep walking from where it is.
        pushHeld(command, origin, after, onDone, 0, stepped, pushes)
      }
      // A boundary the set already knows is the word's end on every surface, and on Chrome it is the
      // only one there is: Chrome does not hold at a word's end, it steps on through the space.
      moved == 1 && after.movingOffset() in knownBoundaries -> {
        Diag.log("  word walk: reached the known boundary ${after.movingOffset()}")
        pullBackThenRelease(origin, after.movingOffset(), onDone)
      }
      moved == 1 -> pushHeld(command, origin, after, onDone, 0, true, pushes)
      else -> {
        Diag.log("  word walk: jumped $current -> ${after.movingOffset()} — taking it as the step, recording nothing")
        releaseThenReport(origin, after.movingOffset(), onDone)
      }
    }
  }

  /**
   * The word walk's fallback to [growOneUnit], with the lift read back first.
   *
   * "Nothing moved" is only what the walk's settle saw, and a push's announcement can arrive after
   * it. Measured 2026-09-24 on Chrome's error page, from 22, the start of `down` (22..26): two silent
   * pushes, the fallback, and then a plain grow that started from 26 — the second push had reached
   * the end of `down` late — and grew one more, into `or`. The press reported `26 -> 27` though it
   * began at 22, one character past a word end, in 1 run of 3 in each of two sittings.
   *
   * So the fallback lifts, waits for the announcements to stop, and grows only when the edge is
   * still where the press began. An edge already past it is the step, reported from [origin] and
   * recorded nowhere, like the walk's own jump: nothing says whether a late landing was a snap. A
   * landing in another node than [frame]'s is the step too, as the walk takes a crossing, unless it
   * is the seam [origin] already sat on, announced in the other frame.
   */
  private fun releaseThenGrow(
    command: PadCommand,
    origin: Int,
    frame: SelectionObserver.Snapshot,
    onDone: (Outcome) -> Unit,
  ) {
    gestures.releaseHeld {
      val atLift = observer.latest
      if (atLift == null) {
        growOneUnit(command, onDone = onDone)
        return@releaseHeld
      }
      awaitQuiet(
        atLift,
        0,
        onResult = { settled ->
          when {
            settled == null -> growOneUnit(command, onDone = onDone)
            settled.isEmpty() -> {
              Diag.log("  word walk: the lift collapsed the selection — a lost handle, not a move")
              onDone(Outcome.HandleLost)
            }
            movedPast(frame, origin, settled) -> {
              Diag.log(
                "  word walk: a late push had moved $origin -> ${settled.movingOffset()} — taking it as the " +
                  "step, recording nothing"
              )
              onDone(Outcome.Moved(origin, settled.movingOffset()))
            }
            else -> growOneUnit(command, onDone = onDone)
          }
        },
      )
    }
  }

  private fun movedPast(frame: SelectionObserver.Snapshot, origin: Int, settled: SelectionObserver.Snapshot): Boolean =
    if (crossedNodes(frame, settled)) !isSeamOf(frame, settled, origin)
    else grownBy(origin, settled.movingOffset()) > 0

  /** Push the held pointer one character further in the growing direction and hand the landing back. */
  private fun pushHeld(
    command: PadCommand,
    origin: Int,
    from: SelectionObserver.Snapshot,
    onDone: (Outcome) -> Unit,
    silentPushes: Int,
    stepped: Boolean,
    pushes: Int,
  ) {
    val at = gestures.heldAt()
    if (at == null) {
      Diag.log("  word walk: nothing is held any more, at ${from.movingOffset()}")
      onDone(Outcome.Moved(origin, from.movingOffset()))
      return
    }
    if (pushes >= MAX_WORD_WALK_PUSHES) {
      Diag.log("  word walk: out of pushes at ${from.movingOffset()}")
      releaseThenReport(origin, from.movingOffset(), onDone)
      return
    }
    // Asked before the reach is sized, so a refusal here that the sizing's own re-ask turns into an
    // answer reads as unmeasured: the safe side, which keeps the two-push rule.
    val measured = locator.characterGeometry(from, activeEdge, crossingOf(command)) != null
    val reach = (if (command.toRight) 1f else -1f) * pixelsPerCharacter(from, crossingOf(command))
    val x = (at.x + reach).coerceIn(0f, (gestures.screenWidth() - 1).toFloat())
    gestureCount++
    if (!gestures.moveHeld(PointF(x, at.y))) {
      Diag.log("  word walk: the move was refused at ${from.movingOffset()}")
      releaseThenReport(origin, from.movingOffset(), onDone)
      return
    }
    awaitChange(from) { after ->
      Diag.log(
        "  word walk push ${pushes + 1}: at ${from.movingOffset()}, reach=$reach x=$x -> " +
          if (after == null) "nothing" else "${after.low()}..${after.high()}"
      )
      if (after != null && !locator.grabbedAHandle(from, after)) {
        gestures.releaseHeld()
        onDone(Outcome.HandleLost)
        return@awaitChange
      }
      walkHeldToWordEnd(command, origin, from, after, onDone, silentPushes, stepped, pushes + 1, measured)
    }
  }

  /** The node's width over its length where it is one line, otherwise whatever a step can measure. */
  private fun averageCharacterWidth(snapshot: SelectionObserver.Snapshot): Float {
    val bounds = snapshot.bounds
    return if (snapshot.sourceIsOneLine() && bounds != null && snapshot.sourceLength > 0) {
      (bounds.width().toFloat() / snapshot.sourceLength).coerceIn(MIN_CHAR_PX, MAX_CHAR_PX)
    } else {
      pixelsPerCharacter(snapshot, Crossing.RIGHTWARD)
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
    silentProbes: Int = 0,
    // The snapshot whose node [target] is counted in: the one the walk started from, since every
    // caller computes its target in the frame of the selection it hands over.
    frame: SelectionObserver.Snapshot? = null,
  ) {
    val before = observer.latestWithFreshBounds()
    val current = before?.movingOffset()
    if (before == null || current == null) {
      onDone(Outcome.NoSelection)
      return
    }
    val targetFrame = frame ?: before
    if (crossedNodes(targetFrame, before)) {
      /*
       * The walk left the target's node, and [target] is a number in a frame that this announcement
       * does not use. Measured 2026-09-24 on `ERR_INVALID_URL`: the walk back from 2 to 0 of the
       * paragraph reached the seam and Chrome announced it in the previous node's frame, as `9..15`.
       * Read as fifteen characters short of 0, the next drag took the handle back across the whole
       * node and destroyed the selection.
       *
       * Mapped across the seam, as [crossedTarget] does ([isSeamOf]), a landing there IS the
       * target. Anywhere else in that node is past the seam, and correcting
       * that would be a grow reckoned in a frame this walk does not share, so the walk stops where
       * it is, with the selection intact.
       */
      Diag.log(
        if (isSeamOf(targetFrame, before, target)) {
          "  shrink: $current in the next node is the seam, which is $target — done"
        } else {
          "  shrink: landed on $current in another node, past $target — stopping there"
        }
      )
      onDone(Outcome.Moved(origin, current))
      return
    }
    if (current == target || guard >= MAX_CORRECTIONS) {
      onDone(Outcome.Moved(origin, current))
      return
    }

    // Counted in the shrinking direction, so "past the target" below means the same thing on both
    // edges. See [towardAnchor].
    val toLose = towardAnchor * (target - current)
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
      if (retriesLeft == 0) {
        Diag.log("  shrink: overshot to $current past $target and out of retries")
        onDone(Outcome.Moved(origin, current))
        return
      }
      awaitSelectionOnScreen { onScreen ->
        if (onScreen) {
          Diag.log("  shrink: overshot to $current past $target; starting the step over from here")
          growOneCharacter(command, onDone, retriesLeft - 1, origin, target)
        } else {
          Diag.log("  shrink: overshot to $current past $target and the selection is gone from screen")
          onDone(Outcome.Moved(origin, current))
        }
      }
      return
    }

    val perChar = pixelsPerCharacter(before, crossingToward(current, target))
    val direction = towardAnchor.toFloat()
    val located = locator.locate(before, activeEdge, toolbarCentre())
    if (located == null) {
      onDone(Outcome.HandleLost)
      return
    }
    // A walk that has already moved reports that move, not the refusal: the selection did change.
    val handle = uncovered(located) { onDone(if (current != origin) Outcome.Moved(origin, current) else it) }
      ?: return

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
    val reach = (measuredReach(before, current, target) ?: (direction * (toLose - BOUNDARY_BIAS) * perChar)) +
      // A probe that moved nothing was too short to leave the character it started in; lengthen it
      // rather than repeat it. Counted in SILENT probes, not in corrections: a correction that did
      // move is aiming afresh from where it landed, and lengthening it anyway is what carried a walk
      // from 13 past its target of 12 and on to 11, measured 2026-09-24.
      direction * silentProbes * perChar * FINAL_STEP_FRACTION
    // A target on another row — a word ← from a line's first word — is aimed at on its own row.
    val wrap = crossRowDelta(before, current, target, BOUNDARY_BIAS)
    val aim = if (wrap == null) {
      PointF(handle.x + reach, handle.y)
    } else {
      PointF(handle.x + wrap.x + direction * silentProbes * perChar * FINAL_STEP_FRACTION, handle.y + wrap.y)
    }

    /*
     * And never past the screen's edge, as [growOneUnit] does not go. Measured 2026-09-24: a walk
     * back from 64 to 63 just after a row change went silent six times while its reach escalated
     * leftward from a handle at x 84, and the seventh aimed below 0, which threw and killed the
     * service. A silent try whose aim was already clamped has nothing further on to reach.
     */
    val width = gestures.screenWidth().toFloat()
    val previousX = aim.x - direction * perChar * FINAL_STEP_FRACTION
    if (silentProbes > 0 && (previousX < 0f || previousX > width - 1)) {
      Diag.log("  shrink: the last reach already left the screen at x $previousX — stopping at $current")
      onDone(Outcome.Moved(origin, current))
      return
    }
    val to = PointF(aim.x.coerceIn(0f, width - 1), aim.y)

    gestureCount++

    gestures.drag(handle, to, DRAG_MS) { completed ->
      if (!completed) {
        onDone(Outcome.HandleLost)
        return@drag
      }
      awaitChange(before) { after ->
        Diag.log(
          "  shrink ${guard + 1}: $current -> target $target, lose $toLose x ${perChar}px, " +
            "handle=(${handle.x}, ${handle.y}) to=(${to.x}, ${to.y}) -> " +
            if (after == null) "nothing" else "${after.low()}..${after.high()}"
        )
        when {
          // Nothing moved: the creep was too short. Try again slightly longer rather than give up
          // one character out.
          after == null ->
            if (guard + 1 < MAX_CORRECTIONS) {
              awaitSelectionOnScreen { onScreen ->
                if (onScreen) {
                  walkBackTo(target, command, origin, onDone, guard + 1, retriesLeft, silentProbes + 1, targetFrame)
                } else {
                  onDone(Outcome.Moved(origin, current))
                }
              }
            } else {
              onDone(Outcome.Moved(origin, current))
            }
          !locator.grabbedAHandle(before, after) -> onDone(Outcome.HandleLost)
          else -> walkBackTo(target, command, origin, onDone, guard + 1, retriesLeft, frame = targetFrame)
        }
      }
    }
  }

  /**
   * How wide one character is, from the node's own geometry — a width divided by a length, never a
   * look at the characters. Only meaningful where the box is a single line; elsewhere fall back to
   * the probe step and let the correction loop do the work.
   */
  private fun pixelsPerCharacter(snapshot: SelectionObserver.Snapshot, crossing: Crossing): Float {
    val bounds = snapshot.bounds
    if (!snapshot.sourceIsOneLine() || bounds == null || snapshot.sourceLength <= 0) {
      /*
       * A wrapped box divides ONE line's width by EVERY line's characters, so its average is a
       * fraction of the truth and is not worth using. The platform's own rectangle for the moving
       * character is, where it will give one — and it has to be used, or locating the handle inside
       * a wrap buys nothing: the step would then aim [STEP_PX] = 12 px where a character is 28-64,
       * announce nothing, and report the same `HandleLost` from one rung further on. That is exactly
       * the under-aiming measured on the article title that was being misclassified as wrapped.
       *
       * Free next to [HandleLocator.locate], which has already asked for this same rectangle on this
       * same snapshot and memoised the answer.
       */
      return (
        locator.characterGeometry(snapshot, activeEdge, crossing)
          // And where the platform will not measure a character, a child node that hugs ONE line
          // will — its width over its length is one line's pitch rather than the parent's average
          // over every line. Same memoised walk `locate` has already paid for on this snapshot.
          ?: locator.lineNodeGeometry(snapshot, activeEdge)
        )
        ?.characterWidth
        ?.coerceIn(MIN_CHAR_PX, MAX_CHAR_PX)
        ?: STEP_PX
    }
    /*
     * A one-line box's average is a good enough ESTIMATE and a bad MEASUREMENT, and a held step
     * needs the measurement.
     *
     * The average is the node's width over its length, so it describes the string rather than the
     * character in front of the handle — and proportional text puts those a long way apart. Measured
     * on `alpha bravo charlie delta echo`, whose average is 27.73 px: stepping left from offset 24
     * crosses the `t` at index 23, one of the narrowest glyphs in the string, and a reach of one
     * *average* character moved **two real ones** (24 → 22). The correction then travelled
     * `(1 - BOUNDARY_BIAS)` of an average character to cross that same narrow glyph, which parked
     * the pointer in the FAR half of the target's cell — and the target app, which floors while the
     * finger is down and finalises to the nearest boundary when it lifts, rounded it back. Five
     * presses in a row announced the step and left the selection where it started: to the user, a
     * dead button.
     *
     * So ask the platform for the character this step is actually about to cross, and keep the
     * average only for the surfaces that will not answer. Chrome's page content is one of them — it
     * returns the node's own bounds, which [HandleLocator.characterGeometry] detects — so this costs
     * a round trip there and changes nothing, and it corrects the step wherever the platform is
     * honest.
     */
    val average = (bounds.width().toFloat() / snapshot.sourceLength).coerceIn(MIN_CHAR_PX, MAX_CHAR_PX)
    val measured = locator.characterGeometry(snapshot, activeEdge, crossing)?.characterWidth ?: return average
    return measured.coerceIn(MIN_CHAR_PX, MAX_CHAR_PX)
  }

  /**
   * Which character a step is about to cross, from the direction the pointer will travel.
   *
   * [PadCommand.toRight] is the pointer's direction on both edges — the reach is applied straight to
   * the handle's x — so it answers this directly. See [Crossing] for why it has to be asked.
   */
  private fun crossingOf(command: PadCommand): Crossing =
    if (command.toRight) Crossing.RIGHTWARD else Crossing.LEFTWARD

  /** The same question where the travel is a correction onto [target] rather than a button press. */
  private fun crossingToward(current: Int, target: Int): Crossing =
    if (target >= current) Crossing.RIGHTWARD else Crossing.LEFTWARD

  /**
   * How far in x to move from the caret at [current] so the handle lands on [target]: the real glyphs
   * between them, less [BOUNDARY_BIAS] of the last one crossed so the finger stops inside the
   * target's cell rather than on its boundary. The same aim the per-glyph formula takes, with the
   * run measured instead of one glyph's width multiplied by a count, which is only right for a run
   * of one. Null where the platform will not measure the run, and the caller keeps that formula.
   *
   * The distance is the run's outer edges, [CharacterRun.span], never its widths added up: Chrome's
   * rectangles overlap their neighbours. Measured 2026-09-24 on a served paragraph, `char →` from 21,
   * the start of `temporarily`: the grow snapped to 32 and the walk back to 22 was sized by ten widths
   * summing to 162 px between carets 150 px apart. The finger stopped 12 px short, inside the `t`, and
   * Chrome put the edge back on 21, three rounds of three per press. Sized by the span, the same walk
   * landed on 22 in one drag, three presses of three, and the `char ←` mirror on the START edge
   * (32 -> 31, across the same word) did the same.
   */
  private fun measuredReach(snapshot: SelectionObserver.Snapshot, current: Int, target: Int): Float? {
    if (current == target) return 0f
    val run = locator.characterRun(snapshot, minOf(current, target), maxOf(current, target)) ?: return null
    val lastCrossed = if (target < current) run.widths.first() else run.widths.last()
    val sign = if (target > current) 1f else -1f
    return sign * (run.span - BOUNDARY_BIAS * lastCrossed)
  }

  /**
   * The move that carries the handle from the caret at [current] to the caret at [target] when the
   * two are on DIFFERENT ROWS of a wrapped node, or null when they share a row or either is unmeasured.
   *
   * Every other reach here is horizontal, and across a line wrap a horizontal reach has nothing to
   * enter. Measured 2026-09-24 on the rig against a seven-line paragraph: with `small` selected at the
   * end of line 1 (the end edge on 28, the space line 1 wraps at), `word →` located the handle within
   * 3 px, reached 18 px right past the line's end and announced nothing, three presses in three,
   * because `frustration` starts line 2. With the toolbar also over the handle the escalation ran to
   * a 360 px reach, off the screen. The rows come from the same per-character rectangles that located
   * the handle, so this reads no text.
   *
   * The x lands [bias] of the target's glyph short of its caret, on the side it was approached from,
   * the same aim the horizontal formulas take; `0` aims at the caret itself.
   */
  private fun crossRowDelta(snapshot: SelectionObserver.Snapshot, current: Int, target: Int, bias: Float): PointF? {
    if (snapshot.sourceIsOneLine() || current == target) return null
    val crossing = crossingToward(current, target)
    val from = locator.characterGeometry(snapshot, activeEdge, crossing, current) ?: return null
    val to = locator.characterGeometry(snapshot, activeEdge, crossing, target) ?: return null
    if (kotlin.math.abs(to.lineBottom - from.lineBottom) <= HandleLocator.LIE_TOLERANCE) return null
    val sign = if (target > current) 1f else -1f
    val delta = PointF(to.caretX - from.caretX - sign * bias * to.characterWidth, to.lineBottom - from.lineBottom)
    Diag.log(
      "  wrap: $current and $target are on different rows (${from.lineBottom} -> ${to.lineBottom}) — " +
        "travelling (${delta.x}, ${delta.y})"
    )
    return delta
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
    val snapshot = observer.latest ?: run {
      onDone(Outcome.NoSelection)
      return
    }
    val current = snapshot.movingOffset()
    /*
     * The nearest boundary in the SHRINKING direction, which is below the END edge and above the
     * START one. Taking the largest below for both put `word ⇥` on the START edge behind itself.
     *
     * Bounded at the anchor as well, because the set outlives a `⇄ swap`: the boundaries it holds
     * were learned while the OTHER edge travelled, so they lie on the far side of the anchor from
     * the edge now moving, and one of them as a target would drag the moving handle straight past
     * the anchor. `from`/`to` then arrive reversed, [movingOffset] starts answering for the wrong
     * end, and the loop corrects against it.
     *
     * **And the anchor itself is not a target either: the floor is one character short of it.** A
     * desktop keyboard collapses the selection to a caret there; a dragged handle cannot. A
     * `TextView` keeps the last character and spends every further gesture discovering that, and
     * Chrome is worse — measured 2026-09-24 on the served seven-line paragraph, `word ←` from
     * `23..28` got to `23..24` on the first drag and the correction onto 23 carried the handle over
     * the anchor, leaving `22..23`, the space BEFORE the word, reported as `HandleLost`. So the
     * anchor is replaced by the floor, and a press that starts on the floor touches nothing.
     */
    val anchor = snapshot.anchorOffset()
    val floor = anchor - towardAnchor
    val anchorHere = anchorNodeKey == nodeKey(snapshot)
    if (anchorHere && towardAnchor * (floor - current) <= 0) {
      Diag.log("  shrink: $current is already one character from the anchor at $anchor — touching nothing")
      onDone(Outcome.AtFloor)
      return
    }
    val target = if (towardAnchor < 0) {
      knownBoundaries.filter { it in anchor until current }.maxOrNull()
    } else {
      knownBoundaries.filter { it in (current + 1)..anchor }.minOrNull()
    }?.let { if (anchorHere && it == anchor) floor else it }
    if (target == null) {
      /*
       * Honest degradation: one character, and say so rather than pretend it was a word.
       *
       * Much rarer than it was, because the set no longer starts empty — a long-press seeds both of
       * its edges (see [noteSelectionEvent]) and every grow adds one. What is left here is the case
       * that cannot be fixed at all: an edge that has travelled into ground no grow has covered, on
       * a target whose word knowledge is only ever expressed by snapping. See [knownBoundaries].
       */
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
    val located = locator.locate(before, activeEdge, toolbarCentre()) ?: run {
      acquireThenRetry(command, onDone)
      return
    }
    val handle = uncovered(located, onDone) ?: return
    val target = PointF(
      if (command.toRight) (gestures.screenWidth() - EDGE_INSET).toFloat() else EDGE_INSET.toFloat(),
      if (command.toRight) (gestures.screenHeight() - EDGE_INSET).toFloat() else EDGE_INSET.toFloat(),
    )
    gestureCount++
    gestures.dragAndHold(handle, target, DRAG_MS, holdMs) { completed ->
      awaitChange(before) { after ->
        when {
          !completed -> onDone(Outcome.HandleLost)
          after == null -> onDone(Outcome.Moved(before.movingOffset(), before.movingOffset()))
          after.isEmpty() -> onDone(Outcome.HandleLost)
          else -> {
            // A sweep that grew landed where the app snapped it, same as any other grow. It will
            // usually have crossed into another node, which [recordBoundary] handles by re-keying,
            // and records only from a known boundary like any other grow.
            recordBoundary(before, after, command)
            onDone(Outcome.Moved(before.movingOffset(), after.movingOffset()))
          }
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
    /*
     * The scan hunts sideways along ONE row — the bottom of the source node's box. On a box measured
     * to span wrapped lines that is the last line, and the moving edge is on it only by luck; every
     * probe aimed at the wrong row is a touch on the page, which collapses the selection and on a
     * link navigates. So this is the one place where "we cannot locate the handle" has to stay
     * "we cannot locate the handle" rather than becoming a search.
     *
     * The two rungs that CAN reach inside a wrap both answer with a row rather than assuming one —
     * [HandleLocator.characterGeometry], the platform's own rectangle for the moving character, and
     * [HandleLocator.lineNodeGeometry], a child node that hugs one line. Chrome is measured to refuse
     * both on page content, and when they have declined there is nothing here left to find.
     */
    if (!locator.scanRowIsKnown(snapshot)) {
      Diag.log(
        "  acquire: the source box spans wrapped lines, so the handle's row is unknown — " +
          "refusing to probe rather than risk the selection"
      )
      onDone(Outcome.RowUnknown)
      return
    }
    val ring = (probe + 1) / 2
    val x = if (probe % 2 == 1) centre + ring * HandleLocator.SCAN_STEP else centre - ring * HandleLocator.SCAN_STEP
    val from = uncovered(PointF(x, snapshot.bounds.bottom + locator.handleDrop), onDone) ?: return
    val y = from.y

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
            onDone(Outcome.Moved(snapshot.movingOffset(), after.movingOffset()))
          }
          // The probe wrecked the selection. Stop; there is nothing left to hunt for.
          after != null -> onDone(Outcome.HandleLost)
          else -> acquireThenRetry(command, onDone, probe + 1)
        }
      }
    }
  }

  /**
   * Remember an offset the target app SNAPPED to, which is the only boundary evidence a step ever
   * produces. Two conditions, both of which the old unconditional version failed.
   *
   * **The step must have GROWN the selection.** The measured granularity rule is directional:
   * growing snaps a whole word, shrinking moves one character. Every call site here is reached by
   * both — see [growsSelection] — so recording on arrival filled the set with the landings of
   * character steps, which `word ←` then jumped to and the pad announced as a word. That is a
   * correctness bug rather than an imprecision: the set's whole claim is that everything in it is a
   * boundary, and `word ←` has nothing else to check it against.
   *
   * **It must have grown by more than one character.** A plain `TextView` grows by character from
   * inside a word, so a one-character grow there proves nothing at all; on a snapping
   * target it means a word one character wide, which the set can afford to miss. Across a node
   * change the offsets are in different frames, so the distance is counted across the seam
   * ([grownAcrossSeam]); where a length is unknown nothing is recorded.
   *
   * **A crossing is held to the same tests.** It used to be believed outright, on the theory that a
   * grow only crosses a node by snapping. A held `char →` crosses by following the finger: measured
   * 2026-09-24 on `ERR_INVALID_URL`, from `9..15` of `"chrome://terms/"` the grab landed on `0..2`,
   * inside `might`, and 2 went into the set before the correction walked the edge back to 1.
   *
   * **A character step records nothing at all**, so it never reaches this. Across a seam its landing
   * is where the finger was: from `9..14`, `14` seeded by the long-press, the same `char →` landed on
   * `0..2` again, three characters on and still mid-`might`. Inside one node on Chrome it is where
   * the grab's detour left the handle (see [heldCharacterStep]). No distance test tells either from
   * a snap.
   *
   * **And it must have STARTED on a known boundary.** The platform snaps a grow only while
   * `mInWord` is false, i.e. only from a boundary; from inside a word it follows the finger one
   * character at a time, so a reach of two characters lands two characters on. Measured 2026-09-24:
   * two `char →` took the end to 21, inside `delta`, and a `word →` that landed on 23 went into the
   * set. A grow from a boundary the set has not learned is missed, which fails safe.
   */
  private fun recordBoundary(
    before: SelectionObserver.Snapshot,
    after: SelectionObserver.Snapshot,
    command: PadCommand,
  ) {
    if (!growsSelection(command)) return
    // Asked before the re-key below, which clears the set when the node changes.
    val fromKnownBoundary = before.movingOffset() in knownBoundaries
    /*
     * Re-key HERE and not only at the top of the next press.
     *
     * A grow can carry the moving end into the next node, and the boundary it lands on is a
     * boundary OF THAT NODE. Syncing the context only in [perform] meant the next press noticed the
     * change and cleared the set — discarding the one boundary that had just been paid a gesture
     * for, on the node the loop had only just arrived in, which is precisely where the set is
     * emptiest.
     */
    syncBoundaryContext(after)
    val grown = if (crossedNodes(before, after)) {
      grownAcrossSeam(before, after, command) ?: run {
        Diag.log("  not recording ${after.movingOffset()}: it crossed a node of unknown length")
        return
      }
    } else {
      grownBy(before.movingOffset(), after.movingOffset())
    }
    if (!fromKnownBoundary) {
      if (grown > 1) {
        Diag.log("  not recording ${after.movingOffset()}: the grow started at ${before.movingOffset()}, not on a known boundary")
      }
      return
    }
    if (grown <= 1) return
    knownBoundaries += after.movingOffset()
  }

  /**
   * How many characters a grow that crossed from [before]'s node into [after]'s travelled, assuming
   * the two nodes are adjacent — the same assumption as [crossedTarget]. Null when either length is
   * unknown, since the seam cannot then be placed.
   */
  private fun grownAcrossSeam(
    before: SelectionObserver.Snapshot,
    after: SelectionObserver.Snapshot,
    command: PadCommand,
  ): Int? {
    if (before.sourceLength < 0 || after.sourceLength < 0) return null
    return if (command.toRight) {
      before.sourceLength - before.movingOffset() + after.movingOffset()
    } else {
      before.movingOffset() + after.sourceLength - after.movingOffset()
    }
  }

  /**
   * How many characters the moving edge travelled AWAY from the anchor — negative when it came back.
   *
   * Signed on purpose. A growing press can land BEHIND where it started: the grab's first move event
   * reaches the platform's shrinking branch, and 11 -> 9 on `bravo` is measured (2026-09-24). With
   * the old `abs` that read as a two-character grow, so 9 and 8, both mid-`bravo`, went into
   * [knownBoundaries], where `word ←` would take them for words.
   */
  private fun grownBy(from: Int, to: Int): Int = -towardAnchor * (to - from)

  /**
   * A selection change the pad did not cause, offered by the service so a **fresh selection can seed
   * the set for free**.
   *
   * A long-press is the only way a user starts a selection on a page, and it snaps a whole word — so
   * both of its edges are word boundaries, and neither costs a gesture to learn. Without this a
   * fresh long-press followed by `word ←` had nothing remembered at all and degraded to a single
   * character, which is the honest answer to an empty set and a poor one to the commonest state
   * there is.
   *
   * **Telling a fresh selection from a moved handle takes no text and no timer**: a long-press
   * replaces both edges, while a handle drag — the user's finger, or a late snap announced after one
   * of our own presses finished — moves one edge and leaves the anchor exactly where it was. So
   * "both edges differ" is the test, and it fails safe: an unrecognised long-press seeds nothing,
   * which is where this started.
   *
   * Offsets stay valid across a long-press within the same node, so a second one adds to the set
   * rather than replacing it; [syncBoundaryContext] clears only when the node itself changes.
   */
  fun noteSelectionEvent(before: SelectionObserver.Snapshot?) {
    val now = observer.latest ?: return
    if (now.isEmpty()) return
    val freshSelection = before == null || (now.low() != before.low() && now.high() != before.high())
    if (!freshSelection) return
    syncBoundaryContext(now)
    anchorNodeKey = nodeKey(now)
    knownBoundaries += now.low()
    knownBoundaries += now.high()
    Diag.log("seeded from a fresh selection at ${now.low()}..${now.high()}: boundaries=$knownBoundaries")
  }

  private fun nodeKey(snapshot: SelectionObserver.Snapshot): String =
    "${snapshot.packageName}|${snapshot.bounds}|${snapshot.sourceLength}"

  /** Offsets are local to the source node, so a change of node invalidates every remembered one. */
  private fun syncBoundaryContext(snapshot: SelectionObserver.Snapshot) {
    val key = nodeKey(snapshot)
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

    /**
     * How long [awaitSelectionOnScreen] waits for the target app to put its toolbar back after a
     * drag before believing it gone. The pad's own mask lingers [AscAccessibilityService]'s 700 ms
     * over the same gap for the same reason; this is a little longer, since a wrong "gone" here ends
     * a press that would have worked.
     */
    const val TOOLBAR_RETURN_MS = 900L

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
     * How far [pullBackThenRelease] moves the held pointer back towards the anchor before a grow
     * lifts. The distance is not what matters, the DIRECTION of the last move is, so this is the
     * smallest move there is: a pixel, well inside the target's cell. See that function for the
     * measurement.
     */
    const val LIFT_PULLBACK_PX = 1f

    /** How long the pull-back is given to play before the lift, about one link. */
    const val LIFT_PULLBACK_MS = 150L

    /**
     * How many pushes [heldSnapThrough] may make towards a word's middle. Each is [CHARS_PER_ATTEMPT]
     * average characters, so this reaches past the middle of a word of about twenty characters —
     * with the pointer already one or two characters in — and each silent push costs a settle.
     */
    const val MAX_SNAP_THROUGH_ATTEMPTS = 6

    /**
     * Silent pushes in a row that [growToWordEnd] takes as the word-end hold when its pushes were sized
     * by an average. Two characters of travel from inside a word always move the handle; one can stay
     * inside its own cell. A push of a measured glyph cannot, and ends the walk on its own.
     */
    const val WORD_WALK_SILENT_PUSHES = 2

    /** Pushes one [growToWordEnd] may make: a long word plus the two silent pushes that end it. */
    const val MAX_WORD_WALK_PUSHES = 24

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
