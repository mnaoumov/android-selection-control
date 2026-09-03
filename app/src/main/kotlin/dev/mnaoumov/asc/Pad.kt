package dev.mnaoumov.asc

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating pad.
 *
 * A `TYPE_ACCESSIBILITY_OVERLAY` window added through `WindowManager.addView` — the window type an
 * accessibility service is entitled to, which needs **no `SYSTEM_ALERT_WINDOW`**, so the app's
 * zero-permission property survives. (`attachAccessibilityOverlayToDisplay` looks like the modern
 * answer and is not: it takes a `SurfaceControl`, and `SurfaceControlViewHost.setView` with a null
 * host token is refused outright.)
 *
 * Two behaviours here are not decoration:
 *  - **the drag strip**, because the pad must be movable off the text being worked on;
 *  - **[setTransparentToTouch]**, because the pad eats the service's own gestures. A dispatch inside
 *    its footprint hits the pad — it will even press its own button — and a selection handle
 *    underneath cannot be driven at all until it gets out of the way.
 */
class Pad(
  private val context: Context,
  private val windowManager: WindowManager,
  private val onCommand: (PadCommand) -> kotlin.Unit,
  private val onSwapEdge: () -> kotlin.Unit,
) {

  private var root: View? = null
  private var params: WindowManager.LayoutParams? = null
  private var statusView: TextView? = null

  val isShowing: Boolean get() = root != null

  fun show() {
    if (isShowing) return

    val layoutParams = WindowManager.LayoutParams().apply {
      width = WindowManager.LayoutParams.WRAP_CONTENT
      height = WindowManager.LayoutParams.WRAP_CONTENT
      x = INITIAL_X
      y = INITIAL_Y
      type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
      gravity = Gravity.TOP or Gravity.START
      format = PixelFormat.TRANSLUCENT
      // NOT_FOCUSABLE so the pad never takes key focus from the app being driven; NOT_TOUCH_MODAL so
      // touches outside it still reach that app.
      flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }

    // A Service has no theme, and an unthemed Button inflates wrong.
    val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
    val view = buildView(themed, layoutParams)

    windowManager.addView(view, layoutParams)
    root = view
    params = layoutParams
  }

  fun hide() {
    val view = root ?: return
    runCatching { windowManager.removeView(view) }
    root = null
    params = null
    statusView = null
  }

  /**
   * Makes the pad ignore touches for the duration of a dispatched gesture, so a handle sitting under
   * it can still be driven. Without this the gesture hits the pad instead of the app — measured: the
   * service pressed its own button, and a handle underneath produced nothing at any reach.
   */
  fun setTransparentToTouch(transparent: Boolean) {
    val view = root ?: return
    val layoutParams = params ?: return
    val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    layoutParams.flags = if (transparent) layoutParams.flags or flag else layoutParams.flags and flag.inv()
    runCatching { windowManager.updateViewLayout(view, layoutParams) }
  }

  fun showStatus(text: String) {
    statusView?.text = text
  }

  private fun buildView(themed: Context, layoutParams: WindowManager.LayoutParams): View {
    val column = LinearLayout(themed).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(BACKGROUND)
      setPadding(PADDING, PADDING, PADDING, PADDING)
    }

    column.addView(buildDragStrip(themed, layoutParams) { column })

    statusView = TextView(themed).apply {
      text = context.getString(R.string.app_name)
      setTextColor(FOREGROUND)
      textSize = 12f
      gravity = Gravity.CENTER
    }
    column.addView(statusView)

    column.addView(row(themed, PadCommand.WORD_LEFT to "⇤ word", PadCommand.WORD_RIGHT to "word ⇥"))
    column.addView(row(themed, PadCommand.CHAR_LEFT to "← char", PadCommand.CHAR_RIGHT to "char →"))
    column.addView(row(themed, PadCommand.PAGE_UP to "⇞ page", PadCommand.PAGE_DOWN to "page ⇟"))
    column.addView(row(themed, PadCommand.DOC_START to "⤒ start", PadCommand.DOC_END to "end ⤓"))

    column.addView(
      Button(themed).apply {
        text = "⇄ swap edge"
        setOnClickListener { onSwapEdge() }
      }
    )
    return column
  }

  private fun row(themed: Context, vararg buttons: Pair<PadCommand, String>): View {
    val line = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
    for ((command, label) in buttons) {
      line.addView(
        Button(themed).apply {
          text = label
          setOnClickListener { onCommand(command) }
        }
      )
    }
    return line
  }

  /**
   * The strip consumes its touches (returns true), unlike a button's listener, because a drag must
   * not also read as a press. `rawX`/`rawY` keep the arithmetic in screen coordinates, the space the
   * drag actually happens in.
   *
   * Note that `LayoutParams.x/y` are inset by the status bar while dispatched gestures use raw
   * screen pixels; anything comparing the two must read the pad's real bounds from the accessibility
   * window list rather than from what was asked for.
   */
  private fun buildDragStrip(
    themed: Context,
    layoutParams: WindowManager.LayoutParams,
    viewProvider: () -> View,
  ): View {
    var fingerDownX = 0f
    var fingerDownY = 0f
    var originX = 0
    var originY = 0

    return TextView(themed).apply {
      text = "≡  drag to move"
      setTextColor(FOREGROUND)
      setBackgroundColor(STRIP_BACKGROUND)
      gravity = Gravity.CENTER
      setPadding(PADDING, PADDING, PADDING, PADDING)
      setOnTouchListener { _, event ->
        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            fingerDownX = event.rawX
            fingerDownY = event.rawY
            originX = layoutParams.x
            originY = layoutParams.y
            true
          }
          MotionEvent.ACTION_MOVE -> {
            layoutParams.x = originX + (event.rawX - fingerDownX).toInt()
            layoutParams.y = originY + (event.rawY - fingerDownY).toInt()
            runCatching { windowManager.updateViewLayout(viewProvider(), layoutParams) }
            true
          }
          else -> false
        }
      }
    }
  }

  private companion object {
    const val INITIAL_X = 240
    const val INITIAL_Y = 1900
    const val PADDING = 16
    const val BACKGROUND = 0xE01565C0.toInt()
    const val STRIP_BACKGROUND = 0xFF0D47A1.toInt()
    const val FOREGROUND = 0xFFFFFFFF.toInt()
  }
}
