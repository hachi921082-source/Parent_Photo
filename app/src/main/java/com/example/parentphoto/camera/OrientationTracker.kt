package com.example.parentphoto.camera

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface

/**
 * 端末の持ち方を Surface.ROTATION_* に変換して通知する(ds 4.1)。
 * Activity は縦固定のままなので、写真の向きだけを ImageCapture.targetRotation で追従させる。
 */
class OrientationTracker(context: Context, private val onRotation: (Int) -> Unit) {
    private var last = -1

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = rotationFor(orientation)
            if (rotation != last) {
                last = rotation
                onRotation(rotation)
            }
        }
    }

    fun enable() {
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun disable() {
        listener.disable()
    }

    companion object {
        /** センサー角度(0〜359、端末の上方向の傾き)を Surface.ROTATION_* へ */
        fun rotationFor(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
            in 45 until 135 -> Surface.ROTATION_270
            in 135 until 225 -> Surface.ROTATION_180
            in 225 until 315 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
    }
}
