package com.kitoko.packer.model

import kotlinx.serialization.Serializable

@Serializable
data class InvoicePayload(
    val o: String, // order id
    val i: List<ItemEntry> // [ [sku, units], ... ]
)

@Serializable
data class ItemEntry(
    val sku: String,
    val units: Int
)

@Serializable
data class PacketPayload(
    val s: String // sku
)

data class PackProgress(
    val orderId: String,
    val required: Map<String, Int>,
    val scannedUnitsBySku: Map<String, Int> = emptyMap(),
    val seenPacketQrs: Set<String> = emptySet()
) {
    val remainingBySku: Map<String, Int>
        get() = required.mapValues { (sku, need) -> need - (scannedUnitsBySku[sku] ?: 0) }

    val isComplete: Boolean
        get() = remainingBySku.values.all { it <= 0 }
}
