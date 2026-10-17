package dev.mnaoumov.asc

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A restart of the service builds a new [PadSettings] over the same preferences file, so each test
 * writes through one instance and reads through a second one, which is what a reconnect does.
 */
class PadSettingsTest {

  private val preferences = InMemoryPreferences()

  private fun reconnect() = PadSettings(preferences)

  @Test
  fun firstRunShowsThePadDockedWithTheMenuShown() {
    val settings = reconnect()
    assertTrue(settings.padVisible)
    assertFalse(settings.menuMasked)
    assertEquals(PadMode.DOCKED, settings.mode)
    assertNull(settings.floatingPosition)
  }

  @Test
  fun aClosedPadStaysClosedAfterARestart() {
    reconnect().padVisible = false
    assertFalse(reconnect().padVisible)
  }

  @Test
  fun aReopenedPadIsShownAfterARestart() {
    reconnect().padVisible = false
    reconnect().padVisible = true
    assertTrue(reconnect().padVisible)
  }

  @Test
  fun theMenuMaskModeAndPositionSurviveARestart() {
    reconnect().apply {
      menuMasked = true
      mode = PadMode.FLOATING
      floatingPosition = 120 to 640
    }
    val restarted = reconnect()
    assertTrue(restarted.menuMasked)
    assertEquals(PadMode.FLOATING, restarted.mode)
    assertEquals(120 to 640, restarted.floatingPosition)
  }

  @Test
  fun anUnknownStoredModeFallsBackToDocked() {
    preferences.edit().putString("mode", "SIDEWAYS").apply()
    assertEquals(PadMode.DOCKED, reconnect().mode)
  }

  /** Just enough of [SharedPreferences] for [PadSettings]; the platform's own needs a device. */
  private class InMemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
      private val pending = mutableMapOf<String, Any?>()
      private val removed = mutableSetOf<String>()

      override fun putString(key: String, value: String?) = apply { pending[key] = value }
      override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
      override fun putInt(key: String, value: Int) = apply { pending[key] = value }
      override fun putLong(key: String, value: Long) = apply { pending[key] = value }
      override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
      override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
      override fun remove(key: String) = apply { removed += key }
      override fun clear() = apply { removed += values.keys }
      override fun commit(): Boolean {
        removed.forEach { values.remove(it) }
        values.putAll(pending)
        return true
      }
      override fun apply() {
        commit()
      }
    }
  }
}
