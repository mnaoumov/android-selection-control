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
    /**
     * The announcement as it arrived, when the driver's frame read it in another node: a caret
     * announced from a node with no text, re-homed onto the text it is drawn on. Null otherwise.
     */
    val rehomedFrom: Snapshot? = null,
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
   * Debug-only: announce every Blink leaf's selection in its no-text parent's frame, the way the
   * handset's Chrome 154 announced a seam beside a bare `<sup>`, which the rig's Chrome 143 and
   * Chromium 157 never do. It runs the announcement through [inTextLeaf] exactly as the phone's would.
   * The service sets it from a marker file, as for [forceNoExtendedSelection].
   */
  var forceContainerFrame = false

  /** The simulated anchor in its container's frame: see [inParentFrame]. */
  private var simulatedAnchor: Pair<AccessibilityNodeInfo, Int>? = null

  /**
   * Rewrites an announcement into the frame its reader needs, or null to leave it as it arrived.
   * The driver sets it, because only the driver knows which edge is moving and where the anchor is.
   */
  var frame: ((Snapshot) -> Snapshot)? = null

  val latest: Snapshot?
    get() = announced?.let(::withLiveSource)?.let { snapshot -> frame?.invoke(snapshot) ?: snapshot }

  /**
   * A live node standing in for the last announcement's source after that source stopped answering
   * `refresh()`: see `SelectionDriver.relocatedStaleSource`. Keyed on the stale node's identity, so
   * the next announcement, which carries a node of its own, drops it by itself.
   */
  @Volatile
  private var liveSource: Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? = null

  /**
   * Reads [stale] as [live] in every snapshot from now until the next announcement. [announced] is
   * left as it is, because an unchanged reference is what says nothing has been announced since.
   */
  fun substituteSource(stale: AccessibilityNodeInfo, live: AccessibilityNodeInfo) {
    liveSource = stale to live
  }

  private fun withLiveSource(snapshot: Snapshot): Snapshot {
    val (stale, live) = liveSource ?: return snapshot
    return if (snapshot.source === stale) snapshot.copy(source = live) else snapshot
  }

  /** The last [RECENT_KEPT] announcements as they arrived, oldest first: see [announcedSince]. */
  private val recent = ArrayDeque<Snapshot>()

  /**
   * Every announcement since [atMs], in its reader's frame, oldest first. A selection that flaps
   * between two frames of one caret leaves only one of them in [latest] at any moment, and which one
   * a settle ends on is chance; this lets a reader ask whether the other was announced too.
   */
  fun announcedSince(atMs: Long): List<Snapshot> =
    synchronized(recent) { recent.filter { it.atMs > atMs } }.map(::withLiveSource)
      .map { snapshot -> frame?.invoke(snapshot) ?: snapshot }

  private fun remember(snapshot: Snapshot) {
    synchronized(recent) {
      recent.addLast(snapshot)
      while (recent.size > RECENT_KEPT) recent.removeFirst()
    }
  }

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
    // Either rung can name a node with no text of its own, and both are read through [inTextLeaf].
    val named = if (fromRoot) extended else NodeRange(announcer, event.fromIndex, event.toIndex)
    val container = named?.let { if (forceContainerFrame) inParentFrame(it.node, it.from, it.to) ?: it else it }
    val range = container?.let { inTextLeaf(it.node, it.from, it.to) } ?: extended
    val source = range?.node ?: announcer
    val bounds = Rect().also { source.getBoundsInScreen(it) }
    liveSource = null
    announced = Snapshot(
      from = range?.from ?: event.fromIndex,
      to = range?.to ?: event.toIndex,
      bounds = bounds,
      // A length, not the characters. See the class note.
      sourceLength = source.text?.length ?: -1,
      packageName = event.packageName?.toString(),
      atMs = SystemClock.uptimeMillis(),
      source = source,
      isBlink = isBlink(source),
    ).also(::remember)
    return true
  }

  private fun isBlink(node: AccessibilityNodeInfo): Boolean =
    runCatching { node.extras?.containsKey(CHROME_ROLE_KEY) }.getOrNull() == true

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
    liveSource = null
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

  /**
   * A range announced from a Blink node with no text of its own, rewritten onto the text leaf that
   * holds its focus, or null where the announcement is not of that shape.
   *
   * On the handset's Chrome 154 a selection edge on the seam beside a bare `<sup>[4]</sup>` was
   * announced from the `<p>`: an `android.view.View` with `srcLen=0` and the paragraph's box, its
   * offsets counting the `<p>`'s text leaves end to end. A long-press on `environment` came as
   * `41..55`, which is 31 + 10 to 31 + 21 + 1 + 2, i.e. `environment.[4` (2026-10-14, OnePlus 15).
   * Nothing here can aim by a node with no text, so every press after it ended `HandleLost`. The
   * rig's Chrome 143 and Chromium 157 announce the same steps from the leaves.
   *
   * The rewrite is the old event's shape again: the focus's leaf as the source, `anchor..focus` when
   * the anchor is in it too, and `0..focus` when it is not. A focus on a seam belongs to both leaves.
   * An END (the focus after the anchor) is put in the leaf before it, because a `0..0` in the leaf
   * after reads as no selection. A START is put in the leaf after it, at that leaf's offset 0. Only
   * lengths and child counts are read.
   *
   * An extended selection ([extendedRange]) whose focus is a text offset in such a container is read
   * the same way. That shape has not been seen; it is the same frame from the other rung.
   */
  private fun inTextLeaf(node: AccessibilityNodeInfo, from: Int, to: Int): NodeRange? {
    if (from < 0 || to < 0 || !isBlink(node)) return null
    if ((runCatching { node.text?.length }.getOrNull() ?: 0) > 0) return null
    if (runCatching { node.childCount }.getOrDefault(0) == 0) return null
    val leaves = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
    val total = collectTextLeaves(node, 0, leaves)
    if (total <= 0 || maxOf(from, to) > total) return null
    val starts = leaves.runningFold(0) { start, (_, length) -> start + length }
    val inside = leaves.indices.firstOrNull { starts[it] < to && to < starts[it + 1] }
    val endsHere = leaves.indices.lastOrNull { starts[it + 1] == to }
    val startsHere = leaves.indices.firstOrNull { starts[it] == to }
    val index = inside
      ?: (if (to >= from) endsHere ?: startsHere else startsHere ?: endsHere)
      ?: return null
    val (leaf, length) = leaves[index]
    val start = starts[index]
    val anchor = if (from in start..start + length) from - start else 0
    Diag.log(
      "selection announced from a node with no text of its own ($from..$to over ${leaves.size} text leaves) — " +
        "re-homed onto leaf ${index + 1} ($length chars at ${Rect().also { leaf.getBoundsInScreen(it) }}) as $anchor..${to - start}",
    )
    return NodeRange(leaf, anchor, to - start)
  }

  /**
   * The text leaves under [node] in tree order, each with its length, and their total: what a
   * container's frame counts. A node whose descendants carry no text stands for its own.
   *
   * The total is -1 when the walk meets an image or a control ([HandleLocator.NOT_PAGE_TEXT_CLASSES]),
   * whose text is a file name or a label and which nothing measured says the frame counts. Chrome's
   * image link is exactly that: a `View` with no text over an `Image` with a file name, announced as
   * `0..0`, which the driver re-homes by its own rule. So such a container is left as it arrived.
   */
  private fun collectTextLeaves(
    node: AccessibilityNodeInfo,
    depth: Int,
    out: MutableList<Pair<AccessibilityNodeInfo, Int>>,
  ): Int {
    if (depth > MAX_DEPTH || out.size >= MAX_LEAVES) return -1
    if (depth > 0 && runCatching { node.className?.toString() }.getOrNull() in HandleLocator.NOT_PAGE_TEXT_CLASSES) return -1
    val count = runCatching { node.childCount }.getOrDefault(0)
    var total = 0
    for (i in 0 until count) {
      val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
      val length = collectTextLeaves(child, depth + 1, out)
      if (length < 0) return -1
      total += length
    }
    if (total > 0) return total
    val own = runCatching { node.text?.length }.getOrNull() ?: 0
    if (own > 0) out += node to own
    return own
  }

  /**
   * [forceContainerFrame]'s rewrite: a Blink leaf's `from..to` counted in its no-text parent's frame,
   * or null where the leaf has no such parent. A one-line `<p>`'s text node is not rewritten, because
   * the `<p>` lists no children and so holds no leaves.
   *
   * The leaf's `0..focus` does not say where the anchor is when it is in another leaf, so the anchor
   * last simulated in the same parent is kept for it. That is a debug approximation: an anchor on a
   * leaf's own first caret after a selection elsewhere in the paragraph is read as the old anchor.
   */
  private fun inParentFrame(node: AccessibilityNodeInfo, from: Int, to: Int): NodeRange? {
    if (from < 0 || to < 0 || !isBlink(node)) return null
    val length = runCatching { node.text?.length }.getOrNull() ?: 0
    if (length == 0) return null
    // The nearest ancestor with no text: `[4]`'s own parent is the `<sup>`, which carries `[4]` too.
    var parent = runCatching { node.parent }.getOrNull() ?: return null
    var climbed = 0
    while ((runCatching { parent.text?.length }.getOrNull() ?: 0) > 0 && climbed++ < MAX_CONTAINER_CLIMB) {
      parent = runCatching { parent.parent }.getOrNull() ?: return null
    }
    if ((runCatching { parent.text?.length }.getOrNull() ?: 0) > 0) {
      Diag.log("debug: no ancestor without text within $MAX_CONTAINER_CLIMB levels — announcing as it arrived")
      return null
    }
    val leaves = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
    if (collectTextLeaves(parent, 0, leaves) <= 0) return null
    // The announcing node can hold its text in a child of its own, which is then the leaf counted.
    val own = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
    val first = own.takeIf { collectTextLeaves(node, 0, it) > 0 }?.first()?.first ?: return null
    /*
     * A leaf can also be announced from a node its container does not list: the rig's Chrome announces
     * `[4]` from a `TextView` while the `<p>` lists a `View` with the same box and text. So the same
     * length and box inside this container stands for it, which is good enough for a debug rewrite.
     */
    val box = Rect().also { first.getBoundsInScreen(it) }
    val index = leaves.indexOfFirst { it.first == first }.takeIf { it >= 0 }
      ?: leaves.indexOfFirst { (leaf, leafLength) ->
        leafLength == own.sumOf { it.second } && Rect().also { leaf.getBoundsInScreen(it) } == box
      }.takeIf { it >= 0 }
      ?: return null.also { Diag.log("debug: no leaf of the container stands for the announcing node ($length chars at $box)") }
    val prefix = leaves.take(index).sumOf { it.second }
    val remembered = simulatedAnchor?.takeIf { it.first == parent }?.second
    val anchor = if (from == 0 && remembered != null && remembered !in prefix..prefix + length) remembered else prefix + from
    simulatedAnchor = parent to anchor
    Diag.log("debug: announcing $from..$to in its parent's frame as $anchor..${prefix + to}")
    return NodeRange(parent, anchor, prefix + to)
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
    /** Announcements [announcedSince] can look back over; a held drag's settle sees about a dozen. */
    private const val RECENT_KEPT = 32

    private const val MAX_DEPTH = 60

    /** A cap on the leaves [collectTextLeaves] gathers, so a container the size of a page cannot stall an event. */
    private const val MAX_LEAVES = 2000

    /** How far [inParentFrame] climbs from a leaf to an ancestor with no text. */
    private const val MAX_CONTAINER_CLIMB = 4

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
