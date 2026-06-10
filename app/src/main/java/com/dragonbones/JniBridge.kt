package com.dragonbones

object JniBridge {
    fun init() {}
    fun onSurfaceCreated() {}
    fun onSurfaceChanged(width: Int, height: Int) {}
    fun onDrawFrame() {}
    fun containsPoint(x: Float, y: Float): String? = null
    fun getAnimationDuration(animationName: String): Float = 1.0f
}
