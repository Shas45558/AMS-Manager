package com.ams.mgr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "school_closures")
data class SchoolClosure(
    @PrimaryKey val date: String,
    val reason: String = "Holiday"
)
