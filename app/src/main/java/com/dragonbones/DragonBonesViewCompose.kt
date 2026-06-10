package com.dragonbones

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun DragonBonesViewCompose(
    modifier: Modifier = Modifier,
    model: DragonBonesModel?,
    controller: DragonBonesController,
    zOrderOnTop: Boolean = true,
    onError: (String) -> Unit = {},
    onRenderStart: () -> Unit = {},
    onRenderFrame: () -> Unit = {},
    onRenderEnd: () -> Unit = {}
) {
    // Stub - DragonBones native rendering not available
}
