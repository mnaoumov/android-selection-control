package dev.mnaoumov.asc

import android.util.Log

/**
 * Diagnostics for the loop.
 *
 * **This logs offsets, pixels and timings — never text.** That is not a convention to be relaxed
 * later: the whole trust story is that the app cannot leak what it never reads, and a log is the
 * easiest place for that to quietly stop being true. The throwaway spike logged node text
 * deliberately, on the owner's own device, and none of it came here.
 *
 * It exists because the first version of this app shipped with no instrumentation at all, and the
 * first bug report — "it doesn't work that well" — could not be answered without guessing.
 */
object Diag {
  const val TAG = "ASC"

  @Volatile
  var enabled = true

  fun log(message: String) {
    if (enabled) Log.i(TAG, message)
  }
}
