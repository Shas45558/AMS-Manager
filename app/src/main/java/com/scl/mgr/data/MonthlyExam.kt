package com.ams.mgr.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "monthly_exams",
    primaryKeys = ["studentId", "yearMonth"],
    indices = [Index(value = ["yearMonth"])]
)
data class MonthlyExam(
    val studentId: Long,
    val yearMonth: String,
    val bangla: Int? = null,
    val english: Int? = null,
    val math: Int? = null,
    val socialScience: Int? = null,
    val science: Int? = null,
    val religion: Int? = null,
    val physics: Int? = null,
    val chemistry: Int? = null,
    val biology: Int? = null,
    val hMOrAgri: Int? = null
)
