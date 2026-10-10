package com.ams.mgr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingStudentDeletionDao {
    @Query("SELECT * FROM pending_student_deletions ORDER BY deletedAt")
    suspend fun getAllOnce(): List<PendingStudentDeletion>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(item: PendingStudentDeletion)

    @Query("DELETE FROM pending_student_deletions WHERE studentId = :studentId")
    suspend fun remove(studentId: Long)

    @Query("DELETE FROM pending_student_deletions")
    suspend fun clearAll()
}
