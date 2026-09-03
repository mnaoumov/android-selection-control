package dev.mnaoumov.asc

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler

/**
 * A synthetic finger that presses once, moves as often as asked, and lifts when told.
 *
 * **NOT WIRED IN. This is a half-finished mechanism kept for the next attempt, not a component the
 * pad uses.** It half worked, and the half that worked is the interesting part: with the pointer
 * already down, one press moved the selection exactly one character in **253 ms and a single
 * gesture** — against 700 ms to 2.3 s and two to seven gestures for the released path the pad
 * actually ships. That is the prize, and it is worth another run at.
 *
 * What is not solved: the grab is unreliable. After the first release, every later grab was refused
 * in silence and twelve escalating drags moved nothing; a later build failed the same way from the
 * very first press. The suspicion is a pointer left down inside the framework — a lift that
 * continues a stroke some in-flight link has already continued leaves one, and nothing afterwards
 * can reach that handle — but that is a suspicion, not a measurement, and the next attempt should
 * start by proving or disproving it rather than by writing more code.
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
    release()
    val nudge = if (towards.x >= from.x) GRAB_NUDGE_PX else -GRAB_NUDGE_PX
    val first = GestureDescription.StrokeDescription(
      Path().apply {
        moveTo(from.x, from.y)
        lineTo(from.x + nudge, from.y)
      },
      0,
      TICK_MS,
      true,
    )
    stroke = first
    at = PointF(from.x + nudge, from.y)
    goal = null
    val accepted = dispatch(gestureOf(first)) { played -> if (played) link() else lost("grab not played") }
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

  private fun lift() {
    val previous = stroke
    val from = at
    forget()
    if (previous == null || from == null) return
    val lift = previous.continueStroke(
      Path().apply { moveTo(from.x, from.y) },
      0,
      TICK_MS,
      false,
    )
    dispatch(gestureOf(lift)) {}
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

    val next = previous.continueStroke(
      Path().apply {
        moveTo(from.x, from.y)
        lineTo(to.x, to.y)
      },
      0,
      TICK_MS,
      true,
    )
    stroke = next
    at = to
    val accepted = dispatch(gestureOf(next)) { played -> if (played) link() else lost("link cancelled") }
    if (!accepted) lost("link refused")
  }

  private fun lost(why: String) {
    Diag.log("held pointer: $why")
    forget()
  }

  private var releasing = false

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

    /** A grab moves a hair, so the target app reads a drag rather than a long press. */
    const val GRAB_NUDGE_PX = 2f
  }
}
