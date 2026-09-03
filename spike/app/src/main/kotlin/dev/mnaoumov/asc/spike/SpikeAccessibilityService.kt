package dev.mnaoumov.asc.spike

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Feasibility spikes: selection actions, then synthesised gestures.
 *
 * The first spike asked: does Chrome expose page text as accessibility nodes that honour
 * ACTION_SET_SELECTION and the movement-granularity actions? The framework clearly intends this
 * (AccessibilityNodeInfo.isTextSelectable exists precisely for selectable-but-not-editable text),
 * but intent is not implementation. The answer was NO — page nodes report sel=false edit=false and
 * performAction returns false. The selection actions stop at the same editable-text-buffer boundary
 * InputConnection does.
 *
 * The gesture spike asks the successor question, which needs no selection action at all: can dispatchGesture
 * synthesise the long-press-and-drag that drives Chrome's OWN selection UI, and can the resulting
 * selection handles then be located and moved by exact pixel? The gesture commands below
 * (tap/long/drag/pressdrag/nodetap/nodelong) and the observation commands (sel/events) exist for
 * that. The first-spike commands are kept because they are the controls that make a gesture-spike negative
 * trustworthy.
 *
 * Driven entirely over adb so the target app stays foregrounded:
 *
 *   adb shell am broadcast -a dev.mnaoumov.asc.spike.CMD --es cmd "dump com.android.chrome"
 *   adb logcat -s ASCSPIKE:I -d
 *
 * The pad build asks the question that decides the product's shape, and it is about GRANULARITY. A pad
 * whose buttons mean Shift+Right and Ctrl+Shift+Right needs a word boundary; finding one means
 * either reading the text — which breaks the trust property below — or relying on the target app
 * snapping the handle during a drag. The gesture spike saw one hint of snapping (a drag targeting x=940 landed
 * at 938, exactly a word end) and could not tell snapping from coincidence. The `probe` command
 * settles it by walking a handle in small pixel steps and reading the ANNOUNCED offsets after each
 * one; `selstate`, `nudge` and `draghold` are the supporting rungs.
 *
 * NOTE ON READING TEXT: this build logs a short preview of each node's text, because without it
 * there is no way to tell which node a selection actually landed on. The real app must NOT do
 * this — the first spike's "never READ the buffer" property is the whole trust story. This is a throwaway
 * diagnostic running on the owner's own device against a page he chose. Note that none of the
 * The pad build commands added here read text: their whole signal is offsets and rectangles.
 */
class SpikeAccessibilityService : AccessibilityService() {

  /** Ring buffer filled by [onAccessibilityEvent], drained by the `events` command. */
  private val events = mutableListOf<String>()

  /**
   * The last announced selection range — the "event rung" of the gesture spike's ladder, kept as state rather
   * than as a log line because the pad build's loop has to ASK what is selected between steps.
   *
   * It exists precisely because the framework offers no way to ask: `textSelectionStart/End` stays
   * -1 on page nodes, and TYPE_VIEW_TEXT_SELECTION_CHANGED is a change notification, not a query.
   * So the only state anyone has is what was last announced.
   */
  private data class SelectionSnapshot(
    val from: Int,
    val to: Int,
    val bounds: Rect?,
    val srcLen: Int,
    val pkg: String?,
    val atMs: Long,
  ) {
    fun describe(): String =
      "from=$from to=$to (range ${low()}..${high()}) srcLen=$srcLen " +
        "srcBounds=${bounds?.flattenToString() ?: "none"} pkg=$pkg"

    /**
     * The event's `from`/`to` are ANCHOR and FOCUS, not min and max — drag the start handle below
     * the anchor and they arrive reversed (`from=27 to=19`). Measured 2026-09-02: taking `from` as
     * the left edge grabs the RIGHT handle, so the selection flips end over end on every step,
     * which is exactly what the first start-handle run did for six steps before the cause was
     * visible. Every consumer must normalise.
     */
    fun low(): Int = minOf(from, to)

    fun high(): Int = maxOf(from, to)
  }

  @Volatile
  private var lastSelection: SelectionSnapshot? = null

  /**
   * Sequencing for the multi-step commands. A gesture's completion callback arrives on the main
   * thread, and the settle between steps must not block it — a blocked service thread stops
   * receiving the very events the probe is reading.
   */
  private val handler = Handler(Looper.getMainLooper())

