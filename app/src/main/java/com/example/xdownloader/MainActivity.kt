package com.example.xdownloader

import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private data class Media(val url: String, val kind: String) // kind: photo | video | gif

    private lateinit var input: EditText
    private lateinit var downloadBtn: Button
    private lateinit var convertBtn: Button
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

    // Si llega desde "Compartir" en la app de X, descarga directamente
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            input.setText(text)
            startDownload()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        // ----- Descarga desde X -----
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

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pasteBtn = Button(this).apply {
            text = "Pegar"
            setOnClickListener { pasteFromClipboard() }
        }
        downloadBtn = Button(this).apply {
            text = "Descargar MP4"
            setOnClickListener { startDownload() }
        }
        row.addView(pasteBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(downloadBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ----- Conversión a GIF -----
        val divider = View(this).apply { setBackgroundColor(0x33000000) }
        root.addView(divider, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
            topMargin = dp(24)
        })

        val convertTitle = TextView(this).apply {
            text = "Convertir a GIF"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(convertTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        val convertInfo = TextView(this).apply {
            text = "Elige un vídeo o una imagen de la galería: se convierte y se guarda como GIF."
            textSize = 14f
        }
        root.addView(convertInfo, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        convertBtn = Button(this).apply {
            text = "Convertir a GIF: elegir de la galería"
            setOnClickListener { pickMedia() }
        }
        root.addView(convertBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ----- Estado -----
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        status = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true) // para poder copiar los mensajes de error
        }
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
        convertBtn.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        status.text = message
    }

    // ---------- Descarga desde X (MP4 / imágenes) ----------

    private fun startDownload() {
        val text = input.text.toString()
        val id = Regex("status(?:es)?/(\\d+)").find(text)?.groupValues?.get(1)
        if (id == null) {
            status.text = "El enlace no es válido. Debe parecerse a x.com/usuario/status/123456…"
            return
        }
        setBusy(true, "Buscando contenido…")
        Thread {
            try {
                val items = fetchMedia(id)
                if (items.isEmpty()) throw Exception("Este tweet no tiene imágenes, vídeos ni GIFs.")
                items.forEachIndexed { i, m ->
                    runOnUiThread { status.text = "Descargando ${i + 1} de ${items.size}…" }
                    save(m, id, i + 1)
                }
                runOnUiThread {
                    setBusy(false, "✅ ${items.size} archivo(s) guardado(s) en la galería (carpeta XDescargas).")
                }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false, "❌ ${e.message ?: "Error desconocido"}") }
            }
        }.start()
    }

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

    // ---------- Convertir a GIF desde la galería ----------

    private fun pickMedia() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            status.text = "No hay ninguna app para elegir archivos."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) startConvert(uri)
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (e: Exception) {
            // se usa un nombre genérico
        }
        val base = (name ?: "archivo").substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return base.ifBlank { "archivo" }
    }

    private fun startConvert(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: ""
        if (mime == "image/gif") {
            status.text = "Ese archivo ya es un GIF."
            return
        }
        val isImage = mime.startsWith("image/")
        val name = displayName(uri) + ".gif"
        setBusy(true, "Convirtiendo a GIF…")
        Thread {
            var gifUri: Uri? = null
            try {
                val target = insertPending(name, "image/gif", Environment.DIRECTORY_PICTURES, true)
                gifUri = target
                contentResolver.openOutputStream(target)!!.use { out ->
                    if (isImage) {
                        GifConverter.convertImage(this@MainActivity, uri, out)
                    } else {
                        GifConverter.convert(this@MainActivity, uri, out) { pct ->
                            runOnUiThread { status.text = "Convirtiendo a GIF… $pct%" }
                        }
                    }
                }
                markDone(target)
                runOnUiThread { setBusy(false, "✅ GIF guardado en la galería (Imágenes/XDescargas).") }
            } catch (e: Throwable) {
                try {
                    gifUri?.let { contentResolver.delete(it, null, null) }
                } catch (x: Exception) {
                    // nada que limpiar
                }
                val msg = if (e is OutOfMemoryError) "Sin memoria suficiente para convertir este archivo." else e.message
                runOnUiThread { setBusy(false, "❌ ${msg ?: "Error desconocido"}") }
            }
        }.start()
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

    // Imágenes y vídeos/GIFs de X tal cual (MP4)
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

    companion object {
        private const val REQ_PICK = 1001
    }
}
