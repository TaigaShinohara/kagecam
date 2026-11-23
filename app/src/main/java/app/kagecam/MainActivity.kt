package app.kagecam

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.content.pm.PackageManager
import android.os.Build
import android.media.MediaPlayer
import android.media.MediaRecorder
import app.kagecam.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 抽出して処理する画像の最大辺（px）。抽出結果のサイズでもある。 */
private const val MAX_DIM = 1080

/** 編集画面の初期値。新規は怖さの初期値、再編集では保存済みパラメータを復元する。 */
internal data class EditorInitial(
    val threshold: Int? = null,
    val preset: ScaryPreset = ScaryPreset.YUREI,
    val ghost: Float = 0.4f,
    val stretch: Float = 1.2f,
    val noise: Float = 0.3f,
)

/**
 * アプリ共通のテーマ（夜の世界）。スクリーンショットテストからも同じものを
 * 使うため MainActivity の外に出してある。
 * onPrimary は骨の白を指定する：血の赤を primary に差し替えただけだと、
 * 主要ボタンの文字に M3 暗色テーマ既定の暗い紫が乗り、読めなくなる
 * （2026-11-23 のスクリーンショット検証で発見・修正）。
 */
@Composable
internal fun KageCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Blood,
            onPrimary = Palette.Bone,
            background = Palette.Night,
            surface = Palette.NightCard,
            onBackground = Palette.Bone,
            onSurface = Palette.Bone,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Palette.Night) {
            content()
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KageCamTheme {
                KageCamApp()
            }
        }
    }
}

