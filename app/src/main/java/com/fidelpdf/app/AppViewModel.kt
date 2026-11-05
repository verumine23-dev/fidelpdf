package com.fidelpdf.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fidelpdf.app.engine.Analyzer
import com.fidelpdf.app.engine.Cutout
import com.fidelpdf.app.engine.CutoutKind
import com.fidelpdf.app.engine.Cutouts
import com.fidelpdf.app.engine.DocModel
import com.fidelpdf.app.engine.Downloads
import com.fidelpdf.app.engine.Fidelity
import com.fidelpdf.app.engine.HistoryEntry
import com.fidelpdf.app.engine.HistoryStore
import com.fidelpdf.app.engine.Imaging
import com.fidelpdf.app.engine.ModelOps
import com.fidelpdf.app.engine.PdfExport
import com.fidelpdf.app.engine.PixelBuf
import com.fidelpdf.app.engine.Renderer
import com.fidelpdf.app.engine.TextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { WELCOME, MAIN, WORKING, RESULT, DETAIL }

enum class MainTab { HISTORY, DOC }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    // --- Navigation
    var screen by mutableStateOf(Screen.WELCOME)
    var tab by mutableStateOf(MainTab.HISTORY)
    var showScanSheet by mutableStateOf(false)

    // --- Traitement
    var stageIndex by mutableIntStateOf(0)
    var stageLabel by mutableStateOf("")
    var workingBitmap by mutableStateOf<Bitmap?>(null)
    var error by mutableStateOf<String?>(null)

    // --- Messages (barre en bas) ; openUri = fichier à ouvrir via le bouton « Ouvrir »
    var message by mutableStateOf<String?>(null)
    var openUri by mutableStateOf<Uri?>(null)

    // --- Résultat courant
    var original by mutableStateOf<Bitmap?>(null)
    var model by mutableStateOf<DocModel?>(null)
    var preview by mutableStateOf<Bitmap?>(null)
    var heat by mutableStateOf<Bitmap?>(null)
    var score by mutableFloatStateOf(0f)
    var suggestions by mutableStateOf<List<RectF>>(emptyList())
    var cutoutItems by mutableStateOf<List<Cutout>>(emptyList())
    var baseTexts by mutableStateOf<List<TextItem>>(emptyList())
    val edits = mutableStateMapOf<Int, String>()

    // --- Historique
    var history by mutableStateOf<List<HistoryEntry>>(emptyList())
    var selected by mutableStateOf<HistoryEntry?>(null)

    // --- Enregistrement sur Android 7-9 (sélecteur de fichier)
    var pendingPicker by mutableStateOf<String?>(null)
    private var pendingBytes: ByteArray? = null

    private var base: DocModel? = null
    private var pix: PixelBuf? = null
    private var cutouts: List<Cutout> = emptyList()
    private var nextCutoutId = 1
    private var renderJob: Job? = null
    private var persistJob: Job? = null
    private var currentId: Long? = null
    private var currentTitle: String = ""
    private var currentSource: String = "Téléversement"

    private val assets get() = getApplication<Application>().assets

    init {
        reloadHistory()
    }

    // ------------------------------------------------------------------ Historique

    fun reloadHistory() {
        viewModelScope.launch {
            history = withContext(Dispatchers.IO) { HistoryStore.list(getApplication<Application>()) }
        }
    }

    fun openEntry(e: HistoryEntry) {
        selected = e
        screen = Screen.DETAIL
    }

    fun deleteEntry(e: HistoryEntry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { HistoryStore.delete(getApplication<Application>(), e.id) }
            if (currentId == e.id) currentId = null
            if (selected?.id == e.id) selected = null
            history = withContext(Dispatchers.IO) { HistoryStore.list(getApplication<Application>()) }
            message = "Scan supprimé"
        }
    }

    private fun deriveTitle(m: DocModel): String? {
        val limitY = m.height * 0.5f
        var best: TextItem? = null
        for (t in m.texts) {
            if (t.box.top >= limitY) continue
            if (t.text.count { it.isLetter() } < 4) continue
            val b = best
            if (b == null || t.box.height() > b.box.height()) best = t
        }
        val text = best?.text?.trim() ?: return null
        return if (text.length > 48) text.substring(0, 48).trim() else text
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(700)
            val m = model ?: return@launch
            val prev = preview ?: return@launch
            val orig = original ?: return@launch
            val sc = score
            val fallbackNumber = history.size + 1
            val app = getApplication<Application>()
            withContext(Dispatchers.IO + NonCancellable) {
                val id = currentId ?: System.currentTimeMillis().also { currentId = it }
                if (currentTitle.isEmpty()) {
                    currentTitle = deriveTitle(m) ?: ("Scan " + fallbackNumber)
                }
                val bos = ByteArrayOutputStream()
                PdfExport.write(m, app.assets, bos)
                val pdf = bos.toByteArray()
                val fonts = m.texts.map { it.font.label }.distinct()
                val entry = HistoryEntry(
                    id = id,
                    title = currentTitle,
                    createdAt = id,
                    score = sc,
                    texts = m.texts.count { it.text.isNotBlank() },
                    rules = m.rules.size,
                    fills = m.fills.size,
                    cutouts = m.cutouts.size,
                    fonts = fonts,
                    width = m.width,
                    height = m.height,
                    pdfBytes = pdf.size.toLong(),
                    source = currentSource
                )
                HistoryStore.save(app, entry, orig, prev, pdf)
            }
            history = withContext(Dispatchers.IO) { HistoryStore.list(app) }
        }
    }

    // ------------------------------------------------------------------ Traitement

    fun process(uri: Uri, fromCamera: Boolean) {
        screen = Screen.WORKING
        error = null
        stageIndex = 0
        stageLabel = ""
        workingBitmap = null
        currentId = null
        currentTitle = ""
        currentSource = if (fromCamera) "Appareil photo" else "Téléversement"
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    val bmp = Imaging.load(getApplication<Application>(), uri)
                    workingBitmap = bmp
                    Analyzer(assets).run(bmp) { i, label ->
                        stageIndex = i
                        stageLabel = label
                    }
                }
                val px = withContext(Dispatchers.Default) { PixelBuf.from(res.bitmap) }
                pix = px
                base = res.model
                cutouts = emptyList()
                cutoutItems = emptyList()
                nextCutoutId = 1
                edits.clear()
                original = res.bitmap
                suggestions = res.suggestions
                baseTexts = res.model.texts
                model = null
                preview = null
                refresh()
                screen = Screen.RESULT
            } catch (e: Throwable) {
                error = "Impossible de traiter ce document : " + (e.message ?: e.javaClass.simpleName)
                screen = Screen.MAIN
            }
        }
    }

    private fun compose(): DocModel {
        var m = base!!
        for (c in cutouts) m = ModelOps.withCutout(m, c)
        if (edits.isNotEmpty()) {
            val snapshot = HashMap(edits)
            m = m.copy(texts = m.texts.map { t ->
                val e = snapshot[t.id]
                if (e != null) t.copy(text = e) else t
            })
        }
        return m
    }

    fun refresh() {
        if (base == null) return
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val bitmapOriginal = original ?: return@launch
            val out = withContext(Dispatchers.Default) {
                val m = compose()
                val prev = Renderer.renderBitmap(m, assets)
                val f = Fidelity.compare(bitmapOriginal, prev, m.paperColor)
                Triple(m, prev, f)
            }
            model = out.first
            preview = out.second
            heat = out.third.heat
            score = out.third.score
            schedulePersist()
        }
    }

    fun editText(id: Int, value: String) {
        edits[id] = value
        refresh()
    }

    fun addCutout(rect: RectF, kind: CutoutKind) {
        val px = pix ?: return
        viewModelScope.launch {
            val id = nextCutoutId++
            val c = withContext(Dispatchers.Default) { Cutouts.extract(px, rect, kind, id) }
            if (c == null) {
                message = "Aucun élément détecté dans cette zone. Resserrez le cadre autour de l'objet."
            } else {
                cutouts = cutouts + c
                cutoutItems = cutouts
                message = kind.label + " détouré et recollé"
                refresh()
            }
        }
    }

    fun removeCutout(id: Int) {
        cutouts = cutouts.filter { it.id != id }
        cutoutItems = cutouts
        refresh()
    }

    // ------------------------------------------------------------------ Téléchargement

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Télécharge le document en cours (page Résultat). */
    fun downloadCurrent() {
        val m = model
        if (m == null) {
            message = "Le PDF n'est pas encore prêt, patientez un instant."
            return
        }
        val a = assets
        launchDownload("FidelPDF_" + stamp() + ".pdf") {
            val bos = ByteArrayOutputStream()
            PdfExport.write(m, a, bos)
            bos.toByteArray()
        }
    }

    /** Télécharge un scan de l'historique. */
    fun downloadEntry(e: HistoryEntry) {
        val app = getApplication<Application>()
        launchDownload("FidelPDF_" + safeName(e.title) + ".pdf") {
            val f = HistoryStore.file(app, e.id, "doc.pdf")
            if (!f.exists()) throw IllegalStateException("Fichier PDF introuvable")
            f.readBytes()
        }
    }

    private fun safeName(title: String): String {
        val s = title.replace(Regex("[^A-Za-z0-9À-ÿ _-]"), "").trim().replace(' ', '_')
        return if (s.isEmpty()) stamp() else s.take(40)
    }

    private fun launchDownload(name: String, provider: () -> ByteArray) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { provider() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val app = getApplication<Application>()
                    val uri = withContext(Dispatchers.IO) { Downloads.saveToDownloads(app, name, bytes) }
                    openUri = uri
                    message = "PDF enregistré dans Téléchargements/FidelPDF"
                } else {
                    pendingBytes = bytes
                    pendingPicker = name
                }
            } catch (e: Throwable) {
                message = "Échec du téléchargement : " + e.javaClass.simpleName + " " + (e.message ?: "")
            }
        }
    }

    /** Fin de l'enregistrement via le sélecteur (Android 7 à 9). */
    fun finishLegacySave(uri: Uri?) {
        val bytes = pendingBytes
        pendingBytes = null
        pendingPicker = null
        if (uri == null || bytes == null) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException("Impossible d'ouvrir le fichier")
                    out.use { it.write(bytes) }
                }
                openUri = uri
                message = "PDF enregistré"
            } catch (e: Throwable) {
                message = "Échec de l'enregistrement : " + e.javaClass.simpleName + " " + (e.message ?: "")
            }
        }
    }

    // ------------------------------------------------------------------ Partage

    fun shareCurrent(onReady: (Uri) -> Unit) {
        val m = model
        if (m == null) {
            message = "Le PDF n'est pas encore prêt, patientez un instant."
            return
        }
        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val dir = File(app.cacheDir, "pdf").apply { mkdirs() }
                    val f = File(dir, "document_fidele.pdf")
                    FileOutputStream(f).use { PdfExport.write(m, app.assets, it) }
                    FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f)
                }
                onReady(uri)
            } catch (e: Throwable) {
                message = "Échec du partage : " + e.javaClass.simpleName + " " + (e.message ?: "")
            }
        }
    }

    fun shareEntry(e: HistoryEntry): Uri? {
        val app = getApplication<Application>()
        val f = HistoryStore.file(app, e.id, "doc.pdf")
        if (!f.exists()) {
            message = "Fichier PDF introuvable"
            return null
        }
        return FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f)
    }
}
