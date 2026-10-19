package com.example.parentphoto.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** ネットワーク状態の確認。 */
object Connectivity {
    /** 通信できる回線が明らかに無いとき true。判定手段が無いときは false(送信を試みる側に倒す)。 */
    fun isClearlyOffline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
