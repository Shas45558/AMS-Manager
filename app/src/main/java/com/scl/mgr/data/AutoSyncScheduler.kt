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
        // A queued job can run after logout. It is safe to stop; login/startup schedules again.
        if (FirebaseAuth.getInstance().currentUser == null) return Result.success()
        return try {
            FirebaseSchoolSync(AppDatabase.get(applicationContext)).sync()
            Result.success()
        } catch (e: SecurityException) {
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
