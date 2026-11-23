package app.kagecam

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 怖さの型（プリセット）。色は「地と影のペア」だけが違い、形は変えない
 * （docs/ART_DIRECTION.md 2.0 ／ docs/UI_CATALOG.md #7）。
 * 色値は Palette と同値に保つこと。ここでは単体テスト（JVM）で Color スタブに
 * 触れないよう、ARGB のリテラルで持つ。
 */
enum class ScaryPreset(
    val label: String,
    val background: Int,
    val silhouette: Int,
    val vignette: Float,
) {
    /** 黒地に白い影。怖さの本命。 */
    YUREI("ゆうれい", 0xFF070708.toInt(), 0xFFEDEAE4.toInt(), 0.5f),

    /** 黒に赤い影。 */
    AKAKAGE("あか影", 0xFF0B0505.toInt(), 0xFF9E1B1B.toInt(), 0.5f),

    /** 紙に墨の影。v0.1 の世界（比較と逃げ道）。 */
    KAGEE("影絵", 0xFFFAF7F1.toInt(), 0xFF1C1917.toInt(), 0f),
}

/**
 * 影マスク（ShadowExtractor.alphaMask）に怖さの効果を重ね、背景まで
 * 焼き込んだ完成画像を作る。効果は「気配」を作る3つだけ：
 * 残像（ずれ）、引き伸ばし（縦の長さ）、ノイズ（粒）。
 */
object ShadowEffects {

    /**
     * 完成画像を作る。重いので背景スレッドで呼ぶこと。
     * @param ghost 残像 0..1（ずらした薄い影を2枚重ねる）
     * @param stretch 引き伸ばし 1..1.7（中心を軸に縦へ伸ばす）
     * @param noise ノイズ 0..1（粒の量。seed 固定で、同じ設定なら同じ粒になる）
     */
    fun compose(
        prepared: ShadowExtractor.Prepared,
        threshold: Int,
        preset: ScaryPreset,
        ghost: Float,
        stretch: Float,
        noise: Float,
    ): Bitmap {
        val w = prepared.width
        val h = prepared.height
        var mask = ShadowExtractor.alphaMask(prepared, threshold)
        if (stretch > 1.001f) mask = stretchMask(mask, w, h, stretch)
        if (ghost > 0.001f) {
            mask = ghostMask(mask, w, h, (ghost * 28).roundToInt(), (ghost * 12).roundToInt())
        }

        val bgR = Color.red(preset.background)
        val bgG = Color.green(preset.background)
        val bgB = Color.blue(preset.background)
        val siR = Color.red(preset.silhouette)
        val siG = Color.green(preset.silhouette)
        val siB = Color.blue(preset.silhouette)

        val random = Random(1234)
        val noiseProbability = noise.coerceIn(0f, 1f) * 0.30f
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f

        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val a = mask[i] / 255f
                var r = (bgR * (1 - a) + siR * a).roundToInt()
                var g = (bgG * (1 - a) + siG * a).roundToInt()
                var b = (bgB * (1 - a) + siB * a).roundToInt()

                // 周辺減光（闇に沈む縁）
                if (preset.vignette > 0f) {
                    val dxn = if (cx > 0) (x - cx) / cx else 0f
                    val dyn = if (cy > 0) (y - cy) / cy else 0f
                    val d = ((dxn * dxn + dyn * dyn) / 2f).coerceIn(0f, 1f)
                    val factor = 1f - preset.vignette * d
                    r = (r * factor).roundToInt()
                    g = (g * factor).roundToInt()
                    b = (b * factor).roundToInt()
                }

                // ノイズ（粒）。毎画素乱数を引くので seed 固定なら再現性がある
                val roll = random.nextFloat()
                if (roll < noiseProbability) {
                    val grain = random.nextInt(256)
                    r = (r * 0.6f + grain * 0.4f).roundToInt()
                    g = (g * 0.6f + grain * 0.4f).roundToInt()
                    b = (b * 0.6f + grain * 0.4f).roundToInt()
                }

                out[i] = Color.rgb(
                    r.coerceIn(0, 255),
                    g.coerceIn(0, 255),
                    b.coerceIn(0, 255),
                )
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, w, 0, 0, w, h)
        return bitmap
    }

    /** 中心を軸に縦へ引き伸ばしたマスクを返す（純粋関数・テスト対象）。 */
    fun stretchMask(mask: IntArray, w: Int, h: Int, stretch: Float): IntArray {
        if (stretch <= 1.001f || h <= 1) return mask.copyOf()
        val out = IntArray(mask.size)
        val cy = (h - 1) / 2f
        for (y in 0 until h) {
            // 丸めは「偶数へ」（kotlin.math.round）：roundToInt（0.5切り上げ）だと
            // 中心の上下で広がり方がずれ、引き伸ばしが非対称になるため
            // （2026-11-23 の単体テスト初回実行で発覚し、テストの仕様どおりに修正）
            val sy = kotlin.math.round(((y - cy) / stretch + cy).toDouble()).toInt().coerceIn(0, h - 1)
            for (x in 0 until w) {
                out[y * w + x] = mask[sy * w + x]
            }
        }
        return out
    }

    /**
     * 残像を重ねたマスクを返す（純粋関数・テスト対象）。
     * 本体＋ずらした薄い影2枚を max 合成する（足し算にすると形が太って崩れるため）。
     */
    fun ghostMask(mask: IntArray, w: Int, h: Int, dx: Int, dy: Int): IntArray {
        if (dx == 0 && dy == 0) return mask.copyOf()
        val out = IntArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = mask[y * w + x]
                a = maxOf(a, sampleShifted(mask, w, h, x - dx, y - dy) * 45 / 100)
                a = maxOf(a, sampleShifted(mask, w, h, x - dx * 2, y - dy * 2) * 22 / 100)
                out[y * w + x] = a
            }
        }
        return out
    }

    private fun sampleShifted(mask: IntArray, w: Int, h: Int, x: Int, y: Int): Int {
        if (x !in 0 until w || y !in 0 until h) return 0
        return mask[y * w + x]
    }
}
