package com.ai.assistance.operit.ui.features.toolbox.screens.speechtotext

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import me.rerere.rikkahub.ui.context.speechtotext.CustomScaffold

/** 语音识别工具屏幕包装器 用于在路由系统中显示SpeechToTextScreen */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechToTextToolScreen(navController: NavController) {
    CustomScaffold { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            SpeechToTextScreen(navController = navController)
        }
    }
}
