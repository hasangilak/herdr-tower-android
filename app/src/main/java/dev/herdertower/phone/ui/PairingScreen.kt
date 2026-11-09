package dev.herdertower.phone.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.herdertower.phone.api.CertificateProbe
import dev.herdertower.phone.api.Endpoint
import dev.herdertower.phone.api.Fingerprint
import dev.herdertower.phone.api.PairLink
import dev.herdertower.phone.api.PairLinkException
import dev.herdertower.phone.api.QrScan
import dev.herdertower.phone.data.TowerSession
import dev.herdertower.phone.orb.TowerOrb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Where a pairing came from: the QR scanned in the app is trusted as it is; a link from outside, or one typed by hand, is compared by eye. */
private enum class Source { Qr, Link, Manual }

/** What the user is pairing with, once the fingerprint is known. */
private data class Candidate(val host: String, val port: Int, val code: String, val fingerprint: String, val source: Source)

@Composable
fun PairingScreen(session: TowerSession, initial: PairLink?, onCancel: (() -> Unit)?) {
    // A link that opened the app may come from any web page or app, not only the Mac's QR: its fingerprint is compared by eye.
    var candidate by remember(initial) { mutableStateOf(initial?.let { Candidate(it.host, it.port, it.code, it.fingerprint, Source.Link) }) }
    var tab by rememberSaveable { mutableStateOf(0) }
    // Android 17+: pairing is a connection to the Mac on the local network, which needs this permission first.
    val localNet = rememberLocalNetwork(askNow = true)

    Backdrop("idle", false) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TowerOrb("idle", Modifier.size(72.dp), renderScale = 1f, interactive = false)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Pair with your tower", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium, color = tc.text)
                    Text("On the Mac: tower phone pair", style = MaterialTheme.typography.bodyMedium, color = tc.muted, fontFamily = FontFamily.Monospace)
                }
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
            }
            LocalNetworkNotice(localNet)
            val c = candidate
            if (c != null) {
                Confirm(session, c, onBack = { candidate = null })
            } else {
                Segments(Modifier.fillMaxWidth()) {
                    Segment("Scan QR", tab == 0) { tab = 0 }
                    Segment("Enter by hand", tab == 1) { tab = 1 }
                }
                if (tab == 0) {
                    Scan(onLink = { candidate = Candidate(it.host, it.port, it.code, it.fingerprint, Source.Qr) })
                } else {
                    Manual(onProbed = { candidate = it })
                }
            }
        }
    }
    }
}

@Composable
private fun Scan(onLink: (PairLink) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var error by remember { mutableStateOf<String?>(null) }

    Text(
        "Point the camera at the QR the Mac shows. It holds the address, a one-time code and the certificate's fingerprint.",
        style = MaterialTheme.typography.bodyMedium, color = tc.muted,
    )
    if (!granted) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The camera is needed to scan the pairing code.", color = tc.text)
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
        LaunchedEffect(Unit) { ask.launch(Manifest.permission.CAMERA) }
    } else {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).border(1.dp, tc.edge, RoundedCornerShape(24.dp)),
        ) {
            QrCamera(onText = { text ->
                try {
                    onLink(PairLink.parse(text))
                    true
                } catch (e: PairLinkException) {
                    error = e.message
                    false
                }
            })
        }
    }
    error?.let { Text(it, color = tc.failed, style = MaterialTheme.typography.bodyMedium) }
}

/** A camera preview that decodes QR codes; [onText] returns true to stop scanning. */
@Composable
private fun QrCamera(onText: (String) -> Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { ContextCompat.getMainExecutor(context) }
    var done by remember { mutableStateOf(false) }
    // The camera is bound to the activity's lifecycle: unbind it when the scanner leaves (a code found, the other tab),
    // or it stays open (and its indicator on) until the app goes to the background.
    var bound by remember { mutableStateOf<Pair<ProcessCameraProvider, List<androidx.camera.core.UseCase>>?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            bound?.let { (provider, cases) ->
                (cases.firstOrNull { it is ImageAnalysis } as? ImageAnalysis)?.clearAnalyzer()
                runCatching { provider.unbind(*cases.toTypedArray()) }
            }
            bound = null
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val scan = QrScan()
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    val text = decode(scan, image)
                    image.close()
                    if (text != null && !done) main.execute { if (!done && onText(text)) done = true }
                }
                if (executor.isShutdown) return@addListener // the scanner left before the camera was ready
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.recoverCatching {
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                }.onSuccess { bound = provider to listOf(preview, analysis) }
            }, main)
            view
        },
    )
}

