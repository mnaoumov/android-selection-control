package dev.mnaoumov.asc.spike

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The first spike feasibility spike.
 *
 * Answers one question: does Chrome expose page text as accessibility nodes that honour
 * ACTION_SET_SELECTION and the movement-granularity actions? The framework clearly intends this
 * (AccessibilityNodeInfo.isTextSelectable exists precisely for selectable-but-not-editable text),
 * but intent is not implementation.
 *
 * Driven entirely over adb so the target app stays foregrounded:
 *
 *   adb shell am broadcast -a dev.mnaoumov.asc.spike.CMD --es cmd "dump com.android.chrome"
 *   adb logcat -s ASCSPIKE:I -d
 *
 * NOTE ON READING TEXT: this build logs a short preview of each node's text, because without it
 * there is no way to tell which node a selection actually landed on. The real app must NOT do
 * this — the first spike's "never READ the buffer" property is the whole trust story. This is a throwaway
 * diagnostic running on the owner's own device against a page he chose.
 */
class SpikeAccessibilityService : AccessibilityService() {

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

  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

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
      else -> log("unknown command '${parts[0]}' — try: dump|node|focus|select|clear|gran|copy|windows")
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

  // ---------------------------------------------------------------- plumbing

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
