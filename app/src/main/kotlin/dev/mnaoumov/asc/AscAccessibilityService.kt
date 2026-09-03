package dev.mnaoumov.asc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Cursor and selection control where no keyboard can reach: text that is selectable by touch but not
 * editable — a web page in a browser, a read-only view.
 *
 * The mechanism, established by two spikes before a line of this was written: the accessibility
 * *selection actions* stop at the same editable-buffer boundary `InputConnection` does, but
 * `dispatchGesture` does not, because it drives the target app's **own** selection UI. So the pad
 * moves the handles the app already draws, and its reach is "anywhere the user could already select
 * by touch".
 *
 * **It never reads the text.** Offsets, lengths and rectangles are the entire signal; see
 * [SelectionObserver].
 */
class AscAccessibilityService : AccessibilityService(), GestureDispatcher {

  private val handler = Handler(Looper.getMainLooper())
  private val observer = SelectionObserver()
  private val locator = HandleLocator()

  private lateinit var driver: SelectionDriver
  private var pad: Pad? = null

  /** One press at a time: the loop reads the result of each gesture before deciding the next. */
  private var busy = false

  override fun onServiceConnected() {
    super.onServiceConnected()

    driver = SelectionDriver(
      gestures = this,
      observer = observer,
      locator = locator,
      handler = handler,
      toolbarCentre = {
        locator.toolbarCentreX(
          windows = windows.orEmpty(),
          packageName = observer.latest?.packageName,
          screenWidth = screenWidth(),
        )
      },
    )

    pad = Pad(
      context = this,
      windowManager = getSystemService(WindowManager::class.java),
      onCommand = ::onCommand,
      onSwapEdge = ::onSwapEdge,
    ).also { it.show() }
  }

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    // An overlay that outlives its service cannot be told to go away.
    pad?.hide()
    pad = null
    return super.onUnbind(intent)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    observer.onEvent(event)
  }

  override fun onInterrupt() = Unit

  // ------------------------------------------------------------------ the pad

  private fun onCommand(command: PadCommand) {
    if (busy) return
    busy = true

    // The node rung, for the surfaces that announce nothing at all — Google Docs reports a range on
    // its node while firing no selection event whatsoever.
    if (observer.isStale(STALE_MS)) {
      observer.refreshFromNodes(rootInActiveWindow, rootInActiveWindow?.packageName?.toString())
    }

    // The pad must not be covering the point it is about to touch.
    pad?.setTransparentToTouch(true)
    driver.perform(command) { outcome ->
      pad?.setTransparentToTouch(false)
      pad?.showStatus(describe(outcome))
      busy = false
    }
  }

  private fun onSwapEdge() {
    driver.activeEdge = if (driver.activeEdge == Edge.END) Edge.START else Edge.END
    locator.forgetAnchor()
    pad?.showStatus("moving the ${if (driver.activeEdge == Edge.END) "end" else "start"}")
  }

  private fun describe(outcome: Outcome): String = when (outcome) {
    is Outcome.Moved ->
      if (outcome.fromOffset == outcome.toOffset) "didn't move" else "moved ${outcome.fromOffset} → ${outcome.toOffset}"
    // Distinguish "there is no selection" from "there is one but I cannot see it", because they need
    // opposite things from the user and the second is common: a selection event fires only on a
    // CHANGE, so one made before the service connected — or a long-press INSIDE an existing
    // selection, which re-selects nothing — leaves the pad blind while text is visibly highlighted.
    Outcome.NoSelection ->
      if (aSelectionSeemsToExist()) "tap elsewhere, then long-press to re-select" else "select some text first"
    Outcome.HandleLost -> "lost the handle — reselect"
    is Outcome.Degraded -> "one character (${outcome.reason})"
  }

  /**
   * Whether *something* on screen looks like a live selection, even though nothing has been
   * announced. The floating toolbar is a real window, so its presence is the signal.
   */
  private fun aSelectionSeemsToExist(): Boolean {
    val foreground = rootInActiveWindow?.packageName?.toString() ?: return false
    return locator.toolbarCentreX(windows.orEmpty(), foreground, screenWidth()) != null
  }

  // --------------------------------------------------------- GestureDispatcher

  override fun drag(from: PointF, to: PointF, durationMs: Long, onFinished: (Boolean) -> Unit) {
    val path = Path().apply {
      moveTo(from.x, from.y)
      lineTo(to.x, to.y)
    }
    dispatch(GestureDescription.StrokeDescription(path, 0, durationMs), onFinished)
  }

  /**
   * Drag, then hold at the destination without lifting — two chained strokes, because
   * `continueStroke` produces a stroke for the *next* gesture and keeps the pointer down between
   * them. A plain drag lifts on arrival and so can never trigger the target app's edge auto-scroll,
   * which is what the page and document steps depend on.
   */
  override fun dragAndHold(
    from: PointF,
    to: PointF,
    dragMs: Long,
    holdMs: Long,
    onFinished: (Boolean) -> Unit,
  ) {
    val move = GestureDescription.StrokeDescription(
      Path().apply {
        moveTo(from.x, from.y)
        lineTo(to.x, to.y)
      },
      0,
      dragMs,
      true,
    )
    dispatch(move) { moved ->
      if (!moved) {
        onFinished(false)
        return@dispatch
      }
      val hold = move.continueStroke(Path().apply { moveTo(to.x, to.y) }, 0, holdMs, false)
      dispatch(hold, onFinished)
    }
  }

  override fun screenWidth(): Int = resources.displayMetrics.widthPixels

  override fun screenHeight(): Int = resources.displayMetrics.heightPixels

  /**
   * `onCompleted` means the strokes were played, NOT that the target app did anything with them, and
   * `dispatchGesture` returning true means only "accepted for dispatch". Everything downstream
   * therefore verifies by reading the selection back, never by trusting either of these.
   */
  private fun dispatch(stroke: GestureDescription.StrokeDescription, onFinished: (Boolean) -> Unit) {
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    val callback = object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) = finish(true)
      override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
      private fun finish(completed: Boolean) {
        runCatching { onFinished(completed) }
      }
    }
    if (!dispatchGesture(gesture, callback, null)) onFinished(false)
  }

  private companion object {
    /** How long an announcement stays trustworthy before the node rung is worth a walk. */
    const val STALE_MS = 4000L
  }
}
