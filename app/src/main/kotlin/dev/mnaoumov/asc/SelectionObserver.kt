package dev.mnaoumov.asc

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * What is selected, in offsets and rectangles — never in characters.
 *
 * This class is where the project's trust property lives, so the rule is stated once and held
 * everywhere below: **nothing here ever touches a node's text content.** Lengths and bounds are
 * read; `CharSequence.length` is a count, not a read of what the characters are. The diagnostic
 * spike that measured all of this did log text previews, deliberately and disposably; none of that
 * is carried over.
 *
 * Two rungs, because neither covers every app (the gesture spike's matrix):
 *  - the **event** rung — `TYPE_VIEW_TEXT_SELECTION_CHANGED` carries offsets plus the source node's
 *    screen bounds. Chrome, both Obsidian modes, Keep and Gecko announce this way.
 *  - the **node** rung — `textSelectionStart/End` on a node. Google Docs is the exact mirror of
 *    Chrome: it fires no selection event at all, yet its node reports a real range.
 */
class SelectionObserver {

  /**
   * A selection as the framework describes it.
   *
   * [from] and [to] are **anchor and focus, not min and max** — drag the start handle below the
   * anchor and they arrive reversed. Use [low] and [high].
   *
   * The offsets are **local to [bounds]'s node**, not to the document, and the source node follows
   * the moving end of the selection: grow past a node's edge and the next snapshot describes the
   * node holding the new end. So [high] is the end *within the current source node*, which is
   * exactly what is needed to locate the handle being moved — and [low] is meaningless as a
   * selection start once more than one node is covered.
   */
  data class Snapshot(
    val from: Int,
    val to: Int,
    val bounds: Rect?,
    val sourceLength: Int,
    val packageName: String?,
    val atMs: Long,
    /**
     * The node the range belongs to, kept so its bounds can be re-read rather than remembered.
     *
     * Bounds captured when the event fired go stale the moment anything scrolls — and in a browser
     * that is constantly: the toolbar collapses on the first scroll and shifts the whole page, and
     * the loop's own drags can scroll too. A handle derived from remembered bounds then aims at
     * where the text used to be, which reads to the user as "it loses the handle far too easily".
     */
    val source: AccessibilityNodeInfo? = null,
    /**
     * Whether the source is Blink page content: Chrome, or a WebView, which tag every node with a
     * `chromeRole` extra. Its class name cannot say so, because Chrome reports page text as
     * `android.widget.TextView`. Read from the extras' KEYS, never from any value in them.
     */
    val isBlink: Boolean = false,
  ) {
    fun low(): Int = minOf(from, to)

    fun high(): Int = maxOf(from, to)

    fun isEmpty(): Boolean = low() == high()

    /**
     * Whether the source node's box is a single line, which is when interpolation across it works.
     *
     * **A shape test, not a height in pixels.** This used to ask whether the box was under 110 px
     * tall, a number calibrated on body text at 56-70 px a line. A Wikipedia article title is one
     * line and 145 px tall, so it failed — the loop then fell back to a 12 px character width where
     * the truth was 64, aimed a fifth of the distance it needed, announced nothing and reported
     * `HandleLost` on every press. A font size is not a wrap.
     *
     * The shape that does hold across font sizes: on ONE line, a character's width and the line's
     * height are the same order — measured 0.44 for that title (64 px over 145) and 0.33 for a plain
     * `TextView` (28 over 85). Wrapping divides the apparent character width by the number of lines
     * AND multiplies the height by it, so the ratio falls quadratically: 0.05 for a four-line
     * paragraph, 0.10 for the three-line phrase on record. Nothing sits near the threshold.
     */
    fun sourceIsOneLine(): Boolean = boxIsOneLine(bounds, sourceLength)

    /**
     * Whether the box is **known** to span wrapped lines — which is not the same as "not one line".
     *
     * The distinction is the whole of it: [sourceIsOneLine] answers false both for a box measured to
     * wrap and for a box whose shape cannot be measured at all, because the ratio it tests needs a
     * length and Gecko announces `srcLen = -1`. Anything that refuses to act on a wrap must ask THIS
     * question, or it also refuses on every surface that simply declines to say how long its text is.
     *
     * What it is for: the handle's ROW. Every rung derives `y` from [bounds]`.bottom`, which on a
     * union of line boxes is the LAST line — correct only if the moving edge happens to sit there.
     */
    fun isKnownMultiLine(): Boolean {
      val box = bounds ?: return false
      return box.height() > 0 && sourceLength > 0 && !sourceIsOneLine()
    }
  }

  /**
   * The last announcement as it arrived, before [frame] reads it. Each announcement is a new object,
   * so an unchanged reference means nothing has been announced since.
   */
  @Volatile
  var announced: Snapshot? = null
    private set

  /**
   * Whether the last announcement was Chrome's frame-root one on a platform that cannot carry its
   * range, so a selection exists that nothing here can read.
   *
   * Chrome's extended selection is server-switched on per install, and it does not look at the
   * platform: on a 36.0 device it still announces from the root with `-1..-1`. The range lives in
   * `getSelection()`, which is 36.1, and the root's extras carry only the two offset types. Measured
   * on the OnePlus 15 (36.0, Chrome 154.0.8037.92) on 2026-10-05. The only way out on such a device
   * is the user's: `chrome://flags#enable-accessibility-extended-selection` set to Disabled, which
   * puts the old announcement back. Any readable announcement clears this.
   */
  @Volatile
  var chromeHidesSelection = false
    private set

  /**
   * Debug-only: treat the platform as below 36.1, so the rig (API 37) reproduces a 36.0 device.
   * The service sets it from a marker file that only `run-as` on a debuggable build can write.
   */
  var forceNoExtendedSelection = false

  /**
   * Rewrites an announcement into the frame its reader needs, or null to leave it as it arrived.
   * The driver sets it, because only the driver knows which edge is moving and where the anchor is.
   */
  var frame: ((Snapshot) -> Snapshot)? = null

  val latest: Snapshot?
    get() = announced?.let { snapshot -> frame?.invoke(snapshot) ?: snapshot }

  /**
   * The latest snapshot with its geometry re-read from the live node, because remembered bounds go
   * stale on every scroll.
   *
   * Returns the snapshot unchanged when the node cannot be refreshed — a node that has gone away is
   * a reason to stop, not a reason to use numbers known to be wrong, and the caller decides which.
   */
  fun latestWithFreshBounds(): Snapshot? {
    val snapshot = latest ?: return null
    val source = snapshot.source ?: return snapshot
    if (!runCatching { source.refresh() }.getOrDefault(false)) return snapshot
    val bounds = Rect().also { source.getBoundsInScreen(it) }
    if (bounds.isEmpty) return snapshot
    return snapshot.copy(bounds = bounds, sourceLength = source.text?.length ?: snapshot.sourceLength)
  }

  /**
   * Feeds the event rung. Returns true if this event changed what we know.
   *
   * A selection event is a **change notification, not a state query** — re-selecting the same range
   * is silent, and nothing lets us ask what is selected, because `textSelectionStart/End` stays -1
   * on page content. So the last announcement is the only state there is, and it must be kept. An
   * announcement from a frame root with no range carries it on the root instead: [extendedRange].
   */
  fun onEvent(event: AccessibilityEvent): Boolean {
    if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return false

    val announcer = event.source ?: return false
    val fromRoot = event.fromIndex < 0 && event.toIndex < 0
    val extended = if (fromRoot) extendedRange(announcer) else null
    chromeHidesSelection = fromRoot && extended == null && !hasExtendedSelection() && isBlinkRoot(announcer)
    if (chromeHidesSelection) {
      Diag.log("selection announced from Chrome's frame root, and below 36.1 nothing carries its range")
    }
    val source = extended?.node ?: announcer
    val bounds = Rect().also { source.getBoundsInScreen(it) }
    announced = Snapshot(
      from = extended?.from ?: event.fromIndex,
      to = extended?.to ?: event.toIndex,
      bounds = bounds,
      // A length, not the characters. See the class note.
      sourceLength = source.text?.length ?: -1,
      packageName = event.packageName?.toString(),
      atMs = SystemClock.uptimeMillis(),
      source = source,
      isBlink = runCatching { source.extras?.containsKey(CHROME_ROLE_KEY) }.getOrNull() == true,
    )
    return true
  }

  /**
   * The node rung, for the surfaces that announce nothing — Google Docs reports a real range on its
   * node while firing no selection event whatsoever.
   *
   * Only consulted when the event rung has gone stale, because walking the tree is expensive next to
   * reading a field that an event already delivered.
   */
  fun refreshFromNodes(root: AccessibilityNodeInfo?, packageName: String?) {
    val node = root?.let { firstNodeWithRange(it, 0) } ?: return
    chromeHidesSelection = false
    val bounds = Rect().also { node.getBoundsInScreen(it) }
    announced = Snapshot(
      from = node.textSelectionStart,
      to = node.textSelectionEnd,
      bounds = bounds,
      sourceLength = node.text?.length ?: -1,
      packageName = packageName,
      atMs = SystemClock.uptimeMillis(),
      source = node,
    )
  }

  /** True when nothing has been announced for [staleMs], i.e. time to try the node rung. */
  fun isStale(staleMs: Long): Boolean {
    val snapshot = latest ?: return true
    return SystemClock.uptimeMillis() - snapshot.atMs > staleMs
  }

  fun forget() {
    announced = null
    chromeHidesSelection = false
  }

  /** `getSelection` is a 36.1 API, a minor level `SDK_INT` cannot express. */
  private fun hasExtendedSelection(): Boolean =
    !forceNoExtendedSelection &&
      android.os.Build.VERSION.SDK_INT >= 36 &&
      android.os.Build.VERSION.SDK_INT_FULL >= android.os.Build.VERSION_CODES_FULL.BAKLAVA_1

  /**
   * Whether [node] is Chrome's (or a WebView's) frame root: the whole `WebView`, which is what
   * announces an extended selection. Its class says so, and so does a `chromeRole` extra if present.
   */
  private fun isBlinkRoot(node: AccessibilityNodeInfo): Boolean =
    node.className?.toString() == WEB_VIEW_CLASS ||
      runCatching { node.extras?.containsKey(CHROME_ROLE_KEY) }.getOrNull() == true

  /** A range in the frame of [node], the node holding the focus: see [extendedRange]. */
  private class NodeRange(val node: AccessibilityNodeInfo, val from: Int, val to: Int)

  /**
   * The range an EXTENDED selection describes, rewritten into the frame the event rung used to
   * deliver, or null where there is none.
   *
   * Chrome's `AccessibilityExtendedSelection` feature (Finch-enabled on the handset's Chrome 154,
   * on by default in Chromium's main) stops announcing a page selection from the node holding the
   * focus. It announces from the frame's ROOT with `-1..-1`, and puts the range on that root's
   * `AccessibilityNodeInfo.getSelection()` instead: an anchor and a focus, each a node and an
   * offset. No page node reports `textSelectionStart/End` either, so this is the only place the
   * range exists.
   *
   * The old event came from the focus node, as `anchor..focus` when the anchor was in it too and as
   * `0..focus` when it was not, and everything downstream (the moving-edge frame, the seam reckoning,
   * the fresh-selection test) is written against that shape. So it is reproduced here exactly
   * rather than passed on as a new one.
   */
  private fun extendedRange(announcer: AccessibilityNodeInfo): NodeRange? {
    if (!hasExtendedSelection()) return null
    val selection = extendedSelection(announcer)
      ?: runCatching { announcer.refresh() }.getOrNull()?.takeIf { it }?.let { extendedSelection(announcer) }
      ?: return null
    val extras = runCatching { announcer.extras }.getOrNull()
    val focus = resolve(
      selection.end.node ?: return null,
      selection.end.offset,
      extras?.getInt(END_OFFSET_TYPE_KEY, OFFSET_TYPE_TEXT) ?: OFFSET_TYPE_TEXT,
    ) ?: return null
    val anchor = selection.start.node?.let { node ->
      resolve(node, selection.start.offset, extras?.getInt(START_OFFSET_TYPE_KEY, OFFSET_TYPE_TEXT) ?: OFFSET_TYPE_TEXT)
    }
    val from = if (anchor != null && anchor.node == focus.node) anchor.from else 0
    return NodeRange(focus.node, from, focus.from)
  }

  @android.annotation.SuppressLint("NewApi") // guarded by hasExtendedSelection, a 36.1 check lint cannot read
  private fun extendedSelection(node: AccessibilityNodeInfo): AccessibilityNodeInfo.Selection? =
    runCatching { node.selection }.getOrNull()

  /**
   * A position as a node and a text offset in it. A `CHILD` position is an index between a
   * container's children, so it is taken to the text leaf on that side of the gap: the start of the
   * child after it, or the end of the last child. Only lengths and child counts are read.
   */
  private fun resolve(node: AccessibilityNodeInfo, offset: Int, offsetType: Int): NodeRange? {
    if (offsetType == OFFSET_TYPE_TEXT) return NodeRange(node, offset, offset)
    if (offsetType != OFFSET_TYPE_CHILD) return null
    val count = node.childCount
    if (count == 0) return null
    var leaf = (if (offset < count) node.getChild(offset) else node.getChild(count - 1)) ?: return null
    val atEnd = offset >= count
    var depth = 0
    while (leaf.childCount > 0 && depth++ < MAX_DEPTH) {
      leaf = leaf.getChild(if (atEnd) leaf.childCount - 1 else 0) ?: return null
    }
    val at = if (atEnd) leaf.text?.length ?: return null else 0
    return NodeRange(leaf, at, at)
  }

  private fun firstNodeWithRange(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
    if (depth > MAX_DEPTH) return null
    if (node.textSelectionStart != -1 && node.textSelectionEnd != -1 &&
      node.textSelectionStart != node.textSelectionEnd
    ) {
      return node
    }
    for (i in 0 until node.childCount) {
      val hit = node.getChild(i)?.let { firstNodeWithRange(it, depth + 1) }
      if (hit != null) return hit
    }
    return null
  }

  companion object {
    private const val MAX_DEPTH = 60

    /** The extra Chrome and WebView put on every node. See [Snapshot.isBlink]. */
    private const val CHROME_ROLE_KEY = "AccessibilityNodeInfo.chromeRole"

    /**
     * Where androidx (and so Chrome) records whether each end of an extended selection counts
     * characters or children, until the platform carries it. `SelectionPositionCompat`'s values.
     */
    private const val START_OFFSET_TYPE_KEY =
      "androidx.view.accessibility.AccessibilityNodeInfoCompat.SELECTION_START_OFFSET_TYPE"
    private const val END_OFFSET_TYPE_KEY =
      "androidx.view.accessibility.AccessibilityNodeInfoCompat.SELECTION_END_OFFSET_TYPE"
    private const val OFFSET_TYPE_TEXT = 0
    private const val OFFSET_TYPE_CHILD = 1

    private const val WEB_VIEW_CLASS = "android.webkit.WebView"

    /**
     * Below this ratio of character width to box height, the box spans wrapped lines and
     * interpolating an offset across it is meaningless. See [Snapshot.sourceIsOneLine] for the
     * measurements; the gap between one line (0.33-0.44) and wrapped (0.05-0.10) is wide enough that
     * the exact threshold does not matter.
     */
    private const val MIN_ONE_LINE_RATIO = 0.2f

    /**
     * The one-line test itself, over a box and the length of the text it holds.
     *
     * It lives here rather than inside [Snapshot] because the same question has to be asked of a
     * node that is **not** the snapshot's source: [HandleLocator.lineNodeGeometry] descends into the
     * source's children looking for one that hugs a single line, and a second copy of this ratio is
     * a second place for the threshold to drift.
     */
    fun boxIsOneLine(box: Rect?, length: Int): Boolean {
      if (box == null || box.height() <= 0 || length <= 0) return false
      val characterWidth = box.width().toFloat() / length
      return characterWidth / box.height() >= MIN_ONE_LINE_RATIO
    }
  }
}
