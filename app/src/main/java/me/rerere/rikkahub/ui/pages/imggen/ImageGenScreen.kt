package me.rerere.rikkahub.ui.pages.imggen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import java.io.File

@Composable
internal fun ImageGenScreen(
    vm: ImgGenVM,
) {
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val numberOfImages by vm.numberOfImages.collectAsStateWithLifecycle()
    val aspectRatio by vm.aspectRatio.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val currentGeneratedImages by vm.currentGeneratedImages.collectAsStateWithLifecycle()
    val referenceImages by vm.referenceImages.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var showSettingsSheet by remember { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    LaunchedEffect(error) {
        error?.let { errorMessage ->
            toaster.show(message = errorMessage, type = ToastType.Error)
            vm.clearError()
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (isGenerating && currentGeneratedImages.isEmpty()) {
                ContainedLoadingIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (0 until minOf(2, currentGeneratedImages.size)).forEach { index ->
                        val image = currentGeneratedImages[index]
                        var showPreview by remember { mutableStateOf(false) }
                        AsyncImage(
                            model = File(image.filePath),
                            contentDescription = null,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showPreview = true },
                            contentScale = ContentScale.Crop
                        )

                        if (showPreview) {
                            ImagePreviewDialog(
                                images = listOf(image.filePath),
                                onDismissRequest = { showPreview = false },
                            )
                        }
                    }
                }
            }
        }
        ImageGenInputBar(
            prompt = prompt,
            vm = vm,
            isGenerating = isGenerating,
            referenceImages = referenceImages,
            settings = settings,
            onShowSettings = { showSettingsSheet = true },
            modifier = Modifier
        )
    }

    if (showSettingsSheet) {
        ImageGenSettingsSheet(
            vm = vm,
            settings = settings,
            numberOfImages = numberOfImages,
            aspectRatio = aspectRatio,
            scope = scope,
            sheetState = sheetState,
            onDismiss = { showSettingsSheet = false }
        )
    }
}
