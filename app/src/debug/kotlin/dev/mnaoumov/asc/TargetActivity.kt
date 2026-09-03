package dev.mnaoumov.asc

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A **debug-only** target to drive the pad against, so the loop can be tested without a browser.
 *
 * Why it exists: the pad's real targets are heavy — Chrome under a software renderer wedges an
 * emulator outright — and worse, their offsets are unknown, so a step cannot be checked against an
 * expected number without first discovering the page's node boundaries. Here the text is fixed and
 * every expected offset is known in advance, which makes a wrong step unambiguous.
 *
 * Why it is a fair target: a `TextView` with `textIsSelectable` is selectable-but-not-editable, uses
 * the platform's own selection handles, and announces `TYPE_VIEW_TEXT_SELECTION_CHANGED` with
 * node-local offsets — the exact mechanism the pad drives in Chrome. What it does NOT reproduce is
 * Chrome's per-phrase inline node tree; each view here is one node, so node-crossing still has to be
 * tested on the real thing.
 *
 * Lives in `src/debug`, so it cannot reach a release build.
 */
class TargetActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(PADDING, PADDING, PADDING, PADDING)
    }

    /*
     * Hardest case FIRST, simplest LAST — which looks backwards and is not.
     *
     * A selectable `TextView` is focusable, the last one added takes focus, and taking focus scrolls
     * it into view. Fighting that with `requestFocus` and `scrollTo(0, 0)` in a posted runnable did
     * not hold: measured on the rig, the screen still opened on whichever selectable view was added
     * last. So the order is chosen to make that behaviour land somewhere useful instead.
     */
    val sections = listOf(
      "WRAPPED" to WRAPPED,
      "DIGITS" to DIGITS,
      "ONE_LINE" to ONE_LINE,
    )
    val views = sections.map { (name, text) ->
      column.addView(label(name))
      selectable(text).also { column.addView(it) }
    }

    setContentView(ScrollView(this).apply { addView(column) })

    // Log where each block landed, in raw screen pixels, so a test can long-press a known word
    // without a human reading coordinates off a screenshot. Names and rectangles only — the fixture
    // text is never logged, exactly as in the app itself.
    column.post {
      val at = IntArray(2)
      views.forEachIndexed { i, view ->
        view.getLocationOnScreen(at)
        Diag.log(
          "target '${sections[i].first}' at ${at[0]},${at[1]} ${view.width}x${view.height} " +
            "len=${sections[i].second.length}"
        )
      }
    }
  }

  private fun label(text: String) = TextView(this).apply {
    this.text = text
    textSize = 11f
    gravity = Gravity.START
    setPadding(0, PADDING, 0, 4)
  }

  private fun selectable(text: String) = TextView(this).apply {
    this.text = text
    textSize = 18f
    setTextIsSelectable(true)
  }

  companion object {
    /** 30 characters; word boundaries at 5, 11, 19, 25. */
    const val ONE_LINE = "alpha bravo charlie delta echo"

    /** 25 characters, deliberately uneven words. */
    const val DIGITS = "1 22 333 4444 55555 66666"

    /** Long enough to wrap, which is the case the handle locator has never solved. */
    const val WRAPPED =
      "The quick brown fox jumps over the lazy dog while a second sentence continues far " +
        "enough to wrap onto several lines so the node reports the union of its line boxes " +
        "rather than one tidy rectangle."

    private const val PADDING = 32
  }
}
