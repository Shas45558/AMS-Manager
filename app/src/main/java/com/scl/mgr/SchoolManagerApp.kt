package com.ams.mgr

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await
import com.ams.mgr.data.SchoolRepository
import com.ams.mgr.data.Student
import com.ams.mgr.data.MonthlyExam
import com.ams.mgr.data.AppDatabase
import com.ams.mgr.data.ExamMark
import com.ams.mgr.data.FirebaseSchoolSync
import com.ams.mgr.data.AutoSyncScheduler
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private data class DrawerItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val demo: Boolean = false
)

private const val PREFS = "student_form_defaults"

@Composable
fun SchoolManagerApp(repository: SchoolRepository) {
    val context = LocalContext.current
    val authPrefs = remember { context.getSharedPreferences("school_manager_auth", android.content.Context.MODE_PRIVATE) }
    var activeRole by remember { mutableStateOf(if (authPrefs.getBoolean("remember", false)) authPrefs.getString("saved_role", null) else null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()

    val items = remember {
        listOf(
            DrawerItem("dashboard", "Dashboard", Icons.Default.Home),
            DrawerItem("students", "Students", Icons.Default.School),
            DrawerItem("teachers", "Teachers", Icons.Default.Person, true),
            DrawerItem("classes", "Classes", Icons.Default.Class, true),
            DrawerItem("subjects", "Subjects", Icons.Default.MenuBook, true),
            DrawerItem("attendance", "Attendance", Icons.Default.CheckCircle),
            DrawerItem("monthly_exam", "Monthly Exam", Icons.Default.Assignment),
            DrawerItem("exams", "Exams & Results", Icons.Default.Assignment, false),
            DrawerItem("fees", "Fees & Payments", Icons.Default.AccountBalanceWallet, true),
            DrawerItem("notices", "Notices", Icons.Default.Notifications, true),
            DrawerItem("events", "Events", Icons.Default.Event, true),
            DrawerItem("reports", "Reports", Icons.Default.BarChart, true),
            DrawerItem("settings", "Sync", Icons.Default.Cloud, false)
        )
    }

    fun navigateFromDrawer(route: String, demo: Boolean, label: String) {
        scope.launch { drawerState.close() }
        val target = if (demo) "demo/$label" else route
        navController.navigate(target) {
            // Drawer destinations are root-level pages. Replace the current root page
            // instead of stacking every drawer click in the back stack.
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = false
            }
            launchSingleTop = true
            restoreState = false
        }
    }

    if (activeRole != null) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(310.dp)) {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.School,
                                contentDescription = "AMS Manager",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("AMS Manager", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("School administration", style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Column(Modifier.fillMaxHeight().padding(bottom = 16.dp)) {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        val visibleItems = items // Teachers also need the Sync destination to upload/download their records.
                        items(visibleItems, key = { it.route }) { item ->
                            NavigationDrawerItem(
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.label)
                                        if (item.demo) {
                                            Spacer(Modifier.width(8.dp))
                                            AssistChip(onClick = {}, label = { Text("Demo") }, enabled = false)
                                        }
                                    }
                                },
                                icon = { Icon(item.icon, contentDescription = null) },
                                selected = false,
                                onClick = { navigateFromDrawer(item.route, item.demo, item.label) },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                        }
                    }
                    if (activeRole == "Admin") {
                        NavigationDrawerItem(
                            label = { Text("Authorization") },
                            icon = { Icon(Icons.Default.Security, contentDescription = null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate("authorization") {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                                    launchSingleTop = true
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    NavigationDrawerItem(
                        label = { Text("About") },
                        icon = { Icon(Icons.Default.Info, contentDescription = null) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate("about") {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                                launchSingleTop = true
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        }
    ) {
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.fillMaxSize()
        ) {
            composable("dashboard") {
                AppScaffold("Dashboard", drawerState, scope) {
                    DashboardScreen(repository, onStudents = { navController.navigate("students") })
                }
            }
            composable("students") {
                AppScaffold("Students", drawerState, scope) {
                    StudentsScreen(
                        repository = repository,
                        onAdd = { navController.navigate("student/add") },
                        onOpen = { id -> navController.navigate("student/$id") }
                    )
                }
            }
            composable("attendance") {
                AppScaffold("Attendance", drawerState, scope) {
                    AttendanceScreen(repository)
                }
            }
            composable("monthly_exam") {
                AppScaffold("Monthly Exam", drawerState, scope) {
                    MonthlyExamScreen(repository)
                }
            }
            composable("exams") {
                AppScaffold("Exams & Results", drawerState, scope) {
                    ExamResultsScreen(repository)
                }
            }
            composable("student/add") {
                AppScaffold("Add Student", drawerState, scope) {
                    StudentFormScreen(repository, null) { navController.popBackStack() }
                }
            }
            composable(
                "student/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { backStack ->
                val id = backStack.arguments?.getLong("id") ?: return@composable
                AppScaffold("Student Details", drawerState, scope) {
                    StudentDetailsScreen(
                        repository = repository,
                        studentId = id,
                        onBack = { navController.popBackStack() },
                        onEdit = { navController.navigate("student/$id/edit") },
                        onDeleted = { navController.popBackStack() }
                    )
                }
            }
            composable(
                "student/{id}/edit",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { backStack ->
                val id = backStack.arguments?.getLong("id") ?: return@composable
                AppScaffold("Edit Student", drawerState, scope) {
                    StudentFormScreen(repository, id) { navController.popBackStack() }
                }
            }
            composable("demo/{name}") { backStack ->
                val name = backStack.arguments?.getString("name") ?: "Feature"
                AppScaffold(name, drawerState, scope) { DemoScreen(name) }
            }
            composable("authorization") {
                AppScaffold("Authorization", drawerState, scope) { AuthorizationScreen() }
            }
            composable("settings") {
                AppScaffold("Sync", drawerState, scope) {
                    FirebaseSyncScreen()
                }
            }
            composable("about") {
                AppScaffold("About", drawerState, scope) { AboutScreen() }
            }
        }
    }
    } else {
        LoginScreen(
            authPrefs = authPrefs,
            onLogin = { role -> activeRole = role },
            onRememberChanged = { remember -> authPrefs.edit().putBoolean("remember", remember).apply() }
        )
    }
}

private fun displayMonthYear(value: String): String = try {
    YearMonth.parse(value).format(DateTimeFormatter.ofPattern("MMMM-yy", java.util.Locale.ENGLISH))
} catch (_: Exception) { value }

@Composable
private fun LoginScreen(
    authPrefs: android.content.SharedPreferences,
    onLogin: (String) -> Unit,
    onRememberChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rememberMe by remember { mutableStateOf(authPrefs.getBoolean("remember", false)) }
    var teacherId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun finishLogin(role: String) {
        authPrefs.edit().putBoolean("remember", rememberMe).apply()
        if (rememberMe) authPrefs.edit().putString("saved_role", role).apply()
        else authPrefs.edit().remove("saved_role").apply()
        onRememberChanged(rememberMe); onLogin(role)
        // A fresh authenticated session can now perform any queued offline changes.
        AutoSyncScheduler.enqueue(context)
    }
    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).result
            if (!account.email.equals("shas45558@gmail.com", ignoreCase = true)) {
                GoogleSignIn.getClient(context, com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
                error = "Admin access denied. Only the authorized administrator account can sign in."; busy = false
            } else {
                busy = true
                scope.launch {
                    try {
                        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
                        FirebaseAuth.getInstance().signInWithCredential(credential).await()
                        error = ""; finishLogin("Admin")
                    } catch (e: Exception) { error = "Firebase admin sign-in failed: ${e.message ?: "Try again."}" }
                    finally { busy = false }
                }
            }
        } catch (e: Exception) { busy = false; error = "Google sign-in failed: ${e.message ?: "Try again."}" }
    }
    val signInClient = remember(context) {
        GoogleSignIn.getClient(context, com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestIdToken("891410663079-j2qnup0bc2a017dl4je985t6h4oobo0f.apps.googleusercontent.com").build())
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.School, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("AMS Manager", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Sign in with Firebase", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { error = ""; busy = true; signInLauncher.launch(signInClient.signInIntent) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Admin — Google Sign-In") }
        HorizontalDivider()
        Text("Teacher login", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(teacherId, { teacherId = it.trim(); error = "" }, label = { Text("User ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it; error = "" }, label = { Text("Password") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (teacherId.isBlank() || password.isBlank()) { error = "Enter User ID and password." }
            else {
                busy = true; error = ""
                scope.launch {
                    try {
                        val normalizedId = teacherId.trim().lowercase()
                        val email = "$normalizedId@ams-manager.invalid"
                        val auth = FirebaseAuth.getInstance()
                        auth.signInWithEmailAndPassword(email, password).await()
                        val uid = auth.currentUser?.uid ?: throw IllegalStateException("Sign-in did not return a user")
                        val profile = FirebaseFirestore.getInstance().collection("teacherAccounts").document(uid).get().await()
                        if (!profile.exists() || profile.getString("role") != "teacher" || profile.getString("schoolId") != "ams-manager-b1434" || profile.getBoolean("enabled") != true) {
                            auth.signOut()
                            throw SecurityException("This account is not an enabled teacher account.")
                        }
                        finishLogin("Teacher")
                    } catch (e: Exception) { error = "Teacher login failed: ${e.message ?: "Check credentials and internet."}" }
                    finally { busy = false }
                }
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Signing in…" else "Sign in as Teacher") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it; onRememberChanged(it); if (!it) authPrefs.edit().remove("saved_role").apply() })
            Text("Remember me — skip login next time")
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}


private data class TeacherAdminRow(
    val uid: String,
    val userId: String,
    val displayName: String,
    val enabled: Boolean
)

@Composable
private fun AuthorizationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firestore = remember { FirebaseFirestore.getInstance() }
    var userId by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var confirmPassword by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var teachers by remember { mutableStateOf<List<TeacherAdminRow>>(emptyList()) }
    var loadingTeachers by remember { mutableStateOf(false) }
    var displayNameEdits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun loadTeachers() {
        val admin = FirebaseAuth.getInstance().currentUser
        if (admin?.email?.equals("shas45558@gmail.com", ignoreCase = true) != true || admin.isEmailVerified != true) {
            message = "Only the verified administrator can view teacher accounts."
            return
        }
        loadingTeachers = true
        scope.launch {
            try {
                val result = firestore.collection("teacherAccounts").whereEqualTo("role", "teacher").get().await()
                teachers = result.documents.mapNotNull { doc ->
                    val id = doc.getString("userId") ?: return@mapNotNull null
                    TeacherAdminRow(doc.id, id, doc.getString("displayName") ?: id, doc.getBoolean("enabled") == true)
                }.sortedBy { it.userId }
                displayNameEdits = teachers.associate { it.uid to it.displayName }
                message = if (teachers.isEmpty()) "No teacher accounts found yet." else "Loaded ${teachers.size} teacher account(s)."
            } catch (e: Exception) {
                message = "Could not load teacher list: ${e.localizedMessage ?: e.javaClass.simpleName}"
            } finally { loadingTeachers = false }
        }
    }

    LaunchedEffect(Unit) { loadTeachers() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Teacher Accounts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Signed-in administrator: shas45558@gmail.com. You can view teacher IDs, update their display names, and enable or disable access.")
        OutlinedTextField(userId, { userId = it.trim().lowercase() }, label = { Text("New User ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password (at least 10 characters)") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(confirmPassword, { confirmPassword = it }, label = { Text("Confirm password") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy, onClick = {
            val admin = FirebaseAuth.getInstance().currentUser
            when {
                admin?.email?.equals("shas45558@gmail.com", ignoreCase = true) != true || admin.isEmailVerified != true -> message = "Only the verified administrator shas45558@gmail.com can create accounts."
                !userId.matches(Regex("^[a-z0-9._-]{3,40}$")) -> message = "User ID must be 3–40 characters: letters, numbers, dot, underscore or hyphen."
                password.length < 10 || password.length > 128 -> message = "Use a password between 10 and 128 characters."
                password != confirmPassword -> message = "Passwords do not match."
                else -> {
                    busy = true; message = ""
                    scope.launch {
                        var secondaryAuth: FirebaseAuth? = null
                        try {
                            val creatorApp = try {
                                FirebaseApp.getInstance("teacherCreator")
                            } catch (_: IllegalStateException) {
                                FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, "teacherCreator")
                                    ?: FirebaseApp.getInstance("teacherCreator")
                            }
                            secondaryAuth = FirebaseAuth.getInstance(creatorApp)
                            val email = "$userId@ams-manager.invalid"
                            val result = secondaryAuth.createUserWithEmailAndPassword(email, password).await()
                            val uid = result.user?.uid ?: throw IllegalStateException("Firebase did not return a new teacher UID")
                            try {
                                FirebaseFirestore.getInstance().collection("teacherAccounts").document(uid).set(mapOf(
                                    "userId" to userId,
                                    "displayName" to userId,
                                    "role" to "teacher",
                                    "schoolId" to "ams-manager-b1434",
                                    "enabled" to true
                                )).await()
                                AutoSyncScheduler.enqueue(context)
                            } catch (e: Exception) {
                                try { result.user?.delete()?.await() } catch (_: Exception) { }
                                throw e
                            }
                            secondaryAuth.signOut()
                            message = "Teacher account created. Give the teacher the User ID and password securely."
                            userId = ""; password = ""; confirmPassword = ""
                            loadTeachers()
                        } catch (e: Exception) {
                            secondaryAuth?.signOut()
                            message = "Could not create account: ${e.message ?: "Check admin sign-in, internet, and Firebase Authentication settings."}"
                        } finally { busy = false }
                    }
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Creating…" else "Create User") }
        if (message.isNotBlank()) Text(message, color = if (message.startsWith("Could not") || message.startsWith("Only")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Manage Teacher IDs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = { loadTeachers() }, enabled = !loadingTeachers) {
                if (loadingTeachers) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp)); Text("Refresh")
            }
        }
        Text("The login ID is tied to the Firebase Authentication account and cannot be renamed from this Spark-only app. To change a login ID, create a new ID and disable the old one. Removing access below disables the profile; it does not delete the underlying Firebase Authentication user.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (teachers.isEmpty() && !loadingTeachers) {
            Text("No teacher records to display. Tap Refresh after creating a teacher.", style = MaterialTheme.typography.bodyMedium)
        }
        teachers.forEach { teacher ->
            var confirmDeleteProfile by remember(teacher.uid) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Login ID: ${teacher.userId}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(if (teacher.enabled) "Status: Enabled" else "Status: Disabled", color = if (teacher.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    OutlinedTextField(
                        value = displayNameEdits[teacher.uid] ?: teacher.displayName,
                        onValueChange = { displayNameEdits = displayNameEdits + (teacher.uid to it) },
                        label = { Text("Teacher display name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy && (displayNameEdits[teacher.uid] ?: teacher.displayName).isNotBlank(), onClick = {
                            val newName = (displayNameEdits[teacher.uid] ?: teacher.displayName).trim()
                            scope.launch {
                                busy = true
                                try {
                                    firestore.collection("teacherAccounts").document(teacher.uid).update("displayName", newName).await()
                                    teachers = teachers.map { if (it.uid == teacher.uid) it.copy(displayName = newName) else it }
                                    message = "Updated profile for ${teacher.userId}."
                                } catch (e: Exception) { message = "Update failed: ${e.localizedMessage ?: e.javaClass.simpleName}" }
                                finally { busy = false }
                            }
                        }, modifier = Modifier.weight(1f)) { Text("Save name") }
                        Button(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val newEnabled = !teacher.enabled
                                    firestore.collection("teacherAccounts").document(teacher.uid).update("enabled", newEnabled).await()
                                    teachers = teachers.map { if (it.uid == teacher.uid) it.copy(enabled = newEnabled) else it }
                                    message = if (newEnabled) "Enabled ${teacher.userId}." else "Disabled ${teacher.userId}; app access is blocked."
                                } catch (e: Exception) { message = "Status update failed: ${e.localizedMessage ?: e.javaClass.simpleName}" }
                                finally { busy = false }
                            }
                        }, modifier = Modifier.weight(1f)) { Text(if (teacher.enabled) "Disable" else "Enable") }
                    }
                    OutlinedButton(enabled = !busy, onClick = { confirmDeleteProfile = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Delete, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Delete teacher profile")
                    }
                    if (confirmDeleteProfile) {
                        AlertDialog(
                            onDismissRequest = { confirmDeleteProfile = false },
                            title = { Text("Delete teacher profile?") },
                            text = { Text("This removes ${teacher.userId}'s Firestore profile so the account can no longer use AMS Manager. It does not delete the Firebase Authentication account or historical school records.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmDeleteProfile = false
                                    scope.launch {
                                        busy = true
                                        try {
                                            firestore.collection("teacherAccounts").document(teacher.uid).delete().await()
                                            teachers = teachers.filterNot { it.uid == teacher.uid }
                                            displayNameEdits = displayNameEdits - teacher.uid
                                            message = "Deleted profile for ${teacher.userId}. Authentication account and historical records were not deleted."
                                        } catch (e: Exception) { message = "Profile delete failed: ${e.localizedMessage ?: e.javaClass.simpleName}" }
                                        finally { busy = false }
                                    }
                                }) { Text("Delete profile") }
                            },
                            dismissButton = { TextButton(onClick = { confirmDeleteProfile = false }) { Text("Cancel") } }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    title: String,
    drawerState: DrawerState,
    scope: CoroutineScope,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch { if (drawerState.isClosed) drawerState.open() else drawerState.close() }
                    }) {
                        Icon(Icons.Default.Menu, contentDescription = "Open menu")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

private data class AttendanceGroupSummary(
    val className: String,
    val section: String,
    val total: Int,
    val present: Int,
    val absent: Int
)

@Composable
private fun DashboardScreen(repository: SchoolRepository, onStudents: () -> Unit) {
    val students by repository.students("").collectAsState(initial = emptyList())
    val totalStudents = students.size

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Welcome to AMS Manager", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(onClick = onStudents, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Total Students", style = MaterialTheme.typography.titleMedium)
                Text("$totalStudents", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text("Manage students and attendance")
            }
        }
    }
}

@Composable
private fun AttendanceCountCard(title: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("$count", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StudentsScreen(repository: SchoolRepository, onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    var query by remember { mutableStateOf("") }
    val students by repository.students(query).collectAsState(initial = emptyList())

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                label = { Text("Search students") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )
            if (students.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "No students yet." else "No students found.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(students, key = { it.id }) { student -> StudentCard(student) { onOpen(student.id) } }
                }
            }
        }
        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) { Icon(Icons.Default.Add, contentDescription = "Add student") }
    }
}

@Composable
private fun StudentCard(student: Student, onClick: () -> Unit) {
    val context = LocalContext.current
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && student.mobileNumber.isNotBlank()) {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${student.mobileNumber}"))
            runCatching { context.startActivity(intent) }
        }
    }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(student.studentName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("ID/Roll: ${student.studentId}")
                Text("Class: ${student.className} • Section: ${student.section}")
                if (student.gender.isNotBlank()) Text("Gender: ${student.gender}")
                if (student.religion.isNotBlank()) Text("Religion: ${student.religion}")
                if (student.fatherName.isNotBlank()) Text("Father: ${student.fatherName}")
                if (student.mobileNumber.isNotBlank()) Text("Mobile: ${student.mobileNumber}")
            }
            if (student.mobileNumber.isNotBlank()) {
                IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${student.mobileNumber}"))
                        runCatching { context.startActivity(intent) }
                    } else {
                        callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                    }
                }) {
                    Icon(Icons.Default.Call, contentDescription = "Call ${student.studentName}", modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudentFormScreen(repository: SchoolRepository, studentId: Long?, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, 0) }
    var existing by remember { mutableStateOf<Student?>(null) }

    var name by remember { mutableStateOf("") }
    var idText by remember { mutableStateOf("") }
    var className by remember { mutableStateOf(prefs.getString("class", "") ?: "") }
    var section by remember { mutableStateOf(prefs.getString("section", "") ?: "") }
    var gender by remember { mutableStateOf(prefs.getString("gender", "") ?: "") }
    var religion by remember { mutableStateOf(prefs.getString("religion", "") ?: "") }
    var father by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(studentId) {
        if (studentId != null) {
            existing = repository.getStudent(studentId)
            existing?.let {
                name = it.studentName
                idText = it.studentId
                className = it.className
                section = it.section
                gender = it.gender
                religion = it.religion
                father = it.fatherName
                address = it.address
                mobile = it.mobileNumber
            }
        }
    }

    fun save() {
        if (name.isBlank() || idText.isBlank() || className.isBlank() || section.isBlank() || gender.isBlank() || religion.isBlank()) {
            error = "Name, ID/Roll, class, section, gender and religion are required."
            return
        }
        val student = Student(
            id = existing?.id ?: 0,
            studentName = name.trim(),
            studentId = idText.trim(),
            className = className.trim(),
            section = section.trim(),
            gender = gender,
            religion = religion,
            fatherName = father.trim(),
            address = address.trim(),
            mobileNumber = mobile.trim()
        )
        scope.launch {
            try {
                if (repository.duplicateExists(student)) {
                    error = "This class, section and roll already exists. Student was not added."
                    return@launch
                }
                if (existing == null) repository.addStudent(student) else repository.updateStudent(student)
                prefs.edit()
                    .putString("class", className)
                    .putString("section", section)
                    .putString("gender", gender)
                    .putString("religion", religion)
                    .apply()
                onSaved()
            } catch (_: IllegalArgumentException) {
                error = "This class, section and roll already exists. Student was not added."
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FormField("Student Name *", name) { name = it }
            FormField("Student ID / Roll *", idText) { idText = it }
            DropdownField("Class *", className, (6..10).map(Int::toString)) { className = it }
            DropdownField("Section *", section, listOf("A", "B")) { section = it }
            DropdownField("Gender *", gender, listOf("Boy", "Girl")) { gender = it }
            DropdownField("Religion *", religion, listOf("Muslim", "Hindu")) { religion = it }
            FormField("Father's Name", father) { father = it }
            FormField("Address", address, minLines = 3) { address = it }
            FormField("Mobile Number", mobile) { mobile = it }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (existing == null) "Add Student" else "Save Changes")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelected(option); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun FormField(label: String, value: String, minLines: Int = 1, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        minLines = minLines
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttendanceScreen(repository: SchoolRepository) {
    val allStudents by repository.students("").collectAsState(initial = emptyList())
    var selectedClass by remember { mutableStateOf("All") }
    var selectedSection by remember { mutableStateOf("All") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var datePickerTarget by remember { mutableStateOf("attendance") }
    var customRange by remember { mutableStateOf(false) }
    var fromDate by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var toDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var tab by remember { mutableIntStateOf(0) }
    var statuses by remember { mutableStateOf<Map<Long, Boolean?>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    val classOptions = remember(allStudents) { listOf("All") + allStudents.map { it.className }.distinct().sortedWith(compareBy { it.toIntOrNull() ?: 999 }) }
    val sectionOptions = remember(allStudents, selectedClass) {
        listOf("All") + allStudents.filter { selectedClass == "All" || it.className == selectedClass }.map { it.section }.distinct().sorted()
    }
    val filteredStudents = remember(allStudents, selectedClass, selectedSection) {
        allStudents.filter {
            (selectedClass == "All" || it.className == selectedClass) &&
            (selectedSection == "All" || it.section == selectedSection)
        }
    }

    LaunchedEffect(date, filteredStudents) {
        val loaded = mutableMapOf<Long, Boolean?>()
        filteredStudents.forEach { student -> loaded[student.id] = repository.attendanceForDate(student.id, date)?.present }
        statuses = loaded
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Mark Attendance") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Monthly View") })
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField("Class", selectedClass, classOptions, Modifier.weight(1f)) {
                selectedClass = it
                selectedSection = "All"
            }
            DropdownField("Section", selectedSection, sectionOptions, Modifier.weight(1f)) { selectedSection = it }
        }

        if (tab == 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Date (YYYY-MM-DD)") }, modifier = Modifier.weight(1f), singleLine = true)
                IconButton(onClick = { showDatePicker = true }) { Icon(Icons.Default.DateRange, contentDescription = "Choose date") }
            }
            if (filteredStudents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No students in this class/section.") }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredStudents, key = { it.id }) { student ->
                        val status = statuses[student.id]
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(student.studentName, fontWeight = FontWeight.Bold)
                                Text("Roll ${student.studentId} • Class ${student.className}-${student.section}")
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { scope.launch { repository.markAttendance(student.id, date, true); statuses = statuses + (student.id to true) } }, modifier = Modifier.weight(1f), colors = if (status == true) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("Present") }
                                    OutlinedButton(onClick = { scope.launch { repository.markAttendance(student.id, date, false); statuses = statuses + (student.id to false) } }, modifier = Modifier.weight(1f)) { Text("Absent") }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val start = if (customRange) fromDate else month.atDay(1).toString()
            val end = if (customRange) toDate else month.atEndOfMonth().toString()
            val validRange = runCatching { LocalDate.parse(start) <= LocalDate.parse(end) }.getOrDefault(false)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !customRange, onClick = { customRange = false }, label = { Text("Monthly") })
                        FilterChip(selected = customRange, onClick = { customRange = true }, label = { Text("Custom Range") })
                    }
                    Spacer(Modifier.height(8.dp))
                    if (customRange) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = fromDate, onValueChange = { fromDate = it }, label = { Text("From date") }, modifier = Modifier.weight(1f), singleLine = true,
                                trailingIcon = { IconButton(onClick = { datePickerTarget = "from"; showDatePicker = true }) { Icon(Icons.Default.DateRange, contentDescription = "Choose from date") } })
                            OutlinedTextField(value = toDate, onValueChange = { toDate = it }, label = { Text("To date") }, modifier = Modifier.weight(1f), singleLine = true,
                                trailingIcon = { IconButton(onClick = { datePickerTarget = "to"; showDatePicker = true }) { Icon(Icons.Default.DateRange, contentDescription = "Choose to date") } })
                        }
                        if (!validRange) Text("From date must be on or before the To date.", color = MaterialTheme.colorScheme.error)
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month") }
                            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, contentDescription = "Next month") }
                        }
                    }
                }
                if (validRange) {
                    items(filteredStudents, key = { "month-${it.id}" }) { student ->
                        var p by remember(student.id, start, end) { mutableIntStateOf(0) }
                        var a by remember(student.id, start, end) { mutableIntStateOf(0) }
                        LaunchedEffect(student.id, start, end) {
                            p = repository.studentPresentCountBetween(student.id, start, end)
                            a = repository.studentAbsentCountBetween(student.id, start, end)
                        }
                        Card(Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text(student.studentName, fontWeight = FontWeight.Bold) },
                                supportingContent = { Text("Roll ${student.studentId} • Class ${student.className}-${student.section}") },
                                trailingContent = { Text("P $p  •  A $a", fontWeight = FontWeight.Bold) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val initialDate = when (datePickerTarget) {
            "from" -> fromDate
            "to" -> toDate
            else -> date
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = runCatching {
            LocalDate.parse(initialDate).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val selected = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                        when (datePickerTarget) {
                            "from" -> fromDate = selected
                            "to" -> toDate = selected
                            else -> date = selected
                        }
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun StudentDetailsScreen(
    repository: SchoolRepository,
    studentId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var student by remember { mutableStateOf<Student?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    val attendance by repository.attendance(studentId).collectAsState(initial = emptyList())
    var selectedDate by remember { mutableStateOf(LocalDate.now().toString()) }

    LaunchedEffect(studentId) { student = repository.getStudent(studentId) }

    student?.let { s ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(s.studentName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("ID/Roll: ${s.studentId}")
                Text("Class: ${s.className} • Section: ${s.section}")
                Text("Gender: ${s.gender} • Religion: ${s.religion}")
                if (s.fatherName.isNotBlank()) Text("Father: ${s.fatherName}")
                if (s.mobileNumber.isNotBlank()) {
                    val context = LocalContext.current
                    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        if (granted) runCatching { context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${s.mobileNumber}"))) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mobile: ${s.mobileNumber}", modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${s.mobileNumber}"))) }
                            } else {
                                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                            }
                        }) {
                            Icon(Icons.Default.Call, contentDescription = "Call ${s.studentName}")
                        }
                    }
                }
                if (s.address.isNotBlank()) Text("Address: ${s.address}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Edit, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Edit")
                    }
                    OutlinedButton(
                        onClick = { showDelete = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Delete")
                    }
                }
            }
            item {
                HorizontalDivider()
                Text("Attendance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = selectedDate, onValueChange = { selectedDate = it }, label = { Text("Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { repository.markAttendance(studentId, selectedDate, true) } }, modifier = Modifier.weight(1f)) { Text("Present") }
                    OutlinedButton(onClick = { scope.launch { repository.markAttendance(studentId, selectedDate, false) } }, modifier = Modifier.weight(1f)) { Text("Absent") }
                }
            }
            item { Text("Attendance History", style = MaterialTheme.typography.titleMedium) }
            items(attendance, key = { it.id }) { record ->
                ListItem(
                    headlineContent = { Text(record.date) },
                    supportingContent = { Text(if (record.present) "Present" else "Absent") },
                    leadingContent = {
                        Icon(
                            if (record.present) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (record.present) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
        }
    } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Student not found.") }

    if (showDelete && student != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete student?") },
            text = { Text("This will permanently remove the student and their attendance records from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    val s = student ?: return@TextButton
                    scope.launch { repository.deleteStudent(s); showDelete = false; onDeleted() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirebaseSyncScreen() {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val scope = rememberCoroutineScope()
    val syncer = remember { FirebaseSchoolSync(AppDatabase.get(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Signed in as: ${auth.currentUser?.email ?: auth.currentUser?.uid ?: "not signed in"}. Cloud sync has not run yet.") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Firebase Sync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Automatic Sync is enabled. Student, attendance, Monthly Exam, and Exams & Results changes are saved locally first, then queued for Firebase sync. WorkManager waits for internet and retries temporary failures, including when connectivity returns. Opening the app and signing in also queues a sync. You can still tap Sync to run it immediately.")
        Text("Student deletions use durable cloud tombstones, so another device's old local copy should not recreate a deleted student; related attendance and exam records are removed too. Teacher account profiles are stored directly in Firestore when created, enabled/disabled, or deleted, so those admin changes are already online. Avoid editing the same mark on two devices before they sync because the last upload may overwrite it.")
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Button(enabled = !busy && auth.currentUser != null, onClick = {
            busy = true
            status = "Syncing school records… keep the app open and connected to the internet."
            scope.launch {
                try {
                    val report = syncer.sync()
                    status = "Sync completed successfully. Uploaded local records: ${report.studentsUploaded} students, ${report.attendanceUploaded} attendance entries, ${report.monthlyExamsUploaded} monthly exam records, and ${report.examMarksUploaded} exam marks. Cloud records were merged into this device."
                } catch (e: Exception) {
                    status = "Sync failed: ${e.localizedMessage ?: e.javaClass.simpleName}. Check internet, Firebase Authentication, and deployed Firestore rules, then retry."
                } finally { busy = false }
            }
        }, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Sync, null)
            Spacer(Modifier.width(8.dp)); Text(if (busy) "Syncing…" else "Sync School Records")
        }
        OutlinedButton(enabled = !busy, onClick = {
            auth.signOut()
            GoogleSignIn.getClient(context, com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
            status = "Signed out. Sign in again to sync."
        }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

@Composable
private fun DemoScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Construction, contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("$name is Demo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("This module will be added in a future version.")
        }
    }
}

@Composable
private fun AboutScreen() {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(modifier = Modifier.size(100.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.School, contentDescription = "AMS Manager", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("AMS Manager", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Version 1.2")
        Spacer(Modifier.height(8.dp))
        Text("Offline student and attendance manager.")
        Spacer(Modifier.height(20.dp))
        Text("Package: com.ams.mgr", style = MaterialTheme.typography.bodySmall)
    }
}


private data class ExamSubjectSpec(val name: String, val key: String, val max: Int, val hasMcq: Boolean = true, val countsForGpa: Boolean = true)

private val CLASS_6_8_EXAM_SUBJECTS = listOf(
    ExamSubjectSpec("Bangla 1st Paper", "bangla1", 100),
    ExamSubjectSpec("Bangla 2nd Paper", "bangla2", 50),
    ExamSubjectSpec("English 1st Paper", "english1", 100, hasMcq = false),
    ExamSubjectSpec("English 2nd Paper", "english2", 50, hasMcq = false),
    ExamSubjectSpec("Mathematics", "math", 100),
    ExamSubjectSpec("Social Sciences", "social", 100),
    ExamSubjectSpec("Science", "science", 100),
    ExamSubjectSpec("Religion", "religion", 100),
    ExamSubjectSpec("ICT", "ict", 25, hasMcq = false),
    ExamSubjectSpec("Agriculture", "agriculture", 20, hasMcq = false),
    ExamSubjectSpec("Oral", "oral", 10, hasMcq = false, countsForGpa = false)
)

private fun gradeForExam(total: Int, max: Int): String {
    if (total <= 0) return "F"
    val p = total * 100.0 / max.coerceAtLeast(1)
    return when { p >= 80 -> "A+"; p >= 70 -> "A"; p >= 60 -> "A-"; p >= 50 -> "B"; p >= 40 -> "C"; p >= 33 -> "D"; else -> "F" }
}

private fun gradePointForExam(total: Int, max: Int): Double = when (gradeForExam(total, max)) {
    "A+" -> 5.0; "A" -> 4.0; "A-" -> 3.5; "B" -> 3.0; "C" -> 2.0; "D" -> 1.0; else -> 0.0
}

private fun examMarkTotal(mark: ExamMark?): Int = (mark?.cq ?: 0) + (mark?.mcq ?: 0)

private fun monthlySubjectMark(exam: MonthlyExam?, key: String): Int = when (key) {
    "bangla1", "bangla2" -> exam?.bangla ?: 0
    "english1", "english2" -> exam?.english ?: 0
    "math" -> exam?.math ?: 0
    "social" -> exam?.socialScience ?: 0
    "science" -> exam?.science ?: 0
    "religion" -> exam?.religion ?: 0
    "ict" -> 0
    "agriculture" -> exam?.hMOrAgri ?: 0
    "oral" -> 0
    else -> 0
}

private fun monthlyTotal(exam: MonthlyExam): Int = listOfNotNull(
    exam.bangla, exam.english, exam.math, exam.socialScience, exam.science,
    exam.religion, exam.physics, exam.chemistry, exam.biology, exam.hMOrAgri
).sum()

private data class StudentResult(
    val student: Student,
    val total: Double,
    val gpa: Double,
    val monthlyEquivalent: Double,
    val firstTerm: Double = 0.0,
    val secondTerm: Double = 0.0,
    val annualExam: Double = 0.0,
    val rankTieAverage: Double = 0.0
)

private data class StudentDetail(
    val studentId: Long,
    val examType: String,
    val subjectRows: List<DetailSubjectRow>,
    val examOnlyObtained: Int,
    val examOnlyMax: Int,
    val adjustedObtained: Double,
    val adjustedMax: Double,
    val monthlyEquivalent: Double,
    val annualTotal: Double,
    val firstTotal: Double,
    val secondTotal: Double,
    val annualAverage: Double,
    val gpa: Double,
    val rank: Int?
)

private data class DetailSubjectRow(
    val name: String,
    val hasMcq: Boolean,
    val cq: String,
    val mcq: String,
    val total: Int,
    val max: Int,
    val gpa: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamResultsScreen(repository: SchoolRepository) {
    val scope = rememberCoroutineScope()
    val students by repository.students("").collectAsState(initial = emptyList())
    var examType by rememberSaveable { mutableStateOf("Annual") }
    var className by rememberSaveable { mutableStateOf("6") }
    var section by rememberSaveable { mutableStateOf("A") }
    var subjectIndex by rememberSaveable { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var marks by remember { mutableStateOf<Map<Long, Pair<String, String>>>(emptyMap()) }
    var allMarks by remember { mutableStateOf<List<ExamMark>>(emptyList()) }
    var resultRows by remember { mutableStateOf<List<StudentResult>>(emptyList()) }
    var selectedMonths by rememberSaveable {
        val now = YearMonth.now()
        val previous = now.minusMonths(1)
        mutableStateOf(listOf(now.toString(), previous.toString()).filter { it.startsWith(now.year.toString()) })
    }
    var message by remember { mutableStateOf<String?>(null) }
    var showExamType by remember { mutableStateOf(false) }
    var showClass by remember { mutableStateOf(false) }
    var showSection by remember { mutableStateOf(false) }
    var showSubject by remember { mutableStateOf(false) }
    var showMonths by remember { mutableStateOf(false) }
    var activeStudentId by remember { mutableStateOf<Long?>(null) }
    var activeField by remember { mutableStateOf("cq") }
    var selectedResultStudent by remember { mutableStateOf<Student?>(null) }
    var resultDisplayOrder by rememberSaveable { mutableStateOf("Roll Number") }
    var detailMarks by remember { mutableStateOf<List<ExamMark>>(emptyList()) }
    var detailFirstMarks by remember { mutableStateOf<List<ExamMark>>(emptyList()) }
    var detailSecondMarks by remember { mutableStateOf<List<ExamMark>>(emptyList()) }
    var detailAnnualMarks by remember { mutableStateOf<List<ExamMark>>(emptyList()) }
    var detailMonthlyRows by remember { mutableStateOf<List<MonthlyExam>>(emptyList()) }
    var detailByStudent by remember { mutableStateOf<Map<Long, StudentDetail>>(emptyMap()) }

    val subjects = CLASS_6_8_EXAM_SUBJECTS
    val subject = subjects[subjectIndex.coerceIn(0, subjects.lastIndex)]
    val classStudents = remember(students, className, section) {
        students.filter { it.className == className && it.section == section }
            .sortedBy { it.studentId.toIntOrNull() ?: Int.MAX_VALUE }
    }

    suspend fun loadExamMarks(type: String): List<ExamMark> {
        val aliases = when (type) {
            "First Term" -> listOf("First Term", "1st Term")
            "Second Term" -> listOf("Second Term", "2nd Term")
            "Annual" -> listOf("Annual", "Annual Exam")
            else -> listOf(type)
        }
        return aliases.flatMap { repository.examMarksOnce(it) }
            .distinctBy { Triple(it.studentId, it.subjectKey, it.examType) }
            .groupBy { it.studentId to it.subjectKey }
            .map { (_, rows) -> rows.firstOrNull { it.examType == type } ?: rows.first() }
    }

    LaunchedEffect(examType, className, section, subjectIndex, classStudents) {
        try {
            val existing = loadExamMarks(examType)
            allMarks = existing
            val byStudent = existing.filter { it.subjectKey == subject.key }.associate { it.studentId to it }
            marks = classStudents.associate { st ->
                val m = byStudent[st.id]
                st.id to (m?.cq?.toString().orEmpty() to m?.mcq?.toString().orEmpty())
            }
        } catch (t: Throwable) {
            allMarks = emptyList()
            marks = classStudents.associate { it.id to ("" to "") }
            message = "Could not load marks: ${t.message ?: "database error"}"
        }
    }

    fun update(studentId: Long, cq: String? = null, mcq: String? = null) {
        val old = marks[studentId] ?: ("" to "")
        val next = (cq ?: old.first).filter(Char::isDigit).take(3) to (mcq ?: old.second).filter(Char::isDigit).take(3)
        val cqN = next.first.toIntOrNull() ?: 0
        val mcqN = next.second.toIntOrNull() ?: 0
        if (cqN + mcqN <= subject.max) marks = marks + (studentId to next)
    }

    fun appendKey(key: String) {
        val id = activeStudentId ?: return
        val old = marks[id] ?: ("" to "")
        val current = if (activeField == "cq") old.first else old.second
        val next = when (key) {
            "C" -> ""
            "⌫" -> current.dropLast(1)
            else -> (current + key).filter(Char::isDigit).take(3)
        }
        if (activeField == "cq") update(id, cq = next) else update(id, mcq = next)
    }

    fun saveAll() {
        scope.launch {
            try {
                val saved = classStudents.mapNotNull { st ->
                    val v = marks[st.id] ?: return@mapNotNull null
                    if (v.first.isBlank() && v.second.isBlank()) return@mapNotNull null
                    ExamMark(st.id, examType, subject.key, v.first.toIntOrNull(), if (subject.hasMcq) v.second.toIntOrNull() else null)
                }
                if (saved.isNotEmpty()) repository.saveExamMarks(saved)
                allMarks = loadExamMarks(examType)
                message = "${subject.name} marks saved"
            } catch (t: Throwable) {
                message = "Could not save marks: ${t.message ?: "database error"}"
            }
        }
    }

    fun calculateResults() {
        scope.launch {
            try {
                val current = loadExamMarks(examType)
            val first = loadExamMarks("First Term")
            val second = loadExamMarks("Second Term")
            val annual = loadExamMarks("Annual")
            // Keep the detail data in memory while Results is open. The student-card
            // dialog must not start additional Room queries when it is tapped.
            detailMarks = current
            detailFirstMarks = first
            detailSecondMarks = second
            detailAnnualMarks = annual
            val loadedMonthlyRows: MutableList<MonthlyExam> = mutableListOf()
            for (month: String in selectedMonths) {
                try {
                    loadedMonthlyRows.addAll(repository.monthlyExamsOnce(month))
                } catch (_: Throwable) {
                    // Keep already-loaded months if one month cannot be read.
                }
            }
            detailMonthlyRows = loadedMonthlyRows
            val monthlyByStudent = classStudents.associate { st ->
                st.id to detailMonthlyRows.filter { ex -> ex.studentId == st.id }
            }

            // Monthly average is based strictly on the number of months selected by the teacher.
            // 1 selected month -> divide by 1, 2 -> divide by 2, 3 -> divide by 3, etc.
            // A missing mark in a selected month contributes 0; it is NOT removed from the divisor.
            fun monthlyAverageForSubject(studentId: Long, subjectKey: String): Double {
                val divisor = selectedMonths.size
                if (divisor == 0) return 0.0

                val rowsByMonth = monthlyByStudent[studentId].orEmpty().associateBy { it.yearMonth }
                val total = selectedMonths.sumOf { month ->
                    val exam = rowsByMonth[month]
                    when (subjectKey) {
                        "bangla1", "bangla2" -> exam?.bangla ?: 0
                        "english1", "english2" -> exam?.english ?: 0
                        "math" -> exam?.math ?: 0
                        "social" -> exam?.socialScience ?: 0
                        "science" -> exam?.science ?: 0
                        "religion" -> exam?.religion ?: 0
                        "agriculture" -> exam?.hMOrAgri ?: 0
                        else -> 0
                    }
                }
                return total.toDouble() / divisor.toDouble()
            }

            fun monthlyAverage(studentId: Long): Double {
                val divisor = selectedMonths.size
                if (divisor == 0) return 0.0

                val rowsByMonth = monthlyByStudent[studentId].orEmpty().associateBy { it.yearMonth }
                val total = selectedMonths.sumOf { month ->
                    monthlyTotal(rowsByMonth[month] ?: MonthlyExam(studentId = studentId, yearMonth = month))
                }
                return total.toDouble() / divisor.toDouble()
            }
            fun examTotal(studentId: Long, termMarks: List<ExamMark>, includeMonthly: Boolean = false): Double {
                val bySubject = termMarks.filter { it.studentId == studentId }.associateBy { it.subjectKey }
                return subjects.sumOf { spec ->
                    examMarkTotal(bySubject[spec.key]).toDouble() +
                        if (includeMonthly) monthlyAverageForSubject(studentId, spec.key) else 0.0
                }
            }

            fun marksBySubject(studentId: Long, termMarks: List<ExamMark>): Map<String, Int> {
                val bySubject = termMarks.filter { it.studentId == studentId }.associateBy { it.subjectKey }
                return subjects.associate { it.key to examMarkTotal(bySubject[it.key]) }
            }

            val computedDetails = mutableMapOf<Long, StudentDetail>()
            resultRows = classStudents.map { st ->
                val monthly = monthlyAverage(st.id)
                val currentBySubject = current.filter { it.studentId == st.id }.associateBy { it.subjectKey }
                val firstBySubject = first.filter { it.studentId == st.id }.associateBy { it.subjectKey }
                val secondBySubject = second.filter { it.studentId == st.id }.associateBy { it.subjectKey }
                val annualBySubject = annual.filter { it.studentId == st.id }.associateBy { it.subjectKey }
                val source = when (examType) { "First Term" -> firstBySubject; "Second Term" -> secondBySubject; else -> annualBySubject }
                val applicable = subjects.filter { it.countsForGpa }

                // First/Second Term: add the teacher-selected average monthly mark for
                // each subject to that subject's exam mark. Monthly marks are therefore
                // part of the term total and the term GPA.
                val termFail = applicable.any { spec ->
                    val adjusted = examMarkTotal(source[spec.key]) + monthlyAverageForSubject(st.id, spec.key).roundToInt()
                    gradeForExam(adjusted, spec.max + 20) == "F"
                }
                val termGpa = if (termFail) 0.0 else applicable.map { spec ->
                    val adjusted = examMarkTotal(source[spec.key]) + monthlyAverageForSubject(st.id, spec.key).roundToInt()
                    gradePointForExam(adjusted, spec.max + 20)
                }.average()

                val examOnly = subjects.sumOf { spec -> examMarkTotal(currentBySubject[spec.key]) }
                val examMax = subjects.sumOf { it.max }
                val adjusted = subjects.sumOf { spec -> examMarkTotal(currentBySubject[spec.key]) + monthlyAverageForSubject(st.id, spec.key) }
                val adjustedMax = examMax + subjects.size * 20
                val subjectRows = subjects.map { spec ->
                    val mark = source[spec.key]
                    val totalMark = examMarkTotal(mark)
                    DetailSubjectRow(
                        name = spec.name,
                        hasMcq = spec.hasMcq,
                        cq = mark?.cq?.toString() ?: "—",
                        mcq = if (spec.hasMcq) mark?.mcq?.toString() ?: "—" else "—",
                        total = totalMark,
                        max = spec.max,
                        gpa = gradePointForExam(totalMark, spec.max)
                    )
                }

                if (examType == "Annual") {
                    // Annual GPA is still based ONLY on the Annual examination, exactly
                    // like the Annual exam itself; First/Second Term never enter Annual GPA.
                    val annualExam = examTotal(st.id, annual, includeMonthly = true)
                    val annualFail = applicable.any { spec ->
                        gradeForExam(examMarkTotal(annualBySubject[spec.key]) + monthlyAverageForSubject(st.id, spec.key).roundToInt(), spec.max + 20) == "F"
                    }
                    val annualGpa = if (annualFail) 0.0 else applicable
                        .map { spec -> gradePointForExam(examMarkTotal(annualBySubject[spec.key]) + monthlyAverageForSubject(st.id, spec.key).roundToInt(), spec.max + 20) }
                        .average()

                    // Annual final/combined total is the average of: First Term total
                    // (including its selected monthly equivalent), Second Term total
                    // (including its selected monthly equivalent), and Annual exam total
                    // (also including its selected monthly equivalent).
                    val firstTotal = examTotal(st.id, first, includeMonthly = true)
                    val secondTotal = examTotal(st.id, second, includeMonthly = true)
                    val combinedAverage = (firstTotal + secondTotal + annualExam) / 3.0
                    val rankTieAverage = combinedAverage
                    computedDetails[st.id] = StudentDetail(st.id, examType, subjectRows, examOnly, examMax, adjusted, adjustedMax.toDouble(), monthly, annualExam, firstTotal, secondTotal, combinedAverage, annualGpa, null)
                    StudentResult(st, combinedAverage, annualGpa, monthly, firstTerm = firstTotal, secondTerm = secondTotal, annualExam = annualExam, rankTieAverage = rankTieAverage)
                } else {
                    val termTotal = examTotal(st.id, source.values.toList(), includeMonthly = true)
                    computedDetails[st.id] = StudentDetail(st.id, examType, subjectRows, examOnly, examMax, adjusted, adjustedMax.toDouble(), monthly, 0.0, 0.0, 0.0, termTotal.toDouble(), termGpa, null)
                    StudentResult(st, termTotal, termGpa, monthly)
                }
            }.sortedWith(
                if (examType == "Annual") {
                    compareByDescending<StudentResult> { it.gpa }
                        .thenByDescending { it.rankTieAverage }
                        .thenByDescending { it.total }
                } else {
                    compareByDescending<StudentResult> { it.gpa }.thenByDescending { it.total }
                }
            ).also { sorted ->
                detailByStudent = sorted.mapIndexedNotNull { index, row ->
                    computedDetails[row.student.id]?.let { rowDetail -> row.student.id to rowDetail.copy(rank = index + 1) }
                }.toMap()
            }
            } catch (t: Throwable) {
                resultRows = emptyList()
                message = "Could not calculate results: ${t.message ?: "database error"}"
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Classes 6–8 Exam & Results", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { OutlinedButton({ showExamType = true }, Modifier.fillMaxWidth()) { Text(examType) } }
            Box(Modifier.weight(.7f)) { OutlinedButton({ showClass = true }, Modifier.fillMaxWidth()) { Text("Class $className") } }
            Box(Modifier.weight(.6f)) { OutlinedButton({ showSection = true }, Modifier.fillMaxWidth()) { Text("Sec $section") } }
        }
        Spacer(Modifier.height(8.dp))
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Enter Marks") })
            Tab(tab == 1, { tab = 1; calculateResults() }, text = { Text("Results") })
        }
        Spacer(Modifier.height(8.dp))
        if (tab == 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Subject: ${subject.name} (Max ${subject.max})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton({ showSubject = true }) { Text("Select") }
            }
            Text(if (subject.hasMcq) "Enter CQ and MCQ. Total is calculated automatically." else "Enter marks. This subject has no MCQ.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1.3f))
                Text("CQ", Modifier.width(86.dp), fontWeight = FontWeight.Bold)
                if (subject.hasMcq) {
                    Spacer(Modifier.width(5.dp))
                    Text("MCQ", Modifier.width(86.dp), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(5.dp))
                Text("Total", Modifier.width(48.dp), fontWeight = FontWeight.Bold)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(classStudents, key = { it.id }) { st ->
                    val v = marks[st.id] ?: ("" to "")
                    val total = (v.first.toIntOrNull() ?: 0) + (v.second.toIntOrNull() ?: 0)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1.3f)) { Text("Roll ${st.studentId}", fontWeight = FontWeight.Bold); Text(st.studentName, maxLines = 1) }
                        Surface(
                            modifier = Modifier
                                .width(86.dp)
                                .height(56.dp)
                                .clickable {
                                    activeStudentId = st.id
                                    activeField = "cq"
                                },
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, if (activeStudentId == st.id && activeField == "cq") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("CQ", style = MaterialTheme.typography.labelSmall)
                                    Text(v.first.ifBlank { "—" }, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (subject.hasMcq) {
                            Spacer(Modifier.width(5.dp))
                            Surface(
                                modifier = Modifier
                                    .width(86.dp)
                                    .height(56.dp)
                                    .clickable {
                                        activeStudentId = st.id
                                        activeField = "mcq"
                                    },
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, if (activeStudentId == st.id && activeField == "mcq") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("MCQ", style = MaterialTheme.typography.labelSmall)
                                        Text(v.second.ifBlank { "—" }, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(5.dp))
                        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total", style = MaterialTheme.typography.labelSmall)
                            Text("$total", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Button({ saveAll() }, Modifier.fillMaxWidth(), enabled = classStudents.isNotEmpty()) { Text("Save Marks") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Monthly average: ${selectedMonths.size} month(s) selected", fontWeight = FontWeight.Bold)
                    Text(selectedMonths.joinToString(", ") { displayMonthYear(it) }, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton({ showMonths = true }) { Text("Select Months (${selectedMonths.size})") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Display order:", fontWeight = FontWeight.Medium)
                FilterChip(selected = resultDisplayOrder == "Roll Number", onClick = { resultDisplayOrder = "Roll Number" }, label = { Text("Roll Number") })
                FilterChip(selected = resultDisplayOrder == "Rank", onClick = { resultDisplayOrder = "Rank" }, label = { Text("Rank") })
            }
            Text("Term/Annual total = exam subject marks + selected-month average. Annual GPA uses the Annual exam (including its monthly equivalent); final Annual total and tie-break use the average of the 3 adjusted exam totals.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Button({ calculateResults() }, Modifier.fillMaxWidth()) { Text("Calculate / Refresh Results") }
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f)) {
                item { Text("$examType — Class $className$section", fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)) }
                items(
                    if (resultDisplayOrder == "Roll Number") resultRows.sortedWith(compareBy<StudentResult>({ it.student.studentId.toIntOrNull() ?: Int.MAX_VALUE }, { it.student.studentName.lowercase() })) else resultRows,
                    key = { it.student.id }
                ) { row ->
                    val rank = resultRows.indexOfFirst { it.student.id == row.student.id } + 1
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { selectedResultStudent = row.student }
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("Rank $rank • ${row.student.studentId} — ${row.student.studentName}", fontWeight = FontWeight.Bold)
                            Text("GPA: ${String.format("%.2f", row.gpa)}   Final Total: ${String.format("%.2f", row.total)}")
                            Text("Tap to view all subjects • CQ + MCQ = Total", style = MaterialTheme.typography.bodySmall)
                            Text("Monthly Equivalent: ${String.format("%.2f", row.monthlyEquivalent)}", style = MaterialTheme.typography.bodySmall)
                            if (examType == "Annual") Text("Combined average: ${String.format("%.2f", row.total)} • 1st: ${String.format("%.2f", row.firstTerm)} • 2nd: ${String.format("%.2f", row.secondTerm)} • Annual: ${String.format("%.2f", row.annualExam)}", style = MaterialTheme.typography.bodySmall)
                            if (examType == "Annual") Text("Annual GPA: Annual exam + monthly equivalent only • tie-break: average of the 3 adjusted exam totals", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (activeStudentId != null && tab == 0) {
            val activeStudent = classStudents.firstOrNull { it.id == activeStudentId }
            val activeValues = marks[activeStudentId] ?: ("" to "")
            val activeValue = if (activeField == "cq") activeValues.first else activeValues.second
            Card(
                Modifier.fillMaxWidth().padding(top = 6.dp).imePadding(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${if (activeField == "cq") "CQ" else "MCQ"} • ${activeStudent?.studentName ?: "Student"}",
                                fontWeight = FontWeight.Bold
                            )
                            Text("${subject.name} • Max ${subject.max}", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(activeValue.ifBlank { "0" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(5.dp))
                    listOf("1","2","3","4","5","6","7","8","9","C","0","⌫").chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            row.forEach { key ->
                                Button(
                                    onClick = { appendKey(key) },
                                    modifier = Modifier.weight(1f).height(42.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    shape = MaterialTheme.shapes.small
                                ) { Text(key, style = MaterialTheme.typography.titleMedium) }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { activeStudentId = null },
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) { Text("Close") }
                        if (subject.hasMcq) {
                            OutlinedButton(
                                onClick = { activeField = if (activeField == "cq") "mcq" else "cq" },
                                modifier = Modifier.weight(1f).height(42.dp)
                            ) { Text(if (activeField == "cq") "MCQ →" else "← CQ") }
                        }
                        Button(
                            onClick = {
                                val index = classStudents.indexOfFirst { it.id == activeStudentId }
                                if (index >= 0 && index < classStudents.lastIndex) {
                                    activeStudentId = classStudents[index + 1].id
                                } else {
                                    activeStudentId = null
                                }
                            },
                            modifier = Modifier.weight(1.3f).height(42.dp)
                        ) { Text("Enter →") }
                    }
                }
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
    if (selectedResultStudent != null) {
        val student = selectedResultStudent!!
        val detail = detailByStudent[student.id]
        if (detail != null) {
            AlertDialog(
                onDismissRequest = { selectedResultStudent = null },
                title = { Text("${student.studentName} • Full Result") },
                text = {
                    Column {
                        Text("Roll ${student.studentId} • ${detail.examType}", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                        ) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Subject", Modifier.weight(1.25f), fontWeight = FontWeight.Bold)
                                Text("CQ", Modifier.weight(.4f), fontWeight = FontWeight.Bold)
                                Text("MCQ", Modifier.weight(.45f), fontWeight = FontWeight.Bold)
                                Text("Total", Modifier.weight(.55f), fontWeight = FontWeight.Bold)
                                Text("GPA", Modifier.weight(.5f), fontWeight = FontWeight.Bold)
                            }
                            detail.subjectRows.forEach { r ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.name, Modifier.weight(1.25f), maxLines = 2)
                                    Text(r.cq, Modifier.weight(.4f))
                                    Text(r.mcq, Modifier.weight(.45f))
                                    Text("${r.total}/${r.max}", Modifier.weight(.55f))
                                    Text(String.format("%.2f", r.gpa), Modifier.weight(.5f), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Exam Total: ${detail.examOnlyObtained} / ${detail.examOnlyMax}", fontWeight = FontWeight.Bold)
                        Text("Monthly Equivalent: ${String.format("%.2f", detail.monthlyEquivalent)}")
                        if (detail.examType == "Annual") {
                            Text("Annual Total: ${String.format("%.2f", detail.annualTotal)}")
                            Text("1st Term Mark: ${String.format("%.2f", detail.firstTotal)}")
                            Text("2nd Term Mark: ${String.format("%.2f", detail.secondTotal)}")
                            Text("Total: ${String.format("%.2f", detail.firstTotal + detail.secondTotal + detail.annualTotal)}")
                            Text("Average: ${String.format("%.2f", detail.annualAverage)}", fontWeight = FontWeight.Bold)
                        } else {
                            Text("Total: ${String.format("%.2f", detail.adjustedObtained)} / ${String.format("%.2f", detail.adjustedMax)}", fontWeight = FontWeight.Bold)
                        }
                        Text("GPA: ${String.format("%.2f", detail.gpa)}", fontWeight = FontWeight.Bold)
                        detail.rank?.let { Text("Rank: $it", fontWeight = FontWeight.Bold) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedResultStudent = null }) { Text("Close") }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { selectedResultStudent = null },
                title = { Text("Result") },
                text = { Text("Result details are not ready. Please tap Calculate / Refresh Results and try again.") },
                confirmButton = { TextButton(onClick = { selectedResultStudent = null }) { Text("Close") } }
            )
        }
    }
    if (showExamType) { DropdownDialog("Exam", listOf("First Term", "Second Term", "Annual"), { examType=it; showExamType=false; resultRows=emptyList() }, { showExamType=false }) }
    if (showClass) { DropdownDialog("Class", listOf("6","7","8"), { className=it; subjectIndex=0; showClass=false }, { showClass=false }) }
    if (showSection) { DropdownDialog("Section", listOf("A","B"), { section=it; showSection=false }, { showSection=false }) }
    if (showSubject) { DropdownDialog("Subject", subjects.map { it.name }, { v -> subjectIndex=subjects.indexOfFirst { it.name==v }.coerceAtLeast(0); showSubject=false }, { showSubject=false }) }
    if (showMonths) {
        MonthSelectionDialog(
            selected = selectedMonths,
            onDone = { selectedMonths = it.sorted(); showMonths = false; calculateResults() },
            onDismiss = { showMonths = false }
        )
    }
}

@Composable
private fun MonthSelectionDialog(selected: List<String>, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val currentYear = YearMonth.now().year
    val options = remember(currentYear) { (1..12).map { YearMonth.of(currentYear, it).toString() }.reversed() }
    var picked by remember(selected) { mutableStateOf(selected.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select this year's monthly exams (select 1 or more)") },
        text = {
            Column {
                options.forEach { month ->
                    Row(Modifier.fillMaxWidth().clickable {
                        picked = if (month in picked) picked - month else if (picked.size < 12) picked + month else picked
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = month in picked, onCheckedChange = {
                            picked = if (it && picked.size < 12) picked + month else picked - month
                        })
                        Text(displayMonthYear(month))
                    }
                }
                Text("Selected: ${picked.size}/12", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = picked.isNotEmpty(), onClick = { onDone(picked.toList()) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DropdownDialog(title: String, values: List<String>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column { values.forEach { v -> TextButton({ onSelect(v) }, Modifier.fillMaxWidth()) { Text(v) } } } }, confirmButton = {})
}

private val LOWER_EXAM_SUBJECTS = listOf(
    "Bangla" to "bangla",
    "English" to "english",
    "Math" to "math",
    "S.Science" to "socialScience",
    "Science" to "science",
    "Religion" to "religion"
)

private val UPPER_EXAM_SUBJECTS = listOf(
    "Bangla" to "bangla",
    "English" to "english",
    "Math" to "math",
    "S.Science" to "socialScience",
    "Religion" to "religion",
    "Physics" to "physics",
    "Chemistry" to "chemistry",
    "Biology" to "biology",
    "H.M./Agri." to "hMOrAgri"
)

private fun examSubjects(className: String): List<Pair<String, String>> =
    if (className == "9" || className == "10") UPPER_EXAM_SUBJECTS else LOWER_EXAM_SUBJECTS

private fun markFor(exam: MonthlyExam?, key: String): Int? = when (key) {
    "bangla" -> exam?.bangla
    "english" -> exam?.english
    "math" -> exam?.math
    "socialScience" -> exam?.socialScience
    "science" -> exam?.science
    "religion" -> exam?.religion
    "physics" -> exam?.physics
    "chemistry" -> exam?.chemistry
    "biology" -> exam?.biology
    "hMOrAgri" -> exam?.hMOrAgri
    else -> null
}

private fun withMark(exam: MonthlyExam, key: String, value: Int?): MonthlyExam = when (key) {
    "bangla" -> exam.copy(bangla = value)
    "english" -> exam.copy(english = value)
    "math" -> exam.copy(math = value)
    "socialScience" -> exam.copy(socialScience = value)
    "science" -> exam.copy(science = value)
    "religion" -> exam.copy(religion = value)
    "physics" -> exam.copy(physics = value)
    "chemistry" -> exam.copy(chemistry = value)
    "biology" -> exam.copy(biology = value)
    "hMOrAgri" -> exam.copy(hMOrAgri = value)
    else -> exam
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthlyExamScreen(repository: SchoolRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val students by repository.students("").collectAsState(initial = emptyList())
    var className by rememberSaveable { mutableStateOf("6") }
    var section by rememberSaveable { mutableStateOf("A") }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var subjectIndex by rememberSaveable { mutableIntStateOf(0) }
    var marks by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var exams by remember { mutableStateOf<Map<Long, MonthlyExam>>(emptyMap()) }
    var message by remember { mutableStateOf<String?>(null) }
    var showClass by remember { mutableStateOf(false) }
    var showSection by remember { mutableStateOf(false) }
    var showMonth by remember { mutableStateOf(false) }
    var showSubject by remember { mutableStateOf(false) }
    var activeStudentId by remember { mutableStateOf<Long?>(null) }
    var pendingPdfAfterPermission by rememberSaveable { mutableStateOf(false) }
    var showPdfSettings by remember { mutableStateOf(false) }
    var pdfOrientation by rememberSaveable { mutableStateOf(PdfOrientation.LANDSCAPE) }
    var pdfPageSize by rememberSaveable { mutableStateOf(PdfPageSize.A4) }
    var pdfTableLayout by rememberSaveable { mutableStateOf(PdfTableLayout.SUBJECTS_AS_COLUMNS) }

    // When the custom marks keypad is open, the Android Back button should
    // close only the keypad instead of closing the Monthly Exam drawer/page.
    BackHandler(enabled = activeStudentId != null) {
        activeStudentId = null
    }

    val subjects = examSubjects(className)
    val selectedSubject = subjects.getOrNull(subjectIndex) ?: subjects.first()
    val classStudents = remember(students, className, section) {
        students.filter { it.className == className && it.section == section }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(month) {
        try {
            exams = repository.monthlyExamsOnce(month).associateBy { it.studentId }
        } catch (t: Throwable) {
            exams = emptyMap()
            message = "Could not load monthly marks: ${t.message ?: "database error"}"
        }
    }
    LaunchedEffect(className, section, month, subjectIndex, classStudents, exams) {
        try {
            // Refresh marks from Room without closing the built-in keypad.
            // The active student is deliberately preserved so Enter -> Next keeps
            // the keypad open on the next marks box.
            val currentActive = activeStudentId
            marks = classStudents.associate { student ->
                student.id to (markFor(exams[student.id], selectedSubject.second)?.toString() ?: "")
            }
            if (currentActive != null && classStudents.none { it.id == currentActive }) {
                activeStudentId = null
            }
        } catch (t: Throwable) {
            message = "Could not refresh monthly marks: ${t.message ?: "database error"}"
        }
    }

    fun setMark(studentId: Long, value: String) {
        val clean = value.filter(Char::isDigit).take(2)
        if (clean.isEmpty() || (clean.toIntOrNull() ?: 0) <= 20) {
            marks = marks + (studentId to clean)
        }
    }

    suspend fun autoSaveStudent(studentId: Long) {
        try {
            val student = classStudents.firstOrNull { it.id == studentId } ?: return
            val existing = exams[studentId] ?: MonthlyExam(studentId, month)
            val raw = marks[studentId]?.trim().orEmpty()
            val value = raw.toIntOrNull()?.coerceIn(0, 20)
            val saved = withMark(existing, selectedSubject.second, if (raw.isBlank()) null else value)
            repository.saveMonthlyExams(listOf(saved))
            exams = exams + (studentId to saved)
        } catch (t: Throwable) {
            message = "Could not save monthly mark: ${t.message ?: "database error"}"
        }
    }

    fun moveToNextStudent() {
        val current = activeStudentId ?: return
        val index = classStudents.indexOfFirst { it.id == current }
        if (index >= 0 && index < classStudents.lastIndex) {
            val next = classStudents[index + 1]
            scope.launch {
                // Save the current student's mark before moving to the next student.
                autoSaveStudent(current)
                activeStudentId = next.id
                listState.animateScrollToItem(index + 1)
                message = "Roll ${classStudents[index].studentId} saved"
            }
        } else {
            scope.launch {
                // Also save the last student's mark when Enter is pressed.
                autoSaveStudent(current)
                message = "Roll ${classStudents[index].studentId} saved"
            }
        }
    }

    fun saveCurrentSubject(next: Boolean) {
        scope.launch {
            val saved = classStudents.map { student ->
                val existing = exams[student.id] ?: MonthlyExam(student.id, month)
                val raw = marks[student.id]?.trim().orEmpty()
                val value = raw.toIntOrNull()?.coerceIn(0, 20)
                withMark(existing, selectedSubject.second, if (raw.isBlank()) null else value)
            }
            repository.saveMonthlyExams(saved)
            exams = (exams + saved.associateBy { it.studentId })
            message = "${selectedSubject.first} saved"
            if (next && subjectIndex < subjects.lastIndex) {
                subjectIndex++
                activeStudentId = null
            }
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingPdfAfterPermission = true
        } else {
            message = "Storage permission is required to save the PDF in Downloads/AMS Manager."
        }
    }

    fun generatePdf() {
        scope.launch {
            try {
                message = "Creating PDF…"
                val all = repository.monthlyExamsOnce(month).associateBy { it.studentId }
                val ordered = classStudents.sortedWith(compareBy({ it.studentId.toIntOrNull() ?: Int.MAX_VALUE }, { it.studentName.lowercase() }))
                val result = generateMonthlyExamPdf(
                    context = context,
                    className = className,
                    section = section,
                    yearMonth = month,
                    subjects = subjects,
                    students = ordered,
                    exams = all,
                    pageSize = pdfPageSize,
                    orientation = pdfOrientation,
                    tableLayout = pdfTableLayout
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, result.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "Share Monthly Exam PDF").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                try {
                    context.startActivity(chooser)
                    message = "PDF saved to Downloads/AMS Manager"
                } catch (_: android.content.ActivityNotFoundException) {
                    message = "PDF saved to Downloads/AMS Manager/${result.fileName}"
                }
            } catch (e: Exception) {
                message = "PDF failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
        }
    }

    LaunchedEffect(pendingPdfAfterPermission) {
        if (pendingPdfAfterPermission) {
            pendingPdfAfterPermission = false
            generatePdf()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Monthly Exam", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Classes 6–8: 6 subjects / 120 marks • Classes 9–10: 9 subjects / 180 marks", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { showClass = true }, modifier = Modifier.fillMaxWidth()) { Text("Class $className") }
                DropdownMenu(showClass, { showClass = false }) {
                    listOf("6", "7", "8", "9", "10").forEach { value ->
                        DropdownMenuItem(text = { Text("Class $value") }, onClick = { className = value; subjectIndex = 0; showClass = false })
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { showSection = true }, modifier = Modifier.fillMaxWidth()) { Text("Section $section") }
                DropdownMenu(showSection, { showSection = false }) {
                    listOf("A", "B").forEach { value ->
                        DropdownMenuItem(text = { Text("Section $value") }, onClick = { section = value; showSection = false })
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { showMonth = true }, modifier = Modifier.fillMaxWidth()) { Text(month) }
                DropdownMenu(showMonth, { showMonth = false }) {
                    val months = (0..11).map { YearMonth.of(YearMonth.now().year, it + 1) }
                    months.forEach { ym ->
                        DropdownMenuItem(text = { Text(ym.format(DateTimeFormatter.ofPattern("MMMM yyyy"))) }, onClick = { month = ym.toString(); showMonth = false })
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Subject selector: teacher can jump directly to any subject.
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { showSubject = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Subject: ${selectedSubject.first}")
            }
            DropdownMenu(showSubject, { showSubject = false }) {
                subjects.forEachIndexed { index, subject ->
                    DropdownMenuItem(
                        text = { Text("${index + 1}. ${subject.first}") },
                        onClick = {
                            subjectIndex = index
                            showSubject = false
                            activeStudentId = null
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Enter marks 0–20 • Tap a box to open the numeric pad", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        if (classStudents.isEmpty()) {
            Card(Modifier.fillMaxWidth()) { Text("No students found for Class $className, Section $section. Add students first.", Modifier.padding(16.dp)) }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Roll", Modifier.width(58.dp), fontWeight = FontWeight.Bold)
                        Text("Student Name", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text("Mark / 20", fontWeight = FontWeight.Bold)
                    }
                }
                items(classStudents, key = { it.id }) { student ->
                    val isActive = activeStudentId == student.id
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(student.studentId, Modifier.width(58.dp))
                        Text(student.studentName, Modifier.weight(1f), maxLines = 1)
                        // This is intentionally NOT a TextField. A read-only TextField can
                        // consume the tap/focus event before the app keypad is opened.
                        // A clickable surface makes every tap reliably open our built-in keypad
                        // and never invokes the Android/system keyboard.
                        Surface(
                            modifier = Modifier
                                .width(100.dp)
                                .height(56.dp)
                                .clickable {
                                    val previous = activeStudentId
                                    if (previous != null && previous != student.id) {
                                        scope.launch {
                                            // Save the previous student's mark before opening another box.
                                            autoSaveStudent(previous)
                                            activeStudentId = student.id
                                        }
                                    } else {
                                        activeStudentId = student.id
                                    }
                                },
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(
                                width = if (isActive) 2.dp else 1.dp,
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            ),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Box(
                                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = marks[student.id].takeUnless { it.isNullOrBlank() } ?: "0–20",
                                    color = if (marks[student.id].isNullOrBlank())
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }

            // The built-in keypad is rendered below the student list rather than as an
            // overlay. This keeps the active marks box visible and prevents the keypad
            // from covering the input field.
        }

        if (activeStudentId != null) {
            val activeStudent = classStudents.firstOrNull { it.id == activeStudentId }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Marks • ${activeStudent?.studentName ?: "Student"}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                "${selectedSubject.first} • 0–20",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Text(
                            marks[activeStudentId] ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(5.dp))

                    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "⌫")
                    keys.chunked(3).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            row.forEach { key ->
                                Button(
                                    onClick = {
                                        val id = activeStudentId ?: return@Button
                                        when (key) {
                                            "C" -> setMark(id, "")
                                            "⌫" -> setMark(id, (marks[id] ?: "").dropLast(1))
                                            else -> setMark(id, (marks[id] ?: "") + key)
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(key, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { activeStudentId = null },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Close")
                        }
                        Button(
                            onClick = { moveToNextStudent() },
                            modifier = Modifier
                                .weight(2f)
                                .height(42.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Enter → Next", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        if (activeStudentId == null) {
            OutlinedButton(
                onClick = { showPdfSettings = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Text("PDF: ${pdfPageSize.label} • ${pdfOrientation.label} • ${pdfTableLayout.label}")
            }
        }

        if (showPdfSettings) {
            AlertDialog(
                onDismissRequest = { showPdfSettings = false },
                title = { Text("PDF Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Page size", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PdfOptionButton("A4", pdfPageSize == PdfPageSize.A4) { pdfPageSize = PdfPageSize.A4 }
                            PdfOptionButton("Letter", pdfPageSize == PdfPageSize.LETTER) { pdfPageSize = PdfPageSize.LETTER }
                            PdfOptionButton("Legal", pdfPageSize == PdfPageSize.LEGAL) { pdfPageSize = PdfPageSize.LEGAL }
                        }
                        Text("Orientation", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PdfOptionButton("Portrait", pdfOrientation == PdfOrientation.PORTRAIT) { pdfOrientation = PdfOrientation.PORTRAIT }
                            PdfOptionButton("Landscape", pdfOrientation == PdfOrientation.LANDSCAPE) { pdfOrientation = PdfOrientation.LANDSCAPE }
                        }
                        Text("Table / row type", fontWeight = FontWeight.Bold)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PdfOptionButton("Subjects in columns (wide)", pdfTableLayout == PdfTableLayout.SUBJECTS_AS_COLUMNS) { pdfTableLayout = PdfTableLayout.SUBJECTS_AS_COLUMNS }
                            PdfOptionButton("Subjects in rows (one column)", pdfTableLayout == PdfTableLayout.SUBJECTS_AS_ROWS) { pdfTableLayout = PdfTableLayout.SUBJECTS_AS_ROWS }
                        }
                    }
                },
                confirmButton = { Button(onClick = { showPdfSettings = false }) { Text("Done") } }
            )
        }

        // Keep the main action row out of the way while the built-in keypad is open.
        // This gives the keypad and the student list their own layout space instead of
        // allowing anything to be covered by an overlay.
        if (activeStudentId == null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { if (subjectIndex > 0) subjectIndex-- }, enabled = subjectIndex > 0, modifier = Modifier.weight(1f)) { Text("Previous") }
                Button(onClick = { saveCurrentSubject(subjectIndex < subjects.lastIndex) }, enabled = classStudents.isNotEmpty(), modifier = Modifier.weight(1.3f)) {
                    Text(if (subjectIndex < subjects.lastIndex) "Save & Next" else "Save All")
                }
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                        ) {
                            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            generatePdf()
                        }
                    },
                    enabled = classStudents.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Generate PDF") }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
    }
}

private enum class PdfOrientation(val label: String) { PORTRAIT("Portrait"), LANDSCAPE("Landscape") }
private enum class PdfPageSize(val label: String) { A4("A4"), LETTER("Letter"), LEGAL("Legal") }
private enum class PdfTableLayout(val label: String) { SUBJECTS_AS_COLUMNS("Subjects in columns"), SUBJECTS_AS_ROWS("Subjects in rows") }

@Composable
private fun PdfOptionButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) { Text(label) }
    }
}

private data class PdfOutput(val uri: Uri, val fileName: String)

private fun generateMonthlyExamPdf(
    context: android.content.Context,
    className: String,
    section: String,
    yearMonth: String,
    subjects: List<Pair<String, String>>,
    students: List<Student>,
    exams: Map<Long, MonthlyExam>,
    pageSize: PdfPageSize,
    orientation: PdfOrientation,
    tableLayout: PdfTableLayout
): PdfOutput {
    val doc = PdfDocument()
    val base = when (pageSize) {
        PdfPageSize.A4 -> 595 to 842
        PdfPageSize.LETTER -> 612 to 792
        PdfPageSize.LEGAL -> 612 to 1008
    }
    val pageWidth = if (orientation == PdfOrientation.LANDSCAPE) maxOf(base.first, base.second) else minOf(base.first, base.second)
    val pageHeight = if (orientation == PdfOrientation.LANDSCAPE) minOf(base.first, base.second) else maxOf(base.first, base.second)
    val margin = 28f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 18f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 8.5f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 8f }
    val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 7f }
    val totalMax = subjects.size * 20
    val monthTitle = try { YearMonth.parse(yearMonth).format(DateTimeFormatter.ofPattern("MMMM yyyy")) } catch (_: Exception) { yearMonth }
    var pageNumber = 0

    fun newPage(): Pair<PdfDocument.Page, Float> {
        pageNumber++
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        val c = page.canvas
        c.drawText("AMS Manager", margin, 30f, titlePaint)
        c.drawText("Monthly Examination Result", margin, 48f, headerPaint)
        c.drawText("Class $className • Section $section • $monthTitle", margin, 62f, textPaint)
        c.drawText("Maximum: $totalMax", pageWidth - 90f, 62f, headerPaint)
        c.drawLine(margin, 70f, pageWidth - margin, 70f, paint)
        return page to 82f
    }

    fun drawFooter(canvas: android.graphics.Canvas) {
        canvas.drawText("Page $pageNumber", pageWidth - 62f, pageHeight - 14f, smallPaint)
    }

    if (tableLayout == PdfTableLayout.SUBJECTS_AS_COLUMNS) {
        val columns = listOf("Roll", "Student") + subjects.map { it.first } + "Total"
        val usable = pageWidth - margin * 2
        val rollW = 42f
        val totalW = 48f
        val studentW = if (orientation == PdfOrientation.LANDSCAPE) 150f else 105f
        val remaining = (usable - rollW - studentW - totalW).coerceAtLeast(1f)
        val subjectW = remaining / subjects.size.coerceAtLeast(1)
        val widths = mutableListOf(rollW, studentW).apply { repeat(subjects.size) { add(subjectW) }; add(totalW) }
        var index = 0
        while (index < students.size || students.isEmpty() && pageNumber == 0) {
            val (page, startY) = newPage()
            val c = page.canvas
            var y = startY
            val headerH = 25f
            paint.style = Paint.Style.STROKE
            c.drawRect(margin, y, pageWidth - margin, y + headerH, paint)
            var x = margin
            columns.forEachIndexed { i, name ->
                c.drawText(name, x + 3f, y + 16f, headerPaint)
                x += widths[i]
                if (i < columns.lastIndex) c.drawLine(x, y, x, y + headerH, paint)
            }
            y += headerH
            val rowH = 22f
            while (index < students.size && y + rowH < pageHeight - 34f) {
                val s = students[index]
                val e = exams[s.id]
                val marks = subjects.map { markFor(e, it.second) }
                val total = marks.filterNotNull().sum()
                val values = listOf(s.studentId, s.studentName) + marks.map { it?.toString() ?: "" } + if (marks.any { it != null }) total.toString() else ""
                x = margin
                c.drawRect(margin, y, pageWidth - margin, y + rowH, paint)
                values.forEachIndexed { i, value ->
                    c.drawText(value.take(if (i == 1) 25 else 10), x + 3f, y + 15f, textPaint)
                    x += widths[i]
                    if (i < values.lastIndex) c.drawLine(x, y, x, y + rowH, paint)
                }
                y += rowH
                index++
            }
            paint.style = Paint.Style.FILL
            drawFooter(c)
            doc.finishPage(page)
            if (students.isEmpty()) break
        }
    } else {
        // Vertical/one-column mode: each student gets a compact block and all subjects
        // are listed vertically, which is easier to print on portrait pages.
        var index = 0
        while (index < students.size || students.isEmpty() && pageNumber == 0) {
            val (page, startY) = newPage()
            val c = page.canvas
            var y = startY
            while (index < students.size) {
                val s = students[index]
                val e = exams[s.id]
                val marks = subjects.map { markFor(e, it.second) }
                val total = marks.filterNotNull().sum()
                val blockH = 24f + subjects.size * 20f + 24f
                if (y + blockH > pageHeight - 34f && y > startY) break
                paint.style = Paint.Style.STROKE
                c.drawRect(margin, y, pageWidth - margin, y + 24f, paint)
                paint.style = Paint.Style.FILL
                c.drawText("Roll: ${s.studentId}", margin + 5f, y + 16f, headerPaint)
                c.drawText("Name: ${s.studentName.take(40)}", margin + 100f, y + 16f, headerPaint)
                y += 24f
                subjects.forEachIndexed { i, subject ->
                    paint.style = Paint.Style.STROKE
                    c.drawRect(margin, y, pageWidth - margin, y + 20f, paint)
                    paint.style = Paint.Style.FILL
                    c.drawText("${i + 1}. ${subject.first}", margin + 6f, y + 14f, textPaint)
                    c.drawText(markFor(e, subject.second)?.toString() ?: "", pageWidth / 2f, y + 14f, textPaint)
                    y += 20f
                }
                paint.style = Paint.Style.STROKE
                c.drawRect(margin, y, pageWidth - margin, y + 24f, paint)
                paint.style = Paint.Style.FILL
                c.drawText("Total: ${if (marks.any { it != null }) total else ""} / $totalMax", margin + 6f, y + 16f, headerPaint)
                y += 30f
                index++
            }
            paint.style = Paint.Style.FILL
            drawFooter(c)
            doc.finishPage(page)
            if (students.isEmpty()) break
        }
    }

    // Dedicated signature page keeps the signature area clean even when the result table
    // spans multiple pages.
    run {
        val (page, startY) = newPage()
        val c = page.canvas
        val y = maxOf(startY + 80f, pageHeight.toFloat() - 130f)
        paint.style = Paint.Style.STROKE
        val left = margin + 45f
        val right = pageWidth - margin - 45f
        c.drawLine(left, y, left + 170f, y, paint)
        c.drawLine(right - 170f, y, right, y, paint)
        paint.style = Paint.Style.FILL
        c.drawText("Class Teacher", left + 42f, y + 18f, headerPaint)
        c.drawText("Head Teacher", right - 125f, y + 18f, headerPaint)
        c.drawText("Signature / Date", left + 45f, y + 32f, smallPaint)
        c.drawText("Signature / Date", right - 125f, y + 32f, smallPaint)
        drawFooter(c)
        doc.finishPage(page)
    }

    val fileName = "Monthly_Exam_Class_${className}_${section}_${yearMonth}_${pageSize.label}_${orientation.label}.pdf"
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AMS Manager")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Unable to create Downloads/AMS Manager file")
            try {
                resolver.openOutputStream(uri)?.use { output -> doc.writeTo(output) }
                    ?: throw java.io.IOException("Unable to open PDF output stream")
                resolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                PdfOutput(uri, fileName)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloads, "AMS Manager")
            if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("Unable to create Downloads/AMS Manager")
            val file = File(dir, fileName)
            file.outputStream().use { output -> doc.writeTo(output) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            PdfOutput(uri, fileName)
        }
    } finally {
        doc.close()
    }
}
