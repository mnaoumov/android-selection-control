package dev.mnaoumov.asc.spike

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The spike has no UI worth the name — it is driven over adb. This exists only to give the app a
 * launcher entry and a one-tap route to the Accessibility settings screen, which is where the
 * service has to be enabled by hand (and where the first spike's risk 3, the restricted-settings block on
 * sideloaded apps, shows up if it is going to).
 */
class MainActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(PADDING, PADDING * 2, PADDING, PADDING)
    }

    root.addView(
      TextView(this).apply {
        text = buildString {
          appendLine("the first spike feasibility spike.")
          appendLine()
          appendLine("Enable \"ASC Spike\" under Settings → Accessibility, then drive it over adb:")
          appendLine()
          appendLine("adb shell am broadcast \\")
          appendLine("  -a dev.mnaoumov.asc.spike.CMD \\")
          appendLine("  --es cmd \"dump com.android.chrome\"")
          appendLine()
          append("adb logcat -s ASCSPIKE:I -d")
        }
        textSize = 14f
      }
    )

    root.addView(
      Button(this).apply {
        text = "Open Accessibility settings"
        setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
      }
    )

    setContentView(root)
  }

  private companion object {
    const val PADDING = 48
  }
}
