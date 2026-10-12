package com.ams.mgr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StudentPromotionHistoryDao {
    @Query("SELECT * FROM student_promotion_history ORDER BY changedAt DESC")
    suspend fun getAllOnce(): List<StudentPromotionHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: StudentPromotionHistory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<StudentPromotionHistory>)
}
