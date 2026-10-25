package com.ams.mgr.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance WHERE studentId = :studentId ORDER BY date DESC")
    fun observeForStudent(studentId: Long): Flow<List<Attendance>>

    @Query("SELECT * FROM attendance WHERE studentId = :studentId AND date = :date LIMIT 1")
    suspend fun get(studentId: Long, date: String): Attendance?

    @Query("SELECT * FROM attendance ORDER BY id")
    suspend fun getAllOnce(): List<Attendance>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attendance: Attendance)

    @Query("DELETE FROM attendance WHERE studentId = :studentId")
    suspend fun deleteForStudent(studentId: Long)

    @Query("SELECT COUNT(*) FROM attendance WHERE date = :date AND present = 1")
    suspend fun presentCount(date: String): Int

    @Query("SELECT COUNT(*) FROM attendance WHERE date = :date AND present = 0")
    suspend fun absentCount(date: String): Int

    @Query("SELECT COUNT(*) FROM attendance WHERE date BETWEEN :startDate AND :endDate AND present = 1")
    suspend fun presentCountBetween(startDate: String, endDate: String): Int

    @Query("SELECT COUNT(*) FROM attendance WHERE date BETWEEN :startDate AND :endDate AND present = 0")
    suspend fun absentCountBetween(startDate: String, endDate: String): Int

    @Query("SELECT COUNT(*) FROM attendance WHERE studentId = :studentId AND date BETWEEN :startDate AND :endDate AND present = 1")
    suspend fun studentPresentCountBetween(studentId: Long, startDate: String, endDate: String): Int

    @Query("SELECT COUNT(*) FROM attendance WHERE studentId = :studentId AND date BETWEEN :startDate AND :endDate AND present = 0")
    suspend fun studentAbsentCountBetween(studentId: Long, startDate: String, endDate: String): Int
}
