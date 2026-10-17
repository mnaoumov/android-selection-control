package dev.mnaoumov.asc

import android.content.SharedPreferences

/**
 * What the user set on the pad, kept across a restart of the service.
 *
 * **The service is restarted behind the user's back, so nothing the user can see may live only in
 * memory.** Measured 2026-10-16 on the OnePlus 15: OxygenOS's battery manager killed the process six
 * times in 21 minutes (`OplusClearSystemService: K [...] o-kill(72) ... with powersavemode`), and each
 * time the accessibility manager restarted the service 1-80 s later. `onServiceConnected` used to
 * show the pad unconditionally, so a pad the user had closed came back "at random". It reads this
 * instead, and so does every other choice the pad offers: the menu mask, docked or floating, and
 * where a floating pad was dragged.
 *
 * None of it is the text, the selection or anything about the app being driven. These are the pad's
 * own settings, in the app's private preferences, and they never leave the device.
 */
class PadSettings(private val preferences: SharedPreferences) {

  /** Whether the pad is on screen. A first run shows it, because that is what turning the service on asks for. */
  var padVisible: Boolean
    get() = preferences.getBoolean(KEY_PAD_VISIBLE, true)
    set(value) = preferences.edit().putBoolean(KEY_PAD_VISIBLE, value).apply()

  /** Whether the target app's selection toolbar is covered ([ToolbarMask]). */
  var menuMasked: Boolean
    get() = preferences.getBoolean(KEY_MENU_MASKED, false)
    set(value) = preferences.edit().putBoolean(KEY_MENU_MASKED, value).apply()

  /** Docked or floating. An unknown stored name, from some later build, falls back to docked. */
  var mode: PadMode
    get() = PadMode.entries.firstOrNull { it.name == preferences.getString(KEY_MODE, null) } ?: PadMode.DOCKED
    set(value) = preferences.edit().putString(KEY_MODE, value.name).apply()

  /** Where a floating pad was last dragged, in raw screen pixels, or null before the first drag. */
  var floatingPosition: Pair<Int, Int>?
    get() =
      if (preferences.contains(KEY_FLOATING_X) && preferences.contains(KEY_FLOATING_Y)) {
        preferences.getInt(KEY_FLOATING_X, 0) to preferences.getInt(KEY_FLOATING_Y, 0)
      } else {
        null
      }
    set(value) {
      val editor = preferences.edit()
      if (value == null) {
        editor.remove(KEY_FLOATING_X).remove(KEY_FLOATING_Y)
      } else {
        editor.putInt(KEY_FLOATING_X, value.first).putInt(KEY_FLOATING_Y, value.second)
      }
      editor.apply()
    }

  companion object {
    /** The preferences file, under the app's private data. */
    const val FILE = "pad"

    private const val KEY_PAD_VISIBLE = "padVisible"
    private const val KEY_MENU_MASKED = "menuMasked"
    private const val KEY_MODE = "mode"
    private const val KEY_FLOATING_X = "floatingX"
    private const val KEY_FLOATING_Y = "floatingY"
  }
}
