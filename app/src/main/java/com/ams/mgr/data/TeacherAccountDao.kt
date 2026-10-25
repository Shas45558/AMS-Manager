package com.ams.mgr.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TeacherAccountDao {
    @Query("SELECT * FROM teacher_accounts ORDER BY userId")
    suspend fun getAllOnce(): List<TeacherAccount>

    @Query("SELECT * FROM teacher_accounts WHERE userId = :userId LIMIT 1")
    suspend fun getByUserId(userId: String): TeacherAccount?

    @Upsert
    suspend fun upsert(account: TeacherAccount)

    @Query("DELETE FROM teacher_accounts WHERE userId = :userId")
    suspend fun deleteByUserId(userId: String)
}
