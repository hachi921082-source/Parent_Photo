package com.example.parentphoto.notify

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 送信成功の合図(ds 10.2)。画面の状態機械は「送りました！」に入った時に1回だけ呼ぶ */
fun interface SuccessFeedback {
    fun play()
}

/**
 * 振動 300 ms と短い音(TONE_PROP_ACK 200 ms)を同時に鳴らす。
 * マナーモード・サイレント(着信音モードが NORMAL 以外)では振動だけにし、音は強制しない(Q49)。
 * 送信待ち・ワーカーでの送信成功では鳴らさない。
 */
class Feedback(
    private val vibrate: () -> Unit,
    private val tone: () -> Unit,
    private val ringerNormal: () -> Boolean,
) : SuccessFeedback {

    constructor(context: Context, scope: CoroutineScope) : this(
        vibrate = { vibrateOnce(context.applicationContext) },
        tone = { playTone(scope) },
        ringerNormal = {
            context.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_NORMAL
        },
    )

    override fun play() {
        // 片方が失敗しても、もう片方は鳴らす
        runCatching(vibrate).onFailure { warn("vibrate", it) }
        if (runCatching(ringerNormal).getOrDefault(false)) {
            runCatching(tone).onFailure { warn("tone", it) }
        }
    }

    private companion object {
        const val TAG = "ParentPhoto/Feedback"
        const val VIBRATE_MS = 300L
        const val TONE_VOLUME = 80
        const val TONE_MS = 200
        const val RELEASE_AFTER_MS = 300L

        fun vibrateOnce(context: Context) {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        // 鳴らし終えた後に解放する。待つのはコルーチンで、メインスレッドは止めない
        fun playTone(scope: CoroutineScope) {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
            generator.startTone(ToneGenerator.TONE_PROP_ACK, TONE_MS)
            scope.launch {
                delay(RELEASE_AFTER_MS)
                generator.release()
            }
        }

        fun warn(what: String, e: Throwable) {
            runCatching { Log.w(TAG, "$what failed: ${e.javaClass.simpleName}") }
        }
    }
}
