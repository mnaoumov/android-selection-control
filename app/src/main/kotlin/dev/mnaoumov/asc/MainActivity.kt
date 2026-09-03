package dev.mnaoumov.asc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
      setPadding(PADDING, PADDING * 2, PADDING, PADDING)
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

    setContentView(ScrollView(this).apply { addView(column) })
  }

  private companion object {
    const val PADDING = 48
  }
}
