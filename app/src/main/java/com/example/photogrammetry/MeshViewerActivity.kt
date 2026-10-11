package com.example.photogrammetry

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
import java.io.File
import java.nio.ByteBuffer

class MeshViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startPath = intent.getStringExtra("path")
        setContent { ViewerApp(startPath) }
    }
}

@Composable
fun ViewerApp(startPath: String?) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberCameraNode(engine) { position = Position(z = 2.5f) }
    val mainLightNode = rememberMainLightNode(engine) { intensity = 100_000f }

    var currentPath by remember { mutableStateOf(startPath) }
    var instances by remember { mutableStateOf<List<ModelNode>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    val modelsDir = remember {
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    }

    val arLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {}

    LaunchedEffect(currentPath) {
        val p = currentPath ?: return@LaunchedEffect
        instances = emptyList()
        try {
            val inst = modelLoader.createModelInstance(ByteBuffer.wrap(File(p).readBytes()))
            instances = listOf(ModelNode(modelInstance = inst, scaleToUnits = 1.0f))
            error = null
        } catch (e: Exception) {
            error = "Load failed: ${e.message}"
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            childNodes = instances,
            mainLightNode = mainLightNode,
        )

        if (currentPath == null || error != null) {
            ModelList(
                modelsDir = modelsDir,
                onSelect = { currentPath = it },
                onAr = { path ->
                    arLauncher.launch(
                        Intent(context, ArPlacementActivity::class.java)
                            .putExtra("path", path))
                },
                message = error
            )
        }
    }
}

@Composable
fun ModelList(modelsDir: File, onSelect: (String) -> Unit,
              onAr: (String) -> Unit, message: String?) {
    var files by remember {
        mutableStateOf(modelsDir.listFiles { f ->
            f.extension.lowercase() in listOf("glb", "gltf")
        }?.sortedBy { it.name } ?: emptyList())
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text("Mesh viewer", style = MaterialTheme.typography.headlineSmall)
            Text("Put .glb files in:\n${modelsDir.absolutePath}",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                files = modelsDir.listFiles { f ->
                    f.extension.lowercase() in listOf("glb", "gltf")
                }?.sortedBy { it.name } ?: emptyList()
            }) { Text("Refresh") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f)) {
                items(files.size) { i ->
                    ListItem(
                        headlineContent = { Text(files[i].name) },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { onSelect(files[i].absolutePath) })
                                { Text("Open") }
                                TextButton(onClick = { onAr(files[i].absolutePath) })
                                { Text("AR") }
                            }
                        })
                }
            }
        }
    }
}
