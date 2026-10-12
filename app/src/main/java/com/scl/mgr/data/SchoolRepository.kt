package com.ams.mgr.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class SchoolRepository(private val db: AppDatabase, private val context: Context) {
    fun students(query: String): Flow<List<Student>> =
        if (query.isBlank()) db.studentDao().observeAll() else db.studentDao().search(query)

    fun studentCount(): Flow<Int> = db.studentDao().observeCount()

    suspend fun addStudent(student: Student): Long {
        if (db.studentDao().findDuplicate(student.className, student.section, student.studentId) != null) {
            throw IllegalArgumentException("A student with the same class, section and roll already exists.")
        }
        val id = db.studentDao().insert(student)
        db.pendingStudentUpsertDao().add(PendingStudentUpsert(id))
        AutoSyncScheduler.enqueue(context)
        return id
    }

    suspend fun updateStudent(student: Student) {
        db.studentDao().update(student)
        db.pendingStudentUpsertDao().add(PendingStudentUpsert(student.id))
        AutoSyncScheduler.enqueue(context)
    }

    suspend fun deleteStudent(student: Student) {
        // Queue the deletion locally until sync removes the cloud record and related data.
        db.pendingStudentUpsertDao().remove(student.id)
        db.pendingStudentDeletionDao().add(PendingStudentDeletion(student.id, className = student.className, section = student.section))
        db.attendanceDao().deleteForStudent(student.id)
        db.monthlyExamDao().deleteForStudent(student.id)
        db.examMarkDao().deleteForStudent(student.id)
        db.studentDao().delete(student)
        AutoSyncScheduler.enqueue(context)
    }

    suspend fun getStudent(id: Long): Student? = db.studentDao().getById(id)

    suspend fun duplicateExists(student: Student): Boolean =
        db.studentDao().findDuplicate(student.className, student.section, student.studentId)?.let { it.id != student.id } ?: false

    fun attendance(studentId: Long): Flow<List<Attendance>> = db.attendanceDao().observeForStudent(studentId)

    suspend fun attendanceForDate(studentId: Long, date: String): Attendance? =
        db.attendanceDao().get(studentId, date)

    suspend fun markAttendance(studentId: Long, date: String, present: Boolean) {
        db.attendanceDao().upsert(Attendance(studentId = studentId, date = date, present = present))
        AutoSyncScheduler.enqueue(context)
    }

    suspend fun presentCount(date: String): Int = db.attendanceDao().presentCount(date)
    suspend fun absentCount(date: String): Int = db.attendanceDao().absentCount(date)
    suspend fun presentCountBetween(startDate: String, endDate: String): Int = db.attendanceDao().presentCountBetween(startDate, endDate)
    suspend fun absentCountBetween(startDate: String, endDate: String): Int = db.attendanceDao().absentCountBetween(startDate, endDate)
    suspend fun studentPresentCountBetween(studentId: Long, startDate: String, endDate: String): Int = db.attendanceDao().studentPresentCountBetween(studentId, startDate, endDate)
    fun monthlyExams(yearMonth: String): Flow<List<MonthlyExam>> = db.monthlyExamDao().observeForMonth(yearMonth)

    suspend fun saveMonthlyExams(exams: List<MonthlyExam>) {
        db.monthlyExamDao().upsertAll(exams)
        AutoSyncScheduler.enqueue(context)
    }

    suspend fun monthlyExamsOnce(yearMonth: String): List<MonthlyExam> = db.monthlyExamDao().getForMonth(yearMonth)

    suspend fun examMarksOnce(examType: String): List<ExamMark> = db.examMarkDao().getForExam(examType)

    suspend fun saveExamMarks(marks: List<ExamMark>) {
        db.examMarkDao().upsertAll(marks)
        AutoSyncScheduler.enqueue(context)
    }

    suspend fun studentAbsentCountBetween(studentId: Long, startDate: String, endDate: String): Int = db.attendanceDao().studentAbsentCountBetween(studentId, startDate, endDate)
}
