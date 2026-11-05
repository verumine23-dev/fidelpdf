package com.fidelpdf.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.fidelpdf.app.ui.FidelTheme
import com.fidelpdf.app.ui.HomeScreen
import com.fidelpdf.app.ui.ResultScreen
import com.fidelpdf.app.ui.WorkingScreen
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FidelTheme {
                App(vm)
            }
        }
    }
}

private fun newCameraUri(ctx: Context): Uri {
    val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
    val f = File(dir, "capture_" + System.currentTimeMillis() + ".jpg")
    return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
}

@Composable
fun App(vm: AppViewModel) {
    val ctx = LocalContext.current
    var pendingCamera by rememberSaveable { mutableStateOf<Uri?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingCamera
        if (ok && u != null) vm.process(u)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.process(uri)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.savePdf(uri)
    }

    BackHandler(enabled = vm.screen == Screen.RESULT) { vm.screen = Screen.HOME }

    when (vm.screen) {
        Screen.HOME -> HomeScreen(
            error = vm.error,
            onDismissError = { vm.error = null },
            onCamera = {
                val u = newCameraUri(ctx)
                pendingCamera = u
                camera.launch(u)
            },
            onGallery = { gallery.launch("image/*") }
        )

        Screen.WORKING -> WorkingScreen(vm.stageIndex, vm.stageLabel)

        Screen.RESULT -> ResultScreen(
            vm = vm,
            onBack = { vm.screen = Screen.HOME },
            onSave = { save.launch("document_fidele.pdf") },
            onShare = {
                vm.preparePdfForShare { uri ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Partager le PDF"))
                }
            }
        )
    }
}
