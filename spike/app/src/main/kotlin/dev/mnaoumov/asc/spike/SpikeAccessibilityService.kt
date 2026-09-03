package dev.mnaoumov.asc.spike

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
      "from=$from to=$to srcLen=$srcLen srcBounds=${bounds?.flattenToString() ?: "none"} pkg=$pkg"
  }

  @Volatile
  private var lastSelection: SelectionSnapshot? = null

  /**
   * Sequencing for the multi-step commands. A gesture's completion callback arrives on the main
   * thread, and the settle between steps must not block it — a blocked service thread stops
   * receiving the very events the probe is reading.
   */
  private val handler = Handler(Looper.getMainLooper())

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      val cmd = intent?.getStringExtra("cmd")
      if (cmd == null) {
        log("no --es cmd given")
        return
      }
      runCatching { dispatch(cmd.trim()) }
        .onFailure { log("ERROR while handling '$cmd': ${it::class.java.simpleName}: ${it.message}") }
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    registerReceiver(receiver, IntentFilter(ACTION_CMD), Context.RECEIVER_EXPORTED)
    log("=== service connected; ready for commands on $ACTION_CMD")
  }

  override fun onUnbind(intent: Intent?): Boolean {
    runCatching { unregisterReceiver(receiver) }
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
      "servo" -> servo(
        edge = parts[1],
        dx = parts[2].toFloat(),
        count = parts[3].toInt(),
        settleMs = parts.getOrNull(4)?.toLong() ?: SETTLE_MS,
      )
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
          "selstate|probe|nudge|servo|draghold"
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
   */
  private fun servo(edge: String, dx: Float, count: Int, settleMs: Long) {
    if (edge != EDGE_START && edge != EDGE_END) {
      log("servo: edge must be '$EDGE_START' or '$EDGE_END', got '$edge'")
      return
    }
    val lines = mutableListOf("servo: edge=$edge dx=$dx count=$count settle=${settleMs}ms")

    fun step(i: Int) {
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
      dispatchStroke(
        stroke = GestureDescription.StrokeDescription(line(hx, hy, hx + dx, hy), 0, PROBE_DRAG_MS),
        what = "servo step ${i + 1}/$count",
        verbose = false,
      ) { completed ->
        if (!completed) {
          emit(lines + "  step ${i + 1}: gesture CANCELLED; chain stops here")
          return@dispatchStroke
        }
        handler.postDelayed({
          lines += "  step ${i + 1}: handle=($hx, $hy) -> ${hx + dx}  " + delta(snap, lastSelection)
          step(i + 1)
        }, settleMs)
      }
    }

    step(0)
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
    val offset = if (edge == EDGE_START) snap.from else snap.to
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
      return "NO EVENT (range unchanged, still ${before.from}..${before.to})"
    }
    val movement = if (before == null) {
      "first announcement"
    } else {
      "dFrom=${signed(after.from - before.from)} dTo=${signed(after.to - before.to)}"
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
