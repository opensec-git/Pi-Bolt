package opensec.pibolt.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The release app's package; the benchmark variants keep it. */
const val PACKAGE = "in.opensec.pibolt"

/**
 * What a user does first: start the app, scroll Home, open the newest recent session and its transcript, the chats,
 * and settings, each and back. Written against stable labels only, and every step is optional (a device without
 * projects or chats skips them), so screen redesigns do not break profile generation. Run it on a device with a few
 * projects, sessions and chats, so the profile covers those screens too.
 */
fun MacrobenchmarkScope.firstMinutes() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 5_000)
    // Home is drawn once its projects are read (ReportDrawnWhen in HomeScreen).
    device.wait(Until.hasObject(By.text("Recents")), 3_000)
    scrollFirstList()
    // The first row under "Recents": a saved session, which opens in its project (the session screen, the transcript).
    device.findObject(By.text("Recents"))?.let { head ->
        val below = device.findObjects(By.clickable(true)).filter { it.visibleBounds.top > head.visibleBounds.bottom }
        below.minByOrNull { it.visibleBounds.top }?.let { row -> visitAndReturn(row, settle = 3_000) }
    }
    device.findObject(By.text("Chats"))?.let { visitAndReturn(it) }
    (device.findObject(By.desc("Settings")) ?: device.findObject(By.text("Settings")))?.let { visitAndReturn(it) }
}

/** Opens [target], scrolls what it shows and goes back to where it was. */
private fun MacrobenchmarkScope.visitAndReturn(target: UiObject2, settle: Long = 1_000) {
    target.click()
    device.waitForIdle()
    device.wait(Until.gone(By.desc("Settings")), settle)
    scrollFirstList()
    device.pressBack()
    device.waitForIdle()
    // The first Back may only have closed the keyboard (the session's composer): once more, never more.
    if (!device.hasObject(By.desc("Settings"))) {
        device.pressBack()
        device.waitForIdle()
    }
}

fun MacrobenchmarkScope.scrollFirstList() {
    val list = device.findObject(By.scrollable(true)) ?: return
    // Keep clear of the system gesture areas.
    list.setGestureMargin(device.displayWidth / 5)
    repeat(2) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }
    list.fling(Direction.UP)
    device.waitForIdle()
}
