package com.ams.mgr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceTemplateDao {
    @Query("SELECT * FROM face_templates ORDER BY studentId")
    fun observeAll(): Flow<List<FaceTemplate>>

    @Query("SELECT * FROM face_templates WHERE studentId = :studentId LIMIT 1")
    suspend fun getForStudent(studentId: Long): FaceTemplate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: FaceTemplate)

    @Query("DELETE FROM face_templates WHERE studentId = :studentId")
    suspend fun deleteForStudent(studentId: Long)

    @Query("SELECT COUNT(*) FROM face_templates")
    suspend fun count(): Int

    @Query("UPDATE face_templates SET syncedAt = :syncedAt WHERE studentId = :studentId")
    suspend fun markSynced(studentId: Long, syncedAt: Long)

    @Query("UPDATE face_templates SET syncedAt = 0 WHERE studentId = :studentId")
    suspend fun markDirty(studentId: Long)
}
