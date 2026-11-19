package app.kagecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowEffectsTest {

    @Test
    fun alphaMask_marksDarkAsShadow() {
        // 左半分が暗い（影）、右半分が明るい輝度マップ
        val w = 10
        val h = 10
        val lum = IntArray(w * h) { i -> if (i % w < 5) 30 else 220 }
        val prepared = ShadowExtractor.Prepared(w, h, lum, 125)
        val alpha = ShadowExtractor.alphaMask(prepared, 125)
        assertEquals(255, alpha[2 * w + 2]) // 暗い側の中心は影
        assertEquals(0, alpha[2 * w + 8]) // 明るい側は影でない
    }

    @Test
    fun stretchMask_identityAtOne() {
        val mask = IntArray(25) { it * 10 % 256 }
        val out = ShadowEffects.stretchMask(mask, 5, 5, 1f)
        assertTrue(mask.contentEquals(out))
    }

    @Test
    fun stretchMask_pullsFromCloserToCenter() {
        // 1列だけのマスク：行0に影。stretch 2倍では行1が「行0.5付近＝行1寄り」…
        // 中心行(2)より上では、出力行1は入力行 round((1-2)/2+2)=round(1.5)=2 を参照する
        val w = 1
        val h = 5
        val mask = intArrayOf(0, 0, 200, 0, 0) // 中心行だけ影
        val out = ShadowEffects.stretchMask(mask, w, h, 2f)
        // 伸ばすと中心の影が上下に広がる：行1と行3にも影が出る
        assertEquals(200, out[1])
        assertEquals(200, out[3])
    }

    @Test
    fun ghostMask_addsFainterShiftedCopy() {
        val w = 10
        val h = 3
        val mask = IntArray(w * h)
        mask[1 * w + 2] = 200 // (2,1) に影
        val out = ShadowEffects.ghostMask(mask, w, h, dx = 3, dy = 0)
        assertEquals(200, out[1 * w + 2]) // 本体はそのまま
        assertEquals(90, out[1 * w + 5]) // 1枚目の残像は 200*45/100
        assertEquals(44, out[1 * w + 8]) // 2枚目の残像は 200*22/100
    }

    @Test
    fun ghostMask_noShiftIsIdentity() {
        val mask = IntArray(9) { it * 20 }
        val out = ShadowEffects.ghostMask(mask, 3, 3, 0, 0)
        assertTrue(mask.contentEquals(out))
    }
}
