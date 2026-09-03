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

/** Where the pad lives. */
enum class PadMode {
  /** A block the user drags anywhere. Good for reaching around content. */
  FLOATING,

  /**
   * Pinned to the bottom edge, full width, in a compact two-row layout — where a keyboard would be.
   *
   * Note what this does NOT do: the app underneath is not reflowed, and cannot be. Only an
   * `InputMethodService` pushes another app's content aside, and an IME is exactly what this cannot
   * be, since it is only ever shown for an editable field and so could never appear over a browser
   * page. Docking therefore buys predictability — the pad sits out of the reading area instead of
   * over the middle of it — not extra room.
   */
  DOCKED,
}

/**
 * The floating pad.
 *
 * A `TYPE_ACCESSIBILITY_OVERLAY` window added through `WindowManager.addView` — the window type an
 * accessibility service is entitled to, which needs **no `SYSTEM_ALERT_WINDOW`**, so the app's
 * zero-permission property survives. (`attachAccessibilityOverlayToDisplay` looks like the modern
 * answer and is not: it takes a `SurfaceControl`, and `SurfaceControlViewHost.setView` with a null
 * host token is refused outright.)
 */
class Pad(
  private val context: Context,
  private val windowManager: WindowManager,
  private val onCommand: (PadCommand) -> kotlin.Unit,
  private val onSwapEdge: () -> kotlin.Unit,
  private val onClose: () -> kotlin.Unit,
) {

  private var root: View? = null
  private var params: WindowManager.LayoutParams? = null
  private var statusView: TextView? = null

  var mode: PadMode = PadMode.DOCKED
    private set

  val isShowing: Boolean get() = root != null

  fun show() {
    if (isShowing) return
    val layoutParams = layoutFor(mode)
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

  /** Rebuilds in the other mode, because the layout differs as much as the position does. */
  fun toggleMode() {
    val previousStatus = statusView?.text?.toString()
    mode = if (mode == PadMode.DOCKED) PadMode.FLOATING else PadMode.DOCKED
    hide()
    show()
    previousStatus?.let { showStatus(it) }
  }

  /**
   * Makes the pad ignore touches, so a handle sitting under it can still be driven.
   *
   * Used sparingly and never for a whole press: a non-touchable pad also lets the USER's next tap
   * through onto the page, which is how stray taps reached the content underneath.
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

  private fun layoutFor(mode: PadMode) = WindowManager.LayoutParams().apply {
    when (mode) {
      PadMode.DOCKED -> {
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.BOTTOM or Gravity.START
      }
      PadMode.FLOATING -> {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP or Gravity.START
        x = FLOATING_X
        y = FLOATING_Y
      }
    }
    type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    format = PixelFormat.TRANSLUCENT
    // NOT_FOCUSABLE so the pad never takes key focus from the app being driven; NOT_TOUCH_MODAL so
    // touches outside it still reach that app.
    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
  }

  private fun buildView(themed: Context, layoutParams: WindowManager.LayoutParams): View {
    val column = LinearLayout(themed).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(BACKGROUND)
      setPadding(PADDING, PADDING, PADDING, PADDING)
    }

    // Only the floating pad needs a grip: a docked one has nowhere to go.
    if (mode == PadMode.FLOATING) {
      column.addView(buildDragStrip(themed, layoutParams) { column })
    }

    statusView = TextView(themed).apply {
      text = context.getString(R.string.app_name)
      setTextColor(FOREGROUND)
      textSize = 12f
      gravity = Gravity.CENTER
    }

    /*
     * Status line and the way out, on one row.
     *
     * The pad has to be dismissible from the pad itself: it is an always-on overlay sitting over
     * whatever the user is reading, and until this existed the only ways to get rid of it were the
     * Accessibility switch — which Enhanced Confirmation Mode blocks for a sideloaded build — or the
     * by-hand script. Closing HIDES the overlay and leaves the service connected, deliberately: the
     * service is what can put the pad back (see MainActivity), whereas `disableSelf` would strand
     * the user behind that same blocked switch.
     */
    val header = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
    header.addView(
      statusView,
      LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    header.addView(
      action(themed, "✕") { onClose() },
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
      ),
    )
    column.addView(header)

    if (mode == PadMode.DOCKED) {
      // Two compact rows across the width, so the pad occupies a keyboard-sized strip rather than a
      // block over the middle of the text.
      column.addView(
        rowOf(
          themed,
          commandButtons(
            themed,
            PadCommand.WORD_LEFT to "⇤",
            PadCommand.CHAR_LEFT to "←",
            PadCommand.CHAR_RIGHT to "→",
            PadCommand.WORD_RIGHT to "⇥",
          ) + action(themed, "⇄") { onSwapEdge() }
        )
      )
      column.addView(
        rowOf(
          themed,
          commandButtons(
            themed,
            PadCommand.PAGE_UP to "⇞",
            PadCommand.DOC_START to "⤒",
            PadCommand.DOC_END to "⤓",
            PadCommand.PAGE_DOWN to "⇟",
          ) + action(themed, "▣") { toggleMode() }
        )
      )
    } else {
      column.addView(row(themed, PadCommand.WORD_LEFT to "⇤ word", PadCommand.WORD_RIGHT to "word ⇥"))
      column.addView(row(themed, PadCommand.CHAR_LEFT to "← char", PadCommand.CHAR_RIGHT to "char →"))
      column.addView(row(themed, PadCommand.PAGE_UP to "⇞ page", PadCommand.PAGE_DOWN to "page ⇟"))
      column.addView(row(themed, PadCommand.DOC_START to "⤒ start", PadCommand.DOC_END to "end ⤓"))
      column.addView(
        rowOf(
          themed,
          listOf(
            action(themed, "⇄ swap edge") { onSwapEdge() },
            action(themed, "▣ dock") { toggleMode() },
          )
        )
      )
    }
    return column
  }

  private fun commandButtons(themed: Context, vararg buttons: Pair<PadCommand, String>): List<View> =
    buttons.map { (command, label) -> action(themed, label) { onCommand(command) } }

  private fun row(themed: Context, vararg buttons: Pair<PadCommand, String>): View =
    rowOf(themed, commandButtons(themed, *buttons))

  /** Every child weighted equally, so a docked row spreads across the full width. */

  private fun rowOf(themed: Context, children: List<View>): View {
    val line = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
    for (child in children) {
      child.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      line.addView(child)
    }
    return line
  }

  private fun action(themed: Context, label: String, onClick: () -> kotlin.Unit): View =
    Button(themed).apply {
      text = label
      setOnClickListener { onClick() }
    }

  /**
   * The strip consumes its touches (returns true), unlike a button's listener, because a drag must
   * not also read as a press.
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
    const val FLOATING_X = 240
    const val FLOATING_Y = 1900
    const val PADDING = 16
    const val BACKGROUND = 0xE01565C0.toInt()
    const val STRIP_BACKGROUND = 0xFF0D47A1.toInt()
    const val FOREGROUND = 0xFFFFFFFF.toInt()
  }
}
