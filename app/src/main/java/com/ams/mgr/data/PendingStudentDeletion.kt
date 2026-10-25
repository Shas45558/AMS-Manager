package com.ams.mgr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local deletion request retained until sync removes the cloud record and related data. */
@Entity(tableName = "pending_student_deletions")
data class PendingStudentDeletion(
    @PrimaryKey val studentId: Long,
    val deletedAt: Long = System.currentTimeMillis(),
    val className: String = "",
    val section: String = ""
)
