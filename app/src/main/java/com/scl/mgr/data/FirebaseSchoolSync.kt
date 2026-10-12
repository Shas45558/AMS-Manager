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
        if (!isAdmin) {
            // Authorization and assignment are always read from the server, never cache.
            val profile = firestore.collection("teacherAccounts").document(user.uid).get(Source.SERVER).await()
            if (!profile.exists() || profile.getString("role") != "teacher" || profile.getString("schoolId") != SCHOOL_ID || profile.getBoolean("enabled") != true) {
                throw SecurityException("This account is not authorized to sync school records.")
            }
            assignedClass = profile.getString("assignedClass") ?: ""
            assignedSection = profile.getString("assignedSection") ?: ""
            if (assignedClass.isBlank() || assignedSection.isBlank()) throw SecurityException("The administrator has not assigned a class and section to this teacher.")
        } else if (user.isEmailVerified != true) {
            throw SecurityException("Verify the administrator email before syncing.")
        }

        // Process local deletion requests by deleting cloud records directly. No
        // Firestore deletedStudents collection is created or queried.
        val pendingRows = db.pendingStudentDeletionDao().getAllOnce()
        val eligiblePending = if (isAdmin) pendingRows else pendingRows.filter { it.className == assignedClass && it.section == assignedSection }
        val localPending = eligiblePending.map { it.studentId }.toSet()
        for (studentId in localPending) {
            school.collection("attendance").whereEqualTo("studentId", studentId).get().await().documents.forEach { it.reference.delete().await() }
            school.collection("monthlyExams").whereEqualTo("studentId", studentId).get().await().documents.forEach { it.reference.delete().await() }
            school.collection("examMarks").whereEqualTo("studentId", studentId).get().await().documents.forEach { it.reference.delete().await() }
            school.collection("students").document(studentId.toString()).delete().await()
            db.attendanceDao().deleteForStudent(studentId)
            db.monthlyExamDao().deleteForStudent(studentId)
            db.examMarkDao().deleteForStudent(studentId)
            db.studentDao().deleteById(studentId)
        }

        val allLocalStudents = db.studentDao().getAllOnce()
        // The admin owns the roster and is the only account that uploads student documents.
        // This prevents an old teacher device from recreating a student deleted by the admin.
        if (isAdmin) {
            allLocalStudents.forEach { s -> school.collection("students").document(s.id.toString()).set(mapOf(
                "id" to s.id, "studentName" to s.studentName, "studentId" to s.studentId,
                "className" to s.className, "section" to s.section, "gender" to s.gender,
                "religion" to s.religion, "fatherName" to s.fatherName, "address" to s.address,
                "mobileNumber" to s.mobileNumber
            )).await() }
        }

        // Pull the cloud roster before teacher records are uploaded. For teachers, the cloud
        // roster is authoritative for their assigned class/section: remove stale local students
        // that no longer exist remotely, then merge the current cloud roster into Room.
        val remoteStudentsQuery = if (isAdmin) school.collection("students").get() else school.collection("students")
            .whereEqualTo("className", assignedClass).whereEqualTo("section", assignedSection).get()
        val remoteStudentDocs = remoteStudentsQuery.await().documents
        if (!isAdmin) {
            val remoteIds = remoteStudentDocs.mapNotNull { d -> (d.get("id") as? Number)?.toLong() ?: d.id.toLongOrNull() }.toSet()
            allLocalStudents.filter { it.className == assignedClass && it.section == assignedSection && it.id !in remoteIds }
                .forEach { stale ->
                    db.attendanceDao().deleteForStudent(stale.id)
                    db.monthlyExamDao().deleteForStudent(stale.id)
                    db.examMarkDao().deleteForStudent(stale.id)
                    db.studentDao().deleteById(stale.id)
                }
        }
        remoteStudentDocs.forEach { d ->
            val m = d.data ?: return@forEach
            val id = m.long("id") ?: d.id.toLongOrNull() ?: return@forEach
            db.studentDao().upsert(Student(id, m.str("studentName"), m.str("studentId"), m.str("className"), m.str("section"), m.str("gender"), m.str("religion"), m.str("fatherName"), m.str("address"), m.str("mobileNumber")))
        }
        val students = if (isAdmin) allLocalStudents.filter { it.id !in localPending } else db.studentDao().getAllOnce().filter { it.className == assignedClass && it.section == assignedSection }
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