  /** The attached overlay, if any, and where it sits — see [overlayOn]. */
  private var overlayView: View? = null
  private var overlayParams: WindowManager.LayoutParams? = null

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      val cmd = intent?.getStringExtra("cmd")
      if (cmd == null) {
        log("no --es cmd given")
        return
      }
      runCatching { dispatch(cmd.trim()) }
        .onFailure { failure ->
          // The message alone is not enough for a framework throw — "Adding window failed" says
          // nothing about which call rejected it. One line per frame, per the logcat 4 KB rule.
          log("ERROR while handling '$cmd': ${failure::class.java.simpleName}: ${failure.message}")
          failure.stackTraceToString().lineSequence().take(STACK_FRAMES).forEach { log("  $it") }
        }
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    registerReceiver(receiver, IntentFilter(ACTION_CMD), Context.RECEIVER_EXPORTED)
    log("=== service connected; ready for commands on $ACTION_CMD")
  }

  override fun onUnbind(intent: Intent?): Boolean {
    runCatching { unregisterReceiver(receiver) }
    // Without this an overlay outlives the service that made it, and the only way back is a
    // reinstall — the `overlay off` command needs a running service to receive it.
    overlayOff(quiet = true)
    return super.onUnbind(intent)
  }

  /**
   * Records events into a ring buffer for the `events` command, rather than logging them live —
   * with typeAllMask and notificationTimeout=100 the live stream drowns the command output.
   *
   * Deliberately records NO text: the question is whether Chrome ANNOUNCES a selection range, not
   * what the text says. Indices are the whole signal.
   */
  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    val line = buildString {
      append(AccessibilityEvent.eventTypeToString(event.eventType))
      append(" pkg=").append(event.packageName)
      append(" cls=").append(event.className?.toString()?.substringAfterLast('.'))
      append(" from=").append(event.fromIndex)
      append(" to=").append(event.toIndex)
      append(" count=").append(event.itemCount)
      append(" scrollX=").append(event.scrollX)
      append(" scrollY=").append(event.scrollY)
      // Only for the selection events: with typeAllMask, resolving every event's source would be
      // a tree lookup per event. The source's bounds are what ties an announced range to a node.
      if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
        event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
      ) {
        val source = event.source
        val bounds = source?.let { s -> Rect().also { s.getBoundsInScreen(it) } }
        append(" srcBounds=").append(bounds?.flattenToString() ?: "none")
        append(" srcLen=").append(source?.text?.length ?: -1)

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
          lastSelection = SelectionSnapshot(
            from = event.fromIndex,
            to = event.toIndex,
            bounds = bounds,
            srcLen = source?.text?.length ?: -1,
            pkg = event.packageName?.toString(),
            atMs = SystemClock.uptimeMillis(),
          )
        }
      }
    }
    synchronized(events) {
      events += line
      while (events.size > MAX_EVENTS) events.removeAt(0)
    }
  }

  override fun onInterrupt() = Unit

  // ---------------------------------------------------------------- commands

  private fun dispatch(cmd: String) {
    val parts = cmd.split(Regex("\\s+"))
    when (parts[0]) {
      "dump" -> dump(parts.getOrNull(1))
      "node" -> node(parts[1].toInt())
      "focus" -> focus(parts[1].toInt())
      "select" -> select(parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
      "clear" -> clearSelection(parts[1].toInt())
      "gran" -> granularity(
        index = parts[1].toInt(),
        granularity = parts[2].toInt(),
        extend = parts[3].toBoolean(),
        forward = parts.getOrNull(4)?.toBoolean() ?: true,
      )
      "copy" -> copy(parts[1].toInt())
      "windows" -> windows()

      // The gesture spike — gesture synthesis.
      "tap" -> tap(parts[1].toFloat(), parts[2].toFloat())
      "long" -> longPress(parts[1].toFloat(), parts[2].toFloat(), parts.getOrNull(3)?.toLong() ?: LONG_PRESS_MS)
      "drag" -> drag(
        x1 = parts[1].toFloat(),
        y1 = parts[2].toFloat(),
        x2 = parts[3].toFloat(),
        y2 = parts[4].toFloat(),
        dragMs = parts.getOrNull(5)?.toLong() ?: DRAG_MS,
      )
      "pressdrag" -> pressDrag(
        x1 = parts[1].toFloat(),
        y1 = parts[2].toFloat(),
        x2 = parts[3].toFloat(),
        y2 = parts[4].toFloat(),
        holdMs = parts.getOrNull(5)?.toLong() ?: LONG_PRESS_MS,
        dragMs = parts.getOrNull(6)?.toLong() ?: DRAG_MS,
        settleMs = parts.getOrNull(7)?.toLong() ?: SETTLE_MS,
      )
      "nodetap" -> atNodeCentre(parts[1].toInt(), "nodetap") { x, y -> tap(x, y) }
      "nodelong" -> atNodeCentre(parts[1].toInt(), "nodelong") { x, y ->
        longPress(x, y, parts.getOrNull(2)?.toLong() ?: LONG_PRESS_MS)
      }

      // The gesture spike — observation.
      "sel" -> selections()
      "events" -> events(parts.getOrNull(1)?.toInt() ?: 40)
      "chars" -> chars(parts[1].toInt(), parts[2].toInt(), parts[3].toInt())

      // The pad build — the granularity probe.
      "selstate" -> selState()
      "probe" -> probe(
        x = parts[1].toFloat(),
        y = parts[2].toFloat(),
        dx = parts[3].toFloat(),
        count = parts[4].toInt(),
        settleMs = parts.getOrNull(5)?.toLong() ?: SETTLE_MS,
      )
      "nudge" -> nudge(
        edge = parts[1],
        dx = parts[2].toFloat(),
        settleMs = parts.getOrNull(3)?.toLong() ?: SETTLE_MS,
      )
      "findhandle" -> findHandle(
        y = parts[1].toFloat(),
        centreX = parts[2].toFloat(),
        step = parts.getOrNull(3)?.toFloat() ?: FIND_STEP,
        maxProbes = parts.getOrNull(4)?.toInt() ?: FIND_MAX_PROBES,
      )
      "servo" -> servo(
        edge = parts[1],
        dx = parts[2].toFloat(),
        count = parts[3].toInt(),
        settleMs = parts.getOrNull(4)?.toLong() ?: SETTLE_MS,
      )
      // The pad build Phase 1a — the overlay that has to become the pad.
      "overlay" -> when (parts.getOrNull(1)) {
        "on" -> overlayOn(
          width = parts.getOrNull(2)?.toInt() ?: OVERLAY_W,
          height = parts.getOrNull(3)?.toInt() ?: OVERLAY_H,
          x = parts.getOrNull(4)?.toInt() ?: OVERLAY_X,
          y = parts.getOrNull(5)?.toInt() ?: OVERLAY_Y,
        )
        "off" -> overlayOff(quiet = false)
        "move" -> overlayMove(parts[2].toInt(), parts[3].toInt())
        "state", null -> overlayState()
        else -> log("overlay: expected on|off|move|state, got '${parts[1]}'")
      }

      "draghold" -> dragHold(
        x1 = parts[1].toFloat(),
        y1 = parts[2].toFloat(),
        x2 = parts[3].toFloat(),
        y2 = parts[4].toFloat(),
        dragMs = parts.getOrNull(5)?.toLong() ?: DRAG_MS,
        holdMs = parts.getOrNull(6)?.toLong() ?: HOLD_MS,
      )

      else -> log(
        "unknown command '${parts[0]}' — try: " +
          "dump|node|focus|select|clear|gran|copy|windows|" +
          "tap|long|drag|pressdrag|nodetap|nodelong|sel|events|chars|" +
          "selstate|probe|nudge|servo|draghold|overlay"
      )
    }
  }

  /**
   * Walks the active window depth-first and prints every node. The index printed here is the
   * DFS position, and every other command re-walks the tree the same way — so indices stay
   * meaningful across commands without holding stale node references.
   *
   * [packageFilter] narrows what is PRINTED, never how nodes are indexed.
   */
  private fun dump(packageFilter: String?) {
    val all = walk()
    if (all.isEmpty()) {
      log("dump: getRootInActiveWindow() returned nothing — no tree at all")
      return
    }

    val lines = mutableListOf("dump: ${all.size} nodes in active window" + (packageFilter?.let { ", printing only pkg=$it" } ?: ""))
    all.forEachIndexed { index, (node, depth) ->
      if (packageFilter != null && node.packageName?.toString() != packageFilter) return@forEachIndexed
      lines += describe(index, depth, node)
    }

    // Interesting = anything that claims selectable text, or advertises a selection action.
    // This is the answer to go/no-go criterion 2, pulled out so it cannot be missed in the noise.
    val interesting = all.withIndex().filter { (_, pair) ->
      val n = pair.first
      n.isTextSelectable || n.isEditable || n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }
    }
    lines += "--- ${interesting.size} node(s) with isTextSelectable | isEditable | ACTION_SET_SELECTION:"
    interesting.forEach { (index, pair) -> lines += describe(index, pair.second, pair.first) }

    emit(lines)
  }

  private fun node(index: Int) {
    val all = walk()
    val target = all.getOrNull(index)
    if (target == null) {
      log("node $index: out of range (tree has ${all.size})")
      return
    }
    val (n, depth) = target
    emit(
      listOf(
        describe(index, depth, n),
        "  actionList  = " + n.actionList.joinToString { "${actionName(it.id)}(${it.label ?: ""})" },
        "  granularity = ${granularityNames(n.movementGranularities)}",
        "  textLen     = ${n.text?.length ?: 0}, contentDescLen = ${n.contentDescription?.length ?: 0}",
        "  selection   = ${n.textSelectionStart}..${n.textSelectionEnd}",
        "  flags       = selectable=${n.isTextSelectable} editable=${n.isEditable} focusable=${n.isFocusable} " +
          "a11yFocused=${n.isAccessibilityFocused} visible=${n.isVisibleToUser} enabled=${n.isEnabled}",
      )
    )
  }

  /**
   * ACTION_NEXT/PREVIOUS_AT_MOVEMENT_GRANULARITY generally require the node to hold accessibility
   * focus first, so this is usually the step before [granularity].
   */
  private fun focus(index: Int) {
    perform(index, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null, "focus")
  }

  private fun select(index: Int, start: Int, end: Int) {
    val args = Bundle().apply {
      putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
      putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
    }
    perform(index, AccessibilityNodeInfo.ACTION_SET_SELECTION, args, "select $start..$end")
  }

  /** ACTION_SET_SELECTION with no arguments clears the selection (documented behaviour). */
  private fun clearSelection(index: Int) {
    perform(index, AccessibilityNodeInfo.ACTION_SET_SELECTION, null, "clear selection")
  }

  private fun granularity(index: Int, granularity: Int, extend: Boolean, forward: Boolean) {
    val args = Bundle().apply {
      putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
      putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, extend)
    }
    val action = if (forward) {
      AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
    } else {
      AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
    }
    perform(
      index,
      action,
      args,
      "${if (forward) "next" else "prev"} at ${granularityNames(granularity)} extend=$extend",
    )
  }

  private fun copy(index: Int) {
    perform(index, AccessibilityNodeInfo.ACTION_COPY, null, "copy")
  }

  private fun windows() {
    val lines = mutableListOf("windows: ${windows.size}")
    windows.forEach { w ->
      val bounds = Rect().also { w.getBoundsInScreen(it) }
      lines += "  id=${w.id} type=${w.type} active=${w.isActive} focused=${w.isFocused} " +
        "layer=${w.layer} pkg=${w.root?.packageName} bounds=$bounds"
    }
    emit(lines)
  }

  // ------------------------------------------------------- the gesture spike: gestures

  private fun tap(x: Float, y: Float) {
    dispatchSingleStroke(point(x, y), TAP_MS, "tap ($x,$y)")
  }

  /**
   * A zero-length path held down for [ms]. The framework documents a single moveTo() as "a touch
   * that doesn't move", which is exactly a long-press; the platform long-press timeout is 500 ms,
   * so [LONG_PRESS_MS] leaves margin.
   */
  private fun longPress(x: Float, y: Float, ms: Long) {
    dispatchSingleStroke(point(x, y), ms, "long ($x,$y) ${ms}ms")
  }

  /** Touch down, move, lift — for dragging a selection handle that already exists. */
  private fun drag(x1: Float, y1: Float, x2: Float, y2: Float, dragMs: Long) {
    dispatchSingleStroke(line(x1, y1, x2, y2), dragMs, "drag ($x1,$y1)->($x2,$y2) ${dragMs}ms")
  }

  /**
   * Long-press then drag WITHOUT lifting — the gesture a user makes to select a phrase, and the one
   * the reporter in obsidian-advanced-note-composer#266 says succeeds ~20% of the time by hand.
   *
   * A continued stroke cannot be a second stroke of the same GestureDescription: continueStroke()
   * produces a stroke for the NEXT gesture, and the pointer stays down between the two. So this is
   * three chained dispatches (hold, drag, settle), each fired from the previous callback, and the
   * settle phase exists so the lift does not read as a flick.
   */
  private fun pressDrag(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    holdMs: Long,
    dragMs: Long,
    settleMs: Long,
  ) {
    val what = "pressdrag ($x1,$y1)->($x2,$y2) hold=${holdMs} drag=${dragMs} settle=${settleMs}"
    val hold = GestureDescription.StrokeDescription(point(x1, y1), 0, holdMs, true)

    dispatchStroke(hold, "$what [1/3 hold]") { held ->
      if (!held) return@dispatchStroke
      // continueStroke's path must START where the previous stroke ended.
      val move = hold.continueStroke(line(x1, y1, x2, y2), 0, dragMs, true)
      dispatchStroke(move, "$what [2/3 drag]") { moved ->
        if (!moved) return@dispatchStroke
        val settle = move.continueStroke(point(x2, y2), 0, settleMs, false)
        dispatchStroke(settle, "$what [3/3 settle]") { log("$what -> all three strokes completed") }
      }
    }
  }

  /**
   * Runs a gesture at the centre of node [index]'s screen bounds, so coordinates come from the tree
   * rather than from guesswork about where a word is.
   */
  private fun atNodeCentre(index: Int, what: String, action: (Float, Float) -> Unit) {
    val all = walk()
    val target = all.getOrNull(index)
    if (target == null) {
      log("$what on node $index: out of range (tree has ${all.size})")
      return
    }
    val (n, depth) = target
    val bounds = Rect().also { n.getBoundsInScreen(it) }
    if (bounds.isEmpty) {
      log("$what on node $index: bounds are empty ($bounds) — nothing to aim at")
      return
    }
    log("$what on node $index at centre of $bounds: " + describe(index, depth, n))
    action(bounds.exactCenterX(), bounds.exactCenterY())
  }

  // ---------------------------------------------------- the gesture spike: observation

  /**
   * Every node claiming a selection range. The first spike measured -1..-1 everywhere with no selection; the
   * question now is whether a selection made by TOUCH shows up here — if it does, handle positions
   * follow from bounds + offsets and no screenshot analysis is needed.
   */
  private fun selections() {
    val all = walk()
    val hits = all.withIndex().filter { (_, pair) ->
      val n = pair.first
      n.textSelectionStart != -1 || n.textSelectionEnd != -1 || n.isTextSelectable
    }
    if (hits.isEmpty()) {
      log("sel: no node reports a selection range or isTextSelectable (tree has ${all.size})")
      return
    }
    val lines = mutableListOf("sel: ${hits.size} of ${all.size} node(s) claim a selection or selectability")
    hits.forEach { (index, pair) ->
      val (n, depth) = pair
      lines += describe(index, depth, n) + " selection=${n.textSelectionStart}..${n.textSelectionEnd}"
    }
    emit(lines)
  }

  /**
   * Per-character screen rectangles for [length] characters of node [index] starting at [start],
   * via refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY) — API 26, the mechanism
   * TalkBack uses to find text on screen.
   *
   * This is the rung of the handle-location ladder that would make screenshot analysis unnecessary:
   * TYPE_VIEW_TEXT_SELECTION_CHANGED already gives the selection's character offsets, and this
   * turns offsets into pixels. It reads character GEOMETRY, never the characters themselves.
   */
  private fun chars(index: Int, start: Int, length: Int) {
    val all = walk()
    val target = all.getOrNull(index)
    if (target == null) {
      log("chars on node $index: out of range (tree has ${all.size})")
      return
    }
    val (n, depth) = target
    val args = Bundle().apply {
      putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
      putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
    }
    val refreshed = n.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
    val rects = n.extras.getParcelableArray(
      AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
      RectF::class.java,
    )

    val lines = mutableListOf(
      "chars on node $index [$start, +$length) -> refreshWithExtraData returned $refreshed, " +
        "rects=${rects?.size ?: "null"}",
      "  node: " + describe(index, depth, n),
    )
    rects?.forEachIndexed { offset, rect ->
      lines += "  [${start + offset}] $rect"
    }
    // The two anchors a selection's handles hang from: bottom-left of the first character and
    // bottom-right of the last.
    val first = rects?.firstOrNull { it != null && it.width() > 0 }
    val last = rects?.lastOrNull { it != null && it.width() > 0 }
    if (first != null && last != null) {
      lines += "  START anchor = (${first.left}, ${first.bottom})   END anchor = (${last.right}, ${last.bottom})"
    }
    emit(lines)
  }

  /** The last [count] recorded accessibility events, oldest first. */
  private fun events(count: Int) {
    val snapshot = synchronized(events) { events.toList() }
    if (snapshot.isEmpty()) {
      log("events: nothing recorded")
      return
    }
    emit(listOf("events: last ${minOf(count, snapshot.size)} of ${snapshot.size} recorded") + snapshot.takeLast(count))
  }

  // ------------------------------------------------ the pad build: granularity probe

  /**
   * Both rungs of the selection observer at once, plus the handle pixels derived from them.
   *
   * the gesture spike's matrix is why this prints both: Chrome, both Obsidian modes, Keep and Gecko announce the
   * range as an EVENT while their nodes report -1; Google Docs is the exact mirror, firing no
   * selection event at all while its node reports a real range. Neither rung alone covers both, so
   * the pad has to read through a pair like this one — and measuring through it now means the
   * fallback is exercised before any UI is built on it.
   */
  private fun selState() {
    val lines = mutableListOf<String>()

    val snap = lastSelection
    lines += if (snap == null) {
      "selstate: EVENT rung — nothing announced since the service connected " +
        "(a selection made before that is invisible; re-select to announce it)"
    } else {
      "selstate: EVENT rung — ${snap.describe()} age=${SystemClock.uptimeMillis() - snap.atMs}ms"
    }

    if (snap != null) {
      lines += "  handles: " + listOf(EDGE_START, EDGE_END).joinToString("  ") { edge ->
        val handle = handlePixel(snap, edge)
        if (handle == null) "$edge=<not derivable>" else "$edge=(${handle.first}, ${handle.second})"
      }
      if (snap.srcLen <= 0) {
        lines += "  (srcLen=${snap.srcLen}: no interpolation possible — Gecko announces this, " +
          "so offsets cannot be turned into pixels on that surface)"
      }
    }

    val all = walk()
    val hits = all.withIndex().filter { (_, pair) ->
      pair.first.textSelectionStart != -1 || pair.first.textSelectionEnd != -1
    }
    if (hits.isEmpty()) {
      lines += "selstate: NODE rung — no node reports a selection range (tree has ${all.size})"
    } else {
      lines += "selstate: NODE rung — ${hits.size} of ${all.size} node(s) report a range"
      hits.forEach { (index, pair) ->
        val (n, depth) = pair
        lines += describe(index, depth, n) + " selection=${n.textSelectionStart}..${n.textSelectionEnd}"
      }
    }

    emit(lines)
  }

  /**
   * THE pad-build MEASUREMENT. Walks a selection handle across the text in [count] small drags of [dx]
   * pixels each — every drag starting where the previous one ended — and logs the ANNOUNCED offsets
   * after each step.
   *
   * The whole question is in the resulting sequence:
   *  - offsets rising one per step (15, 16, 17, …) => no snapping; the app tracks the pixel, and a
   *    word button would have to find the boundary itself, i.e. by reading text.
   *  - offsets standing still and then jumping at word ends (15, 15, 24, 24, 31) => the app snaps,
   *    and the word button is implementable without ever reading a character.
   *
   * Open-loop in pixels on purpose: re-deriving the handle each step (what [nudge] does) would let
   * the servo hide the very snapping being measured. The reading is what closes the loop here.
   *
   * A step that produces NO event is a real observation, not a gap — re-selecting the same range is
   * silent (the gesture spike), which is exactly what snapping between two boundaries looks like.
   */
  private fun probe(x: Float, y: Float, dx: Float, count: Int, settleMs: Long) {
    val lines = mutableListOf(
      "probe: start=($x,$y) dx=$dx count=$count settle=${settleMs}ms drag=${PROBE_DRAG_MS}ms",
      "  before: " + (lastSelection?.describe() ?: "<no selection announced yet>"),
    )

    fun step(i: Int, fromX: Float) {
      if (i >= count) {
        emit(lines + "probe: done after $count step(s)")
        return
      }
      val toX = fromX + dx
      val before = lastSelection
      val what = "probe step ${i + 1}/$count"
      dispatchStroke(
        stroke = GestureDescription.StrokeDescription(line(fromX, y, toX, y), 0, PROBE_DRAG_MS),
        what = what,
        verbose = false,
      ) { completed ->
        if (!completed) {
          emit(lines + "  step ${i + 1}: gesture CANCELLED at x=$fromX -> $toX; chain stops here")
          return@dispatchStroke
        }
        handler.postDelayed({
          val after = lastSelection
          lines += "  step ${i + 1}: x=$fromX -> $toX  " + delta(before, after)
          step(i + 1, toX)
        }, settleMs)
      }
    }

    step(0, x)
  }

  /**
   * One closed-loop step — the pad's kernel in miniature, and the thing every button will be built
   * on: derive the handle pixel from the last announced range, drag it by [dx], read the range
   * again.
   *
   * Unlike [probe] this re-derives the handle every time, so error corrects rather than accumulates
   * — which is the property that makes the gesture spike's 20/20 worth anything.
   */
  private fun nudge(edge: String, dx: Float, settleMs: Long) {
    if (edge != EDGE_START && edge != EDGE_END) {
      log("nudge: edge must be '$EDGE_START' or '$EDGE_END', got '$edge'")
      return
    }
    val snap = lastSelection
    if (snap == null) {
      log("nudge: no selection announced yet — long-press some text first, then `selstate`")
      return
    }
    val handle = handlePixel(snap, edge)
    if (handle == null) {
      log("nudge: cannot derive the $edge handle from ${snap.describe()} — needs bounds and srcLen>0")
      return
    }
    val (hx, hy) = handle
    val what = "nudge $edge by $dx from (${hx}, ${hy})"
    log("$what — derived from ${snap.describe()}")
    dispatchStroke(
      stroke = GestureDescription.StrokeDescription(line(hx, hy, hx + dx, hy), 0, DRAG_MS),
      what = what,
    ) { completed ->
      if (!completed) return@dispatchStroke
      handler.postDelayed({
        emit(listOf("$what -> " + delta(snap, lastSelection)))
      }, settleMs)
    }
  }

  /**
   * [nudge] repeated [count] times — the CLOSED-loop walk, and the instrument that actually answers
   * the granularity question.
   *
   * [probe] does the same walk open-loop and was tried first; it loses the handle after a handful
   * of steps, because a handle does not stay under the pixel the last drag lifted at (it follows
   * the selection, which may snap, saturate at a node edge, or extend into a node the event never
   * mentions). Once lost, the steps land on the page instead — measured as TYPE_VIEW_CLICKED events
   * and a dropped selection. Re-deriving the handle from the announced range each step is the fix,
   * and it is also exactly what the pad's buttons will do.
   *
   * Read the offset sequence, not the pixels: values landing INSIDE a word prove character
   * granularity; values that only ever sit on word boundaries, with several steps producing no
   * event in between, prove the app snaps.
   *
   * A step that announces nothing is RETRIED from the same handle at a longer reach — [dx], then
   * 2·[dx], up to [SERVO_MAX_TRIES] — and the multiplier that finally moved it is logged. Two
   * reasons. It turns "how far must the finger travel to advance the selection by one step" into a
   * measured number, which is the constant the pad's buttons need; and it stops the loop repeating
   * one identical failing drag forever, which is what the first run did 24 times before the cause
   * (the target app had been switched away) was even visible.
   */
  private fun servo(edge: String, dx: Float, count: Int, settleMs: Long) {
    if (edge != EDGE_START && edge != EDGE_END) {
      log("servo: edge must be '$EDGE_START' or '$EDGE_END', got '$edge'")
      return
    }
    val lines = mutableListOf("servo: edge=$edge dx=$dx count=$count settle=${settleMs}ms")

    fun attempt(i: Int, tries: Int) {
      if (i >= count) {
        emit(lines + "servo: done after $count step(s)")
        return
      }
      val snap = lastSelection
      if (snap == null) {
        emit(lines + "  step ${i + 1}: nothing announced — long-press some text first; stopping")
        return
      }
      val handle = handlePixel(snap, edge)
      if (handle == null) {
        emit(lines + "  step ${i + 1}: handle not derivable from ${snap.describe()}; stopping")
        return
      }
      val (hx, hy) = handle
      val reach = dx * (tries + 1)
      dispatchStroke(
        stroke = GestureDescription.StrokeDescription(line(hx, hy, hx + reach, hy), 0, PROBE_DRAG_MS),
        what = "servo step ${i + 1}/$count try ${tries + 1}",
        verbose = false,
      ) { completed ->
        if (!completed) {
          emit(lines + "  step ${i + 1}: gesture CANCELLED; chain stops here")
          return@dispatchStroke
        }
        handler.postDelayed({
          val after = lastSelection
          val moved = after != null && after.atMs != snap.atMs
          when {
            moved -> {
              lines += "  step ${i + 1}: handle=($hx, $hy) reach=${reach}px (x${tries + 1})  " +
                delta(snap, after)
              attempt(i + 1, 0)
            }
            tries + 1 < SERVO_MAX_TRIES -> attempt(i, tries + 1)
            else -> emit(
              lines + ("  step ${i + 1}: NOTHING announced from ($hx, $hy) at any reach up to " +
                "${dx * SERVO_MAX_TRIES}px — the handle is not there, or the target app is no " +
                "longer in front (check topResumedActivity); stopping")
            )
          }
        }, settleMs)
      }
    }

    attempt(0, 0)
  }

  /**
   * Finds a selection handle by probing, for the surfaces where arithmetic cannot: block-width or
   * multi-line source nodes, where interpolating an offset across the node's bounds puts the handle
   * ~130 px out in x and ~76 px out in y (measured 2026-09-02), and where
   * `refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)` returns nothing but the node's
   * own bounds.
   *
   * It scans OUTWARD FROM THE CENTRE, alternating right and left, because the centre is the one
   * thing that can be known without arithmetic: the floating toolbar window's horizontal centre
   * tracks the selection's to within about 6 px, and the two handles are symmetric about it. So the
   * unknown is only the selection's half-width, a scan of a few steps rather than of the line.
   *
   * A probe is a deliberately tiny drag. It has to be a drag rather than a tap because only a drag
   * moves a handle, and only a moved handle announces anything — there is no way to ASK whether a
   * given pixel holds one. The disturbance is the measurement, and it is self-correcting: the step
   * that fires tells us both that the handle was there and where it now is.
   *
   * Reports the probe count and the wall clock, because feasibility was never in doubt — a handle is
   * a ~48 px target and a scan will find it — and **cost** is the whole question.
   */
  private fun findHandle(y: Float, centreX: Float, step: Float, maxProbes: Int) {
    val startedAt = SystemClock.uptimeMillis()
    val before = lastSelection
    val lines = mutableListOf(
      "findhandle: y=$y centre=$centreX step=$step max=$maxProbes",
      "  before: " + (before?.describe() ?: "<nothing announced>"),
    )

    fun probe(n: Int) {
      if (n > maxProbes) {
        emit(lines + "findhandle: NOT FOUND in $maxProbes probes (${SystemClock.uptimeMillis() - startedAt}ms)")
        return
      }
      // 1 -> centre+step, 2 -> centre-step, 3 -> centre+2·step, ... so the nearer candidates go first.
      val ring = (n + 1) / 2
      val x = if (n % 2 == 1) centreX + ring * step else centreX - ring * step
      val snapshot = lastSelection
      dispatchStroke(
        stroke = GestureDescription.StrokeDescription(line(x, y, x + FIND_NUDGE, y), 0, PROBE_DRAG_MS),
        what = "findhandle probe $n at ($x, $y)",
        verbose = false,
      ) { completed ->
        if (!completed) {
          emit(lines + "  probe $n at x=$x: gesture CANCELLED; stopping")
          return@dispatchStroke
        }
        handler.postDelayed({
          val after = lastSelection
          val fired = after != null && after.atMs != snapshot?.atMs
          when {
            fired && grabbedAHandle(snapshot, after!!) -> emit(
              lines + listOf(
                "  probe $n at x=$x: HIT — " + delta(snapshot, after),
                "findhandle: found in $n probe(s), ${SystemClock.uptimeMillis() - startedAt}ms; " +
                  "the other handle mirrors it at x=${2 * centreX - x}",
              )
            )
            // An event is NOT proof of a grab. A probe that lands on the page rather than on a
            // handle collapses the selection, and that announces a change too — measured, and it
            // read as a confident hit at a pixel where no handle was. The signature of a real grab
            // is that one edge held while the other moved.
            fired -> emit(
              lines + listOf(
                "  probe $n at x=$x: event fired but the selection did NOT survive — " +
                  delta(snapshot, after),
                "findhandle: ABORTED at probe $n (${SystemClock.uptimeMillis() - startedAt}ms) — " +
                  "the probe destroyed the selection, so there is nothing left to scan for",
              )
            )
            else -> {
              lines += "  probe $n at x=$x: nothing"
              probe(n + 1)
            }
          }
        }, FIND_SETTLE_MS)
      }
    }

    probe(1)
  }

  /**
   * Did that probe move a handle, or wreck the selection?
   *
   * A grab keeps the anchor: one edge holds while the other moves, and the range stays non-empty.
   * A miss lands on the page and collapses the selection to a caret, which announces a change just
   * as loudly — so "an event fired" is not the test, and treating it as one produced a confident
   * hit at a pixel that held no handle.
   */
  private fun grabbedAHandle(before: SelectionSnapshot?, after: SelectionSnapshot): Boolean {
    if (before == null) return false
    if (after.low() == after.high()) return false
    if (after.bounds != before.bounds) return false
    return after.low() == before.low() || after.high() == before.high()
  }

  /**
   * Drag, then HOLD at the destination without lifting — the precondition for the PageDown / End
   * buttons, which need the target app's own edge auto-scroll. Plain [drag] lifts the moment it
   * arrives, so it can never trigger one.
   *
   * Two chained strokes, the [pressDrag] shape minus its hold-first phase: `continueStroke` yields
   * a stroke for the NEXT gesture and keeps the pointer down between them. Whether the chain works
   * here is itself worth knowing — the equivalent chain in [pressDrag] produced no selection at all
   * and was never diagnosed.
   */
  private fun dragHold(x1: Float, y1: Float, x2: Float, y2: Float, dragMs: Long, holdMs: Long) {
    val what = "draghold ($x1,$y1)->($x2,$y2) drag=${dragMs} hold=${holdMs}"
    val before = lastSelection
    val move = GestureDescription.StrokeDescription(line(x1, y1, x2, y2), 0, dragMs, true)

    dispatchStroke(move, "$what [1/2 drag]") { completed ->
      if (!completed) return@dispatchStroke
      val hold = move.continueStroke(point(x2, y2), 0, holdMs, false)
      dispatchStroke(hold, "$what [2/2 hold]") { held ->
        if (!held) return@dispatchStroke
        handler.postDelayed({
          emit(listOf("$what -> " + delta(before, lastSelection)))
        }, SETTLE_MS)
      }
    }
  }

  // ------------------------------------------------------- the pad build: the overlay

  /**
   * Adds a touchable box over whatever is on screen — the thing that has to become the pad.
   *
   * **`TYPE_ACCESSIBILITY_OVERLAY` via `WindowManager.addView`, NOT
   * `attachAccessibilityOverlayToDisplay`.** the gesture spike's design note named the latter, and it is the
   * wrong API for this. It takes a `SurfaceControl`, so the views have to reach it through a
   * `SurfaceControlViewHost` — and `setView` on a host with a null host token is refused,
   * `RuntimeException("Adding window failed")` out of `ViewRootImpl.setView`, measured on the
   * device both before and after attaching the surface, so it is not an ordering problem. That
   * path wants a host token an ordinary app has no public way to mint: `InputTransferToken`'s
   * constructor is package-private.
   *
   * The window type an accessibility service is actually entitled to is `TYPE_ACCESSIBILITY_OVERLAY`
   * (2032), which `WindowManager.addView` accepts **without `SYSTEM_ALERT_WINDOW`** — so the
   * zero-permission property survives, which was the whole reason the gesture spike reached for the other API in
   * the first place. It is also API 22 rather than 34, so it costs nothing in reach.
   *
   * The point of the box is not the box. It is that the pad's buttons must be PRESSABLE, so the
   * view logs raw MotionEvents and button clicks SEPARATELY — a view can receive touches and still
   * never fire a click, and which of the two happens is the whole answer.
   */
  private fun overlayOn(width: Int, height: Int, x: Int, y: Int) {
    overlayOff(quiet = true)

    val params = WindowManager.LayoutParams().apply {
      this.width = width
      this.height = height
      this.x = x
      this.y = y
      type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
      gravity = Gravity.TOP or Gravity.START
      format = PixelFormat.TRANSLUCENT
      // NOT_FOCUSABLE so the pad never steals key focus from the app being driven, and
      // NOT_TOUCH_MODAL so touches outside the box still reach that app.
      flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }

    // A Service has no theme, and an unthemed Button inflates badly or not at all.
    val view = buildPadView(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault), params)

    getSystemService(WindowManager::class.java).addView(view, params)
    overlayView = view
    overlayParams = params
    logOverlayPlacement("added ${width}x$height")
  }

  /** Moves an attached pad, the scripted equivalent of dragging its strip. */
  private fun overlayMove(x: Int, y: Int) {
    val view = overlayView
    val params = overlayParams
    if (view == null || params == null) {
      log("overlay: nothing attached to move")
      return
    }
    params.x = x
    params.y = y
    getSystemService(WindowManager::class.java).updateViewLayout(view, params)
    logOverlayPlacement("moved")
  }

  private fun overlayOff(quiet: Boolean) {
    val view = overlayView
    if (view == null) {
      if (!quiet) log("overlay: nothing attached")
      return
    }
    runCatching { getSystemService(WindowManager::class.java).removeView(view) }
      .onFailure { log("overlay: removeView failed: ${it::class.java.simpleName}: ${it.message}") }
    overlayView = null
    overlayParams = null
    if (!quiet) log("overlay: removed")
  }

  private fun overlayState() {
    if (overlayView == null) {
      log("overlay: not attached")
      return
    }
    logOverlayPlacement("state")
  }

  /**
   * Always report BOTH the requested LayoutParams position and the real screen bounds.
   *
   * They differ by the status bar's height — ask for y=1040 and the window lands at 1181 — because
   * LayoutParams coordinates are content-relative while dispatchGesture's are raw screen pixels.
   * Reading a footprint off the requested value once produced a tap that missed the pad by 11 px,
   * hit the page, followed a link, and read as "gestures pass through the overlay" when the truth
   * is the exact opposite. The accessibility window list is the authority; the request is not.
   */
  private fun logOverlayPlacement(what: String) {
    val params = overlayParams
    val requested = params?.let { Rect(it.x, it.y, it.x + it.width, it.y + it.height) }
    log(
      "overlay: $what — requested=${requested?.flattenToString() ?: "none"} " +
        "real=${realOverlayBounds()?.flattenToString() ?: "<not in the window list yet>"}"
    )
  }

  /** The pad's actual screen rectangle, straight from the accessibility window list. */
  private fun realOverlayBounds(): Rect? = windows
    .firstOrNull {
      it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
        it.root?.packageName == packageName
    }
    ?.let { w -> Rect().also { w.getBoundsInScreen(it) } }

  /**
   * A drag strip above two buttons. The buttons' listeners exist to answer O1 and return false so
   * they observe without consuming; the strip's returns TRUE, because a drag must not also read as
   * a press.
   *
   * The strip is what makes the pad movable, which the owner asked for and which is also the manual
   * half of the O3 mitigation: a pad sitting over a selection handle blocks the very gesture the
   * loop needs, so the user has to be able to push it out of the way.
   */
  private fun buildPadView(ctx: android.content.Context, params: WindowManager.LayoutParams): View {
    val root = LinearLayout(ctx)
    root.orientation = LinearLayout.VERTICAL
    root.setBackgroundColor(OVERLAY_BACKGROUND)

    root.addView(buildDragStrip(ctx, root, params))

    val row = LinearLayout(ctx)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER
    row.setOnTouchListener { _, event ->
      log(
        "overlay: ROOT ${MotionEvent.actionToString(event.actionMasked)} " +
          "at (${event.x}, ${event.y}) raw=(${event.rawX}, ${event.rawY})"
      )
      false
    }
    for (label in OVERLAY_BUTTONS) {
      val button = Button(ctx)
      button.text = label
      button.setOnClickListener {
        log("overlay: BUTTON CLICK '$label' — a press reached the overlay's view hierarchy")
      }
      button.setOnTouchListener { _, event ->
        log("overlay: button '$label' ${MotionEvent.actionToString(event.actionMasked)}")
        false
      }
      row.addView(button)
    }
    root.addView(row)
    return root
  }

  private fun buildDragStrip(
    ctx: android.content.Context,
    root: View,
    params: WindowManager.LayoutParams,
  ): View {
    val strip = TextView(ctx)
    strip.text = DRAG_STRIP_LABEL
    strip.gravity = Gravity.CENTER
    strip.setBackgroundColor(DRAG_STRIP_BACKGROUND)
    strip.setTextColor(DRAG_STRIP_FOREGROUND)
    strip.layoutParams = LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      DRAG_STRIP_HEIGHT,
    )

    // Where the finger went down, and where the window was at that moment. The delta between the
    // two is what moves the pad; tracking rawX/rawY keeps it in screen coordinates, the same space
    // the drag is happening in.
    var fingerDownX = 0f
    var fingerDownY = 0f
    var windowOriginX = 0
    var windowOriginY = 0

    strip.setOnTouchListener { _, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          fingerDownX = event.rawX
          fingerDownY = event.rawY
          windowOriginX = params.x
          windowOriginY = params.y
          true
        }
        MotionEvent.ACTION_MOVE -> {
          params.x = windowOriginX + (event.rawX - fingerDownX).toInt()
          params.y = windowOriginY + (event.rawY - fingerDownY).toInt()
          runCatching { getSystemService(WindowManager::class.java).updateViewLayout(root, params) }
            .onFailure { log("overlay: drag update failed: ${it::class.java.simpleName}: ${it.message}") }
          true
        }
        MotionEvent.ACTION_UP -> {
          logOverlayPlacement("dragged")
          true
        }
        else -> false
      }
    }
    return strip
  }

  /**
   * The handle's screen pixel for one edge of [snap], by the gesture spike's measured geometry: interpolate the
   * offset linearly across the source node's bounds, then drop below the baseline and sit outside
   * the selection's end.
   *
   * Its accuracy is entirely a function of how tightly the node's bounds hug its text, and the gesture spike
   * measured that varying enormously — about a character's error on Chrome's per-phrase inline
   * nodes, useless on Docs' single 12,997-character node covering the viewport. That is a known
   * limit of this rung, not a bug here.
   */
  private fun handlePixel(snap: SelectionSnapshot, edge: String): Pair<Float, Float>? {
    val bounds = snap.bounds ?: return null
    if (snap.srcLen <= 0 || bounds.isEmpty) return null
    val offset = if (edge == EDGE_START) snap.low() else snap.high()
    val anchorX = bounds.left + (offset.toFloat() / snap.srcLen) * bounds.width()
    val x = if (edge == EDGE_START) anchorX - HANDLE_INSET else anchorX + HANDLE_INSET
    return x to (bounds.bottom + HANDLE_DROP)
  }

  /**
   * What changed between two announced ranges. The no-event case is spelled out rather than left
   * blank, because it is a genuine measurement: the framework announces only CHANGES, so "nothing
   * arrived" means the range did not move — which is precisely what a handle snapped to a boundary
   * does while the finger keeps travelling.
   */
  private fun delta(before: SelectionSnapshot?, after: SelectionSnapshot?): String {
    if (after == null) return "no selection has EVER been announced"
    if (before != null && before.atMs == after.atMs) {
      return "NO EVENT (range unchanged, still ${before.low()}..${before.high()})"
    }
    val movement = if (before == null) {
      "first announcement"
    } else {
      "dLow=${signed(after.low() - before.low())} dHigh=${signed(after.high() - before.high())}"
    }
    return "${after.describe()}  [$movement]"
  }

  private fun signed(n: Int): String = if (n >= 0) "+$n" else "$n"

  // ---------------------------------------------------------------- plumbing

  private fun point(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

  private fun line(x1: Float, y1: Float, x2: Float, y2: Float): Path =
    Path().apply {
      moveTo(x1, y1)
      lineTo(x2, y2)
    }

  private fun dispatchSingleStroke(path: Path, durationMs: Long, what: String) {
    dispatchStroke(GestureDescription.StrokeDescription(path, 0, durationMs), what)
  }

  /**
   * The callback's onCompleted means "the strokes were played", NOT "the target app did anything".
   * the first spike's rule stands: only a screenshot is evidence. [onFinished] is used solely to sequence
   * chained strokes and post-gesture reads, which genuinely must not start before the gesture ends;
   * its argument says whether the gesture completed or was cancelled, so a broken chain reports
   * where it broke instead of stalling silently.
   *
   * [verbose] exists for the multi-step commands: [probe] fires dozens of strokes and collects one
   * line per STEP, so the per-stroke chatter would bury the measurement. The dispatch result is
   * still logged when it is anything other than the expected `true`.
   */
  private fun dispatchStroke(
    stroke: GestureDescription.StrokeDescription,
    what: String,
    verbose: Boolean = true,
    onFinished: (Boolean) -> Unit = {},
  ) {
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    val callback = object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) {
        if (verbose) log("$what -> callback onCompleted (means dispatched, NOT that anything happened)")
        finish(true)
      }

      override fun onCancelled(gestureDescription: GestureDescription?) {
        log("$what -> callback onCancelled")
        finish(false)
      }

      private fun finish(completed: Boolean) {
        runCatching { onFinished(completed) }
          .onFailure { log("$what -> ERROR in the follow-up step: ${it::class.java.simpleName}: ${it.message}") }
      }
    }
    val accepted = dispatchGesture(gesture, callback, null)
    if (verbose || !accepted) log("$what -> dispatchGesture returned $accepted")
  }


  private fun perform(index: Int, action: Int, args: Bundle?, what: String) {
    val all = walk()
    val target = all.getOrNull(index)
    if (target == null) {
      log("$what on node $index: out of range (tree has ${all.size})")
      return
    }
    val (n, depth) = target
    val advertised = n.actionList.any { it.id == action }
    val result = if (args == null) n.performAction(action) else n.performAction(action, args)

    // Re-walk so the reported selection is the state AFTER the action, not a stale copy.
    val after = walk().getOrNull(index)?.first

    emit(
      listOf(
        "$what on node $index -> performAction returned $result (action advertised by node: $advertised)",
        "  before: " + describe(index, depth, n),
        "  after : selection=${after?.textSelectionStart}..${after?.textSelectionEnd} " +
          "a11yFocused=${after?.isAccessibilityFocused}",
      )
    )
  }

  private fun walk(): List<Pair<AccessibilityNodeInfo, Int>> {
    val out = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()

    fun recurse(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || depth > MAX_DEPTH) return
      out += n to depth
      for (i in 0 until n.childCount) {
        recurse(n.getChild(i), depth + 1)
      }
    }

    recurse(rootInActiveWindow, 0)
    return out
  }

  private fun describe(index: Int, depth: Int, n: AccessibilityNodeInfo): String {
    val bounds = Rect().also { n.getBoundsInScreen(it) }
    val actions = n.actionList
      .map { actionName(it.id) }
      .filter { it in NOTEWORTHY_ACTIONS }
    return buildString {
      append("[").append(index).append("] ")
      append("  ".repeat(depth.coerceAtMost(12)))
      append(n.className?.toString()?.substringAfterLast('.') ?: "?")
      append(" pkg=").append(n.packageName)
      append(" sel=").append(n.isTextSelectable)
      append(" edit=").append(n.isEditable)
      append(" len=").append(n.text?.length ?: 0)
      append(" gran=").append(granularityNames(n.movementGranularities))
      append(" bounds=").append(bounds.flattenToString())
      if (actions.isNotEmpty()) append(" actions=").append(actions)
      // Spike-only. See the class kdoc: the real app must never read node text.
      n.text?.toString()?.take(TEXT_PREVIEW)?.replace('\n', '⏎')?.let { append(" text=\"").append(it).append("\"") }
    }
  }

  private fun actionName(id: Int): String = when (id) {
    AccessibilityNodeInfo.ACTION_SET_SELECTION -> "SET_SELECTION"
    AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY -> "NEXT_AT_GRANULARITY"
    AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> "PREV_AT_GRANULARITY"
    AccessibilityNodeInfo.ACTION_COPY -> "COPY"
    AccessibilityNodeInfo.ACTION_CUT -> "CUT"
    AccessibilityNodeInfo.ACTION_PASTE -> "PASTE"
    AccessibilityNodeInfo.ACTION_SET_TEXT -> "SET_TEXT"
    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> "A11Y_FOCUS"
    AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> "CLEAR_A11Y_FOCUS"
    AccessibilityNodeInfo.ACTION_FOCUS -> "FOCUS"
    AccessibilityNodeInfo.ACTION_CLICK -> "CLICK"
    AccessibilityNodeInfo.ACTION_LONG_CLICK -> "LONG_CLICK"
    else -> "0x%x".format(id)
  }

  private fun granularityNames(mask: Int): String {
    if (mask == 0) return "none"
    val names = buildList {
      if (mask and AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER != 0) add("CHAR")
      if (mask and AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD != 0) add("WORD")
      if (mask and AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE != 0) add("LINE")
      if (mask and AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH != 0) add("PARA")
      if (mask and AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE != 0) add("PAGE")
    }
    return names.joinToString("|")
  }

  /**
   * logcat truncates a single entry near 4 KB, which silently eats the tail of a dump. Emitting
   * one line per entry sidesteps it entirely.
   */
  private fun emit(lines: List<String>) {
    log("---8<--- begin (${lines.size} lines)")
    lines.forEach { line -> line.chunked(CHUNK).forEach { log(it) } }
    log("--->8--- end")
  }

  private fun log(message: String) {
    Log.i(TAG, message)
  }

  private companion object {
    const val TAG = "ASCSPIKE"
    const val ACTION_CMD = "dev.mnaoumov.asc.spike.CMD"
    const val MAX_DEPTH = 60
    const val CHUNK = 3000
    const val TEXT_PREVIEW = 80
    const val MAX_EVENTS = 300
    const val STACK_FRAMES = 20

    /** ViewConfiguration's tap timeout is 100 ms and its long-press timeout 500 ms; leave margin. */
    const val TAP_MS = 60L
    const val LONG_PRESS_MS = 700L
    const val DRAG_MS = 600L
    const val SETTLE_MS = 250L

    /** The pad build. A probe step is a few pixels, so it needs none of [DRAG_MS] — 30 steps of it would be
     * 18 seconds of dragging. Still long enough not to read as a flick. */
    const val PROBE_DRAG_MS = 150L

    /** The pad build. How long [dragHold] parks at the destination, waiting for an edge auto-scroll. */
    const val HOLD_MS = 2000L

    /**
     * The pad build. [findHandle]'s scan. The step is the handle's own touch radius — probing finer than
     * the target's size only buys duplicate hits — and the nudge is as small as a drag can be while
     * still moving anything.
     */
    const val FIND_STEP = 48f
    const val FIND_NUDGE = 8f
    const val FIND_MAX_PROBES = 16
    const val FIND_SETTLE_MS = 300L

    /**
     * The pad build. How far [servo] escalates a step that announces nothing, in multiples of its dx.
     *
     * 4 was not enough, and the reason turned out to be the finding itself: measured 2026-09-02,
     * Chrome moves the selection end word by word, so the finger has to travel most of the NEXT
     * word before anything happens. A 4-character word snapped at 48 px while a 10-character one
     * had not moved at that reach. The cap therefore has to exceed the widest word on screen, not
     * some multiple of a character.
     */
    const val SERVO_MAX_TRIES = 12

    /**
     * The pad build handle geometry, from the gesture spike's worked example: an end handle announced at to=15 of a
     * srcLen=16 node with bounds 378..721 x ..1341 was grabbed at (729, 1398) — 29.4 px outside the
     * interpolated anchor and 57 px below the text's bottom. The handle is a ~48 px-radius touch
     * target, so this is comfortably inside tolerance. PER-APP, though: Chrome draws teardrops
     * below the baseline while Keep draws circles at the selection's corners, so these are Chrome
     * numbers to be re-derived elsewhere, never a constant to hardcode into the app.
     */
    const val HANDLE_INSET = 30f
    const val HANDLE_DROP = 57f

    const val EDGE_START = "start"
    const val EDGE_END = "end"

    /**
     * The pad build Phase 1a. Deliberately small and low on the 1272x2772 screen: the question "does the
     * overlay swallow touches meant for the app underneath" needs an app underneath that is still
     * reachable, and the probe text sits around y=1100 so the box must stay clear of it.
     */
    const val OVERLAY_W = 660
    const val OVERLAY_H = 260
    const val OVERLAY_X = 300
    const val OVERLAY_Y = 2200
    const val OVERLAY_BACKGROUND = 0xCC1565C0.toInt()
    val OVERLAY_BUTTONS = listOf("WORD >", "CHAR >")

    /** The strip that makes the pad movable — owner's request, and the manual half of the O3 fix. */
    const val DRAG_STRIP_HEIGHT = 90
    const val DRAG_STRIP_LABEL = "≡  drag to move"
    const val DRAG_STRIP_BACKGROUND = 0xEE0D47A1.toInt()
    const val DRAG_STRIP_FOREGROUND = 0xFFFFFFFF.toInt()

    val NOTEWORTHY_ACTIONS = setOf(
      "SET_SELECTION",
      "NEXT_AT_GRANULARITY",
      "PREV_AT_GRANULARITY",
      "COPY",
      "CUT",
      "SET_TEXT",
      "A11Y_FOCUS",
    )
  }
}
