package com.example.xdownloader

import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private data class Media(val url: String, val kind: String) // kind: photo | video | gif

    private lateinit var input: EditText
    private lateinit var rbMp4: RadioButton
    private lateinit var rbGif: RadioButton
    private lateinit var downloadBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Si llega desde "Compartir" en la app de X, rellena el enlace para elegir formato y descargar
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            input.setText(text)
            status.text = "Enlace recibido. Elige el formato y pulsa Descargar."
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val title = TextView(this).apply {
            text = "Descarga imágenes, vídeos y GIFs de X (Twitter)"
            textSize = 18f
        }
        root.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        input = EditText(this).apply {
            hint = "Pega aquí el enlace del tweet"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        val formatLabel = TextView(this).apply {
            text = "Formato para vídeos y GIFs:"
            textSize = 14f
        }
        root.addView(formatLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        rbMp4 = RadioButton(this).apply {
            text = "MP4"
            id = View.generateViewId()
        }
        rbGif = RadioButton(this).apply {
            text = "GIF (máx. 20 s)"
            id = View.generateViewId()
        }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rbMp4)
            addView(rbGif, RadioGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                leftMargin = dp(24)
            })
        }
        root.addView(group, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        rbMp4.isChecked = true

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pasteBtn = Button(this).apply {
            text = "Pegar"
            setOnClickListener { pasteFromClipboard() }
        }
        downloadBtn = Button(this).apply {
            text = "Descargar"
            setOnClickListener { startDownload() }
        }
        row.addView(pasteBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(downloadBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        status = TextView(this).apply { textSize = 16f }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            status.text = "El portapapeles está vacío."
        } else {
            input.setText(text)
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        downloadBtn.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        status.text = message
    }

    private fun startDownload() {
        val text = input.text.toString()
        val id = Regex("status(?:es)?/(\\d+)").find(text)?.groupValues?.get(1)
        if (id == null) {
            status.text = "El enlace no es válido. Debe parecerse a x.com/usuario/status/123456…"
            return
        }
        val asGif = rbGif.isChecked
        setBusy(true, "Buscando contenido…")
        Thread {
            try {
                val items = fetchMedia(id)
                if (items.isEmpty()) throw Exception("Este tweet no tiene imágenes, vídeos ni GIFs.")
                items.forEachIndexed { i, m ->
                    val label = "${i + 1} de ${items.size}"
                    if (asGif && m.kind != "photo") {
                        runOnUiThread { status.text = "Descargando $label…" }
                        saveAsGif(m, id, i + 1) { pct ->
                            runOnUiThread { status.text = "Convirtiendo a GIF $label… $pct%" }
                        }
                    } else {
                        runOnUiThread { status.text = "Descargando $label…" }
                        save(m, id, i + 1)
                    }
                }
                runOnUiThread {
                    setBusy(false, "✅ ${items.size} archivo(s) guardado(s) en la galería (carpeta XDescargas).")
                }
            } catch (e: Throwable) {
                val msg = if (e is OutOfMemoryError) "Sin memoria suficiente para convertir este vídeo." else e.message
                runOnUiThread { setBusy(false, "❌ ${msg ?: "Error desconocido"}") }
            }
        }.start()
    }

    // ---------- Obtención de los medios del tweet ----------

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) DescargadorX/1.0")
        try {
            if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun fetchMedia(id: String): List<Media> {
        try {
            val r = fromFx(id)
            if (r.isNotEmpty()) return r
        } catch (e: Exception) {
            // se prueba el servicio alternativo
        }
        try {
            return fromVx(id)
        } catch (e: Exception) {
            throw Exception("No se pudo obtener el tweet. Puede ser privado, estar borrado o los servicios están caídos.")
        }
    }

    // Servicio principal: api.fxtwitter.com
    private fun fromFx(id: String): List<Media> {
        val tweet = JSONObject(httpGet("https://api.fxtwitter.com/status/$id")).getJSONObject("tweet")
        val all = tweet.optJSONObject("media")?.optJSONArray("all") ?: return emptyList()
        val list = mutableListOf<Media>()
        for (i in 0 until all.length()) {
            val o = all.getJSONObject(i)
            list.add(Media(o.getString("url"), o.optString("type", "photo")))
        }
        return list
    }

    // Servicio alternativo: api.vxtwitter.com
    private fun fromVx(id: String): List<Media> {
        val json = JSONObject(httpGet("https://api.vxtwitter.com/Twitter/status/$id"))
        val arr = json.optJSONArray("media_extended") ?: return emptyList()
        val list = mutableListOf<Media>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val kind = when (o.optString("type")) {
                "image" -> "photo"
                "gif" -> "gif"
                else -> "video"
            }
            list.add(Media(o.getString("url"), kind))
        }
        return list
    }

    // ---------- Guardado en la galería (MediaStore, sin permisos) ----------

    private fun openDownload(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) DescargadorX/1.0")
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw Exception("Error al descargar (HTTP $code).")
        }
        return conn
    }

    private fun insertPending(name: String, mime: String, folder: String, images: Boolean): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/XDescargas")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (images) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        return contentResolver.insert(collection, values)
            ?: throw Exception("No se pudo crear el archivo en el dispositivo.")
    }

    private fun markDone(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        contentResolver.update(uri, values, null, null)
    }

    // Imágenes y vídeos/GIFs tal cual (MP4)
    private fun save(m: Media, tweetId: String, index: Int) {
        val isImage = m.kind == "photo"
        val url = if (isImage && !m.url.contains("?")) m.url + "?name=orig" else m.url

        val clean = m.url.substringBefore('?')
        val ext = clean.substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif", "mp4") }
            ?: if (isImage) "jpg" else "mp4"
        val mime = if (!isImage) "video/mp4" else when (ext) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        val folder = if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES

        val conn = openDownload(url)
        try {
            val uri = insertPending("x_${tweetId}_$index.$ext", mime, folder, isImage)
            try {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    conn.inputStream.use { it.copyTo(out) }
                }
                markDone(uri)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } finally {
            conn.disconnect()
        }
    }

    // Vídeos y GIFs convertidos a GIF animado
    private fun saveAsGif(m: Media, tweetId: String, index: Int, onProgress: (Int) -> Unit) {
        val tmp = File(cacheDir, "x_${tweetId}_$index.mp4")
        try {
            val conn = openDownload(m.url)
            try {
                tmp.outputStream().use { o -> conn.inputStream.use { it.copyTo(o) } }
            } finally {
                conn.disconnect()
            }
            val uri = insertPending(
                "x_${tweetId}_$index.gif", "image/gif", Environment.DIRECTORY_PICTURES, true
            )
            try {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    GifConverter.convert(tmp.path, out, onProgress)
                }
                markDone(uri)
            } catch (e: Throwable) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } finally {
            tmp.delete()
        }
    }
}
