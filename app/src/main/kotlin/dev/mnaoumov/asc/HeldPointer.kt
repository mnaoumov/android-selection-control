package dev.mnaoumov.asc

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler

/**
 * A synthetic finger that presses once, moves as often as asked, and lifts when told.
 *
 * **NOT WIRED IN, and now for a measured reason rather than an unfinished one.** The chain itself
 * works: a grab is accepted, sixty-odd links play in a row, the lift is clean and the next grab is
 * accepted again. What does not work is the target app's side of it.
 *
 * The pattern, from four builds:
 *
 * - A held stroke that presses, detours past the slop and travels to its destination moves the
 *   selection **exactly one character in 222 ms and one gesture** — three times faster and far more
 *   accurate than the released path, which takes 0.7–2.3 s and two to seven escalating drags for the
 *   same step.
 * - Every move AFTER that one does nothing. Not a wrong offset — no change at all, through twelve
 *   escalating destinations, whether the pointer is moved by a continuation or lifted and re-grabbed
 *   for each move.
 * - The page does not scroll and no fresh word is selected while this happens, so the later strokes
 *   are reaching nothing rather than landing somewhere wrong.
 *
 * Which points at the target app, not at this class: Chrome appears to end its handle drag when the
 * first gesture completes and to refuse to start another until something it is waiting for arrives.
 * The lift this class sends is evidently not it.
 *
 * The 222 ms result also explains a finding recorded in AGENTS.md — that the landed offset is not a
 * function of the final pixel. It is a function of the final pixel AND the lift: hold the finger and
 * the handle stays exactly where it was put; lift it and the app snaps the handle somewhere of its
 * own choosing. That is why the released path needs several read-and-correct rounds per character.
 *
 * The owner's design, and the reason for it is everything that goes wrong when each press is its own
 * press-move-release: the handle has to be found again every time, a miss is a tap on the page, and
 * the target app puts its selection toolbar back between every pair of presses. Hold the pointer and
 * the handle is caught once, its position is afterwards *known* rather than derived, and the app
 * stays in a drag for the whole burst — so the toolbar stays down.
 *
 * **The mechanism it is built around, which a first attempt got wrong.** A stroke marked
 * `willContinue` keeps its pointer down, and the continuation must be dispatched from the previous
 * gesture's completion — **immediately**, not after deciding anything. The first attempt continued
 * the chain only once it had read the selection back, three hundred milliseconds later, and by then
 * every stroke had been cancelled: eight of them walked the pointer from x=690 to x=2104 without
 * moving the selection at all. A timer that fired slightly early was no better, because dispatching
 * while a gesture is still playing cancels it.
 *
 * So the chain runs itself. Each link continues the last the instant it finishes, standing still
 * when there is nowhere to go, and [moveTo] simply leaves a destination for the next link to use.
 *
 * The consequence for callers: [moveTo] does not report when the move lands. Nothing here can, the
 * chain runs itself. Read the result from the selection stream, as everything else here does.
 */
