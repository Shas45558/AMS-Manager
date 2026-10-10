package com.ams.mgr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Teacher login credentials stored in the SQLite DB so they travel with the shared backup. */
@Entity(tableName = "teacher_accounts")
data class TeacherAccount(
    @PrimaryKey val userId: String,
    val salt: String,
    val passwordHash: String,
    val updatedAt: Long = System.currentTimeMillis()
)
