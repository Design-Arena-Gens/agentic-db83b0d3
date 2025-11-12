package com.kitoko.packer

import android.app.Activity
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kitoko.packer.auth.AuthState
import com.kitoko.packer.auth.AuthViewModel
import com.kitoko.packer.data.CompletedOrdersStore
import com.kitoko.packer.data.OrderRepository
import com.kitoko.packer.export.CsvExporter
import com.kitoko.packer.export.ScanLogEntry
import com.kitoko.packer.model.InvoicePayload
import com.kitoko.packer.model.PackProgress
import com.kitoko.packer.model.PacketPayload
import com.kitoko.packer.scanner.ScannerView
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Base64

@Composable
fun KitokoApp() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val completedStore = remember { CompletedOrdersStore(context) }
    val orderRepo = remember { OrderRepository() }

    val tts = remember { TextToSpeech(context) {} }

    var logs by remember { mutableStateOf(listOf<ScanLogEntry>()) }
    var currentRequired by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    MaterialTheme {
        NavHost(navController = nav, startDestination = "auth") {
            composable("auth") {
                val vm = remember { AuthViewModel() }
                val state by vm.state.collectAsState()
                when (val s = state) {
                    AuthState.SignedOut -> AuthScreen(onSignIn = { e, p -> vm.signIn(e, p) })
                    is AuthState.SignedIn -> LaunchedEffect(Unit) { nav.navigate("invoice") { popUpTo("auth") { inclusive = true } } }
                    AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is AuthState.Error -> AuthScreen(error = s.message, onSignIn = { e, p -> vm.signIn(e, p) })
                }
            }

            composable("invoice") {
                val completed by completedStore.completedOrders.collectAsState(initial = emptySet())
                ScanInvoiceScreen(
                    completedOrders = completed,
                    onScanned = { orderId, required, raw ->
                        logs = logs + ScanLogEntry(System.currentTimeMillis(), orderId, "invoice_scanned", raw = raw)
                        currentRequired = required
                        nav.navigate("pack/${orderId}")
                    }
                )
            }

            composable(
                route = "pack/{orderId}",
                arguments = listOf(navArgument("orderId") { type = NavType.StringType })
            ) { backStack ->
                val orderId = backStack.arguments?.getString("orderId") ?: return@composable
                val req = currentRequired
                PackScreen(
                    orderId = orderId,
                    required = req,
                    onPacked = {
                        scope.launch {
                            completedStore.markCompleted(orderId)
                            orderRepo.markOrderPacked(orderId)
                        }
                        logs = logs + ScanLogEntry(System.currentTimeMillis(), orderId, "order_packed")
                        tts.speak("Order packed", TextToSpeech.QUEUE_FLUSH, null, "packed")
                        nav.navigate("invoice") { popUpTo("invoice") { inclusive = true } }
                    },
                    onLog = { e -> logs = logs + e }
                )
            }

            composable("export") {
                ExportScreen(logs = logs)
            }
        }
    }
}

@Composable
private fun AuthScreen(error: String? = null, onSignIn: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Kitoko Packer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") })
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onSignIn(email.trim(), password) }) { Text("Sign In") }
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ScanInvoiceScreen(
    completedOrders: Set<String>,
    onScanned: (orderId: String, required: Map<String, Int>, raw: String) -> Unit
) {
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Text("Scan Invoice", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        Box(Modifier.weight(1f)) {
            ScannerView(onBarcode = { raw ->
                val parsed = parseInvoice(raw)
                if (parsed == null) {
                    error = "Invalid invoice QR"
                } else if (completedOrders.contains(parsed.first)) {
                    error = "Already packed"
                } else {
                    onScanned(parsed.first, parsed.second, raw)
                }
            }, modifier = Modifier.fillMaxSize())
        }
        if (!error.isNullOrBlank()) {
            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
    }
}

private fun parseInvoice(raw: String): Pair<String, Map<String, Int>>? {
    if (!raw.startsWith("PKG1:")) return null
    val payloadB64 = raw.removePrefix("PKG1:")
    return try {
        val data = android.util.Base64.decode(payloadB64, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        val json = String(data)
        val obj = Json.decodeFromString(com.kitoko.packer.model.InvoicePayload.serializer(), json)
        val req = obj.i.associate { it.sku to it.units }
        obj.o to req
    } catch (_: Throwable) { null }
}

@Composable
private fun PackScreen(
    orderId: String,
    required: Map<String, Int>,
    onPacked: () -> Unit,
    onLog: (ScanLogEntry) -> Unit
) {
    var progress by remember { mutableStateOf(PackProgress(orderId, required = required)) }
    var overlay by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(orderId) {
        // In a real flow, we would pass required map via nav args or shared VM.
        // As a fallback, if empty, we keep scanning expecting invoice first on same session.
    }

    Column(Modifier.fillMaxSize()) {
        Text("Scan Products", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        Box(Modifier.weight(1f)) {
            ScannerView(onBarcode = { raw ->
                val result = handleProductScan(progress, raw)
                progress = result.first
                result.second?.let { evt -> onLog(evt) }
                if (progress.isComplete) {
                    overlay = "Order Packed"
                    onPacked()
                }
            }, modifier = Modifier.fillMaxSize())
            if (overlay != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)) {
                        Text("Order Packed", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
            items(progress.required.toList()) { (sku, need) ->
                val have = progress.scannedUnitsBySku[sku] ?: 0
                ListItem(
                    headlineContent = { Text(sku) },
                    supportingContent = { Text("$have / $need") }
                )
                Divider()
            }
        }
    }
}

private fun handleProductScan(progress: PackProgress, raw: String): Pair<PackProgress, ScanLogEntry?> {
    // Prevent duplicate same packet QR
    if (raw.startsWith("PKT1:")) {
        if (progress.seenPacketQrs.contains(raw)) return progress to null
    }
    val (sku, isPacket) = when {
        raw.startsWith("PKT1:") -> {
            val payloadB64 = raw.removePrefix("PKT1:")
            val sku = try {
                val data = android.util.Base64.decode(payloadB64, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                val json = String(data)
                val obj = kotlinx.serialization.json.Json.decodeFromString(com.kitoko.packer.model.PacketPayload.serializer(), json)
                obj.s
            } catch (_: Throwable) { return progress to null }
            sku to true
        }
        raw.contains(":") && raw.substringBefore(":").length in 3..8 -> return progress to null // ignore other schemes
        else -> raw to false
    }
    // Count if required and remaining > 0
    val need = progress.required[sku] ?: return progress to null
    val have = progress.scannedUnitsBySku[sku] ?: 0
    if (have >= need) return progress to null

    val newHave = have + 1
    val newScanned = progress.scannedUnitsBySku + (sku to newHave)
    val newSeen = if (isPacket) progress.seenPacketQrs + raw else progress.seenPacketQrs
    val updated = progress.copy(scannedUnitsBySku = newScanned, seenPacketQrs = newSeen)
    val event = ScanLogEntry(System.currentTimeMillis(), progress.orderId, "packet_scanned", sku = sku, raw = raw)
    return updated to event
}

@Composable
private fun ExportScreen(logs: List<ScanLogEntry>) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val launcher = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val data = CsvExporter.buildCsv(logs)
        activity?.let { CsvExporter.writeToUri(it, uri, data) }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Button(onClick = { CsvExporter.launchSave(launcher) }) {
            Text("Export CSV")
        }
    }
}
