package com.ams.mgr.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/** Room <-> Firestore sync with durable student deletion tombstones. */
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

        // Admin handles global tombstones. Teachers operate only on their assigned class/section.
        val tombstoneCollection = school.collection("deletedStudents")
        // Every device must read cloud tombstones. Otherwise a teacher's stale Room copy
        // survives an admin deletion and can be uploaded again during the teacher sync.
        // Teachers must query only their assigned class/section so Firestore rules
        // can authorize the query without exposing other classes' deletion metadata.
        val remoteTombstones = if (isAdmin) {
            tombstoneCollection.get().await().documents
        } else {
            tombstoneCollection.whereEqualTo("className", assignedClass)
                .whereEqualTo("section", assignedSection).get().await().documents
        }
        val remoteDeletedIds = remoteTombstones.mapNotNull { d ->
            val className = d.getString("className")
            val section = d.getString("section")
            val belongsToAssignedClass = isAdmin || (className == assignedClass && section == assignedSection)
            if (!belongsToAssignedClass) null else (d.get("studentId") as? Number)?.toLong() ?: d.id.toLongOrNull()
        }.toSet()
        val pendingRows = db.pendingStudentDeletionDao().getAllOnce()
        val eligiblePending = if (isAdmin) pendingRows else pendingRows.filter { it.className == assignedClass && it.section == assignedSection }
        val localPending = eligiblePending.map { it.studentId }.toSet()
        val deletedIds = remoteDeletedIds + localPending
        for (studentId in deletedIds) {
            val pending = eligiblePending.firstOrNull { it.studentId == studentId }
            if (pending != null) {
                val marker = mutableMapOf<String, Any>("studentId" to studentId, "deletedAt" to System.currentTimeMillis())
                if (pending.className.isNotBlank()) marker["className"] = pending.className
                if (pending.section.isNotBlank()) marker["section"] = pending.section
                tombstoneCollection.document(studentId.toString()).set(marker).await()
            }
            // Only the administrator performs global cloud cleanup for tombstones it sees.
            // Teachers only clean their local records for admin-originated deletions.
            if (isAdmin || studentId in localPending) {
                school.collection("attendance").whereEqualTo("studentId", studentId).get().await().documents.forEach { it.reference.delete().await() }
                school.collection("monthlyExams").whereEqualTo("studentId", studentId).get().await().documents.forEach { it.reference.delete().await() }
                school.collection("examMarks").whereEqualTo("studentId", studentId).get().await().documents.forEach { it.reference.delete().await() }
                school.collection("students").document(studentId.toString()).delete().await()
            }
            db.attendanceDao().deleteForStudent(studentId)
            db.monthlyExamDao().deleteForStudent(studentId)
            db.examMarkDao().deleteForStudent(studentId)
            db.studentDao().deleteById(studentId)
        }

        val allLocalStudents = db.studentDao().getAllOnce()
        val students = if (isAdmin) allLocalStudents else allLocalStudents.filter { it.className == assignedClass && it.section == assignedSection }
        val assignedIds = students.map { it.id }.toSet()
        val attendance = db.attendanceDao().getAllOnce().filter { isAdmin || it.studentId in assignedIds }
        val monthly = db.monthlyExamDao().getAllOnce().filter { isAdmin || it.studentId in assignedIds }
        val marks = db.examMarkDao().getAllOnce().filter { isAdmin || it.studentId in assignedIds }

        // Upload local records; deterministic document IDs make repeated syncs idempotent.
        students.forEach { s -> school.collection("students").document(s.id.toString()).set(mapOf(
            "id" to s.id, "studentName" to s.studentName, "studentId" to s.studentId,
            "className" to s.className, "section" to s.section, "gender" to s.gender,
            "religion" to s.religion, "fatherName" to s.fatherName, "address" to s.address,
            "mobileNumber" to s.mobileNumber
        )).await() }
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

        // Merge remote records into Room. Missing cloud records never delete local records.
        val remoteStudentsQuery = if (isAdmin) school.collection("students").get() else school.collection("students")
            .whereEqualTo("className", assignedClass).whereEqualTo("section", assignedSection).get()
        remoteStudentsQuery.await().documents.forEach { d ->
            val m = d.data ?: return@forEach
            val id = m.long("id") ?: d.id.toLongOrNull() ?: return@forEach
            db.studentDao().upsert(Student(id, m.str("studentName"), m.str("studentId"), m.str("className"), m.str("section"), m.str("gender"), m.str("religion"), m.str("fatherName"), m.str("address"), m.str("mobileNumber")))
        }
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
