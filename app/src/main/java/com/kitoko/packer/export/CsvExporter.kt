package com.kitoko.packer.export

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import java.nio.charset.StandardCharsets

data class ScanLogEntry(
    val timestampMs: Long,
    val orderId: String,
    val event: String,
    val sku: String? = null,
    val raw: String? = null
)

object CsvExporter {
    fun buildCsv(entries: List<ScanLogEntry>): ByteArray {
        val sb = StringBuilder()
        sb.append("timestamp_ms,order_id,event,sku,raw\n")
        for (e in entries) {
            sb.append(e.timestampMs).append(',')
                .append(csv(e.orderId)).append(',')
                .append(csv(e.event)).append(',')
                .append(csv(e.sku ?: "")).append(',')
                .append(csv(e.raw ?: ""))
                .append('\n')
        }
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun csv(s: String): String = '"' + s.replace("\"", "\"\"") + '"'

    fun launchSave(launcher: ActivityResultLauncher<Intent>, suggested: String = "scan_report.csv") {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, suggested)
        }
        launcher.launch(intent)
    }

    fun writeToUri(activity: Activity, uri: Uri, data: ByteArray) {
        activity.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(data)
        }
    }
}
