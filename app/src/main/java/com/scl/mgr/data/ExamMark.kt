package com.ams.mgr.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "exam_marks",
    primaryKeys = ["studentId", "examType", "subjectKey"],
    indices = [Index(value = ["examType"])]
)
data class ExamMark(
    val studentId: Long,
    val examType: String,
    val subjectKey: String,
    val cq: Int? = null,
    val mcq: Int? = null
)
