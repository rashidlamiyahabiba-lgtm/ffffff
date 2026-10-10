package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores records of WhatsApp Linked Devices workflows and pairing requests.
 */
@Entity(tableName = "whatsapp_link_records")
data class WhatsAppLinkRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val pairingCode: String,
    val phoneNumber: String,
    val status: String, // "SUCCESS", "PENDING", "FAILED"
    val details: String
)
