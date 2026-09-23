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
 * Three rungs, in the order the pad build measured them to be reliable. Each is used only where it is known
 * to hold, because the failure mode is not a wrong number but a **destroyed selection**: a drag that
 * misses a handle lands on the page and collapses the range.
 *
 * 1. **Interpolate** across the source node's bounds — accurate to about a character where the node
 *    hugs its text, which is Chrome's per-phrase inline nodes. Only valid when the box is one line:
 *    a phrase that wraps reports the union of its line boxes, and interpolating across that put the
 *    handle ~130 px out in x and ~76 px in y.
 * 2. **Ask the platform for the character's own rectangle** — the only rung that answers with a ROW
 *    rather than assuming one, and therefore the only one that can reach inside a wrap. It is also
 *    the only rung that can be caught lying, which is what makes it safe to try. See
 *    [characterGeometry].
 * 3. **Mirror about the floating toolbar's centre** — the toolbar tracks the selection's centre to
 *    ~6 px, and the handles are symmetric about it, so the moving one is `2·centre − anchor`. Needs
 *    a known anchor, and holds only while both handles are on the same row.
 * 4. **Acquire by scanning** outward from that centre — one probe found a word's handle in 454 ms,
 *    but a miss destroys the selection, so this is the last resort, never the first guess. It is
 *    driven from `SelectionDriver`, and gated on [scanRowIsKnown].
 */
class HandleLocator {

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
   */
  fun characterGeometry(
    snapshot: SelectionObserver.Snapshot,
    edge: Edge,
    crossing: Crossing = Crossing.RIGHTWARD,
  ): CharacterGeometry? {
    val source = snapshot.source ?: return null
    val bounds = snapshot.bounds ?: return null
    val length = snapshot.sourceLength
    if (length <= 0) return null

    val offset = if (edge == Edge.START) snapshot.low() else snapshot.high()
    val key = "${snapshot.atMs}|$edge|$offset|$crossing"
    if (key == memoKey) return memoValue
    memoKey = key
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
    if (rect.width() >= bounds.width() - LIE_TOLERANCE && rect.height() >= bounds.height() - LIE_TOLERANCE) {
      Diag.log("  locate: per-character rects are the node's own bounds here — the platform is not answering")
      return null
    }

    memoValue = CharacterGeometry(
      caretX = if (caretIsRightEdge) rect.right else rect.left,
      lineBottom = rect.bottom,
      characterWidth = rect.width(),
    )
    return memoValue
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
      val offset = if (edge == Edge.START) snapshot.low() else snapshot.high()
      val anchorX = bounds.left + (offset.toFloat() / snapshot.sourceLength) * bounds.width()
      val x = if (edge == Edge.START) anchorX - HANDLE_INSET else anchorX + HANDLE_INSET
      return PointF(x, bounds.bottom + HANDLE_DROP)
    }

    /*
     * Only reached once interpolation is out, so the fast path — Chrome's one-line inline nodes,
     * which is most presses — never pays the round trip. This is the case where every remaining
     * rung is guessing at a row, so an IPC that answers with one is cheap by comparison.
     */
    val character = characterGeometry(snapshot, edge)
    if (character != null) {
      val x =
        if (edge == Edge.START) character.caretX - HANDLE_INSET else character.caretX + HANDLE_INSET
      Diag.log(
        "  locate: character rect gave the $edge handle its own row — " +
          "caret=${character.caretX} lineBottom=${character.lineBottom} charPx=${character.characterWidth}"
      )
      return PointF(x, character.lineBottom + HANDLE_DROP)
    }

    val centre = toolbarCentreX
    val anchor = knownAnchorX
    if (centre != null && anchor != null) {
      return PointF(2 * centre - anchor, bounds.bottom + HANDLE_DROP)
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
   */
  fun toolbarBounds(
    windows: List<AccessibilityWindowInfo>,
    packageName: String?,
    screenWidth: Int,
  ): Rect? {
    if (packageName == null) return null
    return windows
      .asSequence()
      .filter { it.root?.packageName?.toString() == packageName }
      .map { window -> Rect().also { window.getBoundsInScreen(it) } }
      .filter { it.width() > 0 && it.width() < FULL_SCREEN_FRACTION * screenWidth }
      .minByOrNull { it.width().toLong() * it.height() }
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
     * the gesture spike's measured Chrome geometry: an end handle announced at offset 15 of a 16-character node
     * with bounds 378..721 x ..1341 was grabbed at (729, 1398) — 29.4 px outside the interpolated
     * anchor and 57 px below the text's bottom, against a ~48 px-radius touch target.
     *
     * These are **Chrome's** numbers. Keep draws circles at the selection's corners instead, so
     * treat them as a starting point to be corrected by the loop, never as a constant.
     */
    const val HANDLE_INSET = 30f
    const val HANDLE_DROP = 57f

    /**
     * How near a character's rectangle may come to the whole node's box before it is read as the
     * platform repeating those bounds rather than measuring anything. A pixel of rounding either
     * way, no more: a rect that is genuinely a character is smaller than its node by whole
     * characters and whole lines, so there is nothing in between for a tolerance to arbitrate.
     */
    const val LIE_TOLERANCE = 1f

    /** Scanning: the step is the handle's own touch radius; finer only buys duplicate hits. */
    const val SCAN_STEP = 48f
    const val SCAN_NUDGE = 8f
    const val SCAN_MAX_PROBES = 6

    private const val FULL_SCREEN_FRACTION = 0.98f
  }
}
