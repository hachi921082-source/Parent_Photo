package com.example.parentphoto.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * カメラ権限の判定ヘルパー(ds 2.2 / 4.4)。
 * 「コールドスタートごとに1回だけ要求」のフラグはメモリのみで保持する(永続化しない)。
 * プロセスが残る間は再要求しないので、設定アプリから戻った時は再チェックだけを行う。
 */
object CameraPermission {
    const val PERMISSION = Manifest.permission.CAMERA

    // プロセス寿命 = コールドスタート1回分
    @Volatile
    private var requestedThisColdStart = false

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 未許可で、かつこのコールドスタートでまだ要求していないときだけ true */
    fun shouldRequest(context: Context): Boolean = !isGranted(context) && !requestedThisColdStart

    /** システムダイアログを出す直前に呼ぶ */
    fun markRequested() {
        requestedThisColdStart = true
    }
}
