package com.ams.mgr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "students")
data class Student(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentName: String,
    val studentId: String,
    val className: String,
    val section: String,
    val gender: String = "",
    val religion: String = "",
    val fatherName: String = "",
    val address: String = "",
    val mobileNumber: String = "",
    val academicYear: String = "",
    val activeStatus: String = "active",
    val sscYear: String = ""
)
