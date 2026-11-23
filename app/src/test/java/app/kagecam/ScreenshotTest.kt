package app.kagecam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 実機なし検証の標準（2026-11-23 全プロジェクト標準化）：
 * 主要画面のスクリーンショットテスト（Robolectric＋Roborazzi）。
 * レイアウト崩壊（高さ0で真っ白等）・はみ出し・見切れ・遷移の破損を
 * JVM上で捕まえるのが目的で、身体感覚の確認（実機待ち行列）の代わりにはしない。
 *
 * ゴールデン画像（src/test/screenshots/）は recordRoborazziDebug で作り直し、
 * 通常の交付前チェックは verifyRoborazziDebug で照合する。
 * テーマは本体と共通の KageCamTheme を使う（見た目の正本を1つにするため）。
 * カバー範囲：起動直後の画面（棚）／オンボーディング入口／編集画面（怖くする）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        // 端末の「動きを減らす」と同じ状態にして常時アニメーションを止め、
        // 決定的な見た目で撮る（オンボーディング自身の作法に合わせた措置）。
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(
            app.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
    }

    /** 起動直後の画面：棚（作品がまだ無い状態）。 */
    @Test
    fun shelf_empty() {
        val app = RuntimeEnvironment.getApplication()
        composeRule.setContent {
            KageCamTheme {
                ShelfScreen(
                    items = emptyList(),
                    store = ShadowStore(app),
                    onTake = {},
                    onPick = {},
                    onOpen = {},
                    onShowGuide = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage(golden("shelf_empty"))
    }

    /** オンボーディングの入口（1ページ目＝フック）。 */
    @Test
    fun onboarding_firstPage() {
        composeRule.setContent {
            KageCamTheme { OnboardingScreen(onDone = {}) }
        }
        composeRule.onRoot().captureRoboImage(golden("onboarding_firstPage"))
    }

    /** 編集画面「怖くする」：初期プリセット（ゆうれい）で結果が出た状態。 */
    @Test
    fun extract_defaultPreset() {
        composeRule.setContent {
            KageCamTheme {
                ExtractScreen(
                    source = samplePhoto(),
                    initial = EditorInitial(),
                    sessionKey = 1,
                    autoRecord = false,
                    hasExistingAudio = false,
                    onSave = { _, _, _, _ -> },
                    onBack = {},
                )
            }
        }
        // 抽出は別スレッドで走るので、結果が出て案内文が消えるまで待つ
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("何かが、現れようとしている…")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(golden("extract_defaultPreset"))
    }

    /** ゴールデン画像の置き場所（src配下に固定し、リポジトリで管理する）。 */
    private fun golden(name: String): File =
        File("src/test/screenshots/app.kagecam.ScreenshotTest.$name.png")

    /** 影の材料になる、明るい背景＋暗い人影＋地面の影のテスト写真。 */
    private fun samplePhoto(): Bitmap {
        val bmp = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(214, 208, 198)
        canvas.drawRect(0f, 0f, 360f, 480f, paint)
        paint.color = Color.rgb(168, 158, 144)
        canvas.drawRect(0f, 340f, 360f, 480f, paint)
        paint.color = Color.rgb(38, 36, 40)
        canvas.drawCircle(180f, 150f, 34f, paint)
        canvas.drawRect(146f, 180f, 214f, 330f, paint)
        paint.color = Color.rgb(74, 70, 66)
        canvas.drawOval(90f, 336f, 300f, 372f, paint)
        return bmp
    }
}
