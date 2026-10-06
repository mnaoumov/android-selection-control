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
  private val heldPointer = HeldPointer(::onScreen) { gesture, onFinished ->
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

    // The rig is API 37, so only this reproduces a 36.0 device's blind Chrome there. A debuggable
    // build alone can be given the file: `adb shell run-as dev.mnaoumov.asc touch files/<name>`.
    val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    observer.forceNoExtendedSelection = debuggable && java.io.File(filesDir, FORCE_NO_EXTENDED_SELECTION).exists()
    if (observer.forceNoExtendedSelection) Diag.log("debug: treating the platform as below 36.1")
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
    touchedForeignWindow = false
    val edgeBefore = driver.activeEdge
    driver.perform(command) { outcome ->
      if (padCleared) pad?.setTransparentToTouch(false)
      padCleared = false
      pad?.showStatus(
        if (driver.activeEdge != edgeBefore) {
          // `start` on the END edge (or `end` on the START) carried it past the anchor.
          "${describe(outcome)}; now moving the ${if (driver.activeEdge == Edge.END) "end" else "start"}"
        } else if (touchedForeignWindow && isStuck(outcome)) {
          "a notification is in the way"
        } else if (touchedEdgeZone && isStuck(outcome)) {
          Diag.log("  the press touched down in a side gesture zone and went nowhere")
          "the handle is in the screen edge's swipe zone"
        } else {
          describe(outcome)
        }
      )
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
    Outcome.NoSelection, Outcome.HandleLost, Outcome.RowUnknown, Outcome.ColumnUnknown, Outcome.HandleCovered,
    Outcome.AtFloor, Outcome.EdgeUnknown, Outcome.Obstructed -> false
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
  private fun noteEdgeZone(down: PointF): Boolean {
    val zones = sideGestureZones()
    if (down.x >= zones.left && down.x < screenWidth() - zones.right) return false
    if (touchedEdgeZone && observer.announced === announcedAtEdgeDown) {
      Diag.log("  the last touch-down in a side gesture zone went unanswered — not touching (${down.x}, ${down.y})")
      return true
    }
    touchedEdgeZone = true
    announcedAtEdgeDown = observer.announced
    Diag.log(
      "  the touch-down at (${down.x}, ${down.y}) is in a side gesture zone " +
        "(left ${zones.left} px, right ${zones.right} px)"
    )
    if (forceSwallowEdgeZone) Diag.log("  debug: swallowed it")
    return forceSwallowEdgeZone
  }

  /** What had been announced at this press's last touch-down in a side gesture zone. */
  private var announcedAtEdgeDown: SelectionObserver.Snapshot? = null

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
    val pad = padBounds()
    if (pad != null && android.graphics.Rect.intersects(pad, band) && pad.width() * 2 > band.width()) {
      if (pad.exactCenterY() > band.exactCenterY()) {
        band.bottom = minOf(band.bottom, pad.top)
      } else {
        band.top = maxOf(band.top, pad.bottom)
      }
    }
    return ContentBand(band, scrolls = scroller != null)
  }

  private fun onSwapEdge() {
    repeating = null
    driver.activeEdge = if (driver.activeEdge == Edge.END) Edge.START else Edge.END
    locator.forgetAnchor()
    pad?.showStatus("moving the ${if (driver.activeEdge == Edge.END) "end" else "start"}")
  }

  private fun describe(outcome: Outcome): String = when (outcome) {
    is Outcome.Moved -> when {
      // Two offsets in two different nodes: neither number says anything next to the other.
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
    // Deliberately NOT "reselect": nothing was lost and nothing was touched, and the same long-press
    // on the same wrapped paragraph refuses again. Selecting inside ONE line is the thing that works.
    Outcome.RowUnknown -> "this block wraps — select within one line"
    // Not "reselect" either: nothing was touched, and the same block refuses the same way.
    Outcome.ColumnUnknown -> "this block won't say where its text ends"
    // Also not "reselect": the selection is untouched. Lower on screen, the toolbar goes above it.
    Outcome.HandleCovered -> "the menu covers the handle — scroll the text lower"
    Outcome.AtFloor -> "one character left — the app keeps it"
    // Nothing was touched; a heads-up goes by itself, and swiping it away works too.
    Outcome.Obstructed -> "a notification is in the way"
    // Nothing was touched. Nudging that handle by hand announces where it is.
    Outcome.EdgeUnknown -> "can't see the ${if (driver.activeEdge == Edge.END) "end" else "start"} — nudge its handle once"
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
    val detour = onScreen(PointF(from.x + away, from.y))
    val end = onScreen(to)
    val path = Path().apply {
      moveTo(start.x, start.y)
      lineTo(detour.x, detour.y)
      if (end.x != detour.x || end.y != detour.y) lineTo(end.x, end.y)
    }
    // Not dispatched, and said so: a press that goes on escalating into a heads-up gets nowhere.
    if (noteForeignWindow(start)) {
      Diag.log("  the drag was not dispatched")
      onFinished(false)
      return
    }
    if (noteEdgeZone(start)) {
      Diag.log("  the drag was not dispatched")
      onFinished(true)
      return
    }
    clearThePadFor(start) { dispatch(GestureDescription.StrokeDescription(path, 0, durationMs), onFinished) }
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
    if (noteForeignWindow(onScreen(from)) || noteEdgeZone(onScreen(from))) {
      Diag.log("  the grab was not dispatched")
      onGrabbed(false)
      return
    }
    clearThePadFor(onScreen(from)) { heldPointer.grab(from, to, detourBack, shortDetour, onGrabbed) }
  }

  override fun moveHeld(to: PointF): Boolean = heldPointer.moveTo(to)

  override fun releaseHeld(onLifted: () -> Unit) = heldPointer.release(onLifted)

  override fun heldAt(): PointF? = heldPointer.position

  override fun screenWidth(): Int = resources.displayMetrics.widthPixels

  override fun screenHeight(): Int = resources.displayMetrics.heightPixels

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
  }
}
