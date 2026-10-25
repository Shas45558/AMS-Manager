package com.ams.mgr.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class SchoolRepository(private val db: AppDatabase, private val context: Context) {
    fun students(query: String): Flow<List<Student>> =
        if (query.isBlank()) db.studentDao().observeAll() else db.studentDao().search(query)

    fun oldStudents(): Flow<List<Student>> = db.studentDao().observeOld()

    fun sscStudents(): Flow<List<Student>> = db.studentDao().observeSscStudents()

    suspend fun assignSscYear(studentId: Long, sscYear: String) {
        val current = db.studentDao().getById(studentId) ?: return
        if (current.className != "10") throw IllegalArgumentException("SSC year can only be assigned to Class 10 students.")
        db.studentDao().update(current.copy(sscYear = sscYear.trim()))
        db.pendingStudentUpsertDao().add(PendingStudentUpsert(current.id))
        AutoSyncScheduler.enqueue(context)
    }

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
        if (db.faceTemplateDao().getForStudent(student.id) != null) db.faceTemplateDao().markDirty(student.id)
        AutoSyncScheduler.enqueue(context)
    }

    suspend fun saveFaceTemplate(template: FaceTemplate) {
        db.faceTemplateDao().upsert(template.copy(syncedAt = 0L))
        db.pendingFaceDeletionDao().remove(template.studentId)
        AutoSyncScheduler.enqueue(context)
    }

    suspend fun getFaceTemplate(studentId: Long): FaceTemplate? = db.faceTemplateDao().getForStudent(studentId)

    suspend fun getAllFaceTemplates(): List<FaceTemplate> = db.faceTemplateDao().observeAll().first()

    suspend fun deleteFaceTemplate(studentId: Long) {
        db.faceTemplateDao().deleteForStudent(studentId)
        db.pendingFaceDeletionDao().add(PendingFaceDeletion(studentId))
        AutoSyncScheduler.enqueue(context)
    }

    suspend fun deleteStudent(student: Student) {
        // Queue the deletion locally until sync removes the cloud record and related data.
        db.pendingStudentUpsertDao().remove(student.id)
        db.pendingStudentDeletionDao().add(PendingStudentDeletion(student.id, className = student.className, section = student.section))
        db.attendanceDao().deleteForStudent(student.id)
        db.faceTemplateDao().deleteForStudent(student.id)
        db.pendingFaceDeletionDao().add(PendingFaceDeletion(student.id))
        db.monthlyExamDao().deleteForStudent(student.id)
        db.examMarkDao().deleteForStudent(student.id)
        db.studentDao().delete(student)
        AutoSyncScheduler.enqueue(context)
    }


    data class PromotionChange(val studentId: Long, val newRoll: String, val outcome: String)

    suspend fun applyPromotion(changes: List<PromotionChange>, targetClass: String, targetYear: String, moveConflictsToOld: Set<Long> = emptySet()) {
        val now = System.currentTimeMillis()
        // Archive destination-roll conflicts without deleting identity or academic records.
        for (conflictId in moveConflictsToOld) {
            val existing = db.studentDao().getById(conflictId) ?: continue
            db.studentDao().update(existing.copy(activeStatus = "old"))
            db.pendingStudentUpsertDao().add(PendingStudentUpsert(existing.id))
        }
        for (change in changes) {
            val current = db.studentDao().getById(change.studentId) ?: continue
            if (current.className == "10" && change.outcome == "Promote") {
                throw IllegalArgumentException("Class 10 students must be assigned an SSC year; they cannot be promoted to Class 11.")
            }
            val fromYear = current.academicYear.ifBlank { (java.time.LocalDate.now().year).toString() }
            val nextClass = when (change.outcome) {
                "Promote" -> targetClass
                "Repeat" -> current.className
                "Left School" -> current.className
                else -> current.className
            }
            val status = if (change.outcome == "Left School") "left" else "active"
            val history = StudentPromotionHistory(
                historyId = "${current.id}_${now}_${change.outcome.replace(" ", "_")}",
                studentId = current.id, studentName = current.studentName,
                fromClass = current.className, fromSection = current.section, fromRoll = current.studentId,
                fromAcademicYear = fromYear, toClass = nextClass, toSection = current.section,
                toRoll = change.newRoll.trim(), toAcademicYear = targetYear, outcome = change.outcome, changedAt = now
            )
            db.studentPromotionHistoryDao().insert(history)
            db.studentDao().update(current.copy(
                className = nextClass,
                studentId = change.newRoll.trim(),
                academicYear = targetYear,
                activeStatus = status,
                sscYear = if (nextClass == "10" && change.outcome == "Repeat") targetYear else current.sscYear
            ))
            db.pendingStudentUpsertDao().add(PendingStudentUpsert(current.id))
            if (db.faceTemplateDao().getForStudent(current.id) != null) db.faceTemplateDao().markDirty(current.id)
        }
        AutoSyncScheduler.enqueue(context)
    }

    suspend fun promotionHistoryOnce(): List<StudentPromotionHistory> = db.studentPromotionHistoryDao().getAllOnce()

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
