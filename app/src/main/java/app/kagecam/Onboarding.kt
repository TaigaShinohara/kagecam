package app.kagecam

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Path as AndroidPath
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.kagecam.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** オンボーディングの版。内容を大きく変えたら上げると、既存ユーザーにも再表示される。 */
internal const val ONBOARDING_VERSION = 1

private const val PREFS_NAME = "kagecam_onboarding"

/** 選択肢。マッチの映し返しは文法が崩れないよう、選択肢ごとに言い回しを用意する。 */
private enum class ObChoice(
    val id: String,
    val label: String,
    val matchTitle: String,
    val matchRoot: String,
) {
    SCARY(
        "scary",
        "ぞっとする、怖い影がほしい",
        "怖い影を集めたいあなたには、KageCamがぴったり",
        "怖さの型が、3つある",
    ),
    QUIET(
        "quiet",
        "静かできれいな影絵がいい",
        "静かな影絵がほしいあなたには、KageCamがぴったり",
        "紙に墨の型「影絵」がある",
    ),
    SOUND(
        "sound",
        "その場の音も、一緒に残したい",
        "音も一緒に残したいあなたには、KageCamがぴったり",
        "撮影すると、その場の音が自動で付く",
    ),
    UNKNOWN(
        "unknown",
        "まだわからない",
        "まだ分からなくて、大丈夫",
        "まずは1つ、剥がしてみよう",
    ),
}

/** 選択への反応の褒め言葉（静かな語調。直前の再使用は避けて回す）。 */
private val CHOICE_PRAISE = listOf("いい目をしている", "その影、きっと似合う", "影を見る目だ")

/** 試し操作の結果への褒め言葉。 */
private val RESULT_PRAISE = listOf("いい影だ", "気配が、残った", "静かに、怖い")

private fun pickPraise(prefs: SharedPreferences, key: String, lines: List<String>): String {
    val last = prefs.getInt(key, -1)
    var idx = Random.nextInt(lines.size)
    if (lines.size > 1) {
        while (idx == last) idx = Random.nextInt(lines.size)
    }
    prefs.edit().putInt(key, idx).apply()
    return lines[idx]
}

private fun hapticKind(strong: Boolean): Int =
    if (strong && Build.VERSION.SDK_INT >= 30) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }

/**
 * 初回体験（docs/ONBOARDING_DESIGN.md の最終仕様）。
 * 1ページ1主張、選択→マッチ、価値は選択に応じて並べ替え、本物の抽出が動く試し操作つき。
 * 動きはアート方針2.5のオンボーディング例外の範囲だけ（呼吸とグラデの移動のみ）。
 */
