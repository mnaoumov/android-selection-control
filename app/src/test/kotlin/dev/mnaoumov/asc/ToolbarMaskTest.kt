package dev.mnaoumov.asc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mask's decision, without a window manager. The case that matters is the closed pad: with the
 * pad closed and masking still on, a selection change used to put the mask back over the toolbar.
 */
class ToolbarMaskTest {

  private fun action(
    enabled: Boolean = true,
    padShowing: Boolean = true,
    toolbarFound: Boolean = true,
    msSinceToolbarSeen: Long = 0,
  ) = ToolbarMask.action(enabled, padShowing, toolbarFound, msSinceToolbarSeen)

  @Test
  fun coversTheToolbarWhileThePadIsShowingAndMaskingIsOn() {
    assertEquals(MaskAction.COVER, action())
  }

  @Test
  fun aClosedPadNeverCoversTheToolbar() {
    assertEquals(MaskAction.REMOVE, action(padShowing = false))
    assertEquals(MaskAction.REMOVE, action(padShowing = false, toolbarFound = false))
  }

  @Test
  fun maskingOffNeverCoversTheToolbar() {
    assertEquals(MaskAction.REMOVE, action(enabled = false))
  }

  @Test
  fun aBriefBlinkKeepsTheCover() {
    assertEquals(MaskAction.KEEP, action(toolbarFound = false, msSinceToolbarSeen = ToolbarMask.LINGER_MS))
  }

  @Test
  fun aToolbarGoneLongerThanTheLingerTakesTheCoverDown() {
    assertEquals(MaskAction.REMOVE, action(toolbarFound = false, msSinceToolbarSeen = ToolbarMask.LINGER_MS + 1))
  }
}
