package com.ams.mgr.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ExamMarkDao {
    @Query("SELECT * FROM exam_marks WHERE examType = :examType")
    suspend fun getForExam(examType: String): List<ExamMark>

    @Query("SELECT * FROM exam_marks")
    suspend fun getAllOnce(): List<ExamMark>

    @Upsert suspend fun upsert(mark: ExamMark)
    @Upsert suspend fun upsertAll(marks: List<ExamMark>)

    @Query("DELETE FROM exam_marks WHERE studentId = :studentId")
    suspend fun deleteForStudent(studentId: Long)
}
