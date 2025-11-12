package com.kitoko.packer.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class OrderRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    suspend fun markOrderPacked(orderId: String) {
        // Idempotent write for audit; no-op if Firebase not configured
        try {
            firestore.collection("packedOrders").document(orderId)
                .set(mapOf("packedAt" to System.currentTimeMillis()))
                .await()
        } catch (_: Throwable) {
            // ignore offline or missing config
        }
    }
}
