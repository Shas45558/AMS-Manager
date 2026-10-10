package com.ams.mgr.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StudentDao {
    @Query("SELECT * FROM students ORDER BY className, section, studentId, studentName COLLATE NOCASE")
    fun observeAll(): Flow<List<Student>>

    @Query("""
        SELECT * FROM students
        WHERE studentName LIKE '%' || :query || '%'
           OR studentId LIKE '%' || :query || '%'
           OR className LIKE '%' || :query || '%'
           OR section LIKE '%' || :query || '%'
           OR fatherName LIKE '%' || :query || '%'
        ORDER BY className, section, studentId, studentName COLLATE NOCASE
    """)
    fun search(query: String): Flow<List<Student>>

    @Query("SELECT * FROM students WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Student?

    @Query("SELECT * FROM students WHERE className = :className AND section = :section AND studentId = :studentId LIMIT 1")
    suspend fun findDuplicate(className: String, section: String, studentId: String): Student?

    @Query("SELECT * FROM students ORDER BY id")
    suspend fun getAllOnce(): List<Student>

    @Insert
    suspend fun insert(student: Student): Long

    @Update
    suspend fun update(student: Student)

    @Delete
    suspend fun delete(student: Student)

    @Query("SELECT COUNT(*) FROM students")
    fun observeCount(): Flow<Int>
}
