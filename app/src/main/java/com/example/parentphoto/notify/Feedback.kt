package com.example.parentphoto.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
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
 * 振動 600 ms(最大振幅)と短い音(TONE_PROP_ACK 200 ms)を同時に鳴らす。
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

    /** 振動に付ける用途の種類。API で使える属性が違うため、選択だけ切り出してある */
    internal enum class VibrationKind { ACCESSIBILITY, ALARM }

    internal companion object {
        /** API 33 以上は VibrationAttributes(ユーザー補助)、26〜32 は AudioAttributes(アラーム) */
        fun vibrationKind(sdk: Int): VibrationKind =
            if (sdk >= Build.VERSION_CODES.TIRAMISU) VibrationKind.ACCESSIBILITY else VibrationKind.ALARM

        private const val TAG = "ParentPhoto/Feedback"
        private const val VIBRATE_MS = 600L
        // 実機のモーターが弱く、300 ms や既定振幅(システムが約半分に弱める)では感じ取れなかったため、600 ms・最大振幅 255 にする(Q75)
        private const val VIBRATE_AMPLITUDE = 255
        private const val TONE_VOLUME = 80
        private const val TONE_MS = 200
        private const val RELEASE_AFTER_MS = 300L

        private fun vibrateOnce(context: Context) {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator ?: return
            val effect = VibrationEffect.createOneShot(VIBRATE_MS, VIBRATE_AMPLITUDE)
            // 属性なし(USAGE_UNKNOWN)だとタッチ振動扱いになり、タッチ時の振動をオフにした端末では無視される。
            // 明示した用途は着信音モード(通常・マナー・サイレント)や触覚フィードバック設定の影響を受けない(実機M06で確認)
            when (vibrationKind(Build.VERSION.SDK_INT)) {
                VibrationKind.ACCESSIBILITY -> vibrator.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY),
                )
                VibrationKind.ALARM -> @Suppress("DEPRECATION") vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
            }
        }

        // 鳴らし終えた後に解放する。待つのはコルーチンで、メインスレッドは止めない
        private fun playTone(scope: CoroutineScope) {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
            generator.startTone(ToneGenerator.TONE_PROP_ACK, TONE_MS)
            scope.launch {
                delay(RELEASE_AFTER_MS)
                generator.release()
            }
        }

        private fun warn(what: String, e: Throwable) {
            runCatching { Log.w(TAG, "$what failed: ${e.javaClass.simpleName}") }
        }
    }
}
