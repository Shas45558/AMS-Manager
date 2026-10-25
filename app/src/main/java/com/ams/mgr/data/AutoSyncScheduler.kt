package com.ams.mgr.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth

/** Queues a durable, network-constrained sync. WorkManager retries transient failures. */
object AutoSyncScheduler {
    private const val UNIQUE_WORK_NAME = "ams_manager_automatic_firebase_sync"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<FirebaseAutoSyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}

class FirebaseAutoSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // A queued job can run after logout. If a remembered teacher has lost its Firebase
        // session, revoke that local session and clear the local school cache before stopping.
        if (FirebaseAuth.getInstance().currentUser == null) {
            val prefs = applicationContext.getSharedPreferences("school_manager_auth", Context.MODE_PRIVATE)
            if (prefs.getString("saved_role", null) == "Teacher") {
                runCatching { AppDatabase.get(applicationContext).clearAllTables() }
                prefs.edit().remove("saved_role").putBoolean("remember", false)
                    .putBoolean("teacher_verified_online", false).putBoolean("force_teacher_login", true).apply()
            }
            return Result.success()
        }
        return try {
            FirebaseSchoolSync(AppDatabase.get(applicationContext)).sync()
            Result.success()
        } catch (e: SecurityException) {
            // A deleted/disabled teacher must not keep using a remembered session or local copy.
            // Never clear school data for the administrator account.
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null && !user.email.equals(FirebaseSchoolSync.ADMIN_EMAIL, ignoreCase = true)) {
                runCatching { AppDatabase.get(applicationContext).clearAllTables() }
                applicationContext.getSharedPreferences("school_manager_auth", Context.MODE_PRIVATE)
                    .edit().remove("saved_role")
                    .putBoolean("remember", false)
                    .putBoolean("teacher_verified_online", false)
                    .putBoolean("force_teacher_login", true)
                    .apply()
                FirebaseAuth.getInstance().signOut()
            }
            // Auth/rules errors need user action, not endless background retries.
            Result.failure()
        } catch (e: IllegalStateException) {
            // Missing/expired sign-in can be fixed on the next login.
            Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