class HeldPointer(
  private val handler: Handler,
  /** Dispatches a gesture; the callback fires when the framework has finished playing it. */
  private val dispatch: (GestureDescription, (Boolean) -> kotlin.Unit) -> Boolean,
) {

  private var stroke: GestureDescription.StrokeDescription? = null
  private var at: PointF? = null
  private var goal: PointF? = null

  /** Where the pointer is, or null when nothing is pressed. */
  val position: PointF? get() = at

  val isHeld: Boolean get() = stroke != null

  /**
   * Press at [from] and start the chain.
   *
   * The first stroke travels a couple of pixels rather than standing still, because a stationary
   * press that is held **is a long press** — and a long press that missed the handle would select a
   * fresh word under the finger instead of failing quietly.
   */
  fun grab(from: PointF, towards: PointF, onGrabbed: (Boolean) -> kotlin.Unit) {
    // Never start a second chain on top of a live one: the old chain's next completion would lift
    // the new chain's stroke, and both pointers would be lost.
    if (stroke != null) {
      Diag.log("held: refusing to grab, already holding")
      onGrabbed(false)
      return
    }
    /*
     * The grab IS the first move, along the same path a released drag would take.
     *
     * A grab that only pressed and twitched two pixels never caught the handle: the chain ran
     * healthily — sixty-odd links, a clean lift, the next grab accepted — while the selection sat
     * still through twelve escalating moves. The released path that does work travels past the touch
     * slop and then on to its destination in one stroke, so the first link here does exactly that
     * and only the *holding* is new.
     */
    val away = if (towards.x >= from.x) SLOP_DETOUR_PX else -SLOP_DETOUR_PX
    val first = GestureDescription.StrokeDescription(
      Path().apply {
        moveTo(from.x, from.y)
        lineTo(from.x + away, from.y)
        lineTo(towards.x, towards.y)
      },
      0,
      GRAB_MS,
      true,
    )
    stroke = first
    at = PointF(towards.x, towards.y)
    goal = null
    val accepted = dispatch(gestureOf(first)) { played -> if (played) link() else lost("grab not played") }
    Diag.log("held: grab at ${from.x} accepted=$accepted")
    if (!accepted) forget()
    onGrabbed(accepted)
  }

  /** Ask the pressed pointer to travel to [point]. Silent: the selection stream reports the result. */
  fun moveTo(point: PointF) {
    goal = PointF(point.x, point.y)
  }

  /**
   * Lift, if anything is down — but at the END of the link that is currently playing, never on top
   * of it.
   *
   * Lifting eagerly is what broke the second press of every burst: the lift continued a stroke that
   * an in-flight link had already continued, which leaves the framework holding a pointer nobody can
   * reach. Afterwards every grab was refused in silence and twelve escalating drags moved nothing.
   * So a release is a REQUEST — the chain notices it when the current link finishes and closes
   * itself with a stroke that does not continue.
   */
  fun release() {
    if (stroke == null) return
    releasing = true
  }

  /**
   * Lift now, without waiting for the link in flight to finish.
   *
   * Needed because a press has to start from a pointer that is definitely up: the grab is the only
   * stroke the target app acts on, and a second chain started on top of a live one loses both.
   */
  fun releaseNow() {
    if (stroke == null) return
    releasing = false
    lift()
  }

  private fun lift() {
    val previous = stroke
    val from = at
    forget()
    if (previous == null || from == null) return

    /*
     * The lift travels one pixel, and that is not a detail.
     *
     * A `StrokeDescription` needs a path with a length; a `moveTo` on its own is empty, and building
     * one throws. The throw landed inside a gesture-completion callback, where it killed the lift
     * silently and left the framework holding a pointer this class had already forgotten — after
     * which every later grab was refused and twelve escalating drags moved nothing. That was the
     * "stuck pointer", and it was self-inflicted.
     */
    val path = Path().apply {
      moveTo(from.x, from.y)
      lineTo(from.x + LIFT_NUDGE_PX, from.y)
    }
    val lift = runCatching { previous.continueStroke(path, 0, TICK_MS, false) }.getOrNull()
    if (lift == null) {
      Diag.log("held: could not build the lift; the pointer may still be down")
      return
    }
    Diag.log("held: lifting after $links link(s)")
    links = 0
    if (!dispatch(gestureOf(lift)) {}) Diag.log("held: lift refused")
  }

  /**
   * One link of the chain, dispatched without waiting for anything.
   *
   * When there is nowhere to go it still sends a stroke — a one-pixel wobble rather than a perfectly
   * stationary one, so the app keeps seeing a drag rather than deciding the finger has settled.
   */
  private fun link() {
    if (releasing) {
      releasing = false
      lift()
      return
    }
    val previous = stroke ?: return
    val from = at ?: return
    val to = goal ?: PointF(from.x + idleWobble(), from.y)
    goal = null

    val next = runCatching {
      previous.continueStroke(
        Path().apply {
          moveTo(from.x, from.y)
          lineTo(to.x, to.y)
        },
        0,
        TICK_MS,
        true,
      )
    }.getOrNull()
    if (next == null) {
      lost("could not build a link")
      return
    }
    stroke = next
    at = to
    links++
    val accepted = dispatch(gestureOf(next)) { played -> if (played) link() else lost("link cancelled") }
    if (!accepted) lost("link refused")
  }

  private fun lost(why: String) {
    Diag.log("held pointer: $why")
    forget()
  }

  private var releasing = false

  /** Links since the last grab, for the log: it is the only way to see the chain is alive. */
  private var links = 0

  private var wobble = 1f

  private fun idleWobble(): Float {
    wobble = -wobble
    return wobble
  }

  private fun forget() {
    stroke = null
    at = null
    goal = null
  }

  private fun gestureOf(stroke: GestureDescription.StrokeDescription) =
    GestureDescription.Builder().addStroke(stroke).build()

  private companion object {
    /**
     * How long each link of the chain lasts.
     *
     * Short, because the next link is dispatched from this one's completion and the pointer is
     * standing still in between: a long link is just latency before the pad can move the handle
     * again. Too short and the chain spends its life in dispatch overhead.
     */
    const val TICK_MS = 60L

    /**
     * How far the grab detours past its target before coming back, and how long it takes.
     *
     * Same shape as the released drag that works: a touch that moves less than the system slop and
     * lifts inside the tap timeout IS a tap, and a tap on a link navigates. Here the pointer never
     * lifts, but the detour is also what makes the target app read a drag at all.
     */
    const val SLOP_DETOUR_PX = 60f
    const val GRAB_MS = 150L

    /** A lift needs a path with a length; an empty one throws and strands the pointer. */
    const val LIFT_NUDGE_PX = 1f
  }
}
