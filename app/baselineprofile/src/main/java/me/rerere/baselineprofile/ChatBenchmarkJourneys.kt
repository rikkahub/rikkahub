package me.rerere.baselineprofile

import android.graphics.Rect
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

private const val UI_WAIT_TIMEOUT_MS = 5_000L
private const val MESSAGES_LABEL = "Messages"

internal fun targetPackageName(): String {
    return InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: throw Exception("targetAppId not passed as instrumentation runner arg")
}

private fun targetString(resourceName: String): String {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val resourceId = targetContext.resources.getIdentifier(resourceName, "string", targetContext.packageName)
    check(resourceId != 0) { "Unable to resolve target string resource: $resourceName" }
    return targetContext.getString(resourceId)
}

internal fun MacrobenchmarkScope.launchAndRunChatJourney(device: UiDevice) {
    startActivityAndWait()
    device.waitForIdle()
    openChatDrawer(device)
    scrollChatDrawer(device)
    device.pressBack()
    device.waitForIdle()
    openModelSelector(device)
    scrollModelSelector(device)
    dragFavoriteModelsIfPossible(device)
    device.pressBack()
    device.waitForIdle()
}

private fun MacrobenchmarkScope.openChatDrawer(device: UiDevice) {
    device.wait(Until.hasObject(By.desc(MESSAGES_LABEL)), UI_WAIT_TIMEOUT_MS)
    device.findObject(By.desc(MESSAGES_LABEL))?.click()
    device.waitForIdle()
}

private fun MacrobenchmarkScope.scrollChatDrawer(device: UiDevice) {
    val drawerBounds = Rect(
        0,
        0,
        (device.displayWidth * 0.45f).toInt(),
        device.displayHeight
    )
    swipeWithin(device, drawerBounds, startYRatio = 0.85f, endYRatio = 0.2f)
    swipeWithin(device, drawerBounds, startYRatio = 0.2f, endYRatio = 0.85f)
}

private fun MacrobenchmarkScope.openModelSelector(device: UiDevice) {
    device.wait(Until.hasObject(By.desc(MESSAGES_LABEL)), UI_WAIT_TIMEOUT_MS)
    val explicitSelector = device.findObject(By.desc(targetString("setting_model_page_chat_model")))
    if (explicitSelector != null) {
        explicitSelector.click()
        device.waitForIdle()
        return
    }

    val bottomIconButtons = device.findObjects(By.clazz("android.widget.ImageButton"))
        .filter { button ->
            val bounds = button.visibleBounds
            bounds.centerY() > (device.displayHeight * 0.65f).toInt() &&
                bounds.centerX() < (device.displayWidth * 0.6f).toInt()
        }
        .sortedBy { it.visibleBounds.left }

    bottomIconButtons.firstOrNull()?.click()
    device.waitForIdle()
}

private fun MacrobenchmarkScope.scrollModelSelector(device: UiDevice) {
    val sheetBounds = Rect(
        0,
        (device.displayHeight * 0.2f).toInt(),
        device.displayWidth,
        device.displayHeight
    )
    swipeWithin(device, sheetBounds, startYRatio = 0.8f, endYRatio = 0.3f)
    swipeWithin(device, sheetBounds, startYRatio = 0.3f, endYRatio = 0.8f)
}

private fun MacrobenchmarkScope.dragFavoriteModelsIfPossible(device: UiDevice) {
    val favoritesLabel = targetString("model_list_favorite")
    if (!device.wait(Until.hasObject(By.text(favoritesLabel)), UI_WAIT_TIMEOUT_MS)) {
        return
    }

    val favoritesAnchor = device.findObject(By.text(favoritesLabel)) ?: return
    val anchorBottom = favoritesAnchor.visibleBounds.bottom
    val draggableRows = device.findObjects(By.clazz("android.view.View"))
        .filter { row ->
            val bounds = row.visibleBounds
            bounds.top > anchorBottom &&
                bounds.bottom < device.displayHeight &&
                bounds.height() > 0 &&
                bounds.width() > device.displayWidth / 2
        }
        .sortedBy { it.visibleBounds.top }

    if (draggableRows.size < 2) {
        return
    }

    val from = draggableRows[0].visibleBounds
    val to = draggableRows[1].visibleBounds
    device.drag(
        from.centerX(),
        from.centerY(),
        to.centerX(),
        to.centerY(),
        12
    )
    device.waitForIdle()
}

private fun swipeWithin(
    device: UiDevice,
    bounds: Rect,
    startYRatio: Float,
    endYRatio: Float,
    steps: Int = 20
) {
    val x = bounds.centerX()
    val startY = bounds.top + ((bounds.height()) * startYRatio).toInt()
    val endY = bounds.top + ((bounds.height()) * endYRatio).toInt()
    device.swipe(x, startY, x, endY, steps)
}
