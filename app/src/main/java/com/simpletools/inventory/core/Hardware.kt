package com.simpletools.inventory.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class NfcStatus { ABSENT, DISABLED, READY }

fun nfcStatus(adapter: NfcAdapter?): NfcStatus = when {
    adapter == null -> NfcStatus.ABSENT
    adapter.isEnabled -> NfcStatus.READY
    else -> NfcStatus.DISABLED
}

object TagIdentity {
    const val MIME = "application/vnd.simpletools.inventory"
    fun identify(records: Array<NdefRecord>, uid: ByteArray?): String? {
        val appId = records.firstNotNullOfOrNull { record ->
            if (record.tnf != NdefRecord.TNF_MIME_MEDIA || !record.type.contentEquals(MIME.toByteArray()) ||
                record.payload.size !in 1..120) null
            else record.payload.decodeToString().takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,120}")) }
        }
        if (appId != null) return "ndef:$appId"
        // ISO 14443 Type A random IDs use a four-byte identifier starting at 0x08.
        if (uid == null || uid.isEmpty() || (uid.size == 4 && uid[0] == 8.toByte())) return null
        return "uid:" + MessageDigest.getInstance("SHA-256").digest(uid).joinToString("") {
            "%02x".format(Locale.ROOT, it.toInt() and 255)
        }
    }

    fun read(tag: Tag): String? = identify(Ndef.get(tag)?.cachedNdefMessage?.records ?: emptyArray(), tag.id)
}

fun jpeg(context: Context, uri: Uri): ByteArray {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        require(width in 1..25000 && height in 1..25000)
        val scale = maxOf(width, height).coerceAtLeast(1440)
        decoder.setTargetSize((width * 1440 / scale).coerceAtLeast(1), (height * 1440 / scale).coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    return try {
        ByteArrayOutputStream().use { out ->
            for (quality in listOf(82, 65, 45, 30)) {
                out.reset()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
                if (out.size() <= InventoryRepository.MAX_PHOTO_BYTES) return@use out.toByteArray()
            }
            error("photo")
        }
    } finally {
        bitmap.recycle()
    }
}

@androidx.camera.core.ExperimentalGetImage
@Composable
fun CameraScanner(french: Boolean, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onCode)
    fun tr(fr: String, en: String) = if (french) fr else en
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text(tr("Autoriser la caméra", "Allow camera")) }
        return
    }

    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle, view) {
        val active = AtomicBoolean(true)
        val delivered = AtomicBoolean(false)
        val executor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        val client = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(
            Barcode.FORMAT_QR_CODE, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_PDF417).build())
        val preview = Preview.Builder().build().apply { surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        var provider: ProcessCameraProvider? = null
        analysis.setAnalyzer(executor) { frame ->
            val image = frame.image
            if (image == null || !active.get() || delivered.get()) {
                frame.close()
            } else {
                client.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                    .addOnCompleteListener(executor) { task ->
                        frame.close()
                        val code = if (task.isSuccessful) task.result.firstNotNullOfOrNull { it.rawValue } else null
                        if (active.get() && !code.isNullOrBlank() && code.length <= 2048 &&
                            delivered.compareAndSet(false, true)) callback(code)
                    }
            }
        }
        future.addListener({
            if (active.get()) {
                try {
                    provider = future.get()
                    camera = requireNotNull(provider).bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) { failed = true }
            }
        }, executor)
        onDispose {
            active.set(false)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            client.close()
        }
    }
    Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxWidth(0.78f).height(180.dp).border(2.dp, Color.White))
    }
    Text(if (failed) tr("Caméra indisponible. Utilisez la saisie manuelle.", "Camera unavailable. Enter the code manually.")
        else tr("Centrez le code et rapprochez la caméra si nécessaire.", "Center the code and move closer if needed."))
    if (camera?.cameraInfo?.hasFlashUnit() == true) {
        OutlinedButton(onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) }) {
            Text(tr("Lampe", "Torch"))
        }
    }
}
