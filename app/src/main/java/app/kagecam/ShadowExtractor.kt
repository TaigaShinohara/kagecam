package app.kagecam

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/**
 * 影抽出のコア（MVP方式：明暗の切り分け）。
 *
 * 手順：
 * 1. 輝度（luminance）を取り、3x3ボックスぼかしで輪郭とノイズをなめらかにする
 * 2. 大津法で「影／影でない」の境目（しきい値）の初期値を自動で決める
 * 3. ユーザーのつまみでしきい値をずらせる。境目の前後では透明度をなだらかに変え、
 *    柔らかい影の縁を残す
 * 4. 孤立した点（ノイズ）を取り除く
 *
 * AIセグメンテーションは後付け候補。ここには入れない（docs/ART_DIRECTION.md 参照）。
 */
object ShadowExtractor {

    /** 抽出に使う墨色（Palette.Ink #1C1917 と同値）。単体テスト（JVM）で
     *  android.graphics.Color のスタブに触れないよう、成分を定数で持つ。 */
    private const val INK_R = 28
    private const val INK_G = 25
    private const val INK_B = 23

    /** 縁のなだらかさ（しきい値の前後、この輝度幅で透明度を変える） */
    private const val EDGE_SOFTNESS = 14

    data class Prepared(
        val width: Int,
        val height: Int,
        /** ぼかし済み輝度 0..255、行優先 */
        val luminance: IntArray,
        /** 大津法で決めた初期しきい値 */
        val otsuThreshold: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Prepared) return false
            return width == other.width && height == other.height &&
                otsuThreshold == other.otsuThreshold && luminance.contentEquals(other.luminance)
        }

        override fun hashCode(): Int {
            var r = width * 31 + height
            r = r * 31 + otsuThreshold
            r = r * 31 + luminance.contentHashCode()
            return r
        }
    }

    /** 写真 Bitmap から抽出の下ごしらえ（輝度＋ぼかし＋大津）をする。重いので背景スレッドで呼ぶこと。 */
    fun prepare(source: Bitmap): Prepared {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            // ITU-R BT.709 輝度
            lum[i] = (0.2126f * Color.red(p) + 0.7152f * Color.green(p) + 0.0722f * Color.blue(p))
                .roundToInt().coerceIn(0, 255)
        }
        val blurred = boxBlur(lum, w, h)
        return Prepared(w, h, blurred, otsuThreshold(blurred))
    }

    /** 大津法：クラス間分散が最大になるしきい値を返す。純粋関数（テスト対象）。 */
    fun otsuThreshold(luminance: IntArray): Int {
        val hist = IntArray(256)
        for (v in luminance) hist[v.coerceIn(0, 255)]++
        val total = luminance.size
        if (total == 0) return 128
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * hist[i].toDouble()

        var sumBg = 0.0
        var weightBg = 0
        var bestVariance = -1.0
        var best = 128
        for (t in 0..255) {
            weightBg += hist[t]
            if (weightBg == 0) continue
            val weightFg = total - weightBg
            if (weightFg == 0) break
            sumBg += t * hist[t].toDouble()
            val meanBg = sumBg / weightBg
            val meanFg = (sumAll - sumBg) / weightFg
            val diff = meanBg - meanFg
            val variance = weightBg.toDouble() * weightFg.toDouble() * diff * diff
            if (variance > bestVariance) {
                bestVariance = variance
                best = t
            }
        }
        if (bestVariance < 0.0) {
            // 真っ平な画像（輝度が1種類だけ）では分散が決まらない。
            // 固定値ではなく、その画像自身の輝度（最頻値）を境目にする
            var peak = 128
            var peakCount = -1
            for (i in 0..255) {
                if (hist[i] > peakCount) {
                    peakCount = hist[i]
                    peak = i
                }
            }
            return peak
        }
        return best
    }

    /**
     * しきい値で切り分けた「影の濃さ」マスク（0..255）を返す。純粋計算（テスト対象）。
     * 輝度がしきい値以下＝影（255）、以上＝0。縁は EDGE_SOFTNESS の幅でなだらか。
     * 色付けや効果は ShadowEffects がこのマスクに対して行う。
     */
    fun alphaMask(prepared: Prepared, threshold: Int): IntArray {
        val w = prepared.width
        val h = prepared.height
        val lum = prepared.luminance
        val mask = BooleanArray(w * h)
        for (i in lum.indices) mask[i] = lum[i] <= threshold
        cleanIsolated(mask, w, h)

        val lo = threshold - EDGE_SOFTNESS
        val hi = threshold + EDGE_SOFTNESS
        val alpha = IntArray(w * h)
        for (i in lum.indices) {
            alpha[i] = when {
                !mask[i] && lum[i] > hi -> 0
                lum[i] <= lo -> 255
                lum[i] >= hi -> if (mask[i]) 255 else 0
                else -> ((hi - lum[i]).toFloat() / (hi - lo) * 255).roundToInt().coerceIn(0, 255)
            }
        }
        return alpha
    }

    /** 影だけの透過 Bitmap（墨色）。効果なしの素の抽出結果が要る場合用。 */
    fun render(prepared: Prepared, threshold: Int): Bitmap {
        val w = prepared.width
        val h = prepared.height
        val alpha = alphaMask(prepared, threshold)
        val out = IntArray(w * h)
        for (i in alpha.indices) {
            out[i] = if (alpha[i] <= 0) Color.TRANSPARENT
            else Color.argb(alpha[i], INK_R, INK_G, INK_B)
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, w, 0, 0, w, h)
        return bitmap
    }

    /** 3x3 ボックスぼかし（1回）。端はクランプ。 */
    private fun boxBlur(src: IntArray, w: Int, h: Int): IntArray {
        val dst = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var count = 0
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        sum += src[yy * w + xx]
                        count++
                    }
                }
                dst[y * w + x] = sum / count
            }
        }
        return dst
    }

    /** 周囲（8近傍）に影画素が1つも無い孤立点を落とす。 */
    private fun cleanIsolated(mask: BooleanArray, w: Int, h: Int) {
        val remove = ArrayList<Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!mask[idx]) continue
                var neighbours = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val xx = x + dx
                        val yy = y + dy
                        if (xx in 0 until w && yy in 0 until h && mask[yy * w + xx]) neighbours++
                    }
                }
                if (neighbours == 0) remove.add(idx)
            }
        }
        for (idx in remove) mask[idx] = false
    }
}
