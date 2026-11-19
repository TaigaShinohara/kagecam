package app.kagecam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** 「怖くする」の調整一式。再編集のときにそのまま復元する。 */
data class ShadowParams(
    val threshold: Int,
    val preset: String,
    val ghost: Float,
    val stretch: Float,
    val noise: Float,
)

/**
 * 集めた影1点。
 * v0.3 からは元画像（抽出に使った縮小済み写真）と調整パラメータも一緒に持ち、
 * 保存後の再編集（怖くし直す）を可能にする。v0.2 以前の作品は source/params が
 * 無く、再編集できない（詳細画面でその旨を表示する）。
 */
data class ShadowItem(
    val id: String,
    val fileName: String,
    val createdAt: Long,
    val sourceFileName: String? = null,
    val params: ShadowParams? = null,
    /** v0.5〜：その場の環境音（<id>.m4a）。無い作品は null */
    val audioFileName: String? = null,
)

/**
 * 端末内保存のみ（アカウント・サーバーなし）。
 * 実体：filesDir/shadows/<id>.png（完成画像）＋ <id>_src.jpg（元画像）＋ index.json
 */
class ShadowStore(context: Context) {

    private val dir: File = File(context.filesDir, "shadows").apply { mkdirs() }
    private val indexFile: File = File(dir, "index.json")
    private val backupIndexFile: File = File(dir, "index.json.bak")

    fun loadAll(): List<ShadowItem> {
        if (!indexFile.exists()) return emptyList()
        return parseIndex(indexFile)
            ?: backupIndexFile.takeIf { it.exists() }?.let { parseIndex(it) }
            ?: rebuildFromFiles()
    }

    /** index.json が壊れていたときの最終復旧：残っている実ファイルから台帳を組み直す。
     *  調整パラメータは失われる（再編集不可になる）が、作品そのものは失わない。 */
    private fun rebuildFromFiles(): List<ShadowItem> {
        val pngs = dir.listFiles { f -> f.isFile && f.extension == "png" } ?: return emptyList()
        return pngs.map { f ->
            val id = f.nameWithoutExtension
            ShadowItem(
                id = id,
                fileName = f.name,
                createdAt = f.lastModified(),
                sourceFileName = File(dir, "${id}_src.jpg").takeIf { it.exists() }?.name,
                params = null,
                audioFileName = File(dir, "$id.m4a").takeIf { it.exists() }?.name,
            )
        }.sortedByDescending { it.createdAt }
    }

    private fun parseIndex(file: File): List<ShadowItem>? =
        runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val params = if (obj.has("threshold")) {
                        ShadowParams(
                            threshold = obj.getInt("threshold"),
                            preset = obj.optString("preset", "YUREI"),
                            ghost = obj.optDouble("ghost", 0.4).toFloat(),
                            stretch = obj.optDouble("stretch", 1.2).toFloat(),
                            noise = obj.optDouble("noise", 0.3).toFloat(),
                        )
                    } else {
                        null
                    }
                    val item = ShadowItem(
                        id = obj.getString("id"),
                        fileName = obj.getString("file"),
                        createdAt = obj.getLong("createdAt"),
                        sourceFileName = obj.optString("source", "").ifEmpty { null },
                        params = params,
                        audioFileName = obj.optString("audio", "").ifEmpty { null },
                    )
                    if (File(dir, item.fileName).exists()) add(item)
                }
            }.sortedByDescending { it.createdAt }
        }.getOrNull()

    /** 新規保存。完成画像と、再編集用に元画像＋パラメータを一緒にしまう。環境音があれば同封する。 */
    fun add(bitmap: Bitmap, source: Bitmap?, params: ShadowParams, audioFile: File? = null): ShadowItem {
        val createdAt = System.currentTimeMillis()
        // 同じミリ秒に2件保存してもファイルが衝突しないよう、IDにランダムな尾を付ける
        val id = createdAt.toString() + "_" + UUID.randomUUID().toString().take(8)
        val fileName = "$id.png"
        FileOutputStream(File(dir, fileName)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val sourceFileName = source?.let {
            val name = "${id}_src.jpg"
            FileOutputStream(File(dir, name)).use { out ->
                it.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            name
        }
        val audioFileName = audioFile?.let {
            val name = "$id.m4a"
            it.copyTo(File(dir, name), overwrite = true)
            name
        }
        val item = ShadowItem(id, fileName, createdAt, sourceFileName, params, audioFileName)
        saveIndex(loadAll().toMutableList().apply { add(0, item) })
        return item
    }

    /** 再編集の上書き保存。作成日と元画像はそのまま、完成画像とパラメータだけ差し替える。新しい環境音があれば差し替える。 */
    fun update(
        item: ShadowItem,
        bitmap: Bitmap,
        params: ShadowParams,
        newAudio: File? = null,
        removeAudio: Boolean = false,
    ): ShadowItem {
        FileOutputStream(File(dir, item.fileName)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val audioFileName = when {
            removeAudio -> {
                item.audioFileName?.let { File(dir, it).delete() }
                null
            }
            newAudio != null -> {
                val name = "${item.id}.m4a"
                newAudio.copyTo(File(dir, name), overwrite = true)
                name
            }
            else -> item.audioFileName
        }
        val updated = item.copy(params = params, audioFileName = audioFileName)
        saveIndex(loadAll().map { if (it.id == item.id) updated else it })
        return updated
    }

    fun delete(item: ShadowItem) {
        File(dir, item.fileName).delete()
        item.sourceFileName?.let { File(dir, it).delete() }
        item.audioFileName?.let { File(dir, it).delete() }
        saveIndex(loadAll().filterNot { it.id == item.id })
    }

    fun fileFor(item: ShadowItem): File = File(dir, item.fileName)

    fun audioFileFor(item: ShadowItem): File? =
        item.audioFileName?.let { File(dir, it) }?.takeIf { it.exists() }

    fun loadBitmap(item: ShadowItem, maxSize: Int = 0): Bitmap? =
        decode(File(dir, item.fileName), maxSize)

    fun loadSourceBitmap(item: ShadowItem): Bitmap? {
        val name = item.sourceFileName ?: return null
        return decode(File(dir, name), 0)
    }

    private fun decode(file: File, maxSize: Int): Bitmap? {
        if (!file.exists()) return null
        if (maxSize <= 0) return BitmapFactory.decodeFile(file.absolutePath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun saveIndex(items: List<ShadowItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject()
                .put("id", item.id)
                .put("file", item.fileName)
                .put("createdAt", item.createdAt)
            item.sourceFileName?.let { obj.put("source", it) }
            item.audioFileName?.let { obj.put("audio", it) }
            item.params?.let { p ->
                obj.put("threshold", p.threshold)
                obj.put("preset", p.preset)
                obj.put("ghost", p.ghost.toDouble())
                obj.put("stretch", p.stretch.toDouble())
                obj.put("noise", p.noise.toDouble())
            }
            array.put(obj)
        }
        // 書いている途中で落ちても台帳を失わないよう、一時ファイルに書いてから差し替え、
        // 直前の台帳は .bak に残す（loadAll は index → bak → 実ファイル再構築の順に復旧する）
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(array.toString())
        if (indexFile.exists()) indexFile.copyTo(backupIndexFile, overwrite = true)
        if (!tmp.renameTo(indexFile)) indexFile.writeText(array.toString())
    }
}
