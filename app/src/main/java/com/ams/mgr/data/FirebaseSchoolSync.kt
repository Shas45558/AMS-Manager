package com.ams.mgr.data

import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.first
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
        val isPrimaryAdmin = email == ADMIN_EMAIL && user.isEmailVerified == true
        val isCoAdmin = if (!isPrimaryAdmin) {
            val profile = firestore.collection("adminAccounts").document(user.uid).get(Source.SERVER).await()
            profile.exists() && profile.getString("role") == "co-admin" && profile.getString("schoolId") == SCHOOL_ID && profile.getBoolean("enabled") == true
        } else false
        val isAdmin = isPrimaryAdmin || isCoAdmin
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
        } else if (!isAdmin) {
            throw SecurityException("This account is not an enabled administrator or co-admin.")
        }

        // First apply explicit local deletions. Deletions are queued by the repository
        // and are sent to Firestore before the roster is downloaded.
        val pendingRows = db.pendingStudentDeletionDao().getAllOnce()
        val eligiblePending = if (isAdmin) pendingRows else pendingRows.filter { it.className == assignedClass && it.section in assignedSections }
        val localPending = eligiblePending.map { it.studentId }.toSet()
        for (studentId in localPending) {
            val pendingRow = eligiblePending.firstOrNull { it.studentId == studentId }
            suspend fun deleteStudentRecords(collectionName: String) {
                val query = if (isAdmin) school.collection(collectionName).get().await().documents else {
                    school.collection(collectionName)
                        .whereEqualTo("className", pendingRow?.className ?: assignedClass)
                        .whereEqualTo("section", pendingRow?.section ?: assignedSections.first())
                        .get().await().documents
                }
                query.filter { (it.getLong("studentId") ?: -1L) == studentId }.forEach { it.reference.delete().await() }
            }
            deleteStudentRecords("attendance")
            deleteStudentRecords("monthlyExams")
            deleteStudentRecords("examMarks")
            school.collection("faceTemplates").document(studentId.toString()).delete().await()
            school.collection("students").document(studentId.toString()).delete().await()
            db.pendingFaceDeletionDao().remove(studentId)
            db.pendingStudentUpsertDao().remove(studentId)
            db.attendanceDao().deleteForStudent(studentId)
            db.monthlyExamDao().deleteForStudent(studentId)
            db.examMarkDao().deleteForStudent(studentId)
            db.studentDao().deleteById(studentId)
        }

        // Face-template deletions are queued separately so a deleted biometric cannot
        // be recreated from an older cloud copy.
        val pendingFaceDeletes = db.pendingFaceDeletionDao().getAllOnce()
        for (pending in pendingFaceDeletes) {
            val student = db.studentDao().getById(pending.studentId)
            val allowed = isAdmin || (student != null && student.className == assignedClass && student.section in assignedSections)
            if (!allowed) continue
            school.collection("faceTemplates").document(pending.studentId.toString()).delete().await()
            db.pendingFaceDeletionDao().remove(pending.studentId)
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
        // authoritative for all records this account is allowed to see. Enabled teachers
        // are intentionally allowed to READ the full student roster so Student Details and
        // Face Check can identify any student. Firestore rules still restrict teacher writes
        // to their assigned class/section.
        val remoteStudentDocs = school.collection("students").get().await().documents
        val remoteIds = remoteStudentDocs.mapNotNull { d -> (d.get("id") as? Number)?.toLong() ?: d.id.toLongOrNull() }.toSet()
        val localStudentsBeforeMerge = db.studentDao().getAllOnce()
        val pendingIds = db.pendingStudentUpsertDao().getAllOnce().map { it.studentId }.toSet()
        val eligibleLocal = localStudentsBeforeMerge
        eligibleLocal.filter { it.id !in remoteIds && it.id !in pendingIds && it.id !in localPending }.forEach { stale ->
            // A student can be absent from the cloud roster because it was deleted.
            // Remove only the stale roster row; never erase attendance/exam history here.
            db.studentDao().deleteById(stale.id)
        }
        remoteStudentDocs.forEach { d ->
            val m = d.data ?: return@forEach
            val id = m.long("id") ?: d.id.toLongOrNull() ?: return@forEach
            db.studentDao().upsert(Student(id, m.str("studentName"), m.str("studentId"), m.str("className"), m.str("section"), m.str("gender"), m.str("religion"), m.str("fatherName"), m.str("address"), m.str("mobileNumber"), m.str("academicYear"), m.str("activeStatus").ifBlank { "active" }, m.str("sscYear")))
        }
        // Keep the full local roster for read-only Student Details / Face Check.
        // Only assignedIds are used for teacher write operations below.
        val students = db.studentDao().getAllOnce()
        val assignedIds = students.filter { it.className == assignedClass && it.section in assignedSections }.map { it.id }.toSet()
        val studentById = students.associateBy { it.id }

        // Biometric enrollment sync: only the numerical face embedding is stored, never
        // the original face photo. Admins and enabled teachers may READ all registered
        // embeddings for Face Check; teachers may UPLOAD/replace only assigned students.
        val localFaces = db.faceTemplateDao().observeAll().first().filter { it.studentId in assignedIds || isAdmin }
        localFaces.filter { it.syncedAt < it.updatedAt }.forEach { face ->
            val student = studentById[face.studentId] ?: return@forEach
            val encoded = Base64.encodeToString(face.embedding, Base64.NO_WRAP)
            school.collection("faceTemplates").document(face.studentId.toString()).set(mapOf(
                "studentId" to face.studentId,
                "className" to student.className,
                "section" to student.section,
                "embedding" to encoded,
                "embeddingDimensions" to face.embeddingDimensions,
                "modelVersion" to face.modelVersion,
                "updatedAt" to face.updatedAt
            )).await()
            db.faceTemplateDao().markSynced(face.studentId, face.updatedAt)
        }

        // Read all registered face templates. This is required because Face Check is a
        // school-wide identity lookup, not an attendance operation. Firestore rules allow
        // enabled teachers to read these documents but still restrict writes.
        val remoteFaces = school.collection("faceTemplates").get().await().documents
        remoteFaces.forEach { d ->
            val m = d.data ?: return@forEach
            val studentId = m.long("studentId") ?: d.id.toLongOrNull() ?: return@forEach
            val encoded = m.str("embedding")
            if (encoded.isBlank()) return@forEach
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return@forEach
            val updatedAt = m.long("updatedAt") ?: 0L
            val local = db.faceTemplateDao().getForStudent(studentId)
            if (local == null || updatedAt > local.updatedAt) {
                db.faceTemplateDao().upsert(FaceTemplate(
                    studentId = studentId,
                    embedding = bytes,
                    embeddingDimensions = (m["embeddingDimensions"] as? Number)?.toInt() ?: 512,
                    modelVersion = m.str("modelVersion").ifBlank { "facenet512-v1" },
                    updatedAt = updatedAt,
                    syncedAt = updatedAt
                ))
            }
        }
        val attendance = db.attendanceDao().getAllOnce().filter { isAdmin || it.studentId in assignedIds }
        val monthly = db.monthlyExamDao().getAllOnce().filter { isAdmin || it.studentId in assignedIds }
        val marks = db.examMarkDao().getAllOnce().filter { isAdmin || it.studentId in assignedIds }
        attendance.forEach { a -> school.collection("attendance").document("${a.studentId}_${a.date}").set(mapOf(
            "id" to a.id, "studentId" to a.studentId, "date" to a.date, "present" to a.present,
            "className" to (studentById[a.studentId]?.className ?: ""), "section" to (studentById[a.studentId]?.section ?: "")
        )).await() }
        monthly.forEach { e -> school.collection("monthlyExams").document("${e.studentId}_${e.yearMonth}").set(mapOf(
            "studentId" to e.studentId, "className" to (studentById[e.studentId]?.className ?: ""),
            "section" to (studentById[e.studentId]?.section ?: ""), "yearMonth" to e.yearMonth, "bangla" to e.bangla,
            "english" to e.english, "math" to e.math, "socialScience" to e.socialScience,
            "science" to e.science, "religion" to e.religion, "physics" to e.physics,
            "chemistry" to e.chemistry, "biology" to e.biology, "hMOrAgri" to e.hMOrAgri
        )).await() }
        marks.forEach { e -> school.collection("examMarks").document("${e.studentId}_${e.examType}_${e.subjectKey}").set(mapOf(
            "studentId" to e.studentId, "className" to (studentById[e.studentId]?.className ?: ""),
            "section" to (studentById[e.studentId]?.section ?: ""), "examType" to e.examType, "subjectKey" to e.subjectKey,
            "cq" to e.cq, "mcq" to e.mcq
        )).await() }

        // Student roster was reconciled from the cloud above.
        val remoteAttendance = if (isAdmin) school.collection("attendance").get().await().documents else queryByAssignment("attendance", assignedClass, assignedSections)
        remoteAttendance.forEach { d ->
            val m = d.data ?: return@forEach
            val studentId = m.long("studentId") ?: return@forEach
            val date = m.str("date")
            db.attendanceDao().upsert(Attendance(m.long("id") ?: 0L, studentId, date, m["present"] as? Boolean ?: false))
        }
        val remoteMonthly = if (isAdmin) school.collection("monthlyExams").get().await().documents else queryByAssignment("monthlyExams", assignedClass, assignedSections)
        val monthlyRemote = remoteMonthly.mapNotNull { d ->
            val m = d.data ?: return@mapNotNull null
            val id = m.long("studentId") ?: return@mapNotNull null
            MonthlyExam(id, m.str("yearMonth"), m.int("bangla"), m.int("english"), m.int("math"), m.int("socialScience"), m.int("science"), m.int("religion"), m.int("physics"), m.int("chemistry"), m.int("biology"), m.int("hMOrAgri"))
        }
        db.monthlyExamDao().upsertAll(monthlyRemote)
        val remoteMarks = if (isAdmin) school.collection("examMarks").get().await().documents else queryByAssignment("examMarks", assignedClass, assignedSections)
        val marksRemote = remoteMarks.mapNotNull { d ->
            val m = d.data ?: return@mapNotNull null
            val id = m.long("studentId") ?: return@mapNotNull null
            ExamMark(id, m.str("examType"), m.str("subjectKey"), m.int("cq"), m.int("mcq"))
        }
        db.examMarkDao().upsertAll(marksRemote)
        localPending.forEach { db.pendingStudentDeletionDao().remove(it) }
        return SyncReport(students.size, attendance.size, monthly.size, marks.size)
    }

    private suspend fun queryByAssignment(collection: String, assignedClass: String, assignedSections: Set<String>): List<com.google.firebase.firestore.DocumentSnapshot> {
        if (assignedClass.isBlank() || assignedSections.isEmpty()) return emptyList()
        val result = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
        // Queries explicitly constrain the fields checked by Firestore rules.
        for (section in assignedSections) {
            result += school.collection(collection)
                .whereEqualTo("className", assignedClass)
                .whereEqualTo("section", section)
                .get().await().documents
        }
        return result.distinctBy { it.id }
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