@Composable
internal fun OnboardingScreen(onDone: (destination: String) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    // 端末の「動きを減らす」設定では常時アニメーションを止め、フェードも短くする
    val reduceMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    // 権限ページは「まだ許可されていない時だけ」。セッション中は固定する
    // （途中で許可されてもページ構成がずれないように）
    val needPermissionPage = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
    }

    var choice by remember { mutableStateOf<ObChoice?>(null) }
    var reactingId by remember { mutableStateOf<String?>(null) }
    var choicePraise by remember { mutableStateOf<String?>(null) }
    var advancing by remember { mutableStateOf(false) }

    // ---- 試し操作（本物の抽出が動く） ----
    var samplePrepared by remember { mutableStateOf<ShadowExtractor.Prepared?>(null) }
    var trialThreshold by remember { mutableStateOf(128f) }
    var trialResult by remember { mutableStateOf<Bitmap?>(null) }
    var resultPraise by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val sample = withContext(Dispatchers.Default) { makeSampleBitmap() }
        val prepared = withContext(Dispatchers.Default) { ShadowExtractor.prepare(sample) }
        samplePrepared = prepared
        trialThreshold = prepared.otsuThreshold.toFloat()
    }
    LaunchedEffect(samplePrepared, trialThreshold) {
        val p = samplePrepared ?: return@LaunchedEffect
        delay(60)
        trialResult = withContext(Dispatchers.Default) {
            ShadowEffects.compose(
                prepared = p,
                threshold = trialThreshold.toInt().coerceIn(0, 255),
                preset = ScaryPreset.YUREI,
                ghost = 0.4f,
                stretch = 1.2f,
                noise = 0.3f,
            )
        }
    }

    val valueOrder = if (choice == ObChoice.SOUND) {
        listOf("value_sound", "value_finish", "value_local")
    } else {
        listOf("value_finish", "value_sound", "value_local")
    }
    val pages = buildList {
        add("hook"); add("choice"); add("match"); add("identity")
        addAll(valueOrder)
        add("step1"); add("step2"); add("step3"); add("trial"); add("result")
        if (needPermissionPage) add("permission")
        add("connect")
    }
    val pagerState = rememberPagerState(initialPage = 0) { pages.size }
    val currentPage = pagerState.currentPage
    val currentId = pages.getOrNull(currentPage)

    fun goNext() {
        if (currentPage < pages.lastIndex) {
            scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { goNext() }

    fun onChoiceTap(picked: ObChoice) {
        if (advancing) return
        advancing = true
        choice = picked
        reactingId = picked.id
        prefs.edit().putString("choice", picked.id).apply()
        choicePraise = pickPraise(prefs, "praise_choice_last", CHOICE_PRAISE)
        view.performHapticFeedback(hapticKind(strong = true))
        scope.launch {
            delay(320)
            goNext()
            reactingId = null
            advancing = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Palette.Night)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val pageId = pages[index]
            val visible = index == currentPage
            when (pageId) {
                "hook" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = { goNext() },
                    headline = "影だけが、残る。",
                    sub = null,
                    visual = {
                        ShadowFigure(
                            modifier = Modifier.size(220.dp),
                            color = Palette.Bone,
                            breathing = true,
                            reduceMotion = reduceMotion,
                        )
                    },
                )

                "choice" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = null,
                    headline = "どんな影を、集めたい？",
                    sub = null,
                    visual = {
                        ShadowFigure(
                            modifier = Modifier.size(120.dp),
                            color = Palette.Bone,
                            breathing = false,
                            reduceMotion = reduceMotion,
                        )
                    },
                    lower = {
                        Spacer(Modifier.height(20.dp))
                        ObChoice.values().forEach { option ->
                            val isReacting = reactingId == option.id
                            val bg by animateFloatAsState(
                                targetValue = if (isReacting) 0.4f else 0f,
                                animationSpec = tween(durationMillis = 300),
                                label = "choice-react",
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Palette.Blood.copy(alpha = bg)
                                            .compositeOverNightCard(),
                                    )
                                    .clickable { onChoiceTap(option) }
                                    .padding(horizontal = 16.dp, vertical = 15.dp),
                            ) {
                                Text(option.label, fontSize = 16.sp, color = Palette.Ink)
                            }
                        }
                        Box(modifier = Modifier.height(30.dp)) {
                            val praise = choicePraise
                            if (praise != null && reactingId != null) {
                                Text(praise, fontSize = 14.sp, color = Palette.Bone)
                            }
                        }
                    },
                )

                "match" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = { goNext() },
                    headline = (choice ?: ObChoice.UNKNOWN).matchTitle,
                    sub = (choice ?: ObChoice.UNKNOWN).matchRoot,
                    visual = {
                        ShadowFigure(
                            modifier = Modifier.size(200.dp),
                            color = Palette.Bone,
                            breathing = false,
                            reduceMotion = reduceMotion,
                        )
                    },
                )

                "identity" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = { goNext() },
                    headline = "生成しない。実在した影を、剥がす。",
                    sub = "あなたの写真に、本当にあった影だけ",
                    visual = { PeelVisual(modifier = Modifier.size(220.dp)) },
                )

                "value_finish" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = { goNext() },
                    headline = "怖さは、自分で仕上げる",
                    sub = "型は3つ、つまみは4本",
                    visual = { ThreeTypesVisual(modifier = Modifier.fillMaxWidth().height(190.dp)) },
                )

                "value_sound" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = { goNext() },
                    headline = "その場の音も、一緒に残る",
                    sub = "撮影すると、自動で6秒",
                    visual = { WaveVisual(modifier = Modifier.fillMaxWidth().height(150.dp)) },
                )

                "value_local" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = { goNext() },
                    headline = "写真も音も、端末から出ない",
                    sub = "アカウントなし、送信なし",
                    visual = { ShelfVisual(modifier = Modifier.size(210.dp)) },
                )

                "step1" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = { goNext() },
                    headline = "1. 撮る",
                    sub = "カメラでも、写真を選んでも",
                    visual = { CameraVisual(modifier = Modifier.size(190.dp)) },
                )

                "step2" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = { goNext() },
                    headline = "2. 怖くする",
                    sub = "まず型、つまみはそのあと",
                    visual = { SlidersVisual(modifier = Modifier.size(190.dp)) },
                )

                "step3" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = { goNext() },
                    headline = "3. 棚にしまう",
                    sub = "たまった影は、あとで音も聴ける",
                    visual = { ShelfVisual(modifier = Modifier.size(210.dp)) },
                )

                "trial" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = null,
                    headline = "試しに、影を残してみる",
                    sub = "本物の抽出です。つまみを動かすと、その場で変わる",
                    visual = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(ScaryPreset.YUREI.background)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val r = trialResult
                            if (r != null) {
                                Image(
                                    bitmap = r.asImageBitmap(),
                                    contentDescription = "試しに抽出した影",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(
                                    "何かが、現れようとしている…",
                                    color = Palette.Muted,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    },
                    lower = {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "影の残り具合",
                                fontSize = 13.sp,
                                color = Palette.Muted,
                                modifier = Modifier.width(96.dp),
                            )
                            Slider(
                                value = trialThreshold,
                                onValueChange = { trialThreshold = it },
                                valueRange = 0f..255f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Palette.Blood,
                                    activeTrackColor = Palette.Blood,
                                ),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                resultPraise = pickPraise(prefs, "praise_result_last", RESULT_PRAISE)
                                view.performHapticFeedback(hapticKind(strong = true))
                                goNext()
                            },
                            enabled = trialResult != null,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Blood),
                        ) { Text("これで残す", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    },
                )

                "result" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = { goNext() },
                    headline = "影が、残った",
                    sub = "本番は、あなたの写真で",
                    visual = {
                        Box(
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(ScaryPreset.YUREI.background)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val r = trialResult
                            if (r != null) {
                                Image(
                                    bitmap = r.asImageBitmap(),
                                    contentDescription = "残った影",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    },
                    lower = {
                        val praise = resultPraise
                        if (praise != null) {
                            Spacer(Modifier.height(14.dp))
                            Text(praise, fontSize = 16.sp, color = Palette.Bone)
                        }
                    },
                )

                "permission" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = null,
                    headline = "音を残すために、マイクを使います",
                    sub = "音は端末の中だけ。外には出ません",
                    visual = { MicVisual(modifier = Modifier.size(170.dp)) },
                    lower = {
                        Spacer(Modifier.height(22.dp))
                        Button(
                            onClick = {
                                view.performHapticFeedback(hapticKind(strong = false))
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Blood),
                        ) { Text("許可する", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { goNext() },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(999.dp),
                        ) { Text("今はしない", fontSize = 15.sp, color = Palette.Ink) }
                    },
                )

                "connect" -> ObScaffold(
                    visible = visible,
                    reduceMotion = reduceMotion,
                    onTapNext = null,
                    headline = "では、最初の影を撮りに行こう",
                    sub = null,
                    visual = {
                        ShadowFigure(
                            modifier = Modifier.size(170.dp),
                            color = Palette.Bone,
                            breathing = true,
                            reduceMotion = reduceMotion,
                        )
                    },
                    lower = {
                        Spacer(Modifier.height(22.dp))
                        Button(
                            onClick = {
                                view.performHapticFeedback(hapticKind(strong = true))
                                onDone("camera")
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Blood),
                        ) { Text("撮る", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onDone("shelf") },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(999.dp),
                        ) { Text("棚を見る", fontSize = 15.sp, color = Palette.Ink) }
                    },
                )
            }
        }

        if (currentId != "connect") {
            TextButton(
                onClick = { onDone("shelf") },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 30.dp, end = 10.dp),
            ) { Text("スキップ", fontSize = 14.sp, color = Palette.Muted) }
        }

        WindowDots(
            total = pages.size,
            current = currentPage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp),
        )
    }
}

