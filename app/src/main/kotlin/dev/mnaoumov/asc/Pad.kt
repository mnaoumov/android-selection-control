package dev.mnaoumov.asc

import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
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
  private val onPressStart: (PadCommand) -> kotlin.Unit,
  private val onPressEnd: () -> kotlin.Unit,
  private val onSwapEdge: () -> kotlin.Unit,
  private val onToggleMenu: () -> kotlin.Unit,
  private val onClose: () -> kotlin.Unit,
) {

  private var root: View? = null
  private var params: WindowManager.LayoutParams? = null
  private var statusView: TextView? = null

  private var heldCommand: PadCommand? = null
  private var lastTouchAtMs = 0L

  private var menuButton: Button? = null
  private var menuMasked = false

  /**
   * Reflects whether the target app's selection toolbar is currently covered.
   *
   * The label states what is true, not what pressing it will do — "menu off" means the menu is off —
   * because a button that describes its own action reads as a command and this is a state.
   */
  fun setMenuMasked(masked: Boolean) {
    menuMasked = masked
    menuButton?.text = menuLabel()
  }

  private fun menuLabel(): String = if (menuMasked) "▤\nmenu off" else "▤\nmenu on"

  private fun wrapContent() = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.WRAP_CONTENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
  )

  /**
   * Whether a finger really is still on [command]'s button — the gate on repeating it.
   *
   * **`ACTION_UP` alone cannot be trusted here, and that is measured, not defensive.** While the
   * service is dispatching its own gestures the pad's pending UP can simply never arrive: a single
   * injected tap logged `action=0` and then nothing, and the next touch three seconds later opened
   * with `action=3` (CANCEL). In between, the repeat chain treated the button as held and walked the
   * selection four characters for one tap.
   *
   * So the test is positive evidence rather than the absence of a release: the last touch event on
   * that button has to be recent. A held finger keeps producing MOVE events, a lifted one stops, and
   * a swallowed UP now costs at most one extra step instead of an unbounded run.
   */
  fun fingerStillDown(command: PadCommand): Boolean =
    heldCommand == command && SystemClock.uptimeMillis() - lastTouchAtMs < TOUCH_FRESH_MS

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
    view.post { logButtonBounds(view) }
  }

  /**
   * Logs where each button actually ended up, in raw screen pixels.
   *
   * Testing the pad means pressing its buttons with `adb shell input tap`, and computing those
   * coordinates by hand from a screenshot is how a tap ends up 11 px outside the pad and gets
   * written down as "gestures pass through the overlay" — a wrong conclusion that cost a whole
   * round of measurement. Ask the layout instead. Labels are the pad's own glyphs, not user text.
   */
  private fun logButtonBounds(view: View) {
    val at = IntArray(2)
    fun walk(v: View) {
      if (v is Button) {
        v.getLocationOnScreen(at)
        // Flatten the caption's newline: a log line that wraps loses everything after it.
        val label = v.text.toString().replace("\n", "/")
        Diag.log("pad button '$label' at ${at[0]},${at[1]} ${v.width}x${v.height}")
      }
      if (v is LinearLayout) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(view)
  }

  fun hide() {
    val view = root ?: return
    runCatching { windowManager.removeView(view) }
    root = null
    params = null
    statusView = null
    menuButton = null
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
        /*
         * Sit ABOVE the navigation bar, not under it.
         *
         * An accessibility overlay is laid out in raw screen coordinates, so a window docked to
         * `Gravity.BOTTOM` reaches the physical bottom edge and the system draws the navigation bar
         * on top of it. Measured on the rig at 720x1520: the gesture pill lay across the second row
         * of buttons. The offset is read from `WindowMetrics` rather than from an
         * `OnApplyWindowInsetsListener`, because this window is never dispatched navigation-bar
         * insets at all — the listener fires with `bottom = 0` and the row stays covered.
         */
        y = navigationBarHeight()
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

  private fun navigationBarHeight(): Int =
    windowManager.currentWindowMetrics.windowInsets
      .getInsets(WindowInsets.Type.navigationBars())
      .bottom

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
    // The mask toggle lives beside the close button rather than in the direction grid: it is a
    // setting about the view, not a movement, and the grid is already five buttons wide.
    menuButton = button(themed, menuLabel()).apply { setOnClickListener { onToggleMenu() } }
    header.addView(menuButton, wrapContent())
    header.addView(action(themed, "✕") { onClose() }, wrapContent())
    column.addView(header)

    if (mode == PadMode.DOCKED) {
      // Two compact rows across the width, so the pad occupies a keyboard-sized strip rather than a
      // block over the middle of the text.
      column.addView(
        rowOf(
          themed,
          commandButtons(
            themed,
            PadCommand.WORD_LEFT to "⇤\nword",
            PadCommand.CHAR_LEFT to "←\nchar",
            PadCommand.CHAR_RIGHT to "→\nchar",
            PadCommand.WORD_RIGHT to "⇥\nword",
          ) + action(themed, "⇄\nswap") { onSwapEdge() }
        )
      )
      column.addView(
        rowOf(
          themed,
          commandButtons(
            themed,
            PadCommand.PAGE_UP to "⇞\npage",
            PadCommand.DOC_START to "⤒\nstart",
            PadCommand.DOC_END to "⤓\nend",
            PadCommand.PAGE_DOWN to "⇟\npage",
          ) + action(themed, "▣\nfloat") { toggleMode() }
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
    buttons.map { (command, label) -> holdable(themed, label, command) }

  /**
   * A direction button that repeats while it is held down.
   *
   * Deliberately **not** a repeat timer. A press here is a closed loop that takes anywhere from
   * 300 ms to 2.2 s — several dispatched gestures, each verified by reading the selection back — so a
   * fixed-interval timer would queue presses faster than they complete and the extra ones would be
   * dropped on the `busy` guard. Instead the service starts the next step when the previous one
   * *finishes*, if the finger is still down, which paces itself for free and needs no initial-delay
   * constant either: a tap's finger has always lifted long before the first step completes, so a tap
   * is exactly one step.
   *
   * Touch rather than click, because press and release are separate facts here, and `isPressed` is
   * set by hand since consuming the touch means the button no longer draws that state itself.
   */
  private fun holdable(themed: Context, label: String, command: PadCommand): View =
    button(themed, label).apply {
      setOnTouchListener { view, event ->
        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            view.isPressed = true
            heldCommand = command
            lastTouchAtMs = SystemClock.uptimeMillis()
            onPressStart(command)
            true
          }
          MotionEvent.ACTION_MOVE -> {
            // Freshness, not position: see [fingerStillDown].
            lastTouchAtMs = SystemClock.uptimeMillis()
            true
          }
          MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            view.isPressed = false
            heldCommand = null
            onPressEnd()
            true
          }
          else -> false
        }
      }
    }

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
    button(themed, label).apply { setOnClickListener { onClick() } }

  /**
   * A pad button, captioned where there is no room to spell it out.
   *
   * The docked pad is five buttons across the screen — 137 px each on the rig — so a wordy label
   * does not fit and a bare arrow does not say whether it moves a character or a word. Both lines
   * therefore go on the button, and the glyph is scaled back up so it stays the readable part.
   */
  private fun button(themed: Context, label: String): Button = Button(themed).apply {
    isAllCaps = false
    val newline = label.indexOf('\n')
    if (mode == PadMode.DOCKED && newline > 0) {
      textSize = CAPTION_TEXT_SIZE
      maxLines = 2
      setPadding(0, 0, 0, 0)
      text = SpannableString(label).apply {
        setSpan(RelativeSizeSpan(GLYPH_SCALE), 0, newline, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
    } else {
      text = label
    }
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

    /**
     * How recent the last touch event on a button must be for a hold to keep repeating.
     *
     * Generous, because a finger resting still produces MOVE events only now and then; short enough
     * that a swallowed UP cannot run away with the selection.
     */
    const val TOUCH_FRESH_MS = 600L

    /** Small enough that "start" fits a fifth of the screen width. */
    const val CAPTION_TEXT_SIZE = 10f

    /** The glyph carries the direction, so it is scaled back up above its caption. */
    const val GLYPH_SCALE = 1.8f
  }
}
