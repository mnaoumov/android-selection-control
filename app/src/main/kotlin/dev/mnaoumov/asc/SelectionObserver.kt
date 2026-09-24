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

  @Volatile
  var latest: Snapshot? = null
    private set

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
   * on page content. So the last announcement is the only state there is, and it must be kept.
   */
  fun onEvent(event: AccessibilityEvent): Boolean {
    if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return false

    val source = event.source ?: return false
    val bounds = Rect().also { source.getBoundsInScreen(it) }
    latest = Snapshot(
      from = event.fromIndex,
      to = event.toIndex,
      bounds = bounds,
      // A length, not the characters. See the class note.
      sourceLength = source.text?.length ?: -1,
      packageName = event.packageName?.toString(),
      atMs = SystemClock.uptimeMillis(),
      source = source,
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
    val bounds = Rect().also { node.getBoundsInScreen(it) }
    latest = Snapshot(
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
    latest = null
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
