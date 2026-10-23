package com.example.parentphoto.ui

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Preview の送り先一覧の寸法(dp)。
 * @property buttonHeight 送り先ボタンの最小の高さ
 * @property spacing ボタンの間隔
 * @property viewportHeight 一覧の高さ(スクロールする時は最後のボタンが半分切れる高さ)
 * @property scrollable 一覧がスクロールするか
 */
data class PreviewListLayout(
    val buttonHeight: Float,
    val spacing: Float,
    val viewportHeight: Float,
    val scrollable: Boolean,
)

/** Preview の送り先一覧と写真の高さの決め方(純粋関数。issue #4、ds 3.4) */
object PreviewListSizing {
    const val NORMAL_SPACING = 16f
    const val COMPACT_SPACING = 8f
    const val MIN_BUTTON = 56f

    /** 写真の下限を決める「送り先 3 件」の件数 */
    const val FLOOR_COUNT = 3

    /** 小さい画面で 3 件分が入らない時の写真の絶対的な下限(画面の高さの割合) */
    const val ABSOLUTE_PHOTO_FRACTION = 0.25f

    /** 上のやり直す行(上余白 16 + ボタン + 写真との間隔 16)の高さ */
    fun retakeRowHeight(settingDp: Float): Float = 16f + settingDp + 16f

    /** 写真の下の間隔 16 と最下部の余白 16 */
    const val LIST_GAPS = 32f

    /** 件数 n の一覧が、設定の高さ・間隔 16 のままで必要とする高さ */
    fun naturalHeight(count: Int, settingDp: Float): Float =
        if (count <= 0) 0f else count * settingDp + (count - 1) * NORMAL_SPACING

    /**
     * 一覧に使える最大の高さ。3 件分の一覧の高さを上限とし(= 写真は 3 件の時より小さくならない)、
     * 小さい画面では写真の絶対的な下限を守る分だけさらに小さくする。
     */
    fun availableHeight(totalHeight: Float, settingDp: Float): Float {
        val byFloor = naturalHeight(FLOOR_COUNT, settingDp)
        val bySpace = totalHeight - retakeRowHeight(settingDp) - LIST_GAPS - totalHeight * ABSOLUTE_PHOTO_FRACTION
        return max(0f, min(byFloor, bySpace))
    }

    /**
     * 入るなら設定どおり。入らなければまず高さを(最小 56 まで)縮め、それでも入らなければ間隔を 8 に詰め、
     * それでも入らなければスクロール(最後のボタンを半分切って見せる)。
     */
    fun layout(available: Float, count: Int, settingDp: Float): PreviewListLayout {
        val natural = naturalHeight(count, settingDp)
        if (count <= 0 || natural <= available) {
            return PreviewListLayout(settingDp, NORMAL_SPACING, natural, false)
        }
        val minH = min(MIN_BUTTON, settingDp)
        val h16 = (available - (count - 1) * NORMAL_SPACING) / count
        if (h16 >= minH) return PreviewListLayout(min(settingDp, h16), NORMAL_SPACING, available, false)
        val h8 = (available - (count - 1) * COMPACT_SPACING) / count
        if (h8 >= minH) return PreviewListLayout(min(settingDp, h8), COMPACT_SPACING, available, false)
        // スクロール: 全部は入らないので、最後に見えるボタンが半分切れる高さにする
        val h = minH
        val step = h + COMPACT_SPACING
        val k = floor((available - h / 2f) / step).toInt()
        if (k < 1) return PreviewListLayout(h, COMPACT_SPACING, available, true)
        return PreviewListLayout(h, COMPACT_SPACING, k * step + h / 2f, true)
    }
}
