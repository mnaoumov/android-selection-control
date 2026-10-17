package dev.mnaoumov.asc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The launcher entry, and the **prominent disclosure** Play requires of an accessibility app that is
 * not an `isAccessibilityTool`.
 *
 * The requirement is specific and it is why this is a screen rather than a line in a settings menu:
 * the disclosure must appear during normal use, must ask for affirmative consent, and is not
 * satisfied by the privacy policy. It also has to be demonstrable in the review video, so the flow
 * is deliberately linear — read, agree, then and only then the settings link.
 */
class MainActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      // The docked pad covers the bottom of every screen, this one included, so the last buttons
      // need room to scroll up past it.
      val clearOfPad = (PAD_CLEARANCE_DP * resources.displayMetrics.density).toInt()
      setPadding(PADDING, PADDING * 2, PADDING, clearOfPad)
    }

    column.addView(
      TextView(this).apply {
        text = getString(R.string.disclosure_title)
        textSize = 22f
      }
    )

    column.addView(
      TextView(this).apply {
        text = getString(R.string.disclosure_body)
        textSize = 15f
        setPadding(0, PADDING, 0, PADDING)
      }
    )

    column.addView(
      Button(this).apply {
        text = getString(R.string.disclosure_agree)
        setOnClickListener {
          Toast.makeText(this@MainActivity, R.string.consent_recorded, Toast.LENGTH_LONG).show()
          startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
      }
    )

    /*
     * Declining has to be as available as agreeing, or the "consent" is only a speed bump: Play's
     * prominent-disclosure requirement is an affirmative choice, and a choice needs a second
     * option. Declining changes nothing — the service stays off because nothing turned it on.
     */
    column.addView(
      Button(this).apply {
        text = getString(R.string.disclosure_decline)
        setOnClickListener {
          Toast.makeText(this@MainActivity, R.string.disclosure_declined, Toast.LENGTH_LONG).show()
          finish()
        }
      }
    )

    /*
     * The way back from the pad's own close button.
     *
     * Closing the pad hides the overlay and leaves the service connected, so putting it back is a
     * method call rather than a trip through Accessibility settings — which matters because that
     * switch is blocked by Enhanced Confirmation Mode for a sideloaded build.
     */
    column.addView(
      Button(this).apply {
        text = getString(R.string.show_pad)
        setOnClickListener {
          val message =
            if (AscAccessibilityService.showPad()) R.string.pad_shown else R.string.pad_not_running
          Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
      }
    )

    column.addView(batteryRow)

    setContentView(ScrollView(this).apply { addView(column) })
  }

  /**
   * Asks for the battery-optimization exemption, shown only while the app does not have it.
   *
   * **An optimized app's service is killed and restarted by the ROM, again and again.** Measured on a
   * OnePlus 15 (OxygenOS 16) on 2026-10-16: six kills in 21 minutes, each logged by its battery
   * manager as `o-kill(72) ... with powersavemode`, each followed by the accessibility manager
   * restarting the service. Every kill drops the selection state and any run in progress.
   *
   * **The settings list, not the direct prompt.** `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   * would be one tap, but it needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which ends
   * the app's no-permissions property, and Play allows that permission only for apps whose core
   * function cannot work otherwise. The settings list needs no permission at all. The OEM link is
   * there because OnePlus and others add a second switch of their own ("Allow background activity")
   * that the platform exemption does not cover.
   */
  private val batteryRow by lazy {
    LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, PADDING, 0, 0)
      addView(
        TextView(this@MainActivity).apply {
          text = getString(R.string.battery_body)
          textSize = 15f
        }
      )
      addView(
        Button(this@MainActivity).apply {
          text = getString(R.string.battery_settings)
          setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
          }
        }
      )
      addView(
        Button(this@MainActivity).apply {
          text = getString(R.string.battery_oem_help)
          setOnClickListener {
            val maker = Build.MANUFACTURER.lowercase().replace(Regex("[^a-z0-9]"), "")
            val page = Uri.parse("https://dontkillmyapp.com/$maker")
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, page)) }
              .onFailure { Toast.makeText(this@MainActivity, page.toString(), Toast.LENGTH_LONG).show() }
          }
        }
      )
    }
  }

  override fun onResume() {
    super.onResume()
    // Re-read on every return, so the row goes away once the user has granted the exemption.
    val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    batteryRow.visibility = if (exempt) View.GONE else View.VISIBLE
  }

  private companion object {
    const val PADDING = 48

    /** More than the docked pad's height: 152 dp on the rig, three rows of buttons and the padding. */
    const val PAD_CLEARANCE_DP = 240
  }
}
