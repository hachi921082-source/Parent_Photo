package com.example.parentphoto.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.parentphoto.ParentPhotoApp
import com.example.parentphoto.ui.theme.FamilyPhotoTheme

/**
 * 唯一のActivity。MainViewModel を持ち、再開(ON_RESUME)と権限ダイアログの結果を伝える。
 * 画面そのものは MainScreen が状態ごとに出す。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(application as ParentPhotoApp)
    }

    // カメラ権限(ds 2.2)。結果は ViewModel が isGranted() で読み直す
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onCameraPermissionResult(granted)
        }

    // 通知権限(ds 10.1、API 33 以上)。拒否されてもバッジだけになるので結果は使わない
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 再開のたびに優先順位を評価し直す(権限の再要求はしない、ds 2.2)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
            },
        )
        setContent {
            FamilyPhotoTheme {
                val model by viewModel.ui.collectAsState()

                LaunchedEffect(model.cameraPermissionRequest) {
                    if (model.cameraPermissionRequest) {
                        viewModel.onCameraPermissionRequestLaunched()
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
                LaunchedEffect(model.notificationPermissionRequest) {
                    if (model.notificationPermissionRequest) {
                        viewModel.onNotificationPermissionRequestLaunched()
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                MainScreen(model, viewModel, (application as ParentPhotoApp).cameraController)
            }
        }
    }
}
