package com.ams.mgr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingStudentUpsertDao {
    @Query("SELECT * FROM pending_student_upserts ORDER BY queuedAt")
    suspend fun getAllOnce(): List<PendingStudentUpsert>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(item: PendingStudentUpsert)

    @Query("DELETE FROM pending_student_upserts WHERE studentId = :studentId")
    suspend fun remove(studentId: Long)
}
