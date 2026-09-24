package dev.mnaoumov.asc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
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
  private var mask: ToolbarMask? = null

  /** Last rectangle the mask was told to cover, so the log records changes rather than every event. */
  private var maskedBounds: android.graphics.Rect? = null

  /** When the target app last had a toolbar on screen, for the linger above. */
  private var toolbarLastSeenAtMs = 0L

  /** One press at a time: the loop reads the result of each gesture before deciding the next. */
  private var busy = false

  /**
   * The synthetic finger that does not lift between corrections (the held-pointer fix).
   *
   * Built on the raw `dispatchGesture` rather than on [dispatch], because a chain needs the
   * gesture-level `Boolean` — "accepted for dispatch" — to tell a refusal apart from a cancellation,
   * and [dispatch] folds both into its callback.
   */
  private val heldPointer = HeldPointer { gesture, onFinished ->
    val callback = object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) = finish(true)
      override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
      private fun finish(completed: Boolean) {
        runCatching { onFinished(completed) }
          .onFailure { Diag.log("held: a link's follow-up threw ${it::class.java.simpleName}: ${it.message}") }
      }
    }
    dispatchGesture(gesture, callback, null)
  }

  /**
   * The command a run is repeating, or null when nothing is running.
   *
   * A run continues with **no finger on the glass** (see [Pad.holdable]): the fast path holds a
   * synthetic pointer down for the whole step, and any real touch ends the target app's tracking of
   * it. So this is set when a long press is RELEASED and cleared by the next touch, rather than
   * tracking a finger that is still down.
   */
  private var repeating: PadCommand? = null

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
      onTap = ::onTap,
      onRepeat = ::onRepeat,
      onStopRepeat = ::onStopRepeat,
      onSwapEdge = ::onSwapEdge,
      onToggleMenu = ::onToggleMenu,
      onClose = {
        repeating = null
        mask?.hide()
        pad?.hide()
      },
    ).also { it.show() }

    mask = ToolbarMask(this, getSystemService(WindowManager::class.java))

    connected = this
  }

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    if (connected === this) connected = null
    // An overlay that outlives its service cannot be told to go away.
    mask?.hide()
    mask = null
    pad?.hide()
    pad = null
    return super.onUnbind(intent)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    /*
     * The previous snapshot has to be taken BEFORE the observer overwrites it, because what the
     * driver needs from an event is not its contents but how it differs from what came before: a
     * selection whose BOTH edges changed is a new one, and a long-press snaps a whole word, so both
     * of its edges are word boundaries the pad can have for nothing.
     *
     * Offered only between presses. Mid-press every announcement is ours, and our own steps are
     * already accounted for where they are made.
     */
    val previous = observer.latest
    val changed = observer.onEvent(event)
    if (changed && !busy && ::driver.isInitialized) driver.noteSelectionEvent(previous)
    // The toolbar comes back on every selection change, so the mask has to follow it there rather
    // than only after a press of our own.
    refreshMask()
  }

  /**
   * Repaints the mask over wherever the target app's selection toolbar is now.
   *
   * Cheap enough to run per event only because it short-circuits when masking is off; the window
   * list is not free to walk.
   */
  private fun refreshMask() {
    val mask = mask ?: return
    if (!mask.enabled) return

    /*
     * Never mid-press.
     *
     * The target app takes its toolbar down while a handle is being dragged and puts it back
     * afterwards, so following it during a press meant adding and removing an overlay window six
     * times inside one step — and that press took 3.6 s, spent ten gestures and moved nothing. The
     * mask only has to be right when the user is looking at it, which is between presses.
     */
    if (busy) return

    val foreground = observer.latest?.packageName ?: rootInActiveWindow?.packageName?.toString()
    val bounds = locator.toolbarBounds(windows.orEmpty(), foreground, screenWidth())

    /*
     * A missing toolbar is usually a blink, not a dismissal — it goes away for the duration of any
     * drag, including the user's own. Hiding on the first null made the mask strobe. So keep the
     * cover in place briefly, and take it down only once the toolbar has stayed away.
     */
    val now = android.os.SystemClock.uptimeMillis()
    if (bounds != null) toolbarLastSeenAtMs = now
    val gone = bounds == null && now - toolbarLastSeenAtMs > MASK_LINGER_MS

    if (bounds != maskedBounds && (bounds != null || gone)) {
      Diag.log("mask -> $bounds")
      maskedBounds = bounds
    }
    when {
      bounds != null -> mask.update(bounds)
      gone -> mask.update(null)
      // else: leave the cover where it is until the toolbar has been away long enough to believe.
    }
  }

  private fun onToggleMenu() {
    val masked = mask?.toggle() ?: return
    pad?.setMenuMasked(masked)
    refreshMask()
    pad?.showStatus(if (masked) "menu hidden" else "menu shown")
  }

  override fun onInterrupt() = Unit

  // ------------------------------------------------------------------ the pad

  /** A tap: exactly one step, started once the finger is off the glass. */
  private fun onTap(command: PadCommand) {
    repeating = null
    startStep(command)
  }

  /**
   * Begin a step from a fresh loop turn, never from inside the touch handler that asked for it.
   *
   * Calling straight through would dispatch the grab while the button's own `ACTION_UP` is still
   * being delivered, and a real touch is exactly what ends the target app's tracking of a held
   * pointer. Posting costs one loop turn and lets the touch finish first.
   *
   * A delay of 250 ms was tried here and removed: the chain was still being cancelled one link after
   * the grab, and a repeat run — whose second and later steps happen with no finger on the screen at
   * all — was cancelled identically. That ruled the touch out as the cause, so the delay was buying
   * nothing but latency.
   */
  private fun startStep(command: PadCommand) {
    if (busy) return
    handler.post { step(command) }
  }

  /**
   * A long press, released: step until something stops it.
   *
   * The run is chained off completion rather than driven by a timer — see [Pad.holdable] — so this
   * only has to record what is running and start the first step.
   */
  private fun onRepeat(command: PadCommand) {
    repeating = command
    pad?.showStatus("repeating — tap to stop")
    startStep(command)
  }

  /** Any touch on a direction button stops a run. True when there was one to stop. */
  private fun onStopRepeat(): Boolean {
    val wasRunning = repeating != null
    repeating = null
    return wasRunning
  }

  private fun step(command: PadCommand) {
    if (busy) return
    busy = true

    // The node rung, for the surfaces that announce nothing at all — Google Docs reports a range on
    // its node while firing no selection event whatsoever.
    if (observer.isStale(STALE_MS)) {
      observer.refreshFromNodes(rootInActiveWindow, rootInActiveWindow?.packageName?.toString())
    }

    /*
     * Go transparent ONLY when the pad is actually in the way.
     *
     * A non-touchable pad lets our own gestures through to the app, which is necessary when a handle
     * sits underneath it — but it also lets the USER's next tap through, onto the page. Doing that
     * for the whole of every press meant a second press during the first landed on whatever was
     * beneath the button: measured, it opened an image viewer and spare browser tabs. Most of the
     * time the handle is nowhere near the pad, and then the pad should keep absorbing taps.
     */
    val inTheWay = handleIsUnderThePad()
    if (inTheWay) pad?.setTransparentToTouch(true)
    driver.perform(command) { outcome ->
      if (inTheWay) pad?.setTransparentToTouch(false)
      pad?.showStatus(describe(outcome))
      busy = false
      // The press just moved the selection, so the toolbar has just moved too. After `busy` clears,
      // or the guard in refreshMask would skip it.
      refreshMask()

      /*
       * Keep the run going only while it is still this command's run AND the last step actually got
       * somewhere.
       *
       * Stopping on no progress is a safety property, not tidiness. A step that failed did so by
       * dragging somewhere that was not a handle, and a drag that misses lands on the page — on a
       * link, that navigates. Hammering that at a few presses a second is the worst thing this app
       * could do, and a run that nobody is touching has no finger to lift as a brake, so the brake
       * has to be this: a run ends the moment a step stops moving the selection, leaving the reason
       * on the status line.
       *
       * Posted rather than called, so each step starts from a fresh loop turn and the status line
       * gets drawn between them.
       */
      if (repeating == command && madeProgress(outcome)) {
        handler.post { if (repeating == command) step(command) }
      } else {
        repeating = null
      }
    }
  }

  /**
   * Whether that step moved the selection. [Outcome.Degraded] counts: it means a word step fell back
   * to a single character, which is less than asked for but is still progress.
   */
  private fun madeProgress(outcome: Outcome): Boolean = when (outcome) {
    is Outcome.Moved -> outcome.fromOffset != outcome.toOffset
    is Outcome.Degraded -> true
    Outcome.NoSelection, Outcome.HandleLost -> false
  }

  /**
   * Whether the handle we are about to drag lies inside the pad's own footprint.
   *
   * The pad's bounds come from the accessibility window list rather than from its `LayoutParams`,
   * because those are inset by the status bar while gesture coordinates are raw screen pixels —
   * comparing the two directly is off by the status bar's height.
   */
  private fun handleIsUnderThePad(): Boolean {
    val snapshot = observer.latest ?: return false
    val bounds = snapshot.bounds ?: return false
    val handleY = bounds.bottom + HandleLocator.HANDLE_DROP
    val padBounds = windows.orEmpty()
      .firstOrNull {
        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
          it.root?.packageName?.toString() == packageName
      }
      ?.let { window -> android.graphics.Rect().also { window.getBoundsInScreen(it) } }
      ?: return false
    return handleY >= padBounds.top && handleY <= padBounds.bottom
  }

  private fun onSwapEdge() {
    repeating = null
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

  /**
   * A drag of the selection handle — with a deliberate detour, for safety rather than for effect.
   *
   * The steps this loop makes are small: a few pixels to creep one character, a few tens to cross a
   * word. A touch that moves less than the system's touch slop and lifts inside the tap timeout **is
   * a tap**, so a drag that misses its handle does not fail quietly — the page receives a click, and
   * on a link that NAVIGATES. Measured the hard way: during testing the article changed underneath
   * twice and three stray tabs were opened.
   *
   * So every drag first travels past the slop and only then comes back to where it was actually
   * meant to end. A miss now reads as a scroll — recoverable, and it does not take the page with it
   * — while a hit still finishes at the intended pixel, because the selection follows the finger and
   * the loop reads the state only once the announcements go quiet.
   */
  override fun drag(from: PointF, to: PointF, durationMs: Long, onFinished: (Boolean) -> Unit) {
    val slop = ViewConfiguration.get(this).scaledTouchSlop * SLOP_MULTIPLE
    val away = if (to.x >= from.x) slop.toFloat() else -slop.toFloat()
    val path = Path().apply {
      moveTo(from.x, from.y)
      lineTo(from.x + away, from.y)
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

  /**
   * The held route (the held-pointer fix). The grab travels past the touch slop for the same reason [drag] does — a
   * miss that reads as a tap navigates — and [HeldPointer] adds the detour itself.
   */
  override fun grabAndHold(from: PointF, to: PointF, onGrabbed: (Boolean) -> Unit) {
    // A chain from a previous press cannot be reused: the press itself is a real touch, and a real
    // touch ends the target app's tracking of the handle even though the chain survives it
    // (measured, the held-pointer fix). So start clean rather than inheriting a pointer the app has stopped
    // following.
    if (heldPointer.isHeld) heldPointer.releaseNow()
    heldPointer.grab(from, to, onGrabbed)
  }

  override fun moveHeld(to: PointF): Boolean = heldPointer.moveTo(to)

  override fun releaseHeld(onLifted: () -> Unit) = heldPointer.release(onLifted)

  override fun heldAt(): PointF? = heldPointer.position

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

  companion object {
    /**
     * The connected service, so the launcher screen can put a closed pad back.
     *
     * A plain reference rather than a bound service or a broadcast: the activity and the service
     * share one process (no `android:process` in the manifest), and anything more elaborate would
     * be ceremony around a field. Cleared in [onUnbind], so a stale instance cannot be poked.
     */
    private var connected: AscAccessibilityService? = null

    /** Puts the pad back after the user closed it. False when the service is not running. */
    fun showPad(): Boolean {
      val pad = connected?.pad ?: return false
      pad.show()
      return true
    }

    /** How far past the touch slop a drag detours, so a miss cannot read as a tap. */
    const val SLOP_MULTIPLE = 2

    /** A lift still needs a stroke, and the shortest legal one will do. */
    const val LIFT_MS = 1L

    /** How long an announcement stays trustworthy before the node rung is worth a walk. */
    const val STALE_MS = 4000L

    /**
     * How long after the last press the held pointer is lifted.
     *
     * Long enough that a run of presses shares one grab, short enough that the app is not left
     * mid-drag when the user has moved on.
     */
    const val RELEASE_IDLE_MS = 1500L

    /**
     * How long the toolbar must stay gone before the mask comes down. Longer than the blink a drag
     * causes, short enough that a dismissed selection does not leave a patch on screen.
     */
    const val MASK_LINGER_MS = 700L
  }
}
