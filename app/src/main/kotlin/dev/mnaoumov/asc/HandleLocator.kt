package dev.mnaoumov.asc

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Which end of the selection the pad is currently moving. */
enum class Edge { START, END }

/**
 * Which of the caret's two neighbouring characters a step is about to cross.
 *
 * A caret at offset *k* has a character on each side and they are **different widths**, so "how wide
 * is the character here?" has no answer until the direction of travel is known. Asked the wrong way
 * round it measures the character the step is walking away from: a leftward step from offset 24 of
 * `alpha bravo charlie delta echo` crosses the `t` at index 23 and would be sized by the `a` at 24 —
 * which is most of a character of error in the one place a step cannot afford it.
 *
 * The caret's own x is the same number either way — the left edge of the next character is the right
 * edge of the previous one — so this changes which width comes back, never where the handle is.
 */
enum class Crossing { RIGHTWARD, LEFTWARD }

/**
 * One character of the source node as the platform itself measures it.
 *
 * The point of this over interpolation is [lineBottom]: it is the moving edge's **own line**, not the
 * bottom of a box that may cover four of them. A caret x can be estimated; a row cannot.
 *
 * [characterWidth] is the character on the side named by the [Crossing] that was asked for, which is
 * the one a step of one character has to travel across.
 */
data class CharacterGeometry(val caretX: Float, val lineBottom: Float, val characterWidth: Float)

/**
 * Where on screen the handle being moved is.
 *
 * Five rungs, in the order the pad build measured them to be reliable. Each is used only where it
 * is known to hold, because the failure mode is not a wrong number but a **destroyed selection**:
 * a drag that misses a handle lands on the page and collapses the range.
 *
 * 1. **Interpolate** across the source node's bounds — accurate to about a character where the node
 *    hugs its text, which is Chrome's per-phrase inline nodes. Only valid when the box is one line:
 *    a phrase that wraps reports the union of its line boxes, and interpolating across that put the
 *    handle ~130 px out in x and ~76 px in y. The column comes from rung 2's rectangle whenever it
 *    answers, and from the node's average character only where it does not.
 * 2. **Ask the platform for the character's own rectangle** — the only rung that answers with a ROW
 *    rather than assuming one. It is also the only rung that can be caught lying, which is what
 *    makes it safe to try; Chrome page content is measured to lie. See [characterGeometry].
 * 3. **Descend the tree for a child that hugs one line** — the same row, from the node tree instead
 *    of from the platform's refused answer, for the surfaces that expose a text node finer than the
 *    paragraph. Guarded by a partition check, so a child list that is not a map of the parent's
 *    offsets refuses instead of aiming into the wrong node. See [lineNodeGeometry].
 * 4. **Mirror about the floating toolbar's centre** — the toolbar tracks the selection's centre to
 *    ~6 px, and the handles are symmetric about it, so the moving one is `2·centre − anchor`. Needs
 *    a known anchor, and holds only while both handles are on the same row. Its row is the scan's,
 *    so it is gated on [scanRowIsKnown] too.
 * 5. **Acquire by scanning** outward from that centre — one probe found a word's handle in 454 ms,
 *    but a miss destroys the selection, so this is the last resort, never the first guess. It is
 *    driven from `SelectionDriver`, and gated on [scanRowIsKnown].
 */
class HandleLocator(private val density: () -> Float) {

  /**
   * How far below the text's bottom the moving handle's centre sits, in raw screen pixels. Read
   * through [density] on every call, so a display-density change is followed rather than cached.
   */
  val handleDrop: Float get() = HANDLE_DROP_DP * density()

  /** How far outside the caret the moving handle's centre sits, in raw screen pixels. */
  val handleInset: Float get() = HANDLE_INSET_DP * density()

  /** The last anchor-handle x we were confident about, for the mirror rung. */
  private var knownAnchorX: Float? = null

