package com.ams.mgr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local tombstone retained until a successful sync propagates the deletion to Firestore. */
@Entity(tableName = "pending_student_deletions")
data class PendingStudentDeletion(@PrimaryKey val studentId: Long, val deletedAt: Long = System.currentTimeMillis())
