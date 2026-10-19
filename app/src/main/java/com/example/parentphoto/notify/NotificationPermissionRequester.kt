package com.example.parentphoto.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.parentphoto.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * 通知権限(POST_NOTIFICATIONS)の要求判定(ds 10.1)。
 * API 33 以上で、カメラ権限が決まった後にカメラが初めて表示されたとき1回だけ要求する。
 * 要求済みフラグ(notif_permission_asked)は永続化し、拒否後も再要求しない。
 * 拒否された場合はバッジのみの表示になる。
 */
class NotificationPermissionRequester(
    private val isAsked: suspend () -> Boolean,
    private val markAsked: suspend () -> Unit,
    private val isGranted: () -> Boolean,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    constructor(context: Context, settings: SettingsRepository) : this(
        isAsked = { settings.settings.first().notifPermissionAsked },
        markAsked = { settings.markNotifPermissionAsked() },
        isGranted = {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        },
    )

    /**
     * カメラ権限が確定してカメラを表示した時点で呼ぶ。
     * 実際にシステムダイアログを出すべきときだけ launch を呼び、true を返す。
     */
    suspend fun requestIfNeeded(launch: () -> Unit): Boolean {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) return false
        if (isAsked()) return false
        // すでに許可済みでも、以後は判定不要なのでフラグを立てる
        markAsked()
        if (isGranted()) return false
        launch()
        return true
    }
}