@Composable
private fun KageCamApp() {
    val context = LocalContext.current
    val store = remember { ShadowStore(context) }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf(store.loadAll()) }
    // 画面と選択は ID で持ち、rememberSaveable にする：回転やプロセス再生成でも
    // 「どこにいたか」を失わない（実物は items から引き直す）
    var screenName by rememberSaveable { mutableStateOf("shelf") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var extractSession by rememberSaveable { mutableStateOf(0) }
    var fromCamera by rememberSaveable { mutableStateOf(false) }
    // オンボーディング（v0.8）：完了状態と回答は端末内にだけ残す
    val onboardingPrefs = remember {
        context.getSharedPreferences("kagecam_onboarding", Context.MODE_PRIVATE)
    }
    var showOnboarding by rememberSaveable {
        mutableStateOf(onboardingPrefs.getInt("done_version", 0) < ONBOARDING_VERSION)
    }
    var newWorkPreset by remember { mutableStateOf<ScaryPreset?>(null) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val selected = items.firstOrNull { it.id == selectedId }
    val editing = items.firstOrNull { it.id == editingId }
    // 編集中の元写真の退避先。再生成のときにここから復元する
    val sourceCache = remember { File(context.cacheDir, "edit_source.jpg") }

    suspend fun enterExtract(bitmap: Bitmap, editItem: ShadowItem?, camera: Boolean = false) {
        withContext(Dispatchers.IO) {
            runCatching {
                FileOutputStream(sourceCache).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }
        }
        editingId = editItem?.id
        fromCamera = camera && editItem == null
        // オンボーディングで「静かな影絵」を選んだ人だけ、最初の1作の初期の型を
        // 「影絵」にする（回答を本体の初期状態に反映する。反映は1回だけ）
        newWorkPreset = if (editItem == null) {
            val chosen = onboardingPrefs.getString("choice", null)
            if (chosen == "quiet" && !onboardingPrefs.getBoolean("preset_applied", false)) {
                onboardingPrefs.edit().putBoolean("preset_applied", true).apply()
                ScaryPreset.KAGEE
            } else {
                null
            }
        } else {
            null
        }
        extractSession += 1
        source = bitmap
        screenName = "extract"
    }

    fun openSource(uri: Uri, camera: Boolean) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadOrientedBitmap(context, uri) }
            if (bitmap != null) enterExtract(bitmap, null, camera)
        }
    }

    // 復元：回転・プロセス再生成のあと、編集画面にいたら退避した元写真を戻す
    LaunchedEffect(Unit) {
        if (screenName == "extract" && source == null) {
            val restored = withContext(Dispatchers.IO) {
                if (sourceCache.exists()) BitmapFactory.decodeFile(sourceCache.absolutePath) else null
            }
            if (restored != null) source = restored else screenName = "shelf"
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) openSource(uri, camera = false) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success -> if (success) cameraUri?.let { openSource(it, camera = true) } }

    fun takePicture() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture.jpg")
        val uri = FileProvider.getUriForFile(context, "app.kagecam.fileprovider", file)
        cameraUri = uri
        takePictureLauncher.launch(uri)
    }

    @Composable
    fun shelfContent() {
        ShelfScreen(
            items = items,
            store = store,
            onTake = { takePicture() },
            onPick = {
                pickLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onOpen = { item ->
                selectedId = item.id
                screenName = "detail"
            },
            onShowGuide = { showOnboarding = true },
        )
    }

    if (showOnboarding) {
        OnboardingScreen(
            onDone = { destination ->
                onboardingPrefs.edit()
                    .putInt("done_version", ONBOARDING_VERSION)
                    .apply()
                showOnboarding = false
                if (destination == "camera") takePicture()
            },
        )
    } else when (screenName) {
        "extract" -> {
            BackHandler {
                editingId = null
                screenName = "shelf"
            }
            val src = source
            if (src == null) {
                // 復元中（LaunchedEffect が退避した写真を読み直している）
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("何かが、現れようとしている…", color = Palette.Muted, fontSize = 14.sp)
                }
            } else {
                val initial = editing?.params?.let { params ->
                    EditorInitial(
                        threshold = params.threshold,
                        preset = runCatching { ScaryPreset.valueOf(params.preset) }
                            .getOrDefault(ScaryPreset.YUREI),
                        ghost = params.ghost,
                        stretch = params.stretch,
                        noise = params.noise,
                    )
                } ?: EditorInitial(preset = newWorkPreset ?: ScaryPreset.YUREI)
                ExtractScreen(
                    source = src,
                    initial = initial,
                    sessionKey = extractSession,
                    autoRecord = fromCamera,
                    hasExistingAudio = editing?.audioFileName != null,
                    onSave = { result, ed, audio, removeAudio ->
                        scope.launch {
                            val params = ShadowParams(
                                threshold = ed.threshold ?: 128,
                                preset = ed.preset.name,
                                ghost = ed.ghost,
                                stretch = ed.stretch,
                                noise = ed.noise,
                            )
                            withContext(Dispatchers.IO) {
                                val current = editing
                                if (current == null) {
                                    store.add(result, src, params, audio)
                                } else {
                                    store.update(current, result, params, audio, removeAudio)
                                }
                            }
                            editingId = null
                            items = store.loadAll()
                            screenName = "shelf"
                        }
                    },
                    onBack = {
                        editingId = null
                        screenName = "shelf"
                    },
                )
            }
        }

        "detail" -> {
            BackHandler { screenName = "shelf" }
            val item = selected
            if (item == null) {
                shelfContent()
            } else {
                DetailScreen(
                    item = item,
                    store = store,
                    onReEdit = if (item.params != null && item.sourceFileName != null) {
                        {
                            scope.launch {
                                val bmp = withContext(Dispatchers.IO) {
                                    store.loadSourceBitmap(item)
                                }
                                if (bmp != null) enterExtract(bmp, item)
                            }
                        }
                    } else {
                        null
                    },
                    onBack = { screenName = "shelf" },
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.delete(item) }
                            items = store.loadAll()
                            screenName = "shelf"
                        }
                    },
                )
            }
        }

        else -> shelfContent()
    }
}

// ---------- 棚（ホーム） ----------

@Composable
internal fun ShelfScreen(
    items: List<ShadowItem>,
    store: ShadowStore,
    onTake: () -> Unit,
    onPick: () -> Unit,
    onOpen: (ShadowItem) -> Unit,
    onShowGuide: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text("KageCam", fontSize = 13.sp, color = Palette.Muted, fontWeight = FontWeight.Medium)
                Text("影の棚", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                Text(
                    if (items.isEmpty()) "怖い影だけを集めるカメラ" else "たまった影 ${items.size} 点",
                    fontSize = 13.sp,
                    color = Palette.Muted,
                )
            }
            TextButton(onClick = onShowGuide) {
                Text("はじめかた", fontSize = 13.sp, color = Palette.Muted)
            }
        }
        Spacer(Modifier.height(16.dp))

        if (items.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("まだ影がない", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Spacer(Modifier.height(6.dp))
                    Text("最初の怖い影を探しに行こう", fontSize = 14.sp, color = Palette.Muted)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    ShadowCard(item = item, store = store, onClick = { onOpen(item) })
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onTake,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Blood),
            ) { Text("撮る", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = onPick,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(999.dp),
            ) { Text("写真を選ぶ", fontSize = 16.sp, color = Palette.Ink) }
        }
    }
}