/** 選択の反応でカード地の上に血の赤を薄く重ねた色を作る（地は夜のカード固定）。 */
private fun Color.compositeOverNightCard(): Color {
    val base = Palette.NightCard
    return Color(
        red = this.red * this.alpha + base.red * (1f - this.alpha),
        green = this.green * this.alpha + base.green * (1f - this.alpha),
        blue = this.blue * this.alpha + base.blue * (1f - this.alpha),
        alpha = 1f,
    )
}

/**
 * ページの共通の型：視覚の主役（上）→ 主張（見出し）→ 補足 → 任意の下部。
 * 主役はページが主役になった瞬間に遅いフェードで立つ（方針2.5の例外範囲）。
 * 表示ページは画面タップでも次へ進める。
 */
@Composable
private fun ObScaffold(
    visible: Boolean,
    reduceMotion: Boolean,
    onTapNext: (() -> Unit)?,
    headline: String,
    sub: String?,
    visual: @Composable () -> Unit,
    lower: @Composable (() -> Unit)? = null,
) {
    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (reduceMotion) 150 else 900),
        label = "ob-page-fade",
    )
    val tapModifier = if (onTapNext != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onTapNext() }
    } else {
        Modifier
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(tapModifier)
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(64.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .alpha(fade),
            contentAlignment = Alignment.Center,
        ) { visual() }
        Text(
            headline,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = Palette.Ink,
            modifier = Modifier.semantics { heading() },
        )
        if (sub != null) {
            Spacer(Modifier.height(6.dp))
            Text(sub, fontSize = 14.sp, color = Palette.Muted)
        }
        if (lower != null) lower()
        Spacer(Modifier.height(76.dp))
    }
}

