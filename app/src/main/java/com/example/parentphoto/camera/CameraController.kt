package com.example.parentphoto.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** 撮影結果。ファイルは縮小・向き補正済みの cache/preview.jpg */
data class CaptureResult(val previewFile: File)

/**
 * CameraX の束ね込みと撮影(ds 4.1 / 4.2)。画面状態は持たない。
 * 撮影時刻(capturedAtMillis)は呼び出し側がシャッター直前に記録する。
 */
class CameraController(context: Context, private val files: PreviewFiles) {
    private val appContext = context.applicationContext

    private val preview = Preview.Builder().build()
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
    private val orientationTracker = OrientationTracker(appContext) { imageCapture.targetRotation = it }

    @Volatile
    private var bound = false

    /** カメラを lifecycle に結びつける。失敗時は Result.failure(再試行は次の resume で呼び直す) */
    suspend fun bind(owner: LifecycleOwner, previewView: PreviewView): Result<Unit> {
        return try {
            val provider = awaitProvider()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            orientationTracker.enable()
            bound = true
            Result.success(Unit)
        } catch (e: Exception) {
            bound = false
            Log.e(TAG, "bind failed: ${e.javaClass.simpleName}", e)
            Result.failure(e)
        }
    }

    /** 束ね込みを解除する(Camera/OutboxFull 以外の状態に入る時など) */
    fun unbind() {
        orientationTracker.disable()
        bound = false
        // provider 取得済みなら同期的に解除できる
        runCatching { ProcessCameraProvider.getInstance(appContext).get().unbindAll() }
    }

    /**
     * 撮影して縮小まで行う。失敗時は途中のファイルを消して Result.failure を返す。
     * 呼び出し側は失敗を「撮影できませんでした」表示(photo-send-flow)に結びつける。
     */
    suspend fun capture(): Result<CaptureResult> {
        if (!bound) {
            return Result.failure(IllegalStateException("camera not bound"))
        }
        files.deleteAll()
        val taken = takeRaw()
        if (taken.isFailure) {
            files.deleteAll()
            return Result.failure(taken.exceptionOrNull()!!)
        }
        return ImageProcessor.resize(files).map { CaptureResult(it) }
    }

    private suspend fun takeRaw(): Result<Unit> = suspendCancellableCoroutine { cont ->
        val options = ImageCapture.OutputFileOptions.Builder(files.raw).build()
        try {
            imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(appContext),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(Result.success(Unit))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        // 写真の内容は出さず、エラー種別だけ記録する
                        Log.e(TAG, "capture failed: code=${exception.imageCaptureError}", exception)
                        if (cont.isActive) cont.resume(Result.failure(exception))
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "takePicture threw: ${e.javaClass.simpleName}", e)
            cont.resume(Result.failure(e))
        }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWith(Result.failure(e))
                }
            }, ContextCompat.getMainExecutor(appContext))
        }

    private companion object {
        const val TAG = "ParentPhoto/Camera"
    }
}

/**
 * カメラ映像を全面表示する Composable(FILL_CENTER)。
 * 表示中は resume のたびに束ね込みをやり直し、失敗は onBindFailure で通知する
 * (起動時の初期化失敗も同じ経路。次の resume で自動的に再試行される)。
 */
@Composable
fun CameraView(
    controller: CameraController,
    modifier: Modifier = Modifier,
    onBindFailure: (Throwable) -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val previewView = androidx.compose.runtime.remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(owner, controller) {
        val bindNow = {
            scope.launch {
                controller.bind(owner, previewView).onFailure(onBindFailure)
            }
            Unit
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) bindNow()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.unbind()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
