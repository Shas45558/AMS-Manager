package com.ams.mgr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import com.ams.mgr.data.AppDatabase
import com.ams.mgr.data.AutoSyncScheduler
import com.ams.mgr.data.SchoolRepository
import com.ams.mgr.ui.theme.SchoolManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val database = AppDatabase.get(this)
        val repository = SchoolRepository(database, this)
        // Reconcile local/cloud data on each app launch; WorkManager waits for connectivity.
        AutoSyncScheduler.enqueue(this)
        setContent { SchoolManagerTheme { Surface { SchoolManagerApp(repository) } } }
    }
}
