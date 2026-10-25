package com.ams.mgr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingFaceDeletionDao {
    @Query("SELECT * FROM pending_face_deletions ORDER BY queuedAt")
    suspend fun getAllOnce(): List<PendingFaceDeletion>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(row: PendingFaceDeletion)

    @Query("DELETE FROM pending_face_deletions WHERE studentId = :studentId")
    suspend fun remove(studentId: Long)
}
