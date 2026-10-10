package com.ams.mgr.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MonthlyExamDao {
    @Query("SELECT * FROM monthly_exams WHERE yearMonth = :yearMonth")
    fun observeForMonth(yearMonth: String): Flow<List<MonthlyExam>>

    @Query("SELECT * FROM monthly_exams WHERE yearMonth = :yearMonth")
    suspend fun getForMonth(yearMonth: String): List<MonthlyExam>

    @Query("SELECT * FROM monthly_exams")
    suspend fun getAllOnce(): List<MonthlyExam>

    @Upsert
    suspend fun upsert(exam: MonthlyExam)

    @Upsert
    suspend fun upsertAll(exams: List<MonthlyExam>)

    @Query("DELETE FROM monthly_exams WHERE studentId = :studentId")
    suspend fun deleteForStudent(studentId: Long)
}
