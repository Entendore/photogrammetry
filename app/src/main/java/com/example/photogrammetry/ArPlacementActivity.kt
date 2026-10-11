package com.example.photogrammetry

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberViewNode
import java.io.File
import java.nio.ByteBuffer

class ArPlacementActivity : ComponentActivity() {

    private val camPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setContent { ArPlacementApp(intent.getStringExtra("path")) }
            else {
                Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!checkAr()) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            setContent { ArPlacementApp(intent.getStringExtra("path")) }
        else camPermission.launch(Manifest.permission.CAMERA)
    }

    private fun checkAr(): Boolean = try {
        when (ArCoreApk.getInstance().requestInstall(this, true)) {
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { finish(); false }
            else -> true
        }
    } catch (e: Exception) {
        Toast.makeText(this, "ARCore unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        finish(); false
    }
}

@Composable
fun ArPlacementApp(startPath: String?) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberCameraNode(engine)
    val childNodes = rememberNodes()
    val viewNode = rememberViewNode(engine, modelLoader, cameraNode)
    val collisionSystem = rememberCollisionSystem(viewNode)
    val mainLightNode = rememberMainLightNode(engine)

    var frame by remember { mutableStateOf<Frame?>(null) }
    var modelBytes by remember { mutableStateOf<ByteArray?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var scaleF by remember { mutableFloatStateOf(1f) }
    var modelNode by remember { mutableStateOf<ModelNode?>(null) }
    var anchorNode by remember { mutableStateOf<AnchorNode?>(null) }
    var pickerOpen by remember { mutableStateOf(startPath == null) }

    val modelsDir = remember {
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    }

    fun clearPlacement() {
        anchorNode?.anchor?.detach()
        childNodes.clear()
        anchorNode = null
        modelNode = null
    }

    LaunchedEffect(startPath) {
        startPath?.let { p ->
            try { modelBytes = File(p).readBytes(); loadError = null }
            catch (e: Exception) { loadError = "Load failed: ${e.message}" }
        }
    }

    Box(Modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = viewNode,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            collisionSystem = collisionSystem,
            childNodes = childNodes,
            mainLightNode = mainLightNode,
            planeRenderer = anchorNode == null,
            sessionConfiguration = { session, config ->
                config.depthMode =
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                        Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                config.focusMode = Config.FocusMode.AUTO
            },
            onSessionUpdated = { _, f -> frame = f },
            onSessionCreationError = { loadError = "AR session error: ${it.message}" },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { e, _ ->
                    val bytes = modelBytes ?: return@rememberOnGestureListener
                    val hit: HitResult = frame?.hitTest(e.x, e.y)
                        ?.firstOrNull { it.isValid(depthPoint = false, point = true) }
                        ?: return@rememberOnGestureListener
                    val anchor = hit.createAnchorOrNull() ?: return@rememberOnGestureListener

                    clearPlacement()
                    try {
                        val inst = modelLoader.createModelInstance(ByteBuffer.wrap(bytes))
                        val node = AnchorNode(engine = engine, anchor = anchor)
                        val mn = ModelNode(modelInstance = inst, scaleToUnits = null)
                        mn.scale = Scale(scaleF, scaleF, scaleF)
                        node.addChildNode(mn)
                        childNodes += node
                        anchorNode = node
                        modelNode = mn
                    } catch (ex: Exception) {
                        loadError = "Place failed: ${ex.message}"
                    }
                }
            )
        )

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            loadError?.let {
                Text(it, color = Color.Red, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            if (modelBytes == null) {
                ModelPicker(modelsDir, onPick = { p ->
                    clearPlacement()
                    modelBytes = try { File(p).readBytes() } catch (e: Exception) {
                        loadError = e.message; null
                    }
                    pickerOpen = false
                }, open = pickerOpen, onToggle = { pickerOpen = !pickerOpen })
            } else {
                if (anchorNode == null)
                    Text("Point at a surface, then tap to place",
                        color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${"%.2f".format(scaleF)}×", color = Color.White,
                        style = MaterialTheme.typography.labelMedium)
                    Slider(value = scaleF, onValueChange = {
                        scaleF = it
                        modelNode?.scale = Scale(it, it, it)
                    }, valueRange = 0.05f..5f,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                }
                Row {
                    OutlinedButton(onClick = { clearPlacement() }) { Text("Clear") }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = {
                        clearPlacement(); modelBytes = null; pickerOpen = true
                    }) { Text("Change model") }
                }
            }
        }
    }
}

@Composable
fun ModelPicker(modelsDir: File, open: Boolean, onToggle: () -> Unit, onPick: (String) -> Unit) {
    if (!open) {
        Button(onClick = onToggle) { Text("Choose model (.glb)") }
        Text("Put .glb files in:\n${modelsDir.absolutePath}",
            color = Color.White, style = MaterialTheme.typography.bodySmall)
        return
    }
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Models", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onToggle) { Text("Close") }
            }
            val files = remember {
                modelsDir.listFiles { f -> f.extension.lowercase() in listOf("glb", "gltf") }
                    ?.sortedBy { it.name } ?: emptyList()
            }
            if (files.isEmpty())
                Text("No .glb files found — mesh a scan on PC and push it here.",
                    style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(files.size) { i ->
                    ListItem(
                        headlineContent = { Text(files[i].name) },
                        trailingContent = {
                            TextButton(onClick = { onPick(files[i].absolutePath) })
                            { Text("Load") }
                        })
                }
            }
        }
    }
}
