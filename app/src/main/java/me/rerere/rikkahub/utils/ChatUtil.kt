package me.rerere.rikkahub.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.navigation.NavHostController
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.Screen
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navController: NavHostController,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navController.navigate(
        route = Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
        ),
    ) {
        popUpTo(0) {
            inclusive = true
        }
        launchSingleTop = true
    }
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}
