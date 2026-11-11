package com.rohan.myapplication

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rohan.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

class StackPhotoProvider : FileProvider()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PixelMeasurementScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun PixelMeasurementScreen(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraFile = remember(context) { File(context.cacheDir, "camera/stack.jpg") }
    val cameraUri = remember(context, cameraFile) {
        FileProvider.getUriForFile(context, "${context.packageName}.stack-photos", cameraFile)
    }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var bounds by remember { mutableStateOf<StackBounds?>(null) }
    var loading by remember { mutableStateOf(false) }
    var capturing by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun measurePhoto(uri: Uri, fromCamera: Boolean = false) {
        photo = null
        bounds = null
        loading = true
        message = "Reading photo."
        scope.launch {
            try {
                val (bitmap, detectedBounds) = withContext(Dispatchers.IO) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    val detectedBounds = findStackBounds(bitmap.width, bitmap.height) { y, row ->
                        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    }
                    bitmap to detectedBounds
                }
                photo = bitmap
                bounds = detectedBounds
                message = if (detectedBounds == null) {
                    "No complete stack detected. Use a contrasting background and leave a margin."
                } else {
                    null
                }
            } catch (_: IOException) {
                message = "Could not open this photo."
            } catch (_: SecurityException) {
                message = "Could not access this photo."
            } finally {
                if (fromCamera) cameraFile.delete()
                loading = false
            }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        capturing = false
        if (captured) measurePhoto(cameraUri, fromCamera = true) else cameraFile.delete()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) measurePhoto(uri)
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Stack measurement", style = MaterialTheme.typography.headlineSmall)
        Text("Match the calibration camera, zoom, resolution, and distance. Keep the whole stack in frame on a plain black background.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val error = try {
                    cameraFile.parentFile?.mkdirs()
                    cameraFile.outputStream().use { }
                    capturing = true
                    camera.launch(cameraUri)
                    null
                } catch (_: ActivityNotFoundException) {
                    "No camera app is available."
                } catch (_: IOException) {
                    "Could not prepare space for a photo."
                } catch (_: SecurityException) {
                    "Could not open the camera."
                }
                message = error
                if (error != null) {
                    capturing = false
                    cameraFile.delete()
                }
            }, enabled = !loading && !capturing) {
                Text("Take photo")
            }
            TextButton(onClick = { picker.launch("image/*") }, enabled = !loading && !capturing) {
                Text("Choose photo")
            }
        }

        message?.let { Text(it) }
        val detectedBounds = bounds
        if (detectedBounds != null) {
            val lengthPx = String.format(Locale.US, "%.1f", detectedBounds.length)
            val widthPx = String.format(Locale.US, "%.1f", detectedBounds.width)
            val lengthMm = String.format(Locale.US, "%.2f", calibratedMillimetres(detectedBounds.length))
            val widthMm = String.format(Locale.US, "%.2f", calibratedMillimetres(detectedBounds.width))
            Text("Length: $lengthMm mm ($lengthPx px)\nWidth: $widthMm mm ($widthPx px)")
            Text("Check that the rectangle fits the stack body and leaves out protruding studs.")
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("Stack length and width in mm", "$lengthMm,$widthMm")
                    )
                }) {
                    Text("Copy mm pair")
                }
                TextButton(onClick = {
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("Stack length and width in pixels", "$lengthPx,$widthPx")
                    )
                }) {
                    Text("Copy pixel pair")
                }
            }
        }

        photo?.let { bitmap ->
            Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = "Stack photo",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)
                    .drawWithContent {
                        drawContent()
                        detectedBounds?.let { box ->
                            val scaleX = size.width / bitmap.width
                            val scaleY = size.height / bitmap.height
                            val corners = box.corners.map {
                                Offset(it.x.toFloat() * scaleX, it.y.toFloat() * scaleY)
                            }
                            val outline = Path().apply {
                                moveTo(corners.first().x, corners.first().y)
                                for (corner in corners.drop(1)) lineTo(corner.x, corner.y)
                                close()
                            }
                            drawPath(
                                path = outline,
                                color = Color.Cyan,
                                style = Stroke(2.dp.toPx())
                            )
                        }
                    }
            )
        }
    }
}