private fun decode(scan: QrScan, image: ImageProxy): String? {
    val plane = image.planes[0]
    val w = image.width
    val h = image.height
    val buf = plane.buffer
    val stride = plane.rowStride
    val y = ByteArray(w * h)
    for (row in 0 until h) {
        buf.position(row * stride)
        buf.get(y, row * w, w)
    }
    return scan.decode(y, w, h)
}

@Composable
private fun Manual(onProbed: (Candidate) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(PairLink.DEFAULT_PORT.toString()) }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Text(
        "Type the address and code “tower phone pair” printed. The app then shows the certificate's fingerprint for you to compare with the Mac's.",
        style = MaterialTheme.typography.bodyMedium, color = tc.muted,
    )
    OutlinedTextField(
        host, { host = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Address") }, placeholder = { Text("192.168.1.20") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), label = { Text("Port") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        code, { code = it.filter { c -> c.isDigit() }.take(8) }, Modifier.fillMaxWidth(), label = { Text("Code (8 digits)") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
    )
    error?.let { Text(it, color = tc.failed, style = MaterialTheme.typography.bodyMedium) }
    Button(
        onClick = {
            val h = PairLink.validHost(host)
            val p = PairLink.validPort(port)
            val c = PairLink.validCode(code)
            error = when {
                h == null -> "Enter the Mac's address."
                p == null -> "The port is a number up to 65535."
                c == null -> "The code is 8 digits."
                else -> null
            }
            if (error != null) return@Button
            busy = true
            scope.launch {
                try {
                    val fp = withContext(Dispatchers.IO) { CertificateProbe.fingerprint(h!!, p!!) }
                    onProbed(Candidate(h!!, p!!, c!!, fp, Source.Manual))
                } catch (e: Exception) {
                    error = "Couldn't reach $h:$p (${e.message ?: e.javaClass.simpleName}). Is “tower phone enable” on, and the phone on the same network?"
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Continue")
    }
}

@Composable
private fun Confirm(session: TowerSession, c: Candidate, onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(TowerSession.defaultDeviceName()) }
    var matches by remember(c) { mutableStateOf(c.source == Source.Qr) }
    val paired by session.credentials.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tower at ${c.host}:${c.port}", style = MaterialTheme.typography.titleMedium, color = tc.text)
            Text(
                when (c.source) {
                    Source.Qr -> "Certificate fingerprint (from the QR):"
                    Source.Link -> "This came from a link, not from the QR scanned here. Compare this fingerprint with the one “tower phone pair” printed on the Mac, group by group:"
                    Source.Manual -> "Compare this fingerprint with the one the Mac printed, group by group:"
                },
                style = MaterialTheme.typography.bodyMedium, color = tc.muted,
            )
            Text(
                Fingerprint.grouped(c.fingerprint),
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tc.screen).padding(12.dp),
                fontFamily = FontFamily.Monospace,
                color = tc.screenText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (!matches) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { matches = true }, Modifier.weight(1f)) { Text("It matches") }
            TextButton(onClick = onBack, Modifier.weight(1f)) { Text("It doesn't") }
        }
        Text(
            "If it doesn't match, don't pair: something other than your Mac answered at that address.",
            style = MaterialTheme.typography.bodySmall, color = tc.faint,
        )
        return
    }
    // Pairing again replaces the tower this phone is paired with: say so, with its address.
    paired?.takeIf { it.host != c.host || it.port != c.port || it.fingerprint != c.fingerprint }?.let { old ->
        Text(
            "This replaces the pairing with the tower at ${old.host}:${old.port}.",
            style = MaterialTheme.typography.bodyMedium, color = tc.blocked,
        )
    }
    OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("This phone's name") }, singleLine = true)
    error?.let { Text(it, color = tc.failed, style = MaterialTheme.typography.bodyMedium) }
    Button(
        onClick = {
            busy = true
            error = null
            scope.launch {
                session.pair(Endpoint(c.host, c.port, c.fingerprint), c.code, name.ifBlank { TowerSession.defaultDeviceName() })
                    .onFailure { error = TowerSession.describe(it) }
                busy = false
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Pair")
    }
    TextButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Start over") }
}