@Composable
private fun ShadowCard(item: ShadowItem, store: ShadowStore, onClick: () -> Unit) {
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id) {
        bitmap = withContext(Dispatchers.IO) { store.loadBitmap(item, maxSize = 540) }
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Card)
            .clickable { onClick() }
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "集めた影",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SimpleDateFormat("M月d日 HH:mm", Locale.JAPAN).format(Date(item.createdAt)),
                fontSize = 12.sp,
                color = Palette.Muted,
                modifier = Modifier.weight(1f),
            )
            if (item.audioFileName != null) {
                Text("音つき", fontSize = 11.sp, color = Palette.Muted)
            }
        }
    }
}

// ---------- 怖くする（抽出＋効果） ----------

@Composable
internal fun ExtractScreen(
    source: Bitmap,
    initial: EditorInitial,
    sessionKey: Int,
    autoRecord: Boolean,
    hasExistingAudio: Boolean,
    onSave: (Bitmap, EditorInitial, File?, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var prepared by remember { mutableStateOf<ShadowExtractor.Prepared?>(null) }
    // 調整値はセッション単位で rememberSaveable：回転しても編集中の調整が消えない。
    // 新しい写真を開くたび sessionKey が変わるので、前の写真の値が漏れることもない
    var threshold by rememberSaveable(sessionKey) { mutableStateOf(initial.threshold?.toFloat() ?: 128f) }
    var presetName by rememberSaveable(sessionKey) { mutableStateOf(initial.preset.name) }
    val preset = ScaryPreset.values().firstOrNull { it.name == presetName } ?: ScaryPreset.YUREI
    var ghost by rememberSaveable(sessionKey) { mutableStateOf(initial.ghost) }
    var stretch by rememberSaveable(sessionKey) { mutableStateOf(initial.stretch) }
    var noise by rememberSaveable(sessionKey) { mutableStateOf(initial.noise) }
    var thresholdInitDone by rememberSaveable(sessionKey) { mutableStateOf(false) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var shown by remember { mutableStateOf(false) }
    var showOriginal by rememberSaveable(sessionKey) { mutableStateOf(false) }
    var savedClicked by remember { mutableStateOf(false) }

    // ---- 環境音の同封（v0.5・軸E）：いまの場所の音を6秒だけ録って作品に添える ----
    var pendingAudioExists by rememberSaveable(sessionKey) { mutableStateOf(false) }
    var existingAudioKept by rememberSaveable(sessionKey) { mutableStateOf(hasExistingAudio) }
    var remainSec by remember { mutableStateOf(6) }
    var recording by remember { mutableStateOf(false) }
    var audioDenied by remember { mutableStateOf(false) }
    var audioFailed by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    val tempAudio = remember { File(context.cacheDir, "kagecam_ambient.m4a") }
    val pendingAudio: File? =
        if (pendingAudioExists) tempAudio.takeIf { it.exists() } else null

    fun stopRecording(keep: Boolean) {
        val r = recorder
        recorder = null
        recording = false
        // 止めるのに失敗した（短すぎた等）録音は壊れているので、同封しない
        val stopped = r?.let { runCatching { it.stop() }.isSuccess } ?: false
        r?.release()
        if (keep) {
            if (stopped && tempAudio.exists() && tempAudio.length() > 0) {
                pendingAudioExists = true
                audioFailed = false
            } else {
                tempAudio.delete()
                pendingAudioExists = false
                audioFailed = true
            }
        }
    }
    fun startRecording() {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(tempAudio.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recording = true
            audioFailed = false
        }.onFailure {
            // 失敗したレコーダーを掴んだままにしない（マイクが塞がり続ける）
            runCatching { r.release() }
            recording = false
            audioFailed = true
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startRecording() else audioDenied = true
    }
    fun onRecordTap() {
        if (recording) {
            stopRecording(keep = true)
        } else if (
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    // 撮影（「撮る」）から来たときは、開いた直後にその場の音を自動で録る。
    // カメラアプリが前面にいる間は録音できないので、戻ってきた瞬間が最速になる。
    // 許可がまだ無い場合は一度だけ尋ね、断られたら音なしで普通に使える
    LaunchedEffect(sessionKey) {
        if (autoRecord && !pendingAudioExists && !existingAudioKept && !recording) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startRecording()
            } else {
                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    LaunchedEffect(recording) {
        if (recording) {
            for (sec in 6 downTo 1) {
                remainSec = sec
                delay(1_000)
            }
            if (recording) stopRecording(keep = true)
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (recording) stopRecording(keep = false) }
    }
    val fade by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 900),
        label = "shadow-reveal",
    )

    LaunchedEffect(source) {
        val p = withContext(Dispatchers.Default) { ShadowExtractor.prepare(source) }
        prepared = p
        if (!thresholdInitDone) {
            threshold = initial.threshold?.toFloat() ?: p.otsuThreshold.toFloat()
            thresholdInitDone = true
        }
    }
    LaunchedEffect(prepared, threshold, preset, ghost, stretch, noise) {
        val p = prepared ?: return@LaunchedEffect
        delay(60)
        result = withContext(Dispatchers.Default) {
            ShadowEffects.compose(
                prepared = p,
                threshold = threshold.toInt().coerceIn(0, 255),
                preset = preset,
                ghost = ghost,
                stretch = stretch,
                noise = noise,
            )
        }
        if (!shown) shown = true
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        Text("怖くする", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text("気配だけが、残る", fontSize = 13.sp, color = Palette.Muted)
        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(preset.background)),
            contentAlignment = Alignment.Center,
        ) {
            if (showOriginal) {
                Image(
                    bitmap = source.asImageBitmap(),
                    contentDescription = "元の写真",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val r = result
                if (r != null) {
                    Image(
                        bitmap = r.asImageBitmap(),
                        contentDescription = "怖くした影",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().alpha(fade),
                    )
                } else {
                    Text("何かが、現れようとしている…", color = Palette.Muted, fontSize = 14.sp)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showOriginal = !showOriginal }) {
                Text(
                    if (showOriginal) "結果にもどす" else "元の写真と見比べる",
                    fontSize = 13.sp,
                    color = Palette.Muted,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScaryPreset.values().forEach { p ->
                FilterChip(
                    selected = preset == p,
                    onClick = { presetName = p.name },
                    label = { Text(p.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Palette.Blood,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ScarySlider("影の残り具合", threshold, 0f..255f) { threshold = it }
        ScarySlider("残像", ghost, 0f..1f) { ghost = it }
        ScarySlider("引き伸ばし", stretch, 1f..1.7f) { stretch = it }
        ScarySlider("ノイズ", noise, 0f..1f) { noise = it }
        Spacer(Modifier.height(12.dp))
        Text("音（任意）", fontSize = 13.sp, color = Palette.Muted)
        Spacer(Modifier.height(6.dp))
        if (recording) {
            Button(
                onClick = { stopRecording(keep = true) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Blood),
            ) { Text("録音中… あと${remainSec}秒（タップで止める）", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        } else if (pendingAudioExists) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("新しい音を入れた", fontSize = 14.sp, color = Palette.Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRecordTap() }) { Text("録り直す", color = Palette.Muted) }
                TextButton(onClick = { pendingAudioExists = false }) { Text("やめる", color = Palette.Muted) }
            }
        } else if (existingAudioKept) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("前の音が入っている", fontSize = 14.sp, color = Palette.Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRecordTap() }) { Text("録り直す", color = Palette.Muted) }
                TextButton(onClick = { existingAudioKept = false }) { Text("音を外す", color = Palette.Muted) }
            }
        } else {
            OutlinedButton(
                onClick = { onRecordTap() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(999.dp),
            ) { Text("いまの場所の音を入れる（6秒）", fontSize = 15.sp, color = Palette.Ink) }
        }
        if (hasExistingAudio && !existingAudioKept && !pendingAudioExists && !recording) {
            Text(
                "前の音は外しました（棚にしまうと確定します）",
                fontSize = 12.sp,
                color = Palette.Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (audioFailed && !recording) {
            Text(
                "うまく録音できませんでした",
                fontSize = 12.sp,
                color = Palette.Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (audioDenied) {
            Text(
                "マイクの許可がないので、音は入りません",
                fontSize = 12.sp,
                color = Palette.Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(999.dp),
            ) { Text("もどる", fontSize = 16.sp, color = Palette.Ink) }
            Button(
                onClick = {
                    savedClicked = true
                    result?.let {
                        onSave(
                            it,
                            EditorInitial(
                                threshold = threshold.toInt().coerceIn(0, 255),
                                preset = preset,
                                ghost = ghost,
                                stretch = stretch,
                                noise = noise,
                            ),
                            pendingAudio,
                            pendingAudio == null && !existingAudioKept,
                        )
                    }
                },
                enabled = result != null && !recording && !savedClicked,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Blood),
            ) { Text(if (savedClicked) "しまっています…" else "棚にしまう", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ScarySlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.width(96.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Palette.Blood,
                activeTrackColor = Palette.Blood,
            ),
        )
    }
}

// ---------- 詳細 ----------

@Composable
private fun DetailScreen(
    item: ShadowItem,
    store: ShadowStore,
    onReEdit: (() -> Unit)?,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()

    fun share() {
        shareScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
                    val out = File(shareDir, item.fileName)
                    store.fileFor(item).copyTo(out, overwrite = true)
                    FileProvider.getUriForFile(context, "app.kagecam.fileprovider", out)
                }.getOrNull()
            }
            if (uri != null) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "怖い影を送る"))
            }
        }
    }

    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id) {
        bitmap = withContext(Dispatchers.IO) { store.loadBitmap(item) }
    }
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onBack) {
            Text("〈 棚にもどる", fontSize = 15.sp, color = Palette.Ink)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.Card),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "集めた影",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.JAPAN).format(Date(item.createdAt)),
            fontSize = 15.sp,
            color = Palette.Ink,
        )
        Spacer(Modifier.height(12.dp))
        val audioFile = remember(item.id) { store.audioFileFor(item) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var playing by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            onDispose {
                player?.release()
                player = null
            }
        }
        if (audioFile != null) {
            OutlinedButton(
                onClick = {
                    if (playing) {
                        player?.stop()
                        player?.release()
                        player = null
                        playing = false
                    } else {
                        runCatching {
                            val p = MediaPlayer()
                            p.setDataSource(audioFile.absolutePath)
                            p.setOnCompletionListener {
                                playing = false
                                it.release()
                                player = null
                            }
                            p.setOnErrorListener { mp, _, _ ->
                                playing = false
                                mp.release()
                                if (player === mp) player = null
                                true
                            }
                            p.setVolume(0.75f, 0.75f)
                            p.prepare()
                            p.start()
                            player = p
                            playing = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(
                    if (playing) "音を止める" else "添えた音を聴く",
                    fontSize = 15.sp,
                    color = Palette.Ink,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Button(
            onClick = { share() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Blood),
        ) { Text("共有する", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        if (item.audioFileName != null) {
            Text(
                "共有は画像だけです（音はこの端末の中だけ）",
                fontSize = 12.sp,
                color = Palette.Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        if (onReEdit != null) {
            OutlinedButton(
                onClick = onReEdit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(999.dp),
            ) { Text("怖くし直す", fontSize = 15.sp, color = Palette.Ink) }
            Spacer(Modifier.height(10.dp))
        } else {
            Text(
                "この作品は再編集できません（元の写真を残していない版で作りました）",
                fontSize = 12.sp,
                color = Palette.Muted,
            )
            Spacer(Modifier.height(10.dp))
        }
        if (confirmingDelete) {
            Button(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Blood),
            ) { Text("ほんとうに消す", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        } else {
            OutlinedButton(
                onClick = { confirmingDelete = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(999.dp),
            ) { Text("この影を消す", fontSize = 15.sp, color = Palette.Muted) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------- 画像の読み込み（向き補正＋縮小） ----------

private fun loadOrientedBitmap(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > MAX_DIM * 2 || bounds.outHeight / sample > MAX_DIM * 2) {
        sample *= 2
    }
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null

    var bitmap = decoded
    val longSide = maxOf(bitmap.width, bitmap.height)
    if (longSide > MAX_DIM) {
        val scale = MAX_DIM.toFloat() / longSide
        bitmap = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
    }
    if (!matrix.isIdentity) {
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    return bitmap
}