/**
 * 窓ドット：最大5つ表示、1ページごとに窓は必ず1つだけ動く。圧縮ドットは使わない。
 * 読み上げ用に「全Nページ中Mページ目」のラベルを持つ。
 */
@Composable
private fun WindowDots(total: Int, current: Int, modifier: Modifier = Modifier) {
    val maxDots = 5
    val start = (current - 2).coerceIn(0, maxOf(0, total - maxDots))
    val end = minOf(total, start + maxDots)
    Row(
        modifier = modifier.semantics {
            contentDescription = "全${total}ページ中 ${current + 1}ページ目"
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in start until end) {
            Box(
                modifier = Modifier
                    .size(if (i == current) 10.dp else 7.dp)
                    .clip(CircleShape)
                    .background(if (i == current) Palette.Bone else Palette.NightLine),
            )
        }
    }
}

// ---------- 視覚の主役（すべてコード描画。画像素材は使わない） ----------

/** 立ち姿の影1体を描く。breathing の時は呼吸のようにゆっくり伸縮する（方針2.5の例外）。 */
@Composable
private fun ShadowFigure(
    modifier: Modifier,
    color: Color,
    breathing: Boolean,
    reduceMotion: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "breath")
    val breathScale by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath-scale",
    )
    val scale = if (breathing && !reduceMotion) breathScale else 1f
    Canvas(
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        drawOval(
            color = color.copy(alpha = 0.22f),
            topLeft = Offset(size.width * 0.18f, size.height * 0.88f),
            size = Size(size.width * 0.64f, size.height * 0.055f),
        )
        drawFigure(
            cx = size.width * 0.5f,
            topY = size.height * 0.06f,
            height = size.height * 0.82f,
            color = color,
        )
    }
}

