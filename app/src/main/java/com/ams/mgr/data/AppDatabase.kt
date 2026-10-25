package com.ams.mgr.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Student::class, Attendance::class, MonthlyExam::class, ExamMark::class, TeacherAccount::class, PendingStudentDeletion::class, PendingStudentUpsert::class, StudentPromotionHistory::class, SchoolClosure::class, FaceTemplate::class],
    version = 12,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun monthlyExamDao(): MonthlyExamDao
    abstract fun examMarkDao(): ExamMarkDao
    abstract fun teacherAccountDao(): TeacherAccountDao
    abstract fun pendingStudentDeletionDao(): PendingStudentDeletionDao
    abstract fun pendingStudentUpsertDao(): PendingStudentUpsertDao
    abstract fun studentPromotionHistoryDao(): StudentPromotionHistoryDao
    abstract fun schoolClosureDao(): SchoolClosureDao
    abstract fun faceTemplateDao(): FaceTemplateDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE students ADD COLUMN gender TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE students ADD COLUMN religion TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS monthly_exams (
                        studentId INTEGER NOT NULL,
                        yearMonth TEXT NOT NULL,
                        bangla INTEGER, english INTEGER, math INTEGER,
                        socialScience INTEGER, science INTEGER, religion INTEGER,
                        physics INTEGER, chemistry INTEGER, biology INTEGER, hMOrAgri INTEGER,
                        PRIMARY KEY(studentId, yearMonth)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_monthly_exams_yearMonth ON monthly_exams(yearMonth)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS exam_marks (
                        studentId INTEGER NOT NULL,
                        examType TEXT NOT NULL,
                        subjectKey TEXT NOT NULL,
                        cq INTEGER,
                        mcq INTEGER,
                        PRIMARY KEY(studentId, examType, subjectKey)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_exam_marks_examType ON exam_marks(examType)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS teacher_accounts (userId TEXT NOT NULL, salt TEXT NOT NULL, passwordHash TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(userId))")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS pending_student_deletions (studentId INTEGER NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_student_deletions ADD COLUMN className TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pending_student_deletions ADD COLUMN section TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS pending_student_upserts (studentId INTEGER NOT NULL PRIMARY KEY, queuedAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE students ADD COLUMN academicYear TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE students ADD COLUMN activeStatus TEXT NOT NULL DEFAULT 'active'")
                db.execSQL("CREATE TABLE IF NOT EXISTS student_promotion_history (historyId TEXT NOT NULL PRIMARY KEY, studentId INTEGER NOT NULL, studentName TEXT NOT NULL, fromClass TEXT NOT NULL, fromSection TEXT NOT NULL, fromRoll TEXT NOT NULL, fromAcademicYear TEXT NOT NULL, toClass TEXT NOT NULL, toSection TEXT NOT NULL, toRoll TEXT NOT NULL, toAcademicYear TEXT NOT NULL, outcome TEXT NOT NULL, changedAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE students ADD COLUMN sscYear TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS school_closures (date TEXT NOT NULL PRIMARY KEY, reason TEXT NOT NULL DEFAULT 'Holiday')")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS face_templates (studentId INTEGER NOT NULL, embedding BLOB NOT NULL, embeddingDimensions INTEGER NOT NULL DEFAULT 512, modelVersion TEXT NOT NULL DEFAULT 'facenet512-v1', updatedAt INTEGER NOT NULL, syncedAt INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(studentId))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_face_templates_studentId ON face_templates(studentId)")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "school_manager.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
