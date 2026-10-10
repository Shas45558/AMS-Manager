package com.ams.mgr.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Student::class, Attendance::class, MonthlyExam::class, ExamMark::class, TeacherAccount::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun monthlyExamDao(): MonthlyExamDao
    abstract fun examMarkDao(): ExamMarkDao
    abstract fun teacherAccountDao(): TeacherAccountDao

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

        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "school_manager.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