  /**
   * One remembered answer from [characterGeometry], so a step that needs both the caret and the
   * character's width pays one IPC rather than two.
   *
   * Keyed on the announcement's timestamp, which is unique per event, so it cannot outlive the
   * geometry it describes: the next announcement is a different key and the memo simply misses.
   */
  private var memoKey: String? = null
  private var memoValue: CharacterGeometry? = null

  /**
   * The same one-remembered-answer trick for [lineNodeGeometry], which `locate` and the driver's
   * step sizing both ask for on the same snapshot. Its walk is several IPCs rather than one, so the
   * memo matters more here than it does above.
   */
  private var lineMemoKey: String? = null
  private var lineMemoValue: CharacterGeometry? = null

  fun forgetAnchor() {
    knownAnchorX = null
  }

  fun rememberAnchor(x: Float) {
    knownAnchorX = x
  }

  /**
   * The moving edge's character as the platform measures it, or null where it will not say.
   *
   * **Why this is worth an IPC.** A wrapped node's bounds are the union of its line boxes, so every
   * other rung here puts the handle on the LAST line whatever line the edge is actually on — which
   * is the whole of the wrapped-node defect, and why a probe along that row destroys the selection
   * rather than finding anything. A character rectangle carries the row.
   *
   * **It reads rectangles, never characters.** `EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY` is answered
   * with `RectF`s and asked for with indices, so the project's trust property is untouched — the
   * same standing as `text?.length`, which is a count rather than a look.
   *
   * **And it lies, detectably, which is what makes trying it safe.** On Chrome page content it
   * returns `true` with a correctly-sized array in which every rectangle is just the node's own
   * bounds (AGENTS.md records the control: the editable omnibox returns real per-character rects).
   * A rectangle the size of the whole node is therefore discarded and the caller falls through to
   * the rungs that exist today — so the worst case is one wasted round trip on a path that fails
   * outright without it.
   *
   * Asked for exactly ONE character on purpose. The array is as long as the requested span, and
   * Docs announces a single 12,997-character node.
   *
   * [crossing] names which side of the caret the answer's width should describe — see [Crossing].
   * It defaults to the character after the caret, which is what a caller that only wants the caret's
   * x and row gets either way.
   *
   * [at] reads the caret at another offset of the same node instead of the moving edge's, for a step
   * that has to know which ROW its destination is on before it travels there.
   */
  fun characterGeometry(
    snapshot: SelectionObserver.Snapshot,
    edge: Edge,
    crossing: Crossing = Crossing.RIGHTWARD,
    at: Int? = null,
  ): CharacterGeometry? {
    val source = snapshot.source ?: return null
    val bounds = snapshot.bounds ?: return null
    val length = snapshot.sourceLength
    if (length <= 0) return null

    val offset = (at ?: if (edge == Edge.START) snapshot.low() else snapshot.high()).coerceIn(0, length)
    val key = "${snapshot.atMs}|$offset|$crossing"
    if (key == memoKey) return memoValue
    memoKey = null
    memoValue = null

    /*
     * An offset's caret sits at the LEFT edge of the character it indexes — offset 3 is "before the
     * fourth character". At `offset == length` there is no such character, so the caret is the RIGHT
     * edge of the last one instead. Getting this wrong is a whole character of error at exactly the
     * end of a node, which is where a growing selection spends its time.
     *
     * A LEFTWARD crossing wants the character BEFORE the caret, which is the same rectangle read
     * from its other side: index `offset - 1`, caret at its right edge. At offset 0 there is no such
     * character, so that case keeps the rightward reading rather than clamping onto index 0 and
     * reporting its right edge as the caret — which would be a whole character out.
     */
    val previous = offset >= length || (crossing == Crossing.LEFTWARD && offset > 0)
    val index = (if (previous) offset - 1 else offset).coerceIn(0, length - 1)
    val caretIsRightEdge = previous

    /*
     * The caret has a character on each side, and either one's rectangle places it: the left edge of
     * the one after, the right edge of the one before. So a refusal of the one asked for is not yet a
     * refusal of the caret, and the other side is asked before giving up.
     *
     * That is not symmetry for its own sake. A whitespace character that a line WRAPS at has no box
     * on any line, and Chrome answers for it with the node's own bounds for as long as it is asked,
     * while the characters either side of it answer honestly. Measured 2026-09-24 on one 259-character
     * seven-line paragraph, loaded and asked again: offsets 12, 53 and 243, each followed by a space
     * inside a line, were answered, and offset 62, followed by the space line 2 wraps at, was refused
     * on every ask, and that is exactly where a word grow parks the end edge. Its width then
     * describes the neighbour rather than the character crossed, which is the right proxy: a wrap's
     * space has no width of its own to cross.
     */
    val other = if (caretIsRightEdge) offset.takeIf { it < length } else (offset - 1).takeIf { it >= 0 }
    var rect = characterRect(source, bounds, index)
    var caretFromRight = caretIsRightEdge
    if (rect == null && other != null) {
      rect = characterRect(source, bounds, other)
      caretFromRight = !caretIsRightEdge
      if (rect != null) Diag.log("  locate: index $index has no box of its own — the caret was read from $other")
    }
    if (rect == null) {
      Diag.log("  locate: per-character rects are the node's own bounds here — the platform is not answering")
      return null
    }

    memoKey = key
    memoValue = CharacterGeometry(
      caretX = if (caretFromRight) rect.right else rect.left,
      lineBottom = rect.bottom,
      characterWidth = rect.width(),
    )
    return memoValue
  }

