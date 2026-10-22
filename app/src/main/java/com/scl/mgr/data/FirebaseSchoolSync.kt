package com.ams.mgr.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/** Room <-> Firestore sync. Student roster is authoritative on the admin device; no cloud deletion-marker collection is used. */
class FirebaseSchoolSync(private val db: AppDatabase) {
    private val firestore = FirebaseFirestore.getInstance()
    private val school = firestore.collection("schools").document(SCHOOL_ID)

    suspend fun sync(): SyncReport {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Please sign in before syncing.")
        try {
            user.reload().await()
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
            throw SecurityException("This Firebase account no longer exists.", e)
        }
        val email = user.email?.trim()?.lowercase()
        val isAdmin = email == ADMIN_EMAIL
        var assignedClass = ""
        var assignedSection = ""
        var assignedSections = emptySet<String>()
        if (!isAdmin) {
            // Authorization and assignment are always read from the server, never cache.
            val profile = firestore.collection("teacherAccounts").document(user.uid).get(Source.SERVER).await()
            if (!profile.exists() || profile.getString("role") != "teacher" || profile.getString("schoolId") != SCHOOL_ID || profile.getBoolean("enabled") != true) {
                throw SecurityException("This account is not authorized to sync school records.")
            }
            assignedClass = profile.getString("assignedClass") ?: ""
            assignedSection = profile.getString("assignedSection") ?: ""
            assignedSections = ((profile.get("assignedSections") as? List<*>)?.filterIsInstance<String>() ?: assignedSection.split(",")).map { it.trim() }.filter { it in listOf("A", "B") }.toSet()
            if (assignedClass.isBlank() || assignedSections.isEmpty()) throw SecurityException("The administrator has not assigned a class and section to this teacher.")
        } else if (user.isEmailVerified != true) {
            throw SecurityException("Verify the administrator email before syncing.")
        }

        // First apply explicit local deletions. Deletions are queued by the repository
        // and are sent to Firestore before the roster is downloaded.
        val pendingRows = db.pendingStudentDeletionDao().getAllOnce()
        val eligiblePending = if (isAdmin) pendingRows else pendingRows.filter { it.className == assignedClass && it.section in assignedSections }
        val localPending = eligiblePending.map { it.studentId }.toSet()
        for (studentId in localPending) {
            school.collection("attendance").whereEqualTo("studentId", studentId).get().await().documents.forEach { it.reference.delete().await() }
            school.collection("monthlyExams").whereEqualTo("studentId", studentId).get().await().documents.forEach { it.reference.delete().await() }
            school.collection("examMarks").whereEqualTo("studentId", studentId).get().await().documents.forEach { it.reference.delete().await() }
            school.collection("students").document(studentId.toString()).delete().await()
            db.pendingStudentUpsertDao().remove(studentId)
            db.attendanceDao().deleteForStudent(studentId)
            db.monthlyExamDao().deleteForStudent(studentId)
            db.examMarkDao().deleteForStudent(studentId)
            db.studentDao().deleteById(studentId)
        }

        // Upload only explicit pending additions/edits. Never bulk-upload every local
        // row: an old copy on another device must not resurrect a cloud-deleted student.
        val pendingUpserts = db.pendingStudentUpsertDao().getAllOnce()
        for (pending in pendingUpserts) {
            if (pending.studentId in localPending) continue
            val student = db.studentDao().getById(pending.studentId)
            if (student == null) {
                db.pendingStudentUpsertDao().remove(pending.studentId)
                continue
            }
            // A teacher may promote a student out of their assigned class; Firestore rules
            // validate the existing cloud row is in their assignment and the section is unchanged.
            school.collection("students").document(student.id.toString()).set(mapOf(
                "id" to student.id, "studentName" to student.studentName, "studentId" to student.studentId,
                "className" to student.className, "section" to student.section, "gender" to student.gender,
                "religion" to student.religion, "fatherName" to student.fatherName, "address" to student.address,
                "mobileNumber" to student.mobileNumber, "academicYear" to student.academicYear, "activeStatus" to student.activeStatus, "sscYear" to student.sscYear
            )).await()
            db.pendingStudentUpsertDao().remove(student.id)
        }

        // Promotion history is immutable audit data. Admin writes all history; teachers may upload
        // only history originating from their assigned class and section.
        if (isAdmin || assignedClass.isNotBlank()) {
            val localHistory = db.studentPromotionHistoryDao().getAllOnce().filter { h -> isAdmin || (h.fromClass == assignedClass && h.fromSection in assignedSections) }
            for (h in localHistory) {
                school.collection("promotionHistory").document(h.historyId).set(mapOf(
                    "historyId" to h.historyId, "studentId" to h.studentId, "studentName" to h.studentName,
                    "fromClass" to h.fromClass, "fromSection" to h.fromSection, "fromRoll" to h.fromRoll,
                    "fromAcademicYear" to h.fromAcademicYear, "toClass" to h.toClass, "toSection" to h.toSection,
                    "toRoll" to h.toRoll, "toAcademicYear" to h.toAcademicYear, "outcome" to h.outcome,
                    "changedAt" to h.changedAt
                )).await()
            }
            val remoteHistory = if (isAdmin) school.collection("promotionHistory").get().await().documents.mapNotNull { d ->
                val m = d.data ?: return@mapNotNull null
                StudentPromotionHistory(
                    historyId = m["historyId"] as? String ?: d.id,
                    studentId = (m["studentId"] as? Number)?.toLong() ?: return@mapNotNull null,
                    studentName = m["studentName"] as? String ?: "",
                    fromClass = m["fromClass"] as? String ?: "", fromSection = m["fromSection"] as? String ?: "",
                    fromRoll = m["fromRoll"] as? String ?: "", fromAcademicYear = m["fromAcademicYear"] as? String ?: "",
                    toClass = m["toClass"] as? String ?: "", toSection = m["toSection"] as? String ?: "",
                    toRoll = m["toRoll"] as? String ?: "", toAcademicYear = m["toAcademicYear"] as? String ?: "",
                    outcome = m["outcome"] as? String ?: "", changedAt = (m["changedAt"] as? Number)?.toLong() ?: 0L
                )
            } else emptyList()
            db.studentPromotionHistoryDao().insertAll(remoteHistory)
        }

        // The server roster is fetched after queued changes have been applied. It is
        // authoritative for all records this account is allowed to see.
        val remoteStudentDocs = if (isAdmin) school.collection("students").get().await().documents else assignedSections.flatMap { sectionValue ->
            school.collection("students").whereEqualTo("className", assignedClass).whereEqualTo("section", sectionValue).get().await().documents
        }.distinctBy { it.id }
        val remoteIds = remoteStudentDocs.mapNotNull { d -> (d.get("id") as? Number)?.toLong() ?: d.id.toLongOrNull() }.toSet()
        val localStudentsBeforeMerge = db.studentDao().getAllOnce()
        val pendingIds = db.pendingStudentUpsertDao().getAllOnce().map { it.studentId }.toSet()
        val eligibleLocal = if (isAdmin) localStudentsBeforeMerge else localStudentsBeforeMerge.filter { it.className == assignedClass && it.section in assignedSections }
        eligibleLocal.filter { it.id !in remoteIds && it.id !in pendingIds && it.id !in localPending }.forEach { stale ->
            // A student can be absent from this class because of promotion or a
            // section change. Remove only the stale roster row; never erase history.
            db.studentDao().deleteById(stale.id)
        }
        remoteStudentDocs.forEach { d ->
            val m = d.data ?: return@forEach
            val id = m.long("id") ?: d.id.toLongOrNull() ?: return@forEach
            db.studentDao().upsert(Student(id, m.str("studentName"), m.str("studentId"), m.str("className"), m.str("section"), m.str("gender"), m.str("religion"), m.str("fatherName"), m.str("address"), m.str("mobileNumber"), m.str("academicYear"), m.str("activeStatus").ifBlank { "active" }, m.str("sscYear")))
        }
        val students = if (isAdmin) db.studentDao().getAllOnce() else db.studentDao().getAllOnce().filter { it.className == assignedClass && it.section in assignedSections }
        val assignedIds = students.map { it.id }.toSet()
        val attendance = db.attendanceDao().getAllOnce().filter { isAdmin || it.studentId in assignedIds }
        val monthly = db.monthlyExamDao().getAllOnce().filter { isAdmin || it.studentId in assignedIds }
        val marks = db.examMarkDao().getAllOnce().filter { isAdmin || it.studentId in assignedIds }
        attendance.forEach { a -> school.collection("attendance").document("${a.studentId}_${a.date}").set(mapOf(
            "id" to a.id, "studentId" to a.studentId, "date" to a.date, "present" to a.present
        )).await() }
        monthly.forEach { e -> school.collection("monthlyExams").document("${e.studentId}_${e.yearMonth}").set(mapOf(
            "studentId" to e.studentId, "yearMonth" to e.yearMonth, "bangla" to e.bangla,
            "english" to e.english, "math" to e.math, "socialScience" to e.socialScience,
            "science" to e.science, "religion" to e.religion, "physics" to e.physics,
            "chemistry" to e.chemistry, "biology" to e.biology, "hMOrAgri" to e.hMOrAgri
        )).await() }
        marks.forEach { e -> school.collection("examMarks").document("${e.studentId}_${e.examType}_${e.subjectKey}").set(mapOf(
            "studentId" to e.studentId, "examType" to e.examType, "subjectKey" to e.subjectKey,
            "cq" to e.cq, "mcq" to e.mcq
        )).await() }

        // Student roster was reconciled from the cloud above.
        val remoteAttendance = if (isAdmin) school.collection("attendance").get().await().documents else queryByStudentIds("attendance", assignedIds)
        remoteAttendance.forEach { d ->
            val m = d.data ?: return@forEach
            val studentId = m.long("studentId") ?: return@forEach
            val date = m.str("date")
            db.attendanceDao().upsert(Attendance(m.long("id") ?: 0L, studentId, date, m["present"] as? Boolean ?: false))
        }
        val remoteMonthly = if (isAdmin) school.collection("monthlyExams").get().await().documents else queryByStudentIds("monthlyExams", assignedIds)
        val monthlyRemote = remoteMonthly.mapNotNull { d ->
            val m = d.data ?: return@mapNotNull null
            val id = m.long("studentId") ?: return@mapNotNull null
            MonthlyExam(id, m.str("yearMonth"), m.int("bangla"), m.int("english"), m.int("math"), m.int("socialScience"), m.int("science"), m.int("religion"), m.int("physics"), m.int("chemistry"), m.int("biology"), m.int("hMOrAgri"))
        }
        db.monthlyExamDao().upsertAll(monthlyRemote)
        val remoteMarks = if (isAdmin) school.collection("examMarks").get().await().documents else queryByStudentIds("examMarks", assignedIds)
        val marksRemote = remoteMarks.mapNotNull { d ->
            val m = d.data ?: return@mapNotNull null
            val id = m.long("studentId") ?: return@mapNotNull null
            ExamMark(id, m.str("examType"), m.str("subjectKey"), m.int("cq"), m.int("mcq"))
        }
        db.examMarkDao().upsertAll(marksRemote)
        localPending.forEach { db.pendingStudentDeletionDao().remove(it) }
        return SyncReport(students.size, attendance.size, monthly.size, marks.size)
    }

    private suspend fun queryByStudentIds(collection: String, studentIds: Set<Long>): List<com.google.firebase.firestore.DocumentSnapshot> {
        if (studentIds.isEmpty()) return emptyList()
        val result = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
        // Firestore whereIn supports up to 30 values per query.
        studentIds.toList().chunked(30).forEach { chunk ->
            result += school.collection(collection).whereIn("studentId", chunk).get().await().documents
        }
        return result
    }

    private fun Map<String, Any?>.str(key: String) = this[key] as? String ?: ""
    private fun Map<String, Any?>.long(key: String) = (this[key] as? Number)?.toLong()
    private fun Map<String, Any?>.int(key: String) = (this[key] as? Number)?.toInt()

    companion object {
        const val SCHOOL_ID = "ams-manager-b1434"
        const val ADMIN_EMAIL = "shas45558@gmail.com"
    }
}

data class SyncReport(val studentsUploaded: Int, val attendanceUploaded: Int, val monthlyExamsUploaded: Int, val examMarksUploaded: Int)
