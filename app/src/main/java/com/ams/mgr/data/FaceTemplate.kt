package com.ams.mgr.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local-only biometric template. No face photograph is stored. */
@Entity(
    tableName = "face_templates",
    indices = [Index(value = ["studentId"], unique = true)]
)
data class FaceTemplate(
    @PrimaryKey val studentId: Long,
    val embedding: ByteArray,
    val embeddingDimensions: Int = 128,
    val modelVersion: String = "mobilefacenet128-v1",
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long = 0L
)