  /**
   * Whether the character at [index] has a box of its own. The whitespace a line wraps at has none
   * (see [characterGeometry]), so on a node already answering for its neighbours a refusal here says
   * the line wraps at a SPACE, and the character after it starts a word.
   */
  fun hasOwnBox(snapshot: SelectionObserver.Snapshot, index: Int): Boolean {
    val source = snapshot.source ?: return false
    val bounds = snapshot.bounds ?: return false
    if (index !in 0 until snapshot.sourceLength) return false
    return characterRect(source, bounds, index) != null
  }

  /** One character's rectangle as the platform measures it, or null where it declines or lies. */
  private fun characterRect(source: AccessibilityNodeInfo, bounds: Rect, index: Int): RectF? {
    val args = Bundle().apply {
      putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, index)
      putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
    }
    val refreshed = runCatching {
      source.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
    }.getOrDefault(false)
    if (!refreshed) return null

    val rects = runCatching {
      source.extras?.getParcelableArray(
        AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
        RectF::class.java,
      )
    }.getOrNull()
    // Entries are null for characters the app has not laid out — off-screen, or scrolled away.
    val rect: RectF? = rects?.firstOrNull()
    if (rect == null || rect.isEmpty) return null

    // The lie. A single character is never the width AND the height of the box that holds it; a
    // wrapped node's box is several lines tall and a one-line node's is many characters wide, so
    // either comparison alone would also reject an honest rect on a one-character node.
    if (rect.width() >= bounds.width() - LIE_TOLERANCE && rect.height() >= bounds.height() - LIE_TOLERANCE) return null
    return rect
  }

  /**
   * The widths of the source node's characters `[from, to)`, as the platform measures them — or null
   * where it will not, or where the run is longer than [MAX_SPAN] or leaves one line.
   *
   * For a step of SEVERAL characters, which one glyph's width times a count cannot size. Measured
   * 2026-09-24 walking the END edge back from 19 to 13 across `harlie`: one width of 19 px times six
   * aimed at 107 px where the six real glyphs total much less, and the walk landed on 12 three
   * times running. The same rectangles [characterGeometry] asks for, just more of them, so the same
   * standing: indices in, rectangles out, no text read. Bounded because the answer is as long as the
   * request and some surfaces announce a node of thirteen thousand characters.
   */
  fun characterWidths(snapshot: SelectionObserver.Snapshot, from: Int, to: Int): FloatArray? {
    val source = snapshot.source ?: return null
    val bounds = snapshot.bounds ?: return null
    if (from < 0 || to > snapshot.sourceLength || to - from !in 1..MAX_SPAN) return null

    val args = Bundle().apply {
      putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, from)
      putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, to - from)
    }
    val refreshed = runCatching {
      source.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
    }.getOrDefault(false)
    if (!refreshed) return null
    val rects = runCatching {
      source.extras?.getParcelableArray(
        AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
        RectF::class.java,
      )
    }.getOrNull() ?: return null
    if (rects.size != to - from) return null

    val lineBottom = rects.first()?.bottom ?: return null
    val widths = FloatArray(rects.size)
    for ((i, rect) in rects.withIndex()) {
      // A null entry is a character not laid out; a lie is the node's own box, as in
      // [characterGeometry]; a different bottom is a wrap, across which widths do not add up to a
      // horizontal distance.
      if (rect == null || rect.width() <= 0f) return null
      if (rect.width() >= bounds.width() - LIE_TOLERANCE && rect.height() >= bounds.height() - LIE_TOLERANCE) return null
      if (kotlin.math.abs(rect.bottom - lineBottom) > LIE_TOLERANCE) return null
      widths[i] = rect.width()
    }
    return widths
  }

  /**
   * The moving edge's own line, taken from a **finer node in the tree** — or null where the tree has
   * none to give.
   *
   * **Why the tree is the place left to look.** [characterGeometry] is the direct question and
   * Chrome refuses it: asked for ONE character at a known index on page content it still answers
   * with the node's own box (measured 2026-09-23, recorded in `AGENTS.md`). The tree is the only
   * other source of a ROW — and it has to be asked through the live node, not through a dump:
   * `uiautomator dump` showed the wrapped paragraphs of `chrome://version` as leaves on the same
   * guest where a selection inside one announced a source node of 7 characters that the dump never
   * listed. So "the dump says leaf" is not an answer to this question; only a walk is.
   *
   * **How a child is trusted.** Two conditions, both mechanical, both refusing rather than guessing:
   *
   * - the children's lengths must sum to the parent's. That is what makes a prefix sum a map from
   *   the parent's offsets onto theirs, and Chrome does expose children that are not a partition of
   *   the text — a copy button beside a value — over which a prefix sum lands the caret in the
   *   wrong node entirely;
   * - the chosen child's own box must hug ONE line, by [SelectionObserver.boxIsOneLine], the same
   *   ratio the source is tested with. A child that is itself wrapped carries its parent's defect
   *   and is no better to interpolate across.
   *
   * **Lengths and rectangles only.** `text?.length` is a count, never a look — the same standing the
   * parent's own length has. See [SelectionObserver].
   */
  fun lineNodeGeometry(snapshot: SelectionObserver.Snapshot, edge: Edge): CharacterGeometry? {
    val source = snapshot.source ?: return null
    val parentLength = snapshot.sourceLength
    if (parentLength <= 0) return null

    val offset = (if (edge == Edge.START) snapshot.low() else snapshot.high())
      .coerceIn(0, parentLength)
    val key = "${snapshot.atMs}|$edge|$offset"
    if (key == lineMemoKey) return lineMemoValue
    lineMemoKey = null
    lineMemoValue = null

    val count = runCatching { source.childCount }.getOrDefault(0)
    if (count <= 0) {
      Diag.log("  locate: the wrapped source is a leaf ($parentLength chars) — the tree has nothing finer")
      return null
    }

    val boxes = ArrayList<Rect>(count)
    val lengths = ArrayList<Int>(count)
    var covered = 0
    for (i in 0 until count) {
      val child = runCatching { source.getChild(i) }.getOrNull() ?: return null
      // A count of the glyphs, not a read of them.
      val length = child.text?.length ?: 0
      boxes += Rect().also { child.getBoundsInScreen(it) }
      lengths += length
      covered += length
    }
    if (covered != parentLength) {
      Diag.log(
        "  locate: the source's $count child(ren) hold $covered of its $parentLength characters — " +
          "not a partition, so a prefix sum would aim into the wrong node"
      )
      return null
    }

    /*
     * The caret at `offset` belongs to the child holding the character at that index. At
     * `offset == parentLength` there is no such character, so it belongs to the END of the last
     * non-empty child instead — the same right-edge case [characterGeometry] handles one level down,
     * and the same whole character of error at a node's end if it is got wrong.
     */
    var start = 0
    var chosen = -1
    var chosenStart = 0
    for (i in 0 until count) {
      val length = lengths[i]
      if (length <= 0) continue
      chosen = i
      chosenStart = start
      if (offset < start + length) break
      start += length
    }
    if (chosen == -1) return null

    val box = boxes[chosen]
    val length = lengths[chosen]
    if (!SelectionObserver.boxIsOneLine(box, length)) {
      Diag.log(
        "  locate: the child carrying offset $offset ($length chars, ${box.width()}x${box.height()}) " +
          "wraps too — the tree is finer than the paragraph but not finer than a line"
      )
      return null
    }

    val within = (offset - chosenStart).coerceIn(0, length)
    val geometry = CharacterGeometry(
      caretX = box.left + (within.toFloat() / length) * box.width(),
      lineBottom = box.bottom.toFloat(),
      characterWidth = box.width().toFloat() / length,
    )
    Diag.log(
      "  locate: child $chosen of $count carries offset $offset (its $within of $length) — " +
        "row=${geometry.lineBottom} caret=${geometry.caretX} charPx=${geometry.characterWidth}"
    )
    lineMemoKey = key
    lineMemoValue = geometry
    return geometry
  }

  /**
   * Ask for the moving character's rectangle and throw the answer away, because **the first ask is
   * what makes Chrome measure**.
   *
   * This is the whole of the wrapped-node fix, and it reverses a conclusion this project held from
   * the first spike onwards. `EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY` on Chrome page content is not
   * a query that lies — it is a *request to load*. The first call returns `true` with every
   * rectangle set to the node's own bounds and, as a side effect, makes Chrome compute that node's
   * inline text boxes; a later call returns real per-character rectangles, each carrying its own
   * line's bottom. Measured on the guest 2026-09-23 against a three-line, 81-character Chrome
   * paragraph: press one reported the node's box, press two — **80 ms later**, nothing else changed —
   * answered `caret=320.0 lineBottom=863.0 charPx=8.0`, the caret's own row on the third line.
   *
   * So the ask is moved off the press and onto the announcement that precedes it, which buys the
   * gap for free: a human takes hundreds of milliseconds to reach a button, and a repeat run's own
   * steps are ~400 ms apart. [characterGeometry] deliberately does not memoise a refusal, so the
   * press that follows re-asks rather than being handed this call's null.
   *
   * **Every non-empty selection, not only a wrapped one.** It was first gated on a box measured to
   * wrap, the case that fails outright without it. But a one-line node is refused on its first ask
   * too, and `SelectionDriver.pixelsPerCharacter` then sizes the step by the node's AVERAGE, which
   * this repo measured as the worse aim — so the first press on every newly selected one-line node
   * was the inexact one. Where a surface answers honestly on the first ask (a `TextView`) nothing is
   * wasted for a rightward press: [characterGeometry] memoises the success on this announcement,
   * so the press is served from it and the IPC has only moved earlier.
   *
   * **But never a caret.** An editable field announces a collapsed selection on every keystroke,
   * and the pad never steps a caret, so priming one would be an IPC per keystroke for nothing.
   */
  fun primeCharacterRects(snapshot: SelectionObserver.Snapshot, edge: Edge) {
    if (snapshot.isEmpty()) return
    characterGeometry(snapshot, edge)
  }

  /**
   * Whether a scan may probe along [SelectionObserver.Snapshot.bounds]`.bottom`.
   *
   * The scan hunts sideways from the toolbar's centre at that one row, so it is only ever as good as
   * the row — and on a box measured to span wrapped lines the row is the LAST line's, which the
   * moving edge is on only by luck. A miss there is not a wasted probe: it lands on the page,
   * collapses the selection, and on a link navigates.
   *
   * Narrow on purpose. This refuses only where the wrap is **measured**, so a surface that declines
   * to announce a length (Gecko) keeps exactly the behaviour it has today rather than losing a rung
   * to a shape nobody has established. See [SelectionObserver.Snapshot.isKnownMultiLine].
   */
  fun scanRowIsKnown(snapshot: SelectionObserver.Snapshot): Boolean = !snapshot.isKnownMultiLine()

  /**
   * The moving handle's pixel, or null when nothing trustworthy is available and the caller should
   * acquire instead.
   *
   * Deliberately returns null rather than a guess. A guess costs the user their selection.
   */
  fun locate(snapshot: SelectionObserver.Snapshot, edge: Edge, toolbarCentreX: Float?): PointF? {
    val bounds = snapshot.bounds ?: return null

    if (snapshot.sourceIsOneLine() && snapshot.sourceLength > 0) {
      /*
       * The caret comes from the character's own rectangle wherever the platform gives one, and from
       * the node's AVERAGE character only where it will not. Interpolating by the average is off by
       * however far the prefix's real glyphs diverge from it, while the reach that follows is sized
       * by a measured glyph, so the two disagree. Measured 2026-09-24 on a served one-line
       * `Chrome is made by Google`: the grabs sat exactly 16 px apart per offset across glyphs of
       * 8 to 30 px, and a one-glyph reach from there landed two characters on, from 10 across the
       * `m` and from 6 across the space. The row stays `bounds.bottom`: on one line that is already
       * the caret's own, and it is the row every one-line grab has been measured at.
       *
       * The primer has usually asked already, so on Chrome this is the memo rather than an IPC.
       */
      val offset = if (edge == Edge.START) snapshot.low() else snapshot.high()
      val average = bounds.left + (offset.toFloat() / snapshot.sourceLength) * bounds.width()
      val measured = characterGeometry(snapshot, edge)?.caretX
      if (measured != null) Diag.log("  locate: one-line caret measured=$measured average=$average")
      val anchorX = measured ?: average
      val x = if (edge == Edge.START) anchorX - handleInset else anchorX + handleInset
      return PointF(x, bounds.bottom + handleDrop)
    }

    /*
     * Reached only once interpolation is out, so this is the case where every remaining rung is
     * guessing at a row, and an answer that carries one is worth more than the column above.
     */
    val character = characterGeometry(snapshot, edge)
      ?: lineNodeGeometry(snapshot, edge)
    if (character != null) {
      val x =
        if (edge == Edge.START) character.caretX - handleInset else character.caretX + handleInset
      Diag.log(
        "  locate: a measured rect gave the $edge handle its own row — " +
          "caret=${character.caretX} lineBottom=${character.lineBottom} charPx=${character.characterWidth}"
      )
      return PointF(x, character.lineBottom + handleDrop)
    }

    /*
     * The mirror answers with a column and takes its row from `bounds.bottom`, which is the scan's
     * row and wrong for the same reason: on a box measured to wrap it is the LAST line, and a
     * cold wrapped node reaches here with the moving edge on its first. Measured on the rig
     * 2026-09-24: an END edge at offset 1 of an 81-character three-line paragraph, whose first line
     * ends at 767, was grabbed at y 891 under the third, and the drag landed on the page. Refuse
     * exactly where the scan refuses, so the press ends with the selection intact.
     */
    val centre = toolbarCentreX
    val anchor = knownAnchorX
    if (centre != null && anchor != null && scanRowIsKnown(snapshot)) {
      return PointF(2 * centre - anchor, bounds.bottom + handleDrop)
    }

    return null
  }

  /**
   * The floating selection toolbar's horizontal centre — the one piece of selection geometry that
   * needs no node bounds at all. Identified as a window of the selection's own package that is not
   * the full-screen one.
   */
  fun toolbarCentreX(
    windows: List<AccessibilityWindowInfo>,
    packageName: String?,
    screenWidth: Int,
  ): Float? = toolbarBounds(windows, packageName, screenWidth)?.exactCenterX()

  /**
   * The floating toolbar's rectangle, for anything that needs more than its centre — masking it, in
   * particular. Same identification, so the two can never disagree about which window it is.
   *
   * **A window counts only when something in it can be pressed.** A `TextView` puts each selection
   * handle in a `PopupWindow` of its own, an 88x80 window centred on the handle on the rig, and that is
   * smaller than the toolbar. Picking the smallest non-full-screen window therefore took the handle
   * for the toolbar, and every step on a `TextView` ended `HandleCovered` with nothing touched. The
   * handle is a bare `View`, clickable nowhere; the toolbar is a row of buttons. Chrome draws its
   * handles inside the page, so there the toolbar was already the only candidate.
   */
  fun toolbarBounds(
    windows: List<AccessibilityWindowInfo>,
    packageName: String?,
    screenWidth: Int,
  ): Rect? {
    if (packageName == null) return null
    return windows
      .asSequence()
      .mapNotNull { window ->
        val root = window.root ?: return@mapNotNull null
        if (root.packageName?.toString() != packageName) return@mapNotNull null
        val bounds = Rect().also { window.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.width() >= FULL_SCREEN_FRACTION * screenWidth) return@mapNotNull null
        if (!hasClickableNode(root)) return@mapNotNull null
        bounds
      }
      .minByOrNull { it.width().toLong() * it.height() }
  }

  /** Whether [root] or anything under it is clickable, looking at no more than [TOOLBAR_NODE_BUDGET] nodes. */
  private fun hasClickableNode(root: AccessibilityNodeInfo): Boolean {
    val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
    var visited = 0
    while (pending.isNotEmpty() && visited < TOOLBAR_NODE_BUDGET) {
      val node = pending.removeFirst()
      visited++
      if (node.isClickable) return true
      for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
    }
    return false
  }

  /**
   * [aim], moved off the floating toolbar when the toolbar covers it, or null when no part of the
   * handle the toolbar leaves bare is within reach.
   *
   * Near the top of a page Chrome puts its toolbar BELOW the selection, over the handle, and a grab
   * there presses a toolbar button instead: measured on the rig as **Select all**, which the pad then
   * read as a successful move, and the overflow menu. The mask cannot stop that, because it must not
   * take touches. Touch goes to whichever window owns the pixel of the down, so the fix is to put
   * the down on a pixel the toolbar does not own.
   *
   * The toolbar does not always cover the whole handle. Measured 2026-09-24 after a long-press on the
   * first line of a served page: the line bottom was 415, the handle centre ~443 and the toolbar's
   * touchable top 431, so the handle's top 16 px were bare, and a drag started at y 425 grabbed the
   * end handle and pressed nothing. So the aim moves to just outside the toolbar's nearer horizontal
   * edge, when that is still within [HANDLE_REACH_DP] of the handle's centre and still below the
   * text line, where a down would be a touch on the text instead.
   */
  fun clearOfToolbar(aim: PointF, toolbar: Rect?): PointF? {
    if (toolbar == null || toolbar.isEmpty) return aim
    val margin = TOOLBAR_MARGIN_DP * density()
    val covered = aim.x >= toolbar.left - margin && aim.x <= toolbar.right + margin &&
      aim.y >= toolbar.top - margin && aim.y <= toolbar.bottom + margin
    if (!covered) return aim

    val reach = HANDLE_REACH_DP * density()
    val lineBottom = aim.y - handleDrop
    val above = toolbar.top - margin
    val below = toolbar.bottom + margin
    return when {
      aim.y - above <= reach && above >= lineBottom + TEXT_CLEARANCE_DP * density() -> PointF(aim.x, above)
      below - aim.y <= reach -> PointF(aim.x, below)
      else -> null
    }
  }

  /**
   * Did that drag move a handle, or wreck the selection?
   *
   * A grab keeps the anchor: one edge holds while the other moves, and the range stays non-empty. A
   * miss lands on the page and collapses the selection to a caret — which announces a change just as
   * loudly, so "an event arrived" is emphatically not the test.
   */
  fun grabbedAHandle(before: SelectionObserver.Snapshot?, after: SelectionObserver.Snapshot): Boolean {
    if (before == null) return false
    if (after.packageName != before.packageName) return false

    // What failure actually looks like: a drag that missed the handle lands on the page and
    // collapses the selection to a caret. Every measured miss looked like this — 22..22, 11..11,
    // 160..160 — so emptiness is the discriminator, not novelty.
    if (after.isEmpty()) return false

    /*
     * A DIFFERENT source node is success, not failure.
     *
     * The event's source follows the moving end: grow past a node's edge and the next announcement
     * describes the node now holding that end, with offsets local to it. Treating that as a lost
     * handle made the pad fail at every node boundary — which on a web page is every few words, and
     * is exactly the "loses the handle too easily" the owner reported. The one-edge-held test below
     * only means anything while the offsets share a frame of reference.
     */
    val sameNode = when {
      before.source != null && after.source != null -> before.source == after.source
      else -> after.sourceLength == before.sourceLength
    }
    if (!sameNode) return true

    return after.low() == before.low() || after.high() == before.high()
  }

  companion object {
    /**
     * Where Chrome's moving handle sits relative to the caret, in dp — the one unit both measured
     * devices agree in.
     *
     * The gesture spike measured it on the handset (560 dpi, 3.5x): an end handle announced at
     * offset 15 of a 16-character node with bounds 378..721 x ..1341 was grabbed at (729, 1398) —
     * 29.4 px outside the interpolated anchor and 57 px below the text's bottom, with the centre
     * generally 50..57 px down: 8.4 dp and 14..16 dp. The rig (320 dpi, 2x) measured the centre
     * 27..29 px below and ~21 px outside: 14 dp and ~10 dp. These were once the handset's raw
     * pixels, 57 and 30, which put the rig's aim ~30 px below a ~25 px-radius handle, so every
     * press on page content landed on the page and collapsed the selection. A `TextView` hid it,
     * because the platform's own handle takes touches well past its drawn circle.
     *
     * These are **Chrome's** numbers. Keep draws circles at the selection's corners instead, so
     * treat them as a starting point to be corrected by the loop, never as a constant.
     */
    const val HANDLE_INSET_DP = 9f
    const val HANDLE_DROP_DP = 14f

    /**
     * How far from the handle's centre a down may land and still grab it: about the radius of
     * Chrome's ~25 px handle on the rig (2x). Used only to move an aim off the toolbar.
     */
    const val HANDLE_REACH_DP = 12f

    /** Clearance kept between a moved aim and the toolbar, and between it and the text line. */
    const val TOOLBAR_MARGIN_DP = 2f
    const val TEXT_CLEARANCE_DP = 2f

    /**
     * How near a character's rectangle may come to the whole node's box before it is read as the
     * platform repeating those bounds rather than measuring anything. A pixel of rounding either
     * way, no more: a rect that is genuinely a character is smaller than its node by whole
     * characters and whole lines, so there is nothing in between for a tolerance to arbitrate.
     */
    const val LIE_TOLERANCE = 1f

    /** The longest run [characterWidths] will ask for: a step's worth of characters, never a node's. */
    const val MAX_SPAN = 64

    /** Scanning: the step is the handle's own touch radius; finer only buys duplicate hits. */
    const val SCAN_STEP = 48f
    const val SCAN_NUDGE = 8f
    const val SCAN_MAX_PROBES = 6

    private const val FULL_SCREEN_FRACTION = 0.98f

    /** A toolbar is a handful of buttons; a window with more nodes than this is not one worth walking. */
    private const val TOOLBAR_NODE_BUDGET = 64
  }
}
