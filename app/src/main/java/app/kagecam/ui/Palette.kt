package app.kagecam.ui

import androidx.compose.ui.graphics.Color

/**
 * 色の正本（docs/ART_DIRECTION.md 2.1 の「色の性格」を値で固定したもの）。
 * v0.4 から操作UIも「夜」の世界：背景は闇、文字は骨の白、差し色は血の赤。
 * 怖さは色・明暗・余白だけで作り、装飾やフォントでは作らない。
 * 新しい色を足す前に、ここの役割で表現できないか先に確かめること。
 */
object Palette {
    /** 夜：アプリ全体の背景 */
    val Night = Color(0xFF0D0C0E)

    /** 夜のカード：棚や詳細の面 */
    val NightCard = Color(0xFF1A171C)

    /** 骨の白：本文・見出し */
    val Bone = Color(0xFFEDEAE4)

    /** 灰：日付などの控えめな文字 */
    val Ash = Color(0xFF9A938A)

    /** 夜の線：区切り */
    val NightLine = Color(0xFF2E2A33)

    /** 血の赤（低彩度）：主要ボタンと選択中チップだけ */
    val Blood = Color(0xFF9E1B1B)

    // ---- 旧トークン名の別名（役割は上記へ移行済み。順次置き換える） ----

    /** @see Night */
    val Paper = Night

    /** @see Bone */
    val Ink = Bone

    /** @see NightCard */
    val Card = NightCard

    /** @see Ash */
    val Muted = Ash

    /** @see NightLine */
    val Line = NightLine

    // ---- 作品の中の色（プリセット。ShadowEffects と同値に保つ） ----

    /** ゆうれいの地：ほぼ黒 */
    val Abyss = Color(0xFF070708)

    /** ゆうれいの影：白い影 */
    val Ghost = Color(0xFFEDEAE4)

    /** あか影の地：黒に赤み */
    val DarkGround = Color(0xFF0B0505)
}
