package com.ams.mgr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "student_promotion_history")
data class StudentPromotionHistory(
    @PrimaryKey val historyId: String,
    val studentId: Long,
    val studentName: String,
    val fromClass: String,
    val fromSection: String,
    val fromRoll: String,
    val fromAcademicYear: String,
    val toClass: String,
    val toSection: String,
    val toRoll: String,
    val toAcademicYear: String,
    val outcome: String,
    val changedAt: Long = System.currentTimeMillis()
)
