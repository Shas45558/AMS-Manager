package com.ams.mgr.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Merge-only Room <-> Firestore sync. It never deletes local or cloud documents. */
class FirebaseSchoolSync(private val db: AppDatabase) {
    private val firestore = FirebaseFirestore.getInstance()
    private val school = firestore.collection("schools").document(SCHOOL_ID)

    suspend fun sync(): SyncReport {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Please sign in before syncing.")
        user.reload().await()
        val email = user.email?.trim()?.lowercase()
        if (email != ADMIN_EMAIL) {
            // Spark-compatible role check: role records are writable only by the verified admin.
            val profile = firestore.collection("teacherAccounts").document(user.uid).get().await()
            if (!profile.exists() || profile.getString("role") != "teacher" || profile.getString("schoolId") != SCHOOL_ID || profile.getBoolean("enabled") != true) {
                throw SecurityException("This account is not authorized to sync school records.")
            }
        } else if (user.isEmailVerified != true) {
            throw SecurityException("Verify the administrator email before syncing.")
        }

        val students = db.studentDao().getAllOnce()
        val attendance = db.attendanceDao().getAllOnce()
        val monthly = db.monthlyExamDao().getAllOnce()
        val marks = db.examMarkDao().getAllOnce()

        // Upload first; deterministic document IDs make repeated syncs idempotent.
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
        school.collection("students").get().await().documents.forEach { d ->
            val m = d.data ?: return@forEach
            val id = m.long("id") ?: d.id.toLongOrNull() ?: return@forEach
            db.studentDao().upsert(Student(id, m.str("studentName"), m.str("studentId"), m.str("className"), m.str("section"), m.str("gender"), m.str("religion"), m.str("fatherName"), m.str("address"), m.str("mobileNumber")))
        }
        school.collection("attendance").get().await().documents.forEach { d ->
            val m = d.data ?: return@forEach
            val studentId = m.long("studentId") ?: return@forEach
            val date = m.str("date")
            db.attendanceDao().upsert(Attendance(m.long("id") ?: 0L, studentId, date, m["present"] as? Boolean ?: false))
        }
        val monthlyRemote = school.collection("monthlyExams").get().await().documents.mapNotNull { d ->
            val m = d.data ?: return@mapNotNull null
            val id = m.long("studentId") ?: return@mapNotNull null
            MonthlyExam(id, m.str("yearMonth"), m.int("bangla"), m.int("english"), m.int("math"), m.int("socialScience"), m.int("science"), m.int("religion"), m.int("physics"), m.int("chemistry"), m.int("biology"), m.int("hMOrAgri"))
        }
        db.monthlyExamDao().upsertAll(monthlyRemote)
        val marksRemote = school.collection("examMarks").get().await().documents.mapNotNull { d ->
            val m = d.data ?: return@mapNotNull null
            val id = m.long("studentId") ?: return@mapNotNull null
            ExamMark(id, m.str("examType"), m.str("subjectKey"), m.int("cq"), m.int("mcq"))
        }
        db.examMarkDao().upsertAll(marksRemote)
        return SyncReport(students.size, attendance.size, monthly.size, marks.size)
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
