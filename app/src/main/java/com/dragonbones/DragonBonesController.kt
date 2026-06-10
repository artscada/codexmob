package com.dragonbones

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope

@Stable
class DragonBonesController(val coroutineScope: CoroutineScope) {
    var animationNames: List<String> = emptyList()
    var scale: Float = 0.5f
    var translationX: Float = 0.0f
    var translationY: Float = 0.0f
    var onSlotTap: ((String) -> Unit)? = null
    
    fun destroyView() {}
    suspend fun fetchAnimationNames() {}
    fun fadeInAnimation(name: String, layer: Int, loop: Int, fadeInTime: Float = 0.3f) {}
    fun stopAnimation(name: String) {}
    fun clearAnimationQueue() {}
    fun overrideBonePosition(boneName: String, x: Float, y: Float) {}
    fun resetBone(boneName: String) {}
    fun playAnimation(name: String, fadeInTime: Float = 0.3f) {}
}

@Composable
fun rememberDragonBonesController(): DragonBonesController {
    val scope = rememberCoroutineScope(); return remember { DragonBonesController(scope) }
}

sealed interface AnimationCommand
data class FadeInAnimationCommand(val name: String, val layer: Int, val loop: Int, val fadeInTime: Float, val id: Long = System.currentTimeMillis()) : AnimationCommand
data class StopAnimationCommand(val name: String, val id: Long = System.currentTimeMillis()) : AnimationCommand