/** 人の立ち姿（頭＋胴）を単純な形で描く。 */
private fun DrawScope.drawFigure(cx: Float, topY: Float, height: Float, color: Color) {
    val headR = height * 0.115f
    drawCircle(color = color, radius = headR, center = Offset(cx, topY + headR))
    val bodyW = height * 0.27f
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - bodyW / 2f, topY + headR * 2f + height * 0.035f),
        size = Size(bodyW, height * 0.70f),
        cornerRadius = CornerRadius(bodyW / 2f, bodyW / 2f),
    )
}

/** 写真（明るい面）から影だけが離れて残る図。 */
@Composable
private fun PeelVisual(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 写真の面（明るい灰）
        drawRoundRect(
            color = Color(0xFFB9B2A6),
            topLeft = Offset(w * 0.04f, h * 0.14f),
            size = Size(w * 0.52f, h * 0.72f),
            cornerRadius = CornerRadius(10f, 10f),
        )
        // 写真の中の薄い影
        drawFigure(
            cx = w * 0.30f,
            topY = h * 0.26f,
            height = h * 0.5f,
            color = Color(0xFF4A453E),
        )
        // 剥がれて残った影（骨の白）
        drawFigure(
            cx = w * 0.74f,
            topY = h * 0.20f,
            height = h * 0.62f,
            color = Palette.Bone,
        )
    }
}

/** 同じ影の3つの型（ゆうれい／あか影／影絵）を並べる図。 */
@Composable
private fun ThreeTypesVisual(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val gap = 14f
        val pw = (size.width - gap * 2f) / 3f
        val panels = listOf(
            Color(0xFF070708) to Palette.Bone,
            Color(0xFF0B0505) to Color(0xFF9E1B1B),
            Color(0xFFFAF7F1) to Color(0xFF1C1917),
        )
        panels.forEachIndexed { i, (bg, fg) ->
            val left = i * (pw + gap)
            drawRoundRect(
                color = bg,
                topLeft = Offset(left, 0f),
                size = Size(pw, size.height),
                cornerRadius = CornerRadius(24f, 24f),
            )
            drawFigure(
                cx = left + pw / 2f,
                topY = size.height * 0.16f,
                height = size.height * 0.66f,
                color = fg,
            )
        }
    }
}

/** 音の波形。 */
@Composable
private fun WaveVisual(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val heights = listOf(0.28f, 0.52f, 0.8f, 0.44f, 0.95f, 0.6f, 0.34f, 0.7f, 0.5f, 0.86f, 0.4f, 0.64f, 0.3f, 0.55f)
        val barW = size.width * 0.035f
        val gap = (size.width - barW * heights.size) / (heights.size + 1)
        heights.forEachIndexed { i, ratio ->
            val bh = size.height * ratio
            drawRoundRect(
                color = Palette.Bone.copy(alpha = 0.85f),
                topLeft = Offset(gap + i * (barW + gap), (size.height - bh) / 2f),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/** 端末の中の棚（枠の中に影が収まっている図）。 */
@Composable
private fun ShelfVisual(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 5f
        drawRoundRect(
            color = Palette.Bone.copy(alpha = 0.55f),
            topLeft = Offset(stroke, stroke),
            size = Size(size.width - stroke * 2, size.height - stroke * 2),
            cornerRadius = CornerRadius(28f, 28f),
            style = Stroke(width = stroke),
        )
        drawFigure(
            cx = size.width * 0.5f,
            topY = size.height * 0.20f,
            height = size.height * 0.58f,
            color = Palette.Bone,
        )
    }
}

/** カメラの図。 */
@Composable
private fun CameraVisual(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Palette.Bone,
            topLeft = Offset(w * 0.08f, h * 0.28f),
            size = Size(w * 0.84f, h * 0.5f),
            cornerRadius = CornerRadius(26f, 26f),
        )
        drawRoundRect(
            color = Palette.Bone,
            topLeft = Offset(w * 0.36f, h * 0.18f),
            size = Size(w * 0.28f, h * 0.12f),
            cornerRadius = CornerRadius(12f, 12f),
        )
        drawCircle(
            color = Palette.Night,
            radius = w * 0.13f,
            center = Offset(w * 0.5f, h * 0.53f),
        )
        drawCircle(
            color = Palette.Bone,
            radius = w * 0.055f,
            center = Offset(w * 0.5f, h * 0.53f),
        )
    }
}

