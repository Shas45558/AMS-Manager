package com.ams.mgr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A locally created/edited student that must reach Firestore before cloud reconciliation. */
@Entity(tableName = "pending_student_upserts")
data class PendingStudentUpsert(
    @PrimaryKey val studentId: Long,
    val queuedAt: Long = System.currentTimeMillis()
)
