package app.kagecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowExtractorTest {

    @Test
    fun otsu_separatesDarkAndBrightClusters() {
        // 半分が暗い影（40前後）、半分が明るい地面（200前後）
        val lum = IntArray(1000) { i -> if (i % 2 == 0) 40 + (i % 7) else 200 - (i % 7) }
        val t = ShadowExtractor.otsuThreshold(lum)
        assertTrue("threshold should sit between clusters, was $t", t in 60..180)
    }

    @Test
    fun otsu_handlesUniformImage() {
        val lum = IntArray(100) { 128 }
        val t = ShadowExtractor.otsuThreshold(lum)
        assertTrue(t in 0..255)
    }

    @Test
    fun otsu_uniformReturnsThatBrightness() {
        // 真っ平な画像では固定値でなく、その明るさ自身が境目になる
        assertEquals(200, ShadowExtractor.otsuThreshold(IntArray(50) { 200 }))
        assertEquals(30, ShadowExtractor.otsuThreshold(IntArray(50) { 30 }))
    }

    @Test
    fun otsu_handlesEmpty() {
        assertEquals(128, ShadowExtractor.otsuThreshold(IntArray(0)))
    }
}