/** つまみ（スライダー3本）の図。 */
@Composable
private fun SlidersVisual(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val knobAt = listOf(0.62f, 0.38f, 0.78f)
        knobAt.forEachIndexed { i, ratio ->
            val y = h * (0.26f + i * 0.24f)
            drawRoundRect(
                color = Palette.NightLine,
                topLeft = Offset(w * 0.08f, y - 5f),
                size = Size(w * 0.84f, 10f),
                cornerRadius = CornerRadius(5f, 5f),
            )
            drawCircle(
                color = Palette.Blood,
                radius = w * 0.055f,
                center = Offset(w * (0.08f + 0.84f * ratio), y),
            )
        }
    }
}

/** マイクの図。 */
@Composable
private fun MicVisual(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        drawRoundRect(
            color = Palette.Bone,
            topLeft = Offset(cx - w * 0.11f, h * 0.08f),
            size = Size(w * 0.22f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.11f, w * 0.11f),
        )
        drawArc(
            color = Palette.Bone,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - w * 0.26f, h * 0.26f),
            size = Size(w * 0.52f, h * 0.44f),
            style = Stroke(width = 7f),
        )
        drawLine(
            color = Palette.Bone,
            start = Offset(cx, h * 0.72f),
            end = Offset(cx, h * 0.86f),
            strokeWidth = 7f,
        )
        drawLine(
            color = Palette.Bone,
            start = Offset(cx - w * 0.18f, h * 0.88f),
            end = Offset(cx + w * 0.18f, h * 0.88f),
            strokeWidth = 7f,
        )
    }
}

// ---------- 試し操作のサンプル場面 ----------

/**
 * 試し操作用の場面をコードで描く（空・地面・家・木・人）。
 * 合成の絵だが、抽出と効果は本物（ShadowExtractor／ShadowEffects）を通す。
 * 試した影は棚には保存しない（実在性の柱のため。本番は本人の写真で）。
 */
private fun makeSampleBitmap(): Bitmap {
    val w = 720
    val h = 960
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val paint = Paint().apply { isAntiAlias = true }
    paint.shader = LinearGradient(
        0f,
        0f,
        0f,
        h.toFloat(),
        intArrayOf(0xFFE9EDF1.toInt(), 0xFFC7D0D8.toInt(), 0xFFA7B3BD.toInt()),
        null,
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null
    // 地面
    paint.color = 0xFF79838C.toInt()
    canvas.drawRect(0f, 640f, w.toFloat(), h.toFloat(), paint)
    // 家
    paint.color = 0xFF3A4046.toInt()
    canvas.drawRect(70f, 430f, 320f, 640f, paint)
    val roof = AndroidPath()
    roof.moveTo(50f, 430f)
    roof.lineTo(195f, 320f)
    roof.lineTo(340f, 430f)
    roof.close()
    canvas.drawPath(roof, paint)
    // 木
    paint.color = 0xFF22262B.toInt()
    canvas.drawRect(520f, 470f, 552f, 660f, paint)
    canvas.drawCircle(536f, 400f, 95f, paint)
    canvas.drawCircle(470f, 450f, 60f, paint)
    canvas.drawCircle(600f, 455f, 62f, paint)
    // 人がい地面に落とす影（いちばん「実在の影」らしい部分）
    paint.color = 0xFF565F66.toInt()
    val shadow = AndroidPath()
    shadow.moveTo(380f, 758f)
    shadow.lineTo(432f, 758f)
    shadow.lineTo(560f, 900f)
    shadow.lineTo(500f, 900f)
    shadow.close()
    canvas.drawPath(shadow, paint)
    // 人
    paint.color = 0xFF101216.toInt()
    canvas.drawCircle(400f, 560f, 30f, paint)
    canvas.drawRoundRect(RectF(368f, 592f, 432f, 760f), 28f, 28f, paint)
    return bmp
}
