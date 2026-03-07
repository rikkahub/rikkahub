package me.rerere.baselineprofile

import android.graphics.Rect
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

private const val UI_WAIT_TIMEOUT_MS = 5_000L
private const val LIST_SCROLL_STEPS = 20
private const val FAVORITE_DRAG_STEPS = 6
private const val DRAWER_SCROLL_BOTTOM_START_RATIO = 0.95f
private const val DRAWER_SCROLL_TOP_END_RATIO = 0.08f
private const val DRAWER_SCROLL_RETURN_START_RATIO = 0.85f
private const val DRAWER_SCROLL_RETURN_END_RATIO = 0.2f
private const val SHEET_SCROLL_BOTTOM_START_RATIO = 0.92f
private const val SHEET_SCROLL_TOP_END_RATIO = 0.16f
private const val SHEET_SCROLL_RETURN_START_RATIO = 0.18f
private const val SHEET_SCROLL_RETURN_END_RATIO = 0.9f

internal fun targetPackageName(): String {
    return androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: throw Exception("targetAppId not passed as instrumentation runner arg")
}

internal fun MacrobenchmarkScope.launchAndRunChatJourney(device: UiDevice) {
    startActivityAndWait()
    device.waitForIdle()
    openChatDrawer(device)
    scrollChatDrawer(device)
    openAssistantPicker(device)
    scrollAssistantPicker(device)
    device.pressBack()
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
    openModelSelector(device)
    scrollModelSelector(device)
    dragFavoriteModelsIfPossible(device)
    device.pressBack()
    device.waitForIdle()
}

// 覆盖聊天侧抽屉首次展开
private fun MacrobenchmarkScope.openChatDrawer(device: UiDevice) {
    waitForTaggedObject(device, ChatBenchmarkTags.CHAT_DRAWER_TRIGGER)?.click()
    device.waitForIdle()
}

// 刻意扫到顶和到底
// 复现抽屉边界滚动的顿挫
private fun MacrobenchmarkScope.scrollChatDrawer(device: UiDevice) {
    val conversationList = waitForTaggedObject(device, ChatBenchmarkTags.CHAT_DRAWER_CONVERSATION_LIST) ?: return
    val drawerBounds = conversationList.visibleBounds
    swipeWithin(
        device = device,
        bounds = drawerBounds,
        startYRatio = DRAWER_SCROLL_BOTTOM_START_RATIO,
        endYRatio = DRAWER_SCROLL_TOP_END_RATIO,
    )
    swipeWithin(
        device = device,
        bounds = drawerBounds,
        startYRatio = DRAWER_SCROLL_RETURN_START_RATIO,
        endYRatio = DRAWER_SCROLL_RETURN_END_RATIO,
    )
    swipeWithin(
        device = device,
        bounds = drawerBounds,
        startYRatio = DRAWER_SCROLL_TOP_END_RATIO,
        endYRatio = DRAWER_SCROLL_BOTTOM_START_RATIO,
    )
}

private fun MacrobenchmarkScope.openAssistantPicker(device: UiDevice) {
    waitForTaggedObject(device, ChatBenchmarkTags.ASSISTANT_PICKER_TRIGGER)?.click()
    waitForTaggedObject(device, ChatBenchmarkTags.ASSISTANT_PICKER_LIST)
    device.waitForIdle()
}

private fun MacrobenchmarkScope.scrollAssistantPicker(device: UiDevice) {
    val assistantList = waitForTaggedObject(device, ChatBenchmarkTags.ASSISTANT_PICKER_LIST) ?: return
    swipeWithin(
        device = device,
        bounds = assistantList.visibleBounds,
        startYRatio = SHEET_SCROLL_RETURN_END_RATIO,
        endYRatio = SHEET_SCROLL_TOP_END_RATIO,
    )
    swipeWithin(
        device = device,
        bounds = assistantList.visibleBounds,
        startYRatio = SHEET_SCROLL_RETURN_START_RATIO,
        endYRatio = SHEET_SCROLL_RETURN_END_RATIO,
    )
}

private fun MacrobenchmarkScope.openModelSelector(device: UiDevice) {
    waitForTaggedObject(device, ChatBenchmarkTags.MODEL_SELECTOR_TRIGGER)?.click()
    waitForTaggedObject(device, ChatBenchmarkTags.MODEL_SELECTOR_LIST)
    device.waitForIdle()
}

private fun MacrobenchmarkScope.scrollModelSelector(device: UiDevice) {
    val modelList = waitForTaggedObject(device, ChatBenchmarkTags.MODEL_SELECTOR_LIST) ?: return
    swipeWithin(
        device = device,
        bounds = modelList.visibleBounds,
        startYRatio = SHEET_SCROLL_BOTTOM_START_RATIO,
        endYRatio = SHEET_SCROLL_TOP_END_RATIO,
    )
    swipeWithin(
        device = device,
        bounds = modelList.visibleBounds,
        startYRatio = SHEET_SCROLL_TOP_END_RATIO,
        endYRatio = SHEET_SCROLL_BOTTOM_START_RATIO,
    )
}

// 覆盖收藏模型拖拽和松手归位
private fun MacrobenchmarkScope.dragFavoriteModelsIfPossible(device: UiDevice) {
    if (!device.wait(Until.hasObject(resSelector(ChatBenchmarkTags.MODEL_SELECTOR_FAVORITE_HEADER)), UI_WAIT_TIMEOUT_MS)) {
        return
    }

    val favoriteItems = device.findObjects(resSelector(ChatBenchmarkTags.MODEL_SELECTOR_FAVORITE_ITEM))
        .sortedBy { it.visibleBounds.top }
    if (favoriteItems.size < 2) {
        return
    }

    val from = favoriteItems[0].visibleBounds
    val to = favoriteItems[1].visibleBounds
    device.drag(
        from.centerX(),
        from.centerY(),
        to.centerX(),
        to.centerY(),
        FAVORITE_DRAG_STEPS
    )
    device.waitForIdle()
}

private fun waitForTaggedObject(device: UiDevice, tag: String): UiObject2? {
    val selector = resSelector(tag)
    if (!device.wait(Until.hasObject(selector), UI_WAIT_TIMEOUT_MS)) {
        return null
    }
    return device.findObject(selector)
}

private fun resSelector(tag: String) = By.res(targetPackageName(), tag)

private fun swipeWithin(
    device: UiDevice,
    bounds: Rect,
    startYRatio: Float,
    endYRatio: Float,
    steps: Int = LIST_SCROLL_STEPS
) {
    val x = bounds.centerX()
    val startY = bounds.top + (bounds.height() * startYRatio).toInt()
    val endY = bounds.top + (bounds.height() * endYRatio).toInt()
    device.swipe(x, startY, x, endY, steps)
}
