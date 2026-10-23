package com.example.parentphoto.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** ネットワーク状態の確認。 */
object Connectivity {
    /** 通信できる回線が明らかに無いとき true。判定手段が無いときは false(送信を試みる側に倒す)。 */
    fun isClearlyOffline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 通信できる回線があるか(true)の移り変わり。isClearlyOffline と同じ保守的な見方:
     * 既定の回線が無い、またはインターネットの能力が無いときだけ false。判定手段が無いときは true。
     * 購読の最初に今の状態を1回流す。
     */
    fun onlineFlow(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }

            override fun onLost(network: Network) {
                // 既定の回線が切り替わる途中でも呼ばれるので、今の状態を取り直して判定する
                trySend(!isClearlyOffline(context))
            }
        }
        // 登録前に今の状態を流す(回線が最初から無いとコールバックが1度も呼ばれないため)
        trySend(!isClearlyOffline(context))
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // 登録できない端末では判定できないので、送信を試みる側(オンライン)に倒す
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.conflate().distinctUntilChanged()
}
