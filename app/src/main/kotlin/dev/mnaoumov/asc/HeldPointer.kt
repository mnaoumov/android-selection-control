package dev.mnaoumov.asc

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF

/**
 * A synthetic finger that presses once, moves as often as asked, and lifts when told.
 *
 * **The chain works, and the held-pointer fix measured exactly how.** An earlier version of this comment concluded
 * that Chrome ends its handle drag after the first gesture. That was wrong, and it was wrong because
 * every attempt to test it drove this class from a pad BUTTON — which is a real touch, and a real
 * touch is precisely what breaks it. `spike`'s `held` command runs the same chain from a broadcast,
 * touching nothing, and the picture inverts:
 *
 * - The chain never dies. Ten links and a clean lift, every time, on a browser page and on this
 *   app's own `TargetActivity`. Every link reports `completed=true`, with or without interference.
 * - **But any real touch permanently ends the TARGET APP's tracking**, while leaving this chain
 *   alive and reporting success. One brief tap mid-chain stopped the selection moving from that link
 *   on and it never recovered; a resting finger does the same for as long as it is down. Matched
 *   runs from one selection: 9 of 10 links moved untouched, 3 of 10 with a finger down, 2 of 10
 *   after a single tap. **So a chain cannot span pad presses — the press is what ends it.**
 * - The lift does **not** snap. Three links landed `10..12` and it was still `10..12` after the lift
 *   and 900 ms of settle.
 * - One press as a whole grab-move-lift costs **318–329 ms and one chain**, against 0.7–2.3 s and
 *   two to seven escalating drags released.
 *
 * The old "one move, then nothing through twelve destinations" was measured on a WRAPPED paragraph,
 * whose node bounds are the union of its line boxes — so the interpolated handle sat below the last
 * line and nothing was ever grabbed. That is the handle-location defect's defect, not this class's.
 *
 * The finding that survives all of it: the landed offset is a function of the final pixel AND the
 * lift. Held, the handle stays exactly where it was put; released, the app snaps it somewhere of its
 * own choosing. That is why the released path needs several read-and-correct rounds per character —
 * and exactly what a held press does not have to pay.
 *
 * The owner's design, and the reason for it is everything that goes wrong when each press is its own
 * press-move-release: the handle has to be found again every time, a miss is a tap on the page, and
 * the target app puts its selection toolbar back between every pair of presses. Hold the pointer and
 * the handle is caught once, its position is afterwards *known* rather than derived, and the app
 * stays in a drag — so the toolbar stays down.
 *
 * **That payoff is real, but only WITHIN one burst, never across taps.** A chain lives exactly as
 * long as no real finger touches the screen, so the shape that works is: a tap is its own short
 * chain, and a repeat is one long chain that runs while the finger is OFF the pad, ended by the tap
 * that stops it. A design that expects one chain to survive a button press expects the impossible,
 * and the whole of the pad build's held round was built on that expectation.
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
   * Press at [from] and start the chain. [onGrabbed] fires when the first stroke has **played**, not
   * when it was accepted for dispatch.
   *
   * That distinction cost a whole round of measurement. Reporting on acceptance means the caller
   * starts waiting for the selection to change before the grab has even run: the stroke takes
   * [GRAB_MS], the caller's settle is 220 ms, and the announcement lands after the stroke — so the
   * caller times out, concludes the grab caught nothing, and falls back to the released path. Six of
   * seven presses did exactly that, each one reporting `-> nothing` from a grab that had in fact
   * worked.
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
     * The released path that works travels past the touch slop and then on to its destination in one
     * stroke, so the first link here does exactly that and only the *holding* is new. A touch that
     * moves less than the slop and lifts inside the tap timeout IS a tap, and a tap on a link
     * navigates — the detour is what stops a miss taking the page with it.
     *
     * An earlier note here blamed a two-pixel twitch for never catching the handle, on a run where
     * sixty links played healthily while the selection sat still. That run was on a WRAPPED node,
     * whose derived handle was nowhere near the real one, so it cannot support the inference. The
     * detour stays because the gesture spike measured its worth; the twitch was never the thing being tested.
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
    val accepted = dispatch(gestureOf(first)) { played ->
      // Continue the chain FIRST and tell the caller second: a continuation must be dispatched from
      // this completion immediately, and whatever the caller does next must not get in front of it.
      if (played) link() else lost("grab not played")
      onGrabbed(played)
    }
    Diag.log("held: grab at ${from.x} accepted=$accepted")
    // A refused dispatch never calls back, so this is the one path that has to report for itself —
    // and it must report exactly once, like the other.
    if (!accepted) {
      forget()
      onGrabbed(false)
    }
  }

  /**
   * Ask the pressed pointer to travel to [point]. Silent about the RESULT — the selection stream
   * reports that — but never silent about having no pointer to move.
   *
   * Recording a goal on a chain that has already died is what made the last round unreadable: twelve
   * escalating destinations were dutifully written into [goal], nobody was left alive to read them,
   * and the log showed a grab, then nothing, then another grab. "Every move after the first does
   * nothing" was true, and it was this, not the target app.
   */
  fun moveTo(point: PointF): Boolean {
    if (stroke == null) {
      Diag.log("held: moveTo(${point.x}) ignored — nothing is held")
      return false
    }
    goal = PointF(point.x, point.y)
    return true
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
     * The lift travels one pixel.
     *
     * This used to say that building a `moveTo`-only path throws. It does not — `spike`'s `point()`
     * is exactly that, and `tap` and `long` use it — so that claim is withdrawn. But the nudge is
     * NOT decoration: a zero-length **continuation** is refused, and the refusal arrives as a
     * cancelled link rather than an exception (the held-pointer fix, and see [link]). The lift is a continuation, so
     * it has to travel.
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
   * When there is nowhere to go it still sends a stroke, holding position exactly — see the comment
   * on the idle path for why standing perfectly still matters.
   */
  private fun link() {
    if (releasing) {
      releasing = false
      lift()
      return
    }
    /*
     * A chain that nobody stops runs for ever, at a dispatched gesture every [TICK_MS].
     *
     * Every caller here does release, but "every caller does" is not a property of this class, and
     * the failure it guards against is not a slow loop — it is a finger left pressed on the owner's
     * phone, holding the target app in a drag, until something notices. One dropped callback is all
     * it would take. So the chain has an end of its own, generous enough that no honest burst
     * reaches it.
     */
    if (links >= MAX_LINKS) {
      Diag.log("held: $links links without a release — lifting on my own")
      lift()
      return
    }
    // Both of these used to return in silence, which reads in a log exactly like a chain that simply
    // stopped being asked for anything. They should be impossible — a link only runs from the
    // previous one's completion — so if one ever fires it is worth knowing about, not hiding.
    val previous = stroke ?: run {
      Diag.log("held: a link ran with no stroke to continue — the chain was forgotten under it")
      return
    }
    val from = at ?: run {
      Diag.log("held: a link ran with no position — the chain was forgotten under it")
      return
    }
    val to = goal
    goal = null

    /*
     * An idle link moves half a pixel, alternating, and both halves of that matter.
     *
     * **Not zero.** A continuation whose path has no length is refused by the framework, and the
     * refusal arrives as a cancellation of the link rather than an exception: measured as
     * `LOST after 1 link(s) — link cancelled` on every press, including during a repeat run with no
     * finger anywhere near the screen, which is what ruled out a real touch as the cause.
     *
     * **And not a whole pixel.** It used to wobble a full pixel each way, and when the handle sits
     * near a character boundary that flips the offset back and forth for ever: the selection
     * announces on every tick, the caller's settle never goes quiet, and its "did that move?" test
     * stops meaning anything. Measured — a press that should have cost 330 ms took 3.2 s across 80
     * links, all three corrections reported "nothing", and it landed two characters out.
     *
     * Half a pixel is long enough to be a path and far too short to cross a character, which is tens
     * of pixels wide; alternating keeps the drift at zero over any number of links.
     */
    val next = runCatching {
      previous.continueStroke(
        Path().apply {
          moveTo(from.x, from.y)
          lineTo(to?.x ?: (from.x + idleNudge()), to?.y ?: from.y)
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
    // An idle link went half a pixel; treat that as standing still, so the position a caller reads
    // back is the one it asked for rather than one drifted by bookkeeping.
    if (to != null) at = to
    links++
    val accepted = dispatch(gestureOf(next)) { played -> if (played) link() else lost("link cancelled") }
    if (!accepted) lost("link refused")
  }

  /**
   * Every way this chain can die goes through here, and it logs under the same `held:` prefix as
   * everything else — which it did not, for the whole of the pad build. It said "held pointer: …", so one
   * grep on `held:` saw the grabs and the lifts and none of the deaths, and a chain that died looked
   * exactly like a chain that had never been asked to do anything. That is most of why this class
   * was described as failing silently.
   */
  private fun lost(why: String) {
    Diag.log("held: LOST after $links link(s) — $why")
    links = 0
    forget()
  }

  private var releasing = false

  /** Links since the last grab, for the log: it is the only way to see the chain is alive. */
  private var links = 0

  private var nudge = IDLE_NUDGE_PX

  /** Alternates, so a long idle drifts nowhere. */
  private fun idleNudge(): Float {
    nudge = -nudge
    return nudge
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

    /** How far the lift travels. See [lift] for why this is not the load-bearing detail it was. */
    const val LIFT_NUDGE_PX = 1f

    /**
     * How far an idle link travels: enough to be a path, far too little to cross a character.
     *
     * A character is tens of pixels wide on every surface measured, so half a pixel cannot move the
     * selection — and a zero-length continuation is refused outright. See [link].
     */
    const val IDLE_NUDGE_PX = 0.5f

    /**
     * The chain's own end, in links, if nothing releases it.
     *
     * At [TICK_MS] this is about twelve seconds of holding — far longer than any press or burst, so
     * it never fires in normal use, and short enough that a bug cannot leave a finger pressed on the
     * owner's phone indefinitely.
     */
    const val MAX_LINKS = 200
  }
}
