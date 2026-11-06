package opensec.pibolt.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/** The release app's package; the benchmark variants keep it. */
const val PACKAGE = "in.opensec.pibolt"

/**
 * What a user does first: start the app, scroll the first list, open and leave settings. Written against stable
 * accessibility labels only, and every step is optional, so screen redesigns do not break profile generation.
 */
fun MacrobenchmarkScope.firstMinutes() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 5_000)
    scrollFirstList()
    val settings = device.findObject(By.desc("Settings")) ?: device.findObject(By.text("Settings"))
    if (settings != null) {
        settings.click()
        device.waitForIdle()
        scrollFirstList()
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
