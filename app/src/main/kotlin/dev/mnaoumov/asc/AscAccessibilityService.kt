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
  private val locator = HandleLocator { resources.displayMetrics.density }

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
  private val heldPointer = HeldPointer(
    onScreen = ::onScreen,
    dispatch = { gesture, onFinished ->
      val callback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) = finish(true)
        override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
        private fun finish(completed: Boolean) {
          runCatching { onFinished(completed) }
            .onFailure { Diag.log("held: a link's follow-up threw ${it::class.java.simpleName}: ${it.message}") }
        }
      }
      dispatchGesture(gesture, callback, null)
    },
    edgeLeadPx = ::edgeLead,
  )

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

    // The rig is API 37, so only this reproduces a 36.0 device's blind Chrome there. A debuggable
    // build alone can be given the file: `adb shell run-as dev.mnaoumov.asc touch files/<name>`.
    val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    observer.forceNoExtendedSelection = debuggable && java.io.File(filesDir, FORCE_NO_EXTENDED_SELECTION).exists()
    if (observer.forceNoExtendedSelection) Diag.log("debug: treating the platform as below 36.1")
    // And for the handset's Chrome 154, which announced a seam beside a bare `<sup>` from the `<p>`.
    observer.forceContainerFrame = debuggable && java.io.File(filesDir, FORCE_CONTAINER_FRAME).exists()
    if (observer.forceContainerFrame) Diag.log("debug: announcing every leaf's selection in its container's frame")
    // Likewise for a node that never answers a per-character ask, which the rig's Chromium answers.
    locator.forceRefuseCharacterRects = debuggable && java.io.File(filesDir, FORCE_REFUSE_CHARACTER_RECTS).exists()
    if (locator.forceRefuseCharacterRects) Diag.log("debug: refusing every per-character ask")
    // And for a ROM whose own edge gesture takes a touch the target app excluded, which AOSP does not.
    forceSwallowEdgeZone = debuggable && java.io.File(filesDir, FORCE_SWALLOW_EDGE_ZONE).exists()
    if (forceSwallowEdgeZone) Diag.log("debug: swallowing every handle touch-down in a side gesture zone")
    Diag.log("side gesture zones: ${sideGestureZones()}")

    driver = SelectionDriver(
      gestures = this,
      observer = observer,
      locator = locator,
      handler = handler,
      toolbarBounds = {
        locator.toolbarBounds(
          windows = windows.orEmpty(),
          packageName = observer.latest?.packageName,
          screenWidth = screenWidth(),
        )
      },
      contentBand = ::contentBand,
      foreignWindowAt = ::foreignWindowAt,
      windowRoots = { packageName ->
        windows.orEmpty().mapNotNull { window -> window.root?.takeIf { it.packageName?.toString() == packageName } }
      },
    )
    // And for a page scroll that falls short mid-document, which the rig's Chrome never does by itself.
    driver.forceShortScroll = debuggable && java.io.File(filesDir, FORCE_SHORT_SCROLL).exists()
    if (driver.forceShortScroll) Diag.log("debug: every page scroll swipes a quarter of its distance")
    // And for the handset's silent move onto a node's last caret below the document's last text.
    driver.forceSilentNodeEnd = debuggable && java.io.File(filesDir, FORCE_SILENT_NODE_END).exists()
    if (driver.forceSilentNodeEnd) Diag.log("debug: the move onto a node's last caret after a page drag stays in the empty space")
    // And for the handset's moving node that stops answering refresh() while still drawn.
    driver.forceStaleSource = debuggable && java.io.File(filesDir, FORCE_STALE_SOURCE).exists()
    if (driver.forceStaleSource) Diag.log("debug: the moving edge's node refuses refresh() once per announcement")
    // And for Chrome re-anchoring a selection at the start of a handle drag.
    driver.forceReanchor = debuggable && java.io.File(filesDir, FORCE_REANCHOR).exists()
    if (driver.forceReanchor) Diag.log("debug: a held char grab's landing reads as re-anchored")

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
    if (changed && !busy && ::driver.isInitialized) {
      driver.noteSelectionEvent(previous)
      /*
       * Ask Chrome to measure the new selection's characters NOW, so that the press which follows
       * can be answered at all. On page content the first such request only starts the work — see
       * [HandleLocator.primeCharacterRects], which is the whole of the wrapped-node fix and what
       * makes a one-line node's first press measured rather than averaged — and this
       * announcement is the one moment reliably hundreds of milliseconds ahead of a press.
       */
      observer.latest?.let { locator.primeCharacterRects(it, driver.activeEdge) }
    }
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
    // A document sweep is one press of many pages, so it is stopped the same way a run is.
    if (busy && ::driver.isInitialized) driver.requestStop()
    return wasRunning
  }

  private fun step(command: PadCommand) {
    if (busy) return
    busy = true

    // The node rung, for the surfaces that announce nothing at all — Google Docs reports a range on
    // its node while firing no selection event whatsoever.
    // Also where Chrome hides its range below 36.1: the tree is the one place left that could hold it.
    if (observer.isStale(STALE_MS) || observer.chromeHidesSelection) {
      observer.refreshFromNodes(rootInActiveWindow, rootInActiveWindow?.packageName?.toString())
      if (observer.chromeHidesSelection) Diag.log("node rung: no page node reports a range either")
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
    padCleared = false
    touchedEdgeZone = false
    heldDownInEdgeZone = false
    touchedForeignWindow = false
    locator.aimedByAverage = false
    val edgeBefore = driver.activeEdge
    driver.perform(command) { outcome ->
      if (padCleared) pad?.setTransparentToTouch(false)
      padCleared = false
      pad?.showStatus(
        if (driver.activeEdge != edgeBefore) {
          // `start` on the END edge (or `end` on the START) carried it past the anchor.
          "${describe(outcome, command.toRight)}; now moving the ${if (driver.activeEdge == Edge.END) "end" else "start"}"
        } else if (touchedForeignWindow && isStuck(outcome)) {
          "a notification is in the way"
        } else if (touchedEdgeZone && isStuck(outcome)) {
          Diag.log("  the press touched down in a side gesture zone and went nowhere")
          EDGE_ZONE_STATUS
        } else {
          describe(outcome, command.toRight)
        }
      )
      learnEdgeZoneVerdict(outcome)
      busy = false
      // The press just moved the selection, so the toolbar has just moved too. After `busy` clears,
      // or the guard in refreshMask would skip it.
      refreshMask()
      /*
       * Prime whatever the press left selected. Every announcement it caused arrived while `busy`,
       * where onAccessibilityEvent does not prime, so a grow that carried the edge into a new node
       * left that node unasked. Measured 2026-09-24: a `word →` into an 81-character wrapped
       * paragraph, then a `char ←` 3 s later, and the node was still cold on that press's first ask.
       */
      observer.latest?.let { locator.primeCharacterRects(it, driver.activeEdge) }

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
    is Outcome.Moved -> outcome.madeProgress()
    is Outcome.Degraded -> true
    Outcome.NoSelection, Outcome.HandleLost, Outcome.AnchorMoved, Outcome.RowUnknown, Outcome.ColumnUnknown, Outcome.HandleCovered,
    Outcome.AtFloor, Outcome.AtDocumentEdge, Outcome.EdgeUnknown, Outcome.Obstructed, Outcome.InEdgeZone, Outcome.InGestureBar,
    Outcome.OffScreen, Outcome.BeyondOffScreen, Outcome.NeighbourUnmeasured, Outcome.PastScreenEdge -> false
  }

  /**
   * A press that touched the selection, and so was not refused, but got nowhere. Only these can be
   * the edge zone's doing: a refusal touched nothing.
   */
  private fun isStuck(outcome: Outcome): Boolean =
    outcome == Outcome.HandleLost || (outcome is Outcome.Moved && !outcome.madeProgress())

  /** Whether a handle touch-down of this press fell inside a side system-gesture zone. */
  private var touchedEdgeZone = false

  /** Debug only: [noteEdgeZone] drops such a touch-down, as a ROM's own edge gesture does. */
  private var forceSwallowEdgeZone = false

  /**
   * The left and right system-gesture zones' widths in screen pixels. Zero on three-button
   * navigation, where nothing claims the edges.
   */
  private fun sideGestureZones(): android.graphics.Insets =
    getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
      .getInsets(android.view.WindowInsets.Type.systemGestures())
      .let { android.graphics.Insets.of(it.left, 0, it.right, 0) }

  /**
   * Whether the caller must drop the touch-down at [down], having logged it when it is inside a side
   * system-gesture zone.
   *
   * A START handle on a line's first character sits at the page's margin, and Chrome draws it beyond
   * the caret, so at a 16 dp margin the whole handle is within 16 dp of the screen's edge. Stock
   * Android lets the touch through, because Chrome excludes its handles from the back gesture: on
   * the `handset` profile (gesture navigation, a 105 px zone each side) a grab at x 24.5 moved the
   * START, with `mSystemGestureExclusion` covering the handle. On the OnePlus 15 the same grab
   * announced nothing (2026-10-05). OxygenOS runs an edge gesture of its own, an `edge-swipe` spy
   * monitor over `[0,141]-[84,2772]` on the left, and no aim inside a handle drawn at x 0..56 can
   * leave that strip. So the grab is still made, and a press that went nowhere after one says why
   * instead of "didn't move".
   *
   * **Only one unanswered touch-down per press.** A swallowed grab announces nothing, so the press
   * escalates, and every retry starts from the same pixel with a longer reach inward: measured
   * under the debug marker, twelve drags from x 24.5 reaching up to 630 px. From the left edge that
   * is the back gesture's own shape. So once a touch-down in a zone has gone unanswered, every later
   * one in the press is dropped. A zone grab that worked announced something, and is not held to it.
   */
  private fun noteEdgeZone(down: PointF): EdgeZoneDown {
    if (!inSideZone(down)) return EdgeZoneDown.CLEAR
    if (edgeZoneUnanswered()) {
      Diag.log("  the last touch-down in a side gesture zone went unanswered — not touching (${down.x}, ${down.y})")
      edgeZoneSwallowsHere = true
      return EdgeZoneDown.UNANSWERED
    }
    touchedEdgeZone = true
    announcedAtEdgeDown = observer.announced
    toolbarAtEdgeDown = toolbarShown()
    edgeDownFollowed = false
    val zones = sideGestureZones()
    Diag.log(
      "  the touch-down at (${down.x}, ${down.y}) is in a side gesture zone " +
        "(left ${zones.left} px, right ${zones.right} px), toolbar ${if (toolbarAtEdgeDown) "up" else "down"}"
    )
    if (!forceSwallowEdgeZone) return EdgeZoneDown.MADE
    Diag.log("  debug: swallowed it")
    return EdgeZoneDown.SWALLOWED
  }

  /** What [noteEdgeZone] made of a touch-down. */
  private enum class EdgeZoneDown {
    /** Not in a zone. */
    CLEAR,

    /** In a zone, and made. */
    MADE,

    /** In a zone, and dropped by the debug marker as the ROM's own edge gesture would take it. */
    SWALLOWED,

    /** In a zone after one that went unanswered this press, so dropped. */
    UNANSWERED,
  }

  private fun inSideZone(point: PointF): Boolean {
    val zones = sideGestureZones()
    return point.x < zones.left || point.x >= screenWidth() - zones.right
  }

  /**
   * How far a stroke that touches down at [down] and heads for [next] first rises straight up: past
   * the touch slop when [down] is in a side gesture zone, and 0 otherwise or when the first move is
   * already mostly vertical and past it. Negative, because it is up.
   *
   * OxygenOS's edge strip judges a stroke by its first move. Measured 2026-10-14 on the OnePlus 15
   * (Chrome 154, a served page of one-line rows, the strip `[1188,548]-[1272,2772]` on the right and
   * `[0,141]-[84,2772]` on the left, a touch slop of 28 px) with hand drags (`input motionevent`),
   * judged by screenshot. From an END handle at (1249, 1879), a drag 44 px inward along the row was
   * swallowed 2 times of 2: the selection stayed and the toolbar stayed up. The same drag after a
   * straight rise of 30, 40 or 95 px moved the END exactly one character each time. After a rise of
   * 20 px, inside the slop, it was swallowed. A START at the left margin did the same: inward
   * swallowed 2 of 2, a 40 px rise first moved it one character, and a 20 px rise was swallowed.
   *
   * It rises rather than drops because the handle hangs below its line. A 40 px drop carried the
   * END onto the next row, while a rise stays on the handle's own line. Stock Android lets the touch
   * through either way, so there the rise costs only its own few milliseconds.
   */
  private fun edgeLead(down: PointF, next: PointF): Float {
    if (!inSideZone(down)) return 0f
    val lead = ViewConfiguration.get(this).scaledTouchSlop + EDGE_LEAD_PAST_SLOP_DP * resources.displayMetrics.density
    val dx = kotlin.math.abs(next.x - down.x)
    val dy = kotlin.math.abs(next.y - down.y)
    return if (dy >= lead && dy >= dx) 0f else -lead
  }

  /**
   * Whether this press made a touch-down in a side zone and nothing has answered it since: no
   * announcement, and the target app has not been seen taking its toolbar down.
   */
  private fun edgeZoneUnanswered(): Boolean =
    touchedEdgeZone && observer.announced === announcedAtEdgeDown && !edgeDownFollowed

  /** What had been announced at this press's last touch-down in a side gesture zone. */
  private var announcedAtEdgeDown: SelectionObserver.Snapshot? = null

  /** Whether the target app's selection toolbar was up at this press's last zone touch-down. */
  private var toolbarAtEdgeDown = false

  /** Whether the target app took its toolbar down after this press's last zone touch-down. */
  private var edgeDownFollowed = false

  /**
   * Runs [then] once the target app's toolbar is back, or after [EDGE_DOWN_TOOLBAR_WAIT_MS], when
   * [down] is in a side zone; at once otherwise.
   *
   * [noteToolbarAfterEdgeDown] needs the toolbar up at the touch-down, and a touch-down straight after
   * another drag finds it still down for that one. Measured 2026-10-14 on the rig: `⇥ word`'s row
   * change lifted at the left margin, and the grow touched down in the zone with the toolbar down.
   */
  private fun awaitToolbarBeforeEdgeDown(down: PointF, waited: Long = 0, then: () -> Unit) {
    if (!inSideZone(down) || toolbarShown() || waited >= EDGE_DOWN_TOOLBAR_WAIT_MS) {
      if (waited > 0) Diag.log("  side gesture zone: waited ${waited}ms for the toolbar before touching down")
      then()
      return
    }
    handler.postDelayed({ awaitToolbarBeforeEdgeDown(down, waited + EDGE_DOWN_TOOLBAR_POLL_MS, then) }, EDGE_DOWN_TOOLBAR_POLL_MS)
  }

  private fun toolbarShown(): Boolean = locator.toolbarBounds(
    windows = windows.orEmpty(),
    packageName = observer.latest?.packageName,
    screenWidth = screenWidth(),
  ) != null

  /**
   * Looks for the one sign a SILENT zone touch-down reached the target app: its toolbar going down.
   *
   * Silence alone does not say the ROM took the touch. Chrome holds an edge on a word's START until
   * the finger passes the word's middle, so a grab or a short grow from there announces nothing on
   * stock Android too. Measured 2026-10-13 on the rig: the END on line 2's first caret, aimed at x 50
   * inside the 60 px left zone, and `→ char` was read as swallowed, which refused every margin press
   * of the connection after it. But the target app takes its toolbar down for the whole of a handle
   * drag (and puts it back a little after the lift), while a touch-down the ROM swallowed never
   * reaches it and leaves the toolbar up. So a toolbar that was up at the touch-down and is gone now
   * means the app is following the pointer, and the silence is its own hold.
   */
  private fun noteToolbarAfterEdgeDown(at: String) {
    if (!touchedEdgeZone || edgeDownFollowed || !toolbarAtEdgeDown) return
    if (observer.announced !== announcedAtEdgeDown) return
    if (toolbarShown()) {
      Diag.log("  $at: the toolbar is still up after the zone touch-down")
      return
    }
    Diag.log("  $at: the app took its toolbar down after the zone touch-down, so it is following the pointer — the silence is its own")
    edgeDownFollowed = true
  }

  /** Whether the held pointer was put down in a side gesture zone this press. */
  private var heldDownInEdgeZone = false

  /**
   * Whether this ROM swallows a touch-down in a side gesture zone: null until a press has made one,
   * then what that press found. Kept for the connection, not for ever, because "unanswered" can also
   * be a grab that simply missed, and a wrong `true` would refuse every margin grab after it. A
   * rebind clears it.
   *
   * It is what lets a press stop before it starts. Measured 2026-10-10 on the handset (OxygenOS), each
   * press paid for re-learning it: `⤒ start` scrolled 294 px before its one grab, a right-margin
   * `→ char` pushed a pointer Chrome never saw six times, and a left-margin one waited out twelve
   * dropped tries. Stock Android takes the grab (the `handset` profile, 2026-10-05), so the first
   * press of a connection has to try.
   */
  private var edgeZoneSwallowsHere: Boolean? = null

  override fun edgeZoneSwallows(point: PointF): Boolean {
    val p = onScreen(point)
    if (!inSideZone(p)) return false
    return when {
      edgeZoneUnanswered() -> {
        Diag.log("  (${p.x}, ${p.y}) is in a side gesture zone, and this press's touch-down there went unanswered")
        edgeZoneSwallowsHere = true
        true
      }
      edgeZoneSwallowsHere == true -> {
        Diag.log("  (${p.x}, ${p.y}) is in a side gesture zone, and this ROM has swallowed a touch-down there")
        true
      }
      else -> false
    }
  }

  /** Updates [edgeZoneSwallowsHere] from a press that made a touch-down in a side zone. */
  private fun learnEdgeZoneVerdict(outcome: Outcome) {
    if (!touchedEdgeZone) return
    val verdict = when {
      !edgeZoneUnanswered() -> false
      outcome == Outcome.InEdgeZone || isStuck(outcome) -> true
      else -> return
    }
    /*
     * A grab aimed by a box's average can miss the handle outright, and a miss is as silent as a
     * swallowed touch-down. Measured 2026-10-13 on the rig (stock Android, which takes such a grab): a
     * cold Chromium `<p>`'s END placed by its block box at x 706, 186 px right of the handle, announced
     * nothing, and every margin grab after it was refused for the rest of the connection.
     */
    if (verdict && locator.aimedByAverage) {
      Diag.log("  side gesture zones: not learning from this press — its aim was a box's average, which can simply miss")
      return
    }
    if (verdict != edgeZoneSwallowsHere) {
      Diag.log("  side gesture zones: ${if (verdict) "this ROM swallows a touch-down there" else "a touch-down there was answered"}")
    }
    edgeZoneSwallowsHere = verdict
  }

  /** Whether this press dropped a touch-down because another app's window owned its pixel. */
  private var touchedForeignWindow = false

  /**
   * The window of another app that would take a touch-down at [point], or null when the target, the
   * pad, or nothing listed owns it.
   *
   * A touch goes to the topmost window whose touchable region holds it, so the list is walked from
   * the top (it is ordered that way) and the first window holding the point decides. A heads-up
   * notification is SystemUI's, above the target: on the rig a Messages heads-up is a `TYPE_SYSTEM`
   * window with the region `(32,0)-(688,399)` (2026-10-05). The target's own windows, its toolbar
   * included, are not foreign, and neither is the pad, which [clearThePadFor] lets touches through.
   */
  private fun foreignWindowAt(point: PointF): android.graphics.Rect? {
    val target = observer.latest?.packageName ?: rootInActiveWindow?.packageName?.toString() ?: return null
    val x = point.x.toInt()
    val y = point.y.toInt()
    for (window in windows.orEmpty()) {
      val region = android.graphics.Region().also(window::getRegionInScreen)
      if (!region.contains(x, y)) continue
      val owner = window.root?.packageName?.toString()
      if (owner == target) return null
      if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
        (owner == null || owner == packageName)
      ) continue
      return region.bounds
    }
    return null
  }

  /**
   * Whether the caller must drop the touch-down at [down] because another app's window owns it: the
   * floor under every dispatched touch, as [onScreen] is for the coordinates. The driver already
   * avoids such a window where it can, so reaching this means a heads-up arrived mid-press.
   */
  private fun noteForeignWindow(down: PointF): Boolean {
    // The bottom gesture zone is the system's whatever is drawn there: see [HandleLocator.clearOfGestureBar].
    if (down.y >= screenHeight() - systemBottomInset()) {
      Diag.log("  the touch-down at (${down.x}, ${down.y}) is in the bottom system-gesture zone — not touching")
      return true
    }
    val foreign = foreignWindowAt(down) ?: return false
    touchedForeignWindow = true
    Diag.log("  the touch-down at (${down.x}, ${down.y}) is inside another app's window $foreign — not touching")
    return true
  }

  /** Whether this press has made the pad transparent to touch, so its end must make it solid again. */
  private var padCleared = false

  /**
   * Makes the pad transparent to touch when a gesture is about to touch down inside it, and leaves it
   * so until the press ends.
   *
   * Asked of every touch-down point rather than once per press, because the press itself can carry
   * the handle under the pad. Measured 2026-09-24 on the rig: a `word →` from the end of a wrapped
   * line changed row onto a line at y 1193, inside the pad's footprint (y 1152-1470), and the seven
   * drags of the walk back and the fourteen of the next press all landed on the pad, announcing
   * nothing. The once-per-press check also missed that second press, because it placed the handle
   * at the wrapped node's `bounds.bottom`, the LAST line, 1487 and below the pad.
   *
   * The pad's bounds come from the accessibility window list rather than from its `LayoutParams`,
   * because those are inset by the status bar while gesture coordinates are raw screen pixels —
   * comparing the two directly is off by the status bar's height.
   *
   * [then] is the touch-down itself, and it runs only once the pad really lets touches through.
   * Dispatched in the same breath, a grab inside the `✕` reached the pad before its relayout and
   * closed it (measured 2026-10-05 on the handset; see [Pad.setTransparentToTouch]).
   */
  private fun clearThePadFor(down: PointF, then: () -> Unit) {
    val padBounds = if (padCleared) null else padBounds()
    val pad = pad
    if (padBounds == null || pad == null || !padBounds.contains(down.x.toInt(), down.y.toInt())) {
      then()
      return
    }
    Diag.log("  the pad at $padBounds covers the touch-down at (${down.x}, ${down.y}) — letting it through")
    padCleared = true
    val askedAt = android.os.SystemClock.uptimeMillis()
    pad.setTransparentToTouch(true) {
      Diag.log("  the pad lets touches through after ${android.os.SystemClock.uptimeMillis() - askedAt} ms")
      then()
    }
  }

  /** The pad's real screen rectangle, from the accessibility window list. */
  private fun padBounds(): android.graphics.Rect? = windows.orEmpty()
    .firstOrNull {
      it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
        it.root?.packageName?.toString() == packageName
    }
    ?.let { window -> android.graphics.Rect().also { window.getBoundsInScreen(it) } }

  /**
   * The part of the screen where the selection's text can be seen and a handle reached: the
   * OUTERMOST scrollable ancestor of the source node, which on Chrome is the page's viewport
   * (`android.webkit.WebView`, `[0,313][720,1472]` on the rig), or the source's window where nothing
   * scrolls. The docked pad is cut off it, because a handle dragged under the pad cannot be seen.
   *
   * Outermost, not nearest: a page's inner scrollers, a wide table or a code block, are scrollable
   * too, and paging by one of those would page by a few lines.
   */
  private fun contentBand(snapshot: SelectionObserver.Snapshot): ContentBand? {
    val source = snapshot.source ?: return null
    var scroller: android.view.accessibility.AccessibilityNodeInfo? = null
    var node: android.view.accessibility.AccessibilityNodeInfo? = source
    var depth = 0
    while (node != null && depth < MAX_ANCESTORS) {
      if (node.isScrollable) scroller = node
      node = node.parent
      depth++
    }
    val band = scroller?.let { android.graphics.Rect().also(it::getBoundsInScreen) }
      ?: source.window?.let { window -> android.graphics.Rect().also(window::getBoundsInScreen) }
      ?: return null
    val drawn = android.graphics.Rect(band)
    val pad = padBounds()
    if (pad != null && android.graphics.Rect.intersects(pad, band) && pad.width() * 2 > band.width()) {
      if (pad.exactCenterY() > band.exactCenterY()) {
        band.bottom = minOf(band.bottom, pad.top)
      } else {
        band.top = maxOf(band.top, pad.bottom)
      }
    }
    return ContentBand(band, scrolls = scroller != null, drawn = drawn) {
      scroller?.let(::verticalScrollability) ?: (null to null)
    }
  }

  /**
   * Which ways [scroller] says it can scroll, from the scroll actions it lists right now: the
   * directional `ACTION_SCROLL_UP` / `DOWN` where it lists any, otherwise `ACTION_SCROLL_BACKWARD` /
   * `FORWARD`. Null for both when it lists none of them, because then nothing is known.
   */
  private fun verticalScrollability(
    scroller: android.view.accessibility.AccessibilityNodeInfo,
  ): Pair<Boolean?, Boolean?> {
    runCatching { scroller.refresh() }
    val ids = scroller.actionList.orEmpty().map { it.id }.toSet()
    val directional = setOf(android.R.id.accessibilityActionScrollUp, android.R.id.accessibilityActionScrollDown)
    val backward = android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    val forward = android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    return when {
      ids.any { it in directional } ->
        (android.R.id.accessibilityActionScrollUp in ids) to (android.R.id.accessibilityActionScrollDown in ids)
      backward in ids || forward in ids -> (backward in ids) to (forward in ids)
      else -> null to null
    }.also { Diag.log("  band: the scroller lists scroll actions ${ids.filter { it in directional || it == backward || it == forward }} -> up=${it.first} down=${it.second}") }
  }

  private fun onSwapEdge() {
    repeating = null
    driver.activeEdge = if (driver.activeEdge == Edge.END) Edge.START else Edge.END
    locator.forgetAnchor()
    pad?.showStatus("moving the ${if (driver.activeEdge == Edge.END) "end" else "start"}")
  }

  private fun describe(outcome: Outcome, toRight: Boolean): String = when (outcome) {
    is Outcome.Moved -> when {
      // Two offsets in two different nodes: neither number says anything next to the other.
      // Stopped one short of where it was going: the next caret's handle would be off the screen.
      outcome.stoppedAtScreenEdge -> "stopped at the screen's edge"
      outcome.crossedNode -> "moved into another block"
      outcome.fromOffset == outcome.toOffset -> "didn't move"
      else -> "moved ${outcome.fromOffset} → ${outcome.toOffset}"
    }
    // Distinguish "there is no selection" from "there is one but I cannot see it", because they need
    // opposite things from the user and the second is common: a selection event fires only on a
    // CHANGE, so one made before the service connected — or a long-press INSIDE an existing
    // selection, which re-selects nothing — leaves the pad blind while text is visibly highlighted.
    // Re-selecting cannot help where Chrome hides the range from this Android (SelectionObserver
    // .chromeHidesSelection): only its flag does, so the status line names it.
    Outcome.NoSelection -> when {
      !aSelectionSeemsToExist() -> "select some text first"
      observer.chromeHidesSelection ->
        // The docked status shows two lines at 360 dp, so the flag's id and URL do not fit. Searching
        // chrome://flags for these two words finds it; the Play listing spells it out in full.
        "Chrome hides it: disable flag \"extended selection\""
      else -> "tap elsewhere, then long-press to re-select"
    }
    Outcome.HandleLost -> "lost the handle — reselect"
    // The app moved the other end as well; the selection on screen is what the pad steps from now.
    Outcome.AnchorMoved -> "the app moved the other end too — check the selection"
    // Deliberately NOT "reselect": nothing was lost and nothing was touched, and the same long-press
    // on the same wrapped paragraph refuses again. Selecting inside ONE line is the thing that works.
    Outcome.RowUnknown -> "this block wraps — select within one line"
    // Not "reselect" either: nothing was touched, and the same block refuses the same way.
    Outcome.ColumnUnknown -> "this block won't say where its text ends"
    // Also not "reselect": the selection is untouched. Lower on screen, the toolbar goes above it.
    Outcome.HandleCovered -> "the menu covers the handle — scroll the text lower"
    Outcome.AtFloor -> "one character left — the app keeps it"
    Outcome.AtDocumentEdge -> "already at the ${if (driver.activeEdge == Edge.END) "end" else "start"} of the text"
    // Nothing was touched; a heads-up goes by itself, and swiping it away works too.
    Outcome.Obstructed -> "a notification is in the way"
    // Nothing more was touched. The selection is intact, and the other edge can still be moved.
    Outcome.InEdgeZone -> EDGE_ZONE_STATUS
    // Nothing was touched. Higher on screen the handle is clear of the system's gesture bar.
    Outcome.InGestureBar -> "the handle is on the gesture bar — scroll the text higher"
    // Nothing was touched. Nudging that handle by hand announces where it is.
    Outcome.EdgeUnknown -> "can't see the ${if (driver.activeEdge == Edge.END) "end" else "start"} — nudge its handle once"
    // Nothing was touched. Scrolling the selection back into view makes the next press work.
    Outcome.OffScreen -> "the selection is scrolled out of view — scroll it back"
    // Nothing was touched. The text beyond the edge is off screen; the next press works once it is not.
    Outcome.BeyondOffScreen -> "the text ${if (toRight) "after it is below — scroll down" else "before it is above — scroll up"} a little"
    Outcome.NeighbourUnmeasured -> "the text ${if (driver.activeEdge == Edge.END) "after" else "before"} it won't say where it is — try again"
    // Nothing was touched. A handle on that caret would be drawn off the screen, and nothing can grab it there.
    Outcome.PastScreenEdge -> "the next character is at the screen's edge"
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
   *
   * "Comes back" is not free, though: the target app sees the visit. With [pastTarget] false the
   * detour goes no further than [to], and only as far as one pixel past the touch slop, so a reach
   * longer than the slop is a straight drag. The miss still reads as a scroll. See
   * `SelectionDriver.growOneUnit` for what the visit cost a word grow on Chrome.
   */
  override fun drag(
    from: PointF,
    to: PointF,
    durationMs: Long,
    pastTarget: Boolean,
    onFinished: (Boolean) -> Unit,
  ) {
    val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
    val forward = to.x >= from.x
    // Not past [to]: only as far as the slop needs, which a reach longer than the slop already is.
    val distance = if (pastTarget) {
      (touchSlop * SLOP_MULTIPLE).toFloat()
    } else {
      maxOf(kotlin.math.abs(to.x - from.x), touchSlop + 1f)
    }
    val away = if (forward) distance else -distance
    val start = onScreen(from)
    val lead = edgeLead(start, PointF(from.x + away, from.y))
    val detour = onScreen(PointF(from.x + away, from.y + lead))
    val end = onScreen(to)
    val path = Path().apply {
      moveTo(start.x, start.y)
      if (lead != 0f) {
        val raised = onScreen(PointF(start.x, start.y + lead))
        lineTo(raised.x, raised.y)
        Diag.log("  drag: rising ${-lead} px before it leaves the side gesture zone")
      }
      lineTo(detour.x, detour.y)
      if (end.x != detour.x || end.y != detour.y) lineTo(end.x, end.y)
    }
    awaitToolbarBeforeEdgeDownUnlessRefused(start) { dragNow(start, path, durationMs, onFinished) }
  }

  /** [awaitToolbarBeforeEdgeDown], skipped when [noteEdgeZone] is about to drop the touch-down anyway. */
  private fun awaitToolbarBeforeEdgeDownUnlessRefused(down: PointF, then: () -> Unit) {
    if (edgeZoneUnanswered() || forceSwallowEdgeZone) then() else awaitToolbarBeforeEdgeDown(down, then = then)
  }

  private fun dragNow(start: PointF, path: Path, durationMs: Long, onFinished: (Boolean) -> Unit) {
    // Not dispatched, and said so: a press that goes on escalating into a heads-up gets nowhere.
    if (noteForeignWindow(start)) {
      Diag.log("  the drag was not dispatched")
      onFinished(false)
      return
    }
    when (noteEdgeZone(start)) {
      EdgeZoneDown.CLEAR, EdgeZoneDown.MADE -> Unit
      // Played as far as the press can tell, as on a ROM whose edge gesture took it.
      EdgeZoneDown.SWALLOWED -> {
        Diag.log("  the drag was not dispatched")
        onFinished(true)
        return
      }
      // Not dispatched, and said so, so the escalation ends here rather than waiting out every try.
      EdgeZoneDown.UNANSWERED -> {
        Diag.log("  the drag was not dispatched")
        onFinished(false)
        return
      }
    }
    clearThePadFor(start) {
      dispatch(GestureDescription.StrokeDescription(path, 0, durationMs)) { played ->
        // Straight after the lift, while the app still has its toolbar down for the drag.
        noteToolbarAfterEdgeDown("drag")
        onFinished(played)
      }
    }
  }

  /**
   * Drag, then hold at the destination without lifting — two chained strokes, because
   * `continueStroke` produces a stroke for the *next* gesture and keeps the pointer down between
   * them. The page steps scroll with it: a swipe that stands still before it lifts has no velocity
   * left to fling with, so the page travels the distance asked.
   *
   * The hold is a zero-length `moveTo` continuation that does not itself continue, and that shape
   * plays: measured 2026-09-28, a 1200 ms hold reported completed and the press took 1560 ms. It is
   * a zero-length continuation that is itself CONTINUED which is refused (see [HeldPointer]).
   */
  override fun dragAndHold(
    from: PointF,
    to: PointF,
    dragMs: Long,
    holdMs: Long,
    onFinished: (Boolean) -> Unit,
  ) {
    val start = onScreen(from)
    val end = onScreen(to)
    val move = GestureDescription.StrokeDescription(
      Path().apply {
        moveTo(start.x, start.y)
        lineTo(end.x, end.y)
      },
      0,
      dragMs,
      true,
    )
    if (noteForeignWindow(start)) {
      Diag.log("  the swipe was not dispatched")
      onFinished(false)
      return
    }
    clearThePadFor(start) {
      dispatch(move) { moved ->
        if (!moved) {
          onFinished(false)
          return@dispatch
        }
        val hold = move.continueStroke(Path().apply { moveTo(end.x, end.y) }, 0, holdMs, false)
        dispatch(hold, onFinished)
      }
    }
  }

  /**
   * The held route (the held-pointer fix). The grab travels past the touch slop for the same reason [drag] does — a
   * miss that reads as a tap navigates — and [HeldPointer] adds the detour itself.
   */
  override fun grabAndHold(
    from: PointF,
    to: PointF,
    detourBack: Boolean,
    pastTarget: Boolean,
    onGrabbed: (Boolean) -> Unit,
  ) {
    // A chain from a previous press cannot be reused: the press itself is a real touch, and a real
    // touch ends the target app's tracking of the handle even though the chain survives it
    // (measured, the held-pointer fix). So start clean rather than inheriting a pointer the app has stopped
    // following.
    if (heldPointer.isHeld) heldPointer.releaseNow()
    // Not past [to]: the same bound [drag] uses, as far as [to] or one pixel past the slop.
    val shortDetour = if (pastTarget) null else ViewConfiguration.get(this).scaledTouchSlop + 1f
    awaitToolbarBeforeEdgeDownUnlessRefused(onScreen(from)) { grabNow(from, to, detourBack, shortDetour, onGrabbed) }
  }

  private fun grabNow(
    from: PointF,
    to: PointF,
    detourBack: Boolean,
    shortDetour: Float?,
    onGrabbed: (Boolean) -> Unit,
  ) {
    if (noteForeignWindow(onScreen(from))) {
      Diag.log("  the grab was not dispatched")
      onGrabbed(false)
      return
    }
    when (noteEdgeZone(onScreen(from))) {
      EdgeZoneDown.CLEAR -> Unit
      EdgeZoneDown.MADE -> heldDownInEdgeZone = true
      EdgeZoneDown.SWALLOWED, EdgeZoneDown.UNANSWERED -> {
        Diag.log("  the grab was not dispatched")
        onGrabbed(false)
        return
      }
    }
    clearThePadFor(onScreen(from)) {
      heldPointer.grab(from, to, detourBack, shortDetour) { grabbed ->
        noteToolbarAfterEdgeDown("grab")
        onGrabbed(grabbed)
      }
    }
  }

  /**
   * Refused, and the pointer lifted, when it went down in a side gesture zone and nothing has been
   * announced since: the edge gesture took the down, so the target app is not following this
   * pointer, and every push of it is time spent on nothing. Measured 2026-10-10 on the handset, a
   * right-margin grab went on into six snap-through pushes, about 1.6 s of a 2.0 s press.
   */
  override fun moveHeld(to: PointF): Boolean {
    if (heldDownInEdgeZone && heldPointer.isHeld) noteToolbarAfterEdgeDown("held")
    if (heldDownInEdgeZone && heldPointer.isHeld && edgeZoneUnanswered()) {
      Diag.log("  held: the grab in a side gesture zone went unanswered — lifting rather than pushing on")
      edgeZoneSwallowsHere = true
      heldPointer.releaseNow()
      return false
    }
    return heldPointer.moveTo(to)
  }

  override fun releaseHeld(onLifted: () -> Unit) = heldPointer.release(onLifted)

  override fun heldAt(): PointF? = heldPointer.position

  override fun screenWidth(): Int = resources.displayMetrics.widthPixels

  override fun screenHeight(): Int = resources.displayMetrics.heightPixels

  override fun systemTopInset(): Int =
    getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets.let { insets ->
      // The rig reports a 159 px status bar and a 183 px mandatory gesture zone below it.
      maxOf(
        insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top,
        insets.getInsets(android.view.WindowInsets.Type.systemGestures()).top,
        insets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures()).top,
      )
    }

  override fun systemBottomInset(): Int =
    getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets.let { insets ->
      // The rig reports a 48 px navigation bar and a 64 px mandatory gesture zone above the screen's bottom.
      maxOf(
        insets.getInsets(android.view.WindowInsets.Type.systemGestures()).bottom,
        insets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures()).bottom,
      )
    }

  override fun touchSlop(): Int =ViewConfiguration.get(this).scaledTouchSlop

  /**
   * [point], moved onto the screen if it lies off it.
   *
   * Every point a stroke visits goes through here, because `StrokeDescription` THROWS on a path
   * whose bounds are negative, and the throw kills the process: the pad vanishes mid-press and the
   * system restarts the service a second later. Measured 2026-09-24 on the rig, a walk-back whose
   * reach escalated leftward from a handle at x 84 aimed its seventh try below 0. A slop detour
   * near the left edge goes negative on its own. The callers stop escalating at the edge; this is
   * the floor under all of them, so no caller's arithmetic can take the service down.
   */
  private fun onScreen(point: PointF) = PointF(
    point.x.coerceIn(0f, (screenWidth() - 1).toFloat()),
    point.y.coerceIn(0f, (screenHeight() - 1).toFloat()),
  )

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

    /** The debug marker file under `filesDir` that forces the 36.1 gate off: see [onServiceConnected]. */
    const val FORCE_NO_EXTENDED_SELECTION = "force-no-extended-selection"

    /** The debug marker file that makes every per-character ask refuse: see [onServiceConnected]. */
    const val FORCE_REFUSE_CHARACTER_RECTS = "force-refuse-character-rects"

    /** The debug marker file that drops a handle touch-down in a side gesture zone: see [noteEdgeZone]. */
    const val FORCE_SWALLOW_EDGE_ZONE = "force-swallow-edge-zone"

    /** The debug marker file that cuts every page scroll short: see `SelectionDriver.forceShortScroll`. */
    const val FORCE_SHORT_SCROLL = "force-short-scroll"

    /** The debug marker file that keeps the move onto a node's last caret silent: see `SelectionDriver.forceSilentNodeEnd`. */
    const val FORCE_SILENT_NODE_END = "force-silent-node-end"

    /** The debug marker file that announces a leaf's selection in its container's frame: see `SelectionObserver.forceContainerFrame`. */
    const val FORCE_CONTAINER_FRAME = "force-container-frame"

    /** The debug marker file that makes the moving edge's node refuse `refresh()`: see `SelectionDriver.forceStaleSource`. */
    const val FORCE_STALE_SOURCE = "force-stale-source"

    /** The debug marker file that reads a held char grab's landing as re-anchored: see `SelectionDriver.forceReanchor`. */
    const val FORCE_REANCHOR = "force-reanchor"

    /** The status line for a press the side gesture zone stopped. */
    const val EDGE_ZONE_STATUS = "the handle is in the screen edge's swipe zone"

    /** How far [contentBand] walks up from a source node looking for the page's scroller. */
    const val MAX_ANCESTORS = 64

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

    /**
     * How long a touch-down in a side gesture zone waits for the target app's toolbar to come back
     * first: see [awaitToolbarBeforeEdgeDown]. The driver's own wait for it is 900 ms too.
     */
    const val EDGE_DOWN_TOOLBAR_WAIT_MS = 900L

    const val EDGE_DOWN_TOOLBAR_POLL_MS = 25L

    /** How far past the touch slop [edgeLead]'s rise goes, so the strip has seen a vertical move. */
    const val EDGE_LEAD_PAST_SLOP_DP = 4f
  }
}
