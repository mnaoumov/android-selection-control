package dev.mnaoumov.asc

import android.graphics.PointF
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo

/** Which end of the selection the pad is currently moving. */
enum class Edge { START, END }

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
 * 2. **Mirror about the floating toolbar's centre** — the toolbar tracks the selection's centre to
 *    ~6 px, and the handles are symmetric about it, so the moving one is `2·centre − anchor`. Needs
 *    a known anchor, and holds only while both handles are on the same row.
 * 3. **Acquire by scanning** outward from that centre — one probe found a word's handle in 454 ms,
 *    but a miss destroys the selection, so this is the last resort, never the first guess.
 */
class HandleLocator {

  /** The last anchor-handle x we were confident about, for rung 2. */
  private var knownAnchorX: Float? = null

  fun forgetAnchor() {
    knownAnchorX = null
  }

  fun rememberAnchor(x: Float) {
    knownAnchorX = x
  }

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
  ): Float? {
    if (packageName == null) return null
    return windows
      .asSequence()
      .filter { it.root?.packageName?.toString() == packageName }
      .map { window -> Rect().also { window.getBoundsInScreen(it) } }
      .filter { it.width() > 0 && it.width() < FULL_SCREEN_FRACTION * screenWidth }
      .minByOrNull { it.width().toLong() * it.height() }
      ?.exactCenterX()
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

    /** Scanning: the step is the handle's own touch radius; finer only buys duplicate hits. */
    const val SCAN_STEP = 48f
    const val SCAN_NUDGE = 8f
    const val SCAN_MAX_PROBES = 6

    private const val FULL_SCREEN_FRACTION = 0.98f
  }
}
