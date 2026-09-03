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

  fun perform(command: PadCommand, onDone: (Outcome) -> Unit) {
    val snapshot = observer.latest
    if (snapshot == null || snapshot.isEmpty()) {
      onDone(Outcome.NoSelection)
      return
    }
    rememberBoundaryContext(snapshot)

    val growing = (activeEdge == Edge.END) == command.toRight
    when (command.unit) {
      PadCommand.Unit.WORD -> if (growing) growOneUnit(command, onDone = onDone) else shrinkByWord(command, onDone)
      PadCommand.Unit.CHARACTER -> if (growing) growOneCharacter(command, onDone) else growOneUnit(command, onDone = onDone)
      PadCommand.Unit.PAGE -> sweepToEdge(command, PAGE_HOLD_MS, onDone)
      PadCommand.Unit.DOCUMENT -> sweepToEdge(command, DOCUMENT_HOLD_MS, onDone)
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
    val before = observer.latest
    if (before == null) {
      onDone(Outcome.NoSelection)
      return
    }
    val handle = locator.locate(before, activeEdge, toolbarCentre())
    if (handle == null) {
      acquireThenRetry(command, onDone)
      return
    }

    val reach = STEP_PX * (attempt + 1) * (if (command.toRight) 1 else -1)
    gestures.drag(handle, PointF(handle.x + reach, handle.y), DRAG_MS) { completed ->
      if (!completed) {
        onDone(Outcome.HandleLost)
        return@drag
      }
      handler.postDelayed({
        val after = observer.latest
        val fired = after != null && after.atMs != before.atMs
        when {
          fired && locator.grabbedAHandle(before, after!!) -> {
            recordBoundary(after)
            locator.rememberAnchor(handle.x - reach.coerceAtLeast(0f))
            onDone(Outcome.Moved(before.high(), after.high()))
          }
          fired -> onDone(Outcome.HandleLost)
          attempt + 1 < MAX_ATTEMPTS -> growOneUnit(command, attempt + 1, onDone)
          else -> onDone(Outcome.HandleLost)
        }
      }, SETTLE_MS)
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
      walkBackTo(target, command, onDone)
    }
  }

  /** Shrink one character at a time until the offset is [target]. */
  private fun walkBackTo(target: Int, command: PadCommand, onDone: (Outcome) -> Unit, guard: Int = 0) {
    val current = observer.latest?.high()
    if (current == null) {
      onDone(Outcome.NoSelection)
      return
    }
    if (current == target || guard >= MAX_WALK_BACK) {
      onDone(Outcome.Moved(current, current))
      return
    }
    val back = PadCommand.entries.first {
      it.unit == PadCommand.Unit.CHARACTER && it.toRight != command.toRight
    }
    growOneUnit(back) { outcome ->
      if (outcome !is Outcome.Moved) onDone(outcome) else walkBackTo(target, command, onDone, guard + 1)
    }
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
    walkBackTo(target, command, onDone)
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
    val before = observer.latest ?: run {
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
    gestures.dragAndHold(handle, target, DRAG_MS, holdMs) { completed ->
      handler.postDelayed({
        val after = observer.latest
        when {
          !completed -> onDone(Outcome.HandleLost)
          after == null -> onDone(Outcome.NoSelection)
          after.isEmpty() -> onDone(Outcome.HandleLost)
          else -> onDone(Outcome.Moved(before.high(), after.high()))
        }
      }, SETTLE_MS)
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
    val snapshot = observer.latest
    if (centre == null || snapshot?.bounds == null || probe > HandleLocator.SCAN_MAX_PROBES) {
      onDone(Outcome.HandleLost)
      return
    }
    val ring = (probe + 1) / 2
    val x = if (probe % 2 == 1) centre + ring * HandleLocator.SCAN_STEP else centre - ring * HandleLocator.SCAN_STEP
    val y = snapshot.bounds.bottom + HandleLocator.HANDLE_DROP
    val from = PointF(x, y)

    gestures.drag(from, PointF(x + HandleLocator.SCAN_NUDGE, y), DRAG_MS) { completed ->
      if (!completed) {
        onDone(Outcome.HandleLost)
        return@drag
      }
      handler.postDelayed({
        val after = observer.latest
        val fired = after != null && after.atMs != snapshot.atMs
        when {
          fired && locator.grabbedAHandle(snapshot, after!!) -> {
            locator.rememberAnchor(2 * centre - x)
            onDone(Outcome.Moved(snapshot.high(), after.high()))
          }
          // The probe wrecked the selection. Stop; there is nothing left to hunt for.
          fired -> onDone(Outcome.HandleLost)
          else -> acquireThenRetry(command, onDone, probe + 1)
        }
      }, SETTLE_MS)
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
    /** One probe step. The escalation, not this, is what covers a wide word. */
    const val STEP_PX = 12f
    const val DRAG_MS = 150L
    const val SETTLE_MS = 320L

    /** Must exceed the widest word on screen once multiplied by [STEP_PX]. */
    const val MAX_ATTEMPTS = 12

    /** A character step overshoots one word, so the walk back is bounded by a word's length. */
    const val MAX_WALK_BACK = 40

    const val EDGE_INSET = 24
    const val PAGE_HOLD_MS = 1200L
    const val DOCUMENT_HOLD_MS = 6000L
  }
}
