package com.ams.mgr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_face_deletions")
data class PendingFaceDeletion(
    @PrimaryKey val studentId: Long,
    val queuedAt: Long = System.currentTimeMillis()
)
