package com.fidelpdf.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
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
import com.fidelpdf.app.engine.Fidelity
import com.fidelpdf.app.engine.Imaging
import com.fidelpdf.app.engine.ModelOps
import com.fidelpdf.app.engine.PdfExport
import com.fidelpdf.app.engine.PixelBuf
import com.fidelpdf.app.engine.Renderer
import com.fidelpdf.app.engine.TextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class Screen { HOME, WORKING, RESULT }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    var screen by mutableStateOf(Screen.HOME)
    var stageIndex by mutableIntStateOf(0)
    var stageLabel by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    var original by mutableStateOf<Bitmap?>(null)
    var model by mutableStateOf<DocModel?>(null)
    var preview by mutableStateOf<Bitmap?>(null)
    var heat by mutableStateOf<Bitmap?>(null)
    var score by mutableFloatStateOf(0f)
    var suggestions by mutableStateOf<List<RectF>>(emptyList())
    var cutoutItems by mutableStateOf<List<Cutout>>(emptyList())
    var baseTexts by mutableStateOf<List<TextItem>>(emptyList())
    val edits = mutableStateMapOf<Int, String>()

    private var base: DocModel? = null
    private var pix: PixelBuf? = null
    private var cutouts: List<Cutout> = emptyList()
    private var nextCutoutId = 1
    private var renderJob: Job? = null

    private val assets get() = getApplication<Application>().assets

    fun process(uri: Uri) {
        screen = Screen.WORKING
        error = null
        stageIndex = 0
        stageLabel = ""
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    val bmp = Imaging.load(getApplication<Application>(), uri)
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
                refresh()
                screen = Screen.RESULT
            } catch (e: Throwable) {
                error = "Impossible de traiter ce document : " + (e.message ?: e.javaClass.simpleName)
                screen = Screen.HOME
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

    fun savePdf(uri: Uri) {
        val m = model ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    PdfExport.write(m, assets, out)
                }
                message = "PDF enregistré"
            } catch (e: Throwable) {
                message = "Échec de l'enregistrement : " + (e.message ?: "erreur")
            }
        }
    }

    fun preparePdfForShare(onReady: (Uri) -> Unit) {
        val m = model ?: return
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
                message = "Échec de la création du PDF : " + (e.message ?: "erreur")
            }
        }
    }
}
