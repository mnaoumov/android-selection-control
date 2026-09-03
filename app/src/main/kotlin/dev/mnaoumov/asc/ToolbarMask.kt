package dev.mnaoumov.asc

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Covers the target app's floating selection toolbar, on request.
 *
 * The toolbar — Chrome's Copy / Share / Search bar — reappears on **every** selection change, so
 * driving a selection one character at a time makes it flash back into place beside the text on each
 * press. There is no way to stop that: it is the other app's own window, put up by its `ActionMode`,
 * and an accessibility service has no API to suppress another app's UI. What it can do is paint over
 * it, which is what this is.
 *
 * Two deliberate properties:
 *
 * 1. **It never takes touches** (`FLAG_NOT_TOUCHABLE`). The toolbar sits right beside the selection,
 *    which is exactly where the handles are, and this overlay would otherwise eat the service's own
 *    dispatched gestures — the one constraint the pad has had since the first spike. The cost is
 *    that the hidden toolbar's buttons are still there to be pressed blind, so masking hides the
 *    toolbar without disabling it.
 * 2. **It is driven by the window list, not by pixels.** The bounds come from the accessibility
 *    window list, the same source the handle locator uses; nothing reads the screen.
 */
class ToolbarMask(
  private val context: Context,
  private val windowManager: WindowManager,
) {

  private var view: View? = null
  private var params: WindowManager.LayoutParams? = null

  /** Whether the user has asked for the toolbar to be hidden. */
  var enabled = false
    private set

  fun toggle(): Boolean {
    enabled = !enabled
    if (!enabled) hide()
    return enabled
  }

  /**
   * Puts the mask over [bounds], or takes it away when there is nothing to cover.
   *
   * Called after anything that could move the toolbar. The window list lags a move by a frame or
   * two, so a mask that is briefly a few pixels out is expected and corrects itself on the next
   * event rather than being worth chasing.
   */
  fun update(bounds: Rect?) {
    if (!enabled || bounds == null || bounds.isEmpty) {
      hide()
      return
    }
    val layoutParams = params ?: newLayoutParams().also { params = it }
    layoutParams.x = bounds.left
    layoutParams.y = bounds.top
    layoutParams.width = bounds.width()
    layoutParams.height = bounds.height()

    val existing = view
    if (existing == null) {
      val fresh = View(context).apply { setBackgroundColor(maskColour()) }
      runCatching { windowManager.addView(fresh, layoutParams) }
      view = fresh
    } else {
      runCatching { windowManager.updateViewLayout(existing, layoutParams) }
    }
  }

  fun hide() {
    val existing = view ?: return
    runCatching { windowManager.removeView(existing) }
    view = null
  }

  private fun newLayoutParams() = WindowManager.LayoutParams().apply {
    type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    format = PixelFormat.OPAQUE
    gravity = Gravity.TOP or Gravity.START
    /*
     * FLAG_LAYOUT_IN_SCREEN is what makes `x`/`y` mean what the bounds say.
     *
     * Without it a window's position is measured inside the content area, while the bounds from the
     * accessibility window list are raw screen pixels — so the mask landed exactly one status bar
     * (141 px on this phone) below the toolbar, blanking the line of body text underneath it while
     * leaving the toolbar itself in plain view. It looked for all the world like a z-order problem,
     * and the z-order was never wrong: the toolbar is a Chrome `PopupWindow` at layer 21000 against
     * this overlay's 631000.
     */
    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
      WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
  }

  /**
   * The device theme's own window background — white in a light theme, near-black in a dark one.
   *
   * A patch has to be *some* colour, and nothing here may read the screen to find out what is behind
   * it, so the next best thing is the colour the platform itself would paint a surface. A fixed grey
   * looked like a hole in a dark page.
   */
  private fun maskColour(): Int {
    val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_DayNight)
    val value = TypedValue()
    return if (themed.theme.resolveAttribute(android.R.attr.colorBackground, value, true)) {
      if (value.resourceId != 0) themed.getColor(value.resourceId) else value.data
    } else {
      FALLBACK_COLOUR
    }
  }

  private companion object {
    /** For the rare theme that answers nothing: a neutral surface, never the pad's blue. */
    const val FALLBACK_COLOUR = 0xFFF1F1F4.toInt()
  }
}
