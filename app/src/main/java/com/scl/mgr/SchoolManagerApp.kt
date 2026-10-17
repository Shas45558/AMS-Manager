package com.ams.mgr

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Intent
import android.graphics.Paint
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.w3c.dom.Node
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
import kotlinx.coroutines.delay

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
    val savedRole = if (authPrefs.getBoolean("remember", false)) authPrefs.getString("saved_role", null) else null
    // Never render a remembered teacher session until startup authorization has been checked
    // or the device is confirmed offline (offline mode is explicitly unverified).
    var activeRole by remember { mutableStateOf(if (savedRole == "Teacher") null else savedRole) }
    var startupAuthChecked by remember { mutableStateOf(savedRole != "Teacher") }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()

    fun hasInternetConnection(): Boolean {
        val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    LaunchedEffect(Unit) {
        if (savedRole == "Teacher" && authPrefs.getBoolean("remember", false)) {
            if (!hasInternetConnection()) {
                // Allow the requested offline session, but do not mark it as authorized for sync.
                authPrefs.edit().putBoolean("teacher_verified_online", false).apply()
                activeRole = "Teacher"
                startupAuthChecked = true
            } else {
                try {
                    val user = FirebaseAuth.getInstance().currentUser
                    if (user == null) throw SecurityException("Teacher session is no longer signed in.")
                    user.reload().await()
                    val profile = FirebaseFirestore.getInstance().collection("teacherAccounts")
                        .document(user.uid).get(com.google.firebase.firestore.Source.SERVER).await()
                    val valid = profile.exists() && profile.getString("role") == "teacher" &&
                        profile.getString("schoolId") == "ams-manager-b1434" && profile.getBoolean("enabled") == true
                    if (!valid) throw SecurityException("Teacher profile was deleted or disabled.")
                    authPrefs.edit().putBoolean("teacher_verified_online", true).putBoolean("force_teacher_login", false)
                        .putString("assigned_class", profile.getString("assignedClass") ?: "")
                        .putString("assigned_section", profile.getString("assignedSection") ?: "").apply()
                    activeRole = "Teacher"
                } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                    FirebaseAuth.getInstance().signOut()
                    authPrefs.edit().remove("saved_role").putBoolean("teacher_verified_online", false).putBoolean("force_teacher_login", false).apply()
                    runCatching { AppDatabase.get(context).clearAllTables() }
                    activeRole = null
                } catch (e: SecurityException) {
                    FirebaseAuth.getInstance().signOut()
                    authPrefs.edit().remove("saved_role").putBoolean("teacher_verified_online", false).putBoolean("force_teacher_login", false).apply()
                    runCatching { AppDatabase.get(context).clearAllTables() }
                    activeRole = null
                } catch (e: Exception) {
                    // A failed network check is not proof the account is deleted; keep offline mode
                    // but keep cloud sync disabled until a server authorization check succeeds.
                    authPrefs.edit().putBoolean("teacher_verified_online", false).apply()
                    activeRole = "Teacher"
                } finally {
                    startupAuthChecked = true
                }
            }
        }
    }

    // A background sync can discover a deleted profile after connectivity returns. React to its
    // revocation flag so the already-open UI returns to login without waiting for another launch.
    LaunchedEffect(activeRole) {
        while (activeRole == "Teacher") {
            if (authPrefs.getBoolean("force_teacher_login", false)) {
                activeRole = null
                break
            }
            delay(1500)
        }
    }

    val items = remember {
        listOf(
            DrawerItem("dashboard", "Dashboard", Icons.Default.Home),
            DrawerItem("students", "Students", Icons.Default.School),
            DrawerItem("promotion", "Student Promotion", Icons.Default.Upgrade),
            DrawerItem("ssc_students", "SSC Students", Icons.Default.School),
            DrawerItem("teachers", "Teachers", Icons.Default.Person, true),
            DrawerItem("classes", "Classes", Icons.Default.Class, true),
            DrawerItem("subjects", "Subjects", Icons.Default.MenuBook, true),
            DrawerItem("attendance", "Attendance", Icons.Default.CheckCircle),
            DrawerItem("monthly_exam", "Monthly Exam", Icons.Default.Assignment),
            DrawerItem("exams", "Exams & Results", Icons.Default.Assignment, false),
            DrawerItem("fees", "Fees & Payments", Icons.Default.AccountBalanceWallet, true),
            DrawerItem("notices", "Notices", Icons.Default.Notifications, true),
            DrawerItem("events", "Events", Icons.Default.Event, true),
            DrawerItem("reports", "Reports", Icons.Default.BarChart, false),
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
                        val visibleItems = if (activeRole == "Teacher") items.filter { it.route in setOf("dashboard", "students", "promotion", "ssc_students", "attendance", "monthly_exam", "exams", "settings") } else items
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
                    DashboardScreen(repository, teacherMode = activeRole == "Teacher", assignedClass = authPrefs.getString("assigned_class", "") ?: "", assignedSection = authPrefs.getString("assigned_section", "") ?: "", onStudents = { navController.navigate("students") })
                }
            }
            composable("students") {
                AppScaffold("Students", drawerState, scope) {
                    StudentsScreen(
                        repository = repository,
                        teacherMode = activeRole == "Teacher",
                        assignedClass = authPrefs.getString("assigned_class", "") ?: "",
                        assignedSection = authPrefs.getString("assigned_section", "") ?: "",
                        onAdd = { navController.navigate("student/add") },
                        onOpen = { id -> navController.navigate("student/$id") }
                    )
                }
            }
            composable("promotion") {
                AppScaffold("Student Promotion", drawerState, scope) {
                    StudentPromotionScreen(repository, teacherMode = activeRole == "Teacher", assignedClass = authPrefs.getString("assigned_class", "") ?: "", assignedSection = authPrefs.getString("assigned_section", "") ?: "")
                }
            }
            composable("ssc_students") {
                AppScaffold("SSC Students", drawerState, scope) {
                    SscStudentsScreen(repository, teacherMode = activeRole == "Teacher", assignedClass = authPrefs.getString("assigned_class", "") ?: "", assignedSection = authPrefs.getString("assigned_section", "") ?: "")
                }
            }
            composable("attendance") {
                AppScaffold("Attendance", drawerState, scope) {
                    AttendanceScreen(repository, activeRole == "Teacher", authPrefs.getString("assigned_class", "") ?: "", authPrefs.getString("assigned_section", "") ?: "")
                }
            }
            composable("monthly_exam") {
                AppScaffold("Monthly Exam", drawerState, scope) {
                    MonthlyExamScreen(repository, activeRole == "Teacher", authPrefs.getString("assigned_class", "") ?: "", authPrefs.getString("assigned_section", "") ?: "")
                }
            }
            composable("exams") {
                AppScaffold("Exams & Results", drawerState, scope) {
                    ExamResultsScreen(repository, activeRole == "Teacher", authPrefs.getString("assigned_class", "") ?: "", authPrefs.getString("assigned_section", "") ?: "")
                }
            }
            composable("student/add") {
                AppScaffold("Add Student", drawerState, scope) {
                    StudentFormScreen(repository, null, activeRole == "Teacher", authPrefs.getString("assigned_class", "") ?: "", authPrefs.getString("assigned_section", "") ?: "") { navController.popBackStack() }
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
                        teacherMode = activeRole == "Teacher",
                        assignedClass = authPrefs.getString("assigned_class", "") ?: "",
                        assignedSection = authPrefs.getString("assigned_section", "") ?: "",
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
                    StudentFormScreen(repository, id, activeRole == "Teacher", authPrefs.getString("assigned_class", "") ?: "", authPrefs.getString("assigned_section", "") ?: "") { navController.popBackStack() }
                }
            }
            composable("reports") {
                AppScaffold("Reports", drawerState, scope) {
                    ReportsScreen(repository, activeRole == "Teacher", authPrefs.getString("assigned_class", "") ?: "", authPrefs.getString("assigned_section", "") ?: "")
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
    } else if (!startupAuthChecked) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
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
        if (role == "Teacher") authPrefs.edit().putBoolean("teacher_verified_online", true).putBoolean("force_teacher_login", false).apply()
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
                        val profile = FirebaseFirestore.getInstance().collection("teacherAccounts").document(uid).get(com.google.firebase.firestore.Source.SERVER).await()
                        if (!profile.exists() || profile.getString("role") != "teacher" || profile.getString("schoolId") != "ams-manager-b1434" || profile.getBoolean("enabled") != true) {
                            auth.signOut()
                            throw SecurityException("This account is not an enabled teacher account.")
                        }
                        // Always refresh the assignment from the server at login. Without this,
                        // a teacher could keep blank/stale SharedPreferences after an admin assigns a class.
                        val assignedClass = profile.getString("assignedClass")?.trim().orEmpty()
                        val assignedSection = profile.getString("assignedSection")?.trim().orEmpty()
                        authPrefs.edit()
                            .putString("assigned_class", assignedClass)
                            .putString("assigned_section", assignedSection)
                            .putBoolean("teacher_verified_online", true)
                            .putBoolean("force_teacher_login", false)
                            .apply()
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
    val enabled: Boolean,
    val assignedClass: String,
    val assignedSection: String
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AuthorizationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firestore = remember { FirebaseFirestore.getInstance() }
    var userId by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var confirmPassword by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var teachers by remember { mutableStateOf<List<TeacherAdminRow>>(emptyList()) }
    var loadingTeachers by remember { mutableStateOf(false) }
    var displayNameEdits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var assignmentEdits by remember { mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()) }

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
                    TeacherAdminRow(doc.id, id, doc.getString("displayName") ?: id, doc.getBoolean("enabled") == true, doc.getString("assignedClass") ?: "", doc.getString("assignedSection") ?: "")
                }.sortedBy { it.userId }
                displayNameEdits = teachers.associate { it.uid to it.displayName }
                assignmentEdits = teachers.associate { it.uid to (it.assignedClass to it.assignedSection) }
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
                                    "enabled" to true,
                                    "assignedClass" to "",
                                    "assignedSection" to ""
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
                    val assignment = assignmentEdits[teacher.uid] ?: (teacher.assignedClass to teacher.assignedSection)
                    Text("Class and Section Assignment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    var classMenuExpanded by remember(teacher.uid) { mutableStateOf(false) }
                    var sectionMenuExpanded by remember(teacher.uid) { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = classMenuExpanded,
                        onExpandedChange = { classMenuExpanded = !classMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = assignment.first.takeIf { it.isNotBlank() }?.let { "Class $it" } ?: "Select class",
                            onValueChange = {}, readOnly = true,
                            label = { Text("Assigned class") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = classMenuExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = classMenuExpanded,
                            onDismissRequest = { classMenuExpanded = false }
                        ) {
                            (1..10).map { it.toString() }.forEach { classOption ->
                                DropdownMenuItem(
                                    text = { Text("Class $classOption") },
                                    onClick = {
                                        assignmentEdits = assignmentEdits + (teacher.uid to (classOption to assignment.second))
                                        classMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    ExposedDropdownMenuBox(
                        expanded = sectionMenuExpanded,
                        onExpandedChange = { sectionMenuExpanded = !sectionMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = assignment.second.takeIf { it.isNotBlank() }?.let { "Section $it" } ?: "Select section",
                            onValueChange = {}, readOnly = true,
                            label = { Text("Assigned section") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sectionMenuExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = sectionMenuExpanded,
                            onDismissRequest = { sectionMenuExpanded = false }
                        ) {
                            listOf("A", "B", "C", "D", "E").forEach { sectionOption ->
                                DropdownMenuItem(
                                    text = { Text("Section $sectionOption") },
                                    onClick = {
                                        assignmentEdits = assignmentEdits + (teacher.uid to (assignment.first to sectionOption))
                                        sectionMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedButton(enabled = !busy && assignment.first.isNotBlank() && assignment.second.isNotBlank(), onClick = {
                        val classValue = assignment.first.trim()
                        val sectionValue = assignment.second.trim()
                        scope.launch {
                            busy = true
                            try {
                                firestore.collection("teacherAccounts").document(teacher.uid).update(
                                    mapOf("assignedClass" to classValue, "assignedSection" to sectionValue)
                                ).await()
                                teachers = teachers.map { if (it.uid == teacher.uid) it.copy(assignedClass = classValue, assignedSection = sectionValue) else it }
                                assignmentEdits = assignmentEdits + (teacher.uid to (classValue to sectionValue))
                                message = "Assigned ${teacher.userId} to Class $classValue, Section $sectionValue."
                            } catch (e: Exception) {
                                message = "Assignment save failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
                            } finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp)); Text("Save Assignment")
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
private fun DashboardScreen(repository: SchoolRepository, teacherMode: Boolean, assignedClass: String, assignedSection: String, onStudents: () -> Unit) {
    val allStudents by repository.students("").collectAsState(initial = emptyList())
    val students = if (teacherMode) allStudents.filter { it.className == assignedClass && it.section == assignedSection } else allStudents
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
private fun StudentPromotionScreen(repository: SchoolRepository, teacherMode: Boolean, assignedClass: String, assignedSection: String) {
    val allStudents by repository.students("").collectAsState(initial = emptyList())
    val oldStudents by repository.oldStudents().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val currentYear = remember { LocalDate.now().year }
    val classOptions = remember(allStudents, oldStudents, teacherMode, assignedClass) { if (teacherMode) listOf(assignedClass).filter { it.isNotBlank() } else (allStudents + oldStudents).map { it.className }.distinct().sortedWith(compareBy { it.toIntOrNull() ?: 999 }) }
    var sourceClass by rememberSaveable { mutableStateOf("") }
    var sourceSection by rememberSaveable { mutableStateOf("All") }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var rollEdits by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var outcomes by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var showConfirm by remember { mutableStateOf(false) }
    var includeOldStudents by rememberSaveable { mutableStateOf(false) }
    var moveConflictsToOld by rememberSaveable { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(classOptions, teacherMode, assignedClass) { sourceClass = if (teacherMode) assignedClass else if (sourceClass.isBlank() && classOptions.isNotEmpty()) classOptions.first() else sourceClass }
    val sectionOptions = remember(allStudents, oldStudents, sourceClass) { listOf("All") + (allStudents + oldStudents).filter { it.className == sourceClass }.map { it.section }.distinct().sorted() }
    LaunchedEffect(sectionOptions) { if (sourceSection !in sectionOptions) sourceSection = "All" }
    val visibleStudents = remember(allStudents, oldStudents, sourceClass, sourceSection, includeOldStudents, teacherMode, assignedSection) {
        (allStudents + if (includeOldStudents) oldStudents else emptyList())
            .filter { it.className == sourceClass && (sourceSection == "All" || it.section == sourceSection) && (!teacherMode || it.section == assignedSection) }
    }
    val isSscClass = sourceClass == "10"
    val targetClass = (sourceClass.toIntOrNull()?.plus(1))?.toString() ?: sourceClass
    val targetYear = (currentYear + 1).toString()
    val defaultOutcome = if (isSscClass) "Assign SSC year" else "Promote"

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Promote students safely", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Student IDs and academic history stay unchanged. Choose outcomes and edit roll numbers before confirming.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeOldStudents, onCheckedChange = { includeOldStudents = it; selectedIds = emptySet() })
            Text("Show Old Students for later promotion")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = moveConflictsToOld, onCheckedChange = { moveConflictsToOld = it })
            Text("Move existing roll-number conflicts to Old Students")
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (!teacherMode) Box(Modifier.weight(1f)) { DropdownField("Current class", sourceClass, classOptions.ifEmpty { listOf("6") }) { sourceClass = it; selectedIds = emptySet() } }
            else Box(Modifier.weight(1f)) { Text("Assigned class: $assignedClass", modifier = Modifier.padding(12.dp)) }
            if (!teacherMode) Box(Modifier.weight(1f)) { DropdownField("Section", sourceSection, sectionOptions) { sourceSection = it; selectedIds = emptySet() } }
            else Box(Modifier.weight(1f)) { Text("Assigned section: $assignedSection", modifier = Modifier.padding(12.dp)) }
        }
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                if (isSscClass) {
                    Text("Class 10 students stay in Class 10", fontWeight = FontWeight.SemiBold)
                    Text("They will be assigned to SSC-$targetYear. They will NOT be moved to Class 11.")
                } else {
                    Text("Target class: $targetClass", fontWeight = FontWeight.SemiBold)
                    Text("Next academic year: $targetYear")
                }
                Text("Section is kept unchanged for each student.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${selectedIds.size} selected", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = {
                if (selectedIds.size == visibleStudents.size) selectedIds = emptySet()
                else {
                    selectedIds = visibleStudents.map { it.id }.toSet()
                    visibleStudents.forEach { st ->
                        if (rollEdits[st.id] == null) rollEdits = rollEdits + (st.id to st.studentId)
                        if (outcomes[st.id] == null) outcomes = outcomes + (st.id to defaultOutcome)
                    }
                }
            }) { Text(if (selectedIds.size == visibleStudents.size && visibleStudents.isNotEmpty()) "Clear selection" else "Select all") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleStudents, key = { it.id }) { student ->
                val selected = student.id in selectedIds
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = selected, onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + student.id else selectedIds - student.id
                                if (checked) {
                                    rollEdits = rollEdits + (student.id to (rollEdits[student.id] ?: student.studentId))
                                    outcomes = outcomes + (student.id to (outcomes[student.id] ?: defaultOutcome))
                                }
                            })
                            Column(Modifier.weight(1f)) {
                                Text(student.studentName, fontWeight = FontWeight.SemiBold)
                                Text("Class ${student.className}-${student.section} • Roll ${student.studentId}", style = MaterialTheme.typography.bodySmall)
                                if (student.activeStatus == "old") Text("OLD STUDENT", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (selected) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    val outcomeOptions = if (isSscClass) listOf("Assign SSC year", "Left School") else listOf("Promote", "Repeat", "Left School")
                                    val selectedOutcome = outcomes[student.id]?.takeIf { it in outcomeOptions } ?: defaultOutcome
                                    DropdownField("Outcome", selectedOutcome, outcomeOptions) {
                                        outcomes = outcomes + (student.id to it)
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = rollEdits[student.id] ?: student.studentId,
                                        onValueChange = { rollEdits = rollEdits + (student.id to it) },
                                        label = { Text("New roll") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                        enabled = (outcomes[student.id] ?: defaultOutcome) !in setOf("Left School", "Assign SSC year")
                                    )
                                }
                            }
                            val outcome = outcomes[student.id]?.takeIf { it in (if (isSscClass) listOf("Assign SSC year", "Left School") else listOf("Promote", "Repeat", "Left School")) } ?: defaultOutcome
                            Text(
                                when (outcome) { "Promote" -> "Will move to Class $targetClass"; "Assign SSC year" -> "Will stay in Class 10 and be assigned SSC-$targetYear"; "Repeat" -> "Will remain in Class ${student.className}"; else -> "Will be marked as left school" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp))
        Button(onClick = { showConfirm = true }, enabled = selectedIds.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Saving…" else "Review and confirm promotion")
        }
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Confirm student changes") },
            text = { Text(if (isSscClass) "Apply changes to ${selectedIds.size} selected Class 10 students? Assign SSC-$targetYear where selected. Class 10 students will stay in Class 10; they will not be moved to Class 11. Student names, rolls, permanent IDs, and previous records remain unchanged for SSC assignment." else "Apply changes to ${selectedIds.size} selected students? New academic year: $targetYear. Student names and permanent database IDs will remain unchanged. Previous attendance and exam rows will not be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    busy = true
                    message = ""
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        try {
                            val changes = selectedIds.map { id ->
                                val student = visibleStudents.first { it.id == id }
                                val validOptions = if (isSscClass) listOf("Assign SSC year", "Left School") else listOf("Promote", "Repeat", "Left School")
                                val outcome = outcomes[id]?.takeIf { it in validOptions } ?: defaultOutcome
                                SchoolRepository.PromotionChange(id, if (outcome == "Assign SSC year") student.studentId else rollEdits[id] ?: student.studentId, outcome)
                            }
                            val invalid = changes.any { it.outcome !in setOf("Left School", "Assign SSC year") && it.newRoll.isBlank() }
                            if (invalid) throw IllegalArgumentException("Enter a roll number for every selected student.")
                            val duplicateTargets = changes.filter { it.outcome !in setOf("Left School", "Assign SSC year") }.groupBy { change ->
                                val student = visibleStudents.first { it.id == change.studentId }
                                val cls = if (change.outcome == "Promote") targetClass else student.className
                                "$cls|${student.section}|${change.newRoll.trim()}"
                            }.filterValues { it.size > 1 }
                            if (duplicateTargets.isNotEmpty()) throw IllegalArgumentException("Two selected students cannot have the same class, section and roll number.")
                            val conflictIds = changes.flatMap { change ->
                                if (change.outcome in setOf("Left School", "Assign SSC year")) emptyList() else {
                                    val student = visibleStudents.first { it.id == change.studentId }
                                    val cls = if (change.outcome == "Promote") targetClass else student.className
                                    allStudents.filter { other -> other.id !in selectedIds && other.className == cls && other.section == student.section && other.studentId == change.newRoll.trim() && other.activeStatus == "active" }.map { it.id }
                                }
                            }.toSet()
                            if (conflictIds.isNotEmpty() && !moveConflictsToOld) {
                                throw IllegalArgumentException("A roll number already exists. Enable 'Move existing roll-number conflicts to Old Students' or edit the roll number.")
                            }
                            val sscAssignments = changes.filter { it.outcome == "Assign SSC year" }
                            sscAssignments.forEach { repository.assignSscYear(it.studentId, targetYear) }
                            val actualPromotions = changes.filter { it.outcome != "Assign SSC year" }
                            if (actualPromotions.isNotEmpty()) repository.applyPromotion(actualPromotions, targetClass, targetYear, conflictIds)
                            selectedIds = emptySet()
                            message = if (isSscClass) "SSC-$targetYear assignment saved. Class 10 students remain in Class 10; no Class 11 promotion was made. Firebase sync has been queued." else "Promotion saved locally. Firebase sync has been queued."
                        } catch (e: Exception) {
                            message = e.message ?: "Could not save promotion."
                        } finally { busy = false }
                    }
                }) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StudentsScreen(repository: SchoolRepository, teacherMode: Boolean, assignedClass: String, assignedSection: String, onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    var query by remember { mutableStateOf("") }
    var classFilter by rememberSaveable { mutableStateOf("All") }
    var sectionFilter by rememberSaveable { mutableStateOf("All") }
    var genderFilter by rememberSaveable { mutableStateOf("All") }
    var religionFilter by rememberSaveable { mutableStateOf("All") }
    val allStudents by repository.students(query).collectAsState(initial = emptyList())
    val activeAll by repository.students("").collectAsState(initial = emptyList())
    val classOptions = listOf("All") + activeAll.map { it.className }.distinct().sortedWith(compareBy { it.toIntOrNull() ?: 999 })
    val sectionOptions = listOf("All") + activeAll.filter { classFilter == "All" || it.className == classFilter }.map { it.section }.distinct().sorted()
    val students = allStudents.filter { student ->
        (!teacherMode || (assignedClass.isNotBlank() && assignedSection.isNotBlank() && student.className == assignedClass && student.section == assignedSection)) &&
        (teacherMode || classFilter == "All" || student.className == classFilter) &&
        (teacherMode || sectionFilter == "All" || student.section == sectionFilter) &&
        (genderFilter == "All" || student.gender.equals(genderFilter, true)) &&
        (religionFilter == "All" || student.religion.equals(religionFilter, true))
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), label = { Text("Search students") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, singleLine = true)
            if (!teacherMode) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { DropdownField("Class", classFilter, classOptions) { classFilter = it; sectionFilter = "All" } }
                    Box(Modifier.weight(1f)) { DropdownField("Section", sectionFilter, sectionOptions) { sectionFilter = it } }
                }
            } else {
                Text("Assigned class: $assignedClass • Section: $assignedSection", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { DropdownField("Gender", genderFilter, listOf("All", "Boy", "Girl")) { genderFilter = it } }
                Box(Modifier.weight(1f)) { DropdownField("Religion", religionFilter, listOf("All", "Muslim", "Hindu", "Christian", "Buddhist", "Other")) { religionFilter = it } }
            }
            if (students.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (teacherMode && (assignedClass.isBlank() || assignedSection.isBlank())) "No class/section assigned. Contact the administrator." else if (query.isBlank()) "No students match these filters." else "No students found.") }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(students, key = { it.id }) { student -> StudentCard(student) { onOpen(student.id) } }
                }
            }
        }
        if (!teacherMode || (assignedClass.isNotBlank() && assignedSection.isNotBlank())) FloatingActionButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) { Icon(Icons.Default.Add, contentDescription = "Add student") }
    }
}

@Composable
private fun SscStudentsScreen(repository: SchoolRepository, teacherMode: Boolean, assignedClass: String, assignedSection: String) {
    val allSsc by repository.sscStudents().collectAsState(initial = emptyList())
    val active by repository.students("").collectAsState(initial = emptyList())
    val context = LocalContext.current
    val currentYear = remember { LocalDate.now().year }
    var chosenYear by rememberSaveable { mutableStateOf((currentYear + 1).toString()) }
    var classFilter by rememberSaveable { mutableStateOf("10") }
    var sectionFilter by rememberSaveable { mutableStateOf("All") }
    var statusMessage by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf<Long?>(null) }
    val eligible = active.filter { it.className == "10" && (!teacherMode || (assignedClass == "10" && it.section == assignedSection)) }
    val visible = if (teacherMode) eligible else eligible.filter { sectionFilter == "All" || it.section == sectionFilter }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("SSC Students", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Class 10 students can be assigned to an SSC exam year without changing their class, roll, permanent ID, or previous records.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { DropdownField("SSC exam year", chosenYear, (currentYear..currentYear + 5).map { it.toString() }) { chosenYear = it } }
            if (!teacherMode) Box(Modifier.weight(1f)) { DropdownField("Section", sectionFilter, listOf("All") + eligible.map { it.section }.distinct().sorted()) { sectionFilter = it } }
        }
        Button(onClick = {
            busyId = -1L; statusMessage = ""
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                try { visible.forEach { repository.assignSscYear(it.id, chosenYear) }; statusMessage = "Assigned ${visible.size} students to SSC-$chosenYear. Sync queued." }
                catch (e: Exception) { statusMessage = e.message ?: "Could not assign SSC year." }
                finally { busyId = null }
            }
        }, enabled = visible.isNotEmpty() && busyId == null, modifier = Modifier.fillMaxWidth()) { Text(if (busyId != null) "Saving…" else "Assign all visible to SSC-$chosenYear") }
        if (statusMessage.isNotBlank()) Text(statusMessage, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp))
        Text("Assigned SSC batches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        if (allSsc.isEmpty()) Text("No students assigned to an SSC year yet.") else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(allSsc.filter { !teacherMode || (it.className == assignedClass && it.section == assignedSection) }, key = { it.id }) { student ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(student.studentName, fontWeight = FontWeight.SemiBold)
                    Text("Class ${student.className}-${student.section} • Roll ${student.studentId} • SSC-${student.sscYear}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { DropdownField("Change SSC year", student.sscYear, (currentYear..currentYear + 8).map { it.toString() }) { newYear ->
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { runCatching { repository.assignSscYear(student.id, newYear) }.onSuccess { statusMessage = "${student.studentName} assigned to SSC-$newYear. Sync queued." }.onFailure { statusMessage = it.message ?: "Could not update SSC year." } }
                        } }
                        TextButton(onClick = { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { runCatching { repository.assignSscYear(student.id, "") }.onSuccess { statusMessage = "Removed ${student.studentName} from SSC batch." }.onFailure { statusMessage = it.message ?: "Could not remove SSC year." } } }) { Text("Remove") }
                    }
                } }
            }
        }
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
                if (student.sscYear.isNotBlank()) Text("SSC-${student.sscYear}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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
private fun StudentFormScreen(repository: SchoolRepository, studentId: Long?, teacherMode: Boolean, assignedClass: String, assignedSection: String, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, 0) }
    var existing by remember { mutableStateOf<Student?>(null) }

    var name by remember { mutableStateOf("") }
    var idText by remember { mutableStateOf("") }
    var className by remember { mutableStateOf(if (teacherMode) assignedClass else prefs.getString("class", "") ?: "") }
    var section by remember { mutableStateOf(if (teacherMode) assignedSection else prefs.getString("section", "") ?: "") }
    var gender by remember { mutableStateOf(prefs.getString("gender", "") ?: "") }
    var religion by remember { mutableStateOf(prefs.getString("religion", "") ?: "") }
    var father by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(studentId) {
        if (studentId != null) {
            existing = repository.getStudent(studentId)
            if (teacherMode && existing != null && (existing!!.className != assignedClass || existing!!.section != assignedSection)) {
                existing = null
                error = "Access denied: this student is outside your assigned class and section."
            }
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
        if (teacherMode && studentId != null && existing == null) {
            error = "Access denied: this student is not in your assigned class and section."
            return
        }
        if (teacherMode && (assignedClass.isBlank() || assignedSection.isBlank())) {
            error = "No class and section assigned. Contact the administrator."
            return
        }
        if (teacherMode && (className != assignedClass || section != assignedSection)) {
            error = "You can only manage students in your assigned class and section."
            return
        }
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
            mobileNumber = mobile.trim(),
            academicYear = existing?.academicYear?.ifBlank { LocalDate.now().year.toString() } ?: LocalDate.now().year.toString(),
            activeStatus = existing?.activeStatus ?: "active",
            sscYear = existing?.sscYear ?: ""
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
            if (teacherMode) {
                OutlinedTextField(className, {}, label = { Text("Assigned Class *") }, readOnly = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(section, {}, label = { Text("Assigned Section *") }, readOnly = true, modifier = Modifier.fillMaxWidth())
            } else {
                DropdownField("Class *", className, (6..10).map(Int::toString)) { className = it }
                DropdownField("Section *", section, listOf("A", "B")) { section = it }
            }
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
private fun AttendanceScreen(repository: SchoolRepository, teacherMode: Boolean, assignedClass: String, assignedSection: String) {
    val allStudentsRaw by repository.students("").collectAsState(initial = emptyList())
    val allStudents = if (teacherMode) allStudentsRaw.filter { it.className == assignedClass && it.section == assignedSection } else allStudentsRaw
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
    teacherMode: Boolean,
    assignedClass: String,
    assignedSection: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var student by remember { mutableStateOf<Student?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    val attendance by repository.attendance(studentId).collectAsState(initial = emptyList())
    var selectedDate by remember { mutableStateOf(LocalDate.now().toString()) }

    LaunchedEffect(studentId, teacherMode, assignedClass, assignedSection) {
        val found = repository.getStudent(studentId)
        student = if (teacherMode && (found?.className != assignedClass || found?.section != assignedSection)) null else found
    }

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
        Text("The administrator owns the student roster and syncs it to the cloud. Teacher devices download the current assigned roster and remove stale local students, so deleted students are not recreated from old teacher copies. Related attendance and exam records are removed too. Teacher account profiles are stored directly in Firestore when created, enabled/disabled, or deleted, so those admin changes are already online. Avoid editing the same mark on two devices before they sync because the last upload may overwrite it.")
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Button(enabled = !busy && auth.currentUser != null, onClick = {
            busy = true
            status = "Syncing school records… keep the app open and connected to the internet."
            scope.launch {
                try {
                    val report = syncer.sync()
                    status = "Sync completed successfully. Uploaded local records: ${report.studentsUploaded} students, ${report.attendanceUploaded} attendance entries, ${report.monthlyExamsUploaded} monthly exam records, and ${report.examMarksUploaded} exam marks. Cloud records were merged into this device."
                } catch (e: Exception) {
                    if (e is SecurityException && !auth.currentUser?.email.equals(FirebaseSchoolSync.ADMIN_EMAIL, ignoreCase = true)) {
                        runCatching { AppDatabase.get(context).clearAllTables() }
                        context.getSharedPreferences("school_manager_auth", android.content.Context.MODE_PRIVATE)
                            .edit().remove("saved_role").putBoolean("remember", false)
                            .putBoolean("teacher_verified_online", false).putBoolean("force_teacher_login", true).apply()
                        auth.signOut()
                        status = "Teacher access revoked. Local school data was cleared. Please sign in again."
                    } else {
                        status = "Sync failed: ${e.localizedMessage ?: e.javaClass.simpleName}. Check internet, Firebase Authentication, and deployed Firestore rules, then retry."
                    }
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
private fun ExamResultsScreen(repository: SchoolRepository, teacherMode: Boolean, assignedClass: String, assignedSection: String) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val allStudents by repository.students("").collectAsState(initial = emptyList())
    val students = if (teacherMode) allStudents.filter { it.className == assignedClass && it.section == assignedSection } else allStudents
    var examType by rememberSaveable { mutableStateOf("Annual") }
    var className by rememberSaveable { mutableStateOf(if (teacherMode) assignedClass else "6") }
    var section by rememberSaveable { mutableStateOf(if (teacherMode) assignedSection else "A") }
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
            if (!teacherMode) {
                Box(Modifier.weight(.7f)) { OutlinedButton({ showClass = true }, Modifier.fillMaxWidth()) { Text("Class $className") } }
                Box(Modifier.weight(.6f)) { OutlinedButton({ showSection = true }, Modifier.fillMaxWidth()) { Text("Sec $section") } }
            } else {
                Text("Assigned: Class $assignedClass • Section $assignedSection")
            }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            try {
                                val output = generateStudentResultCardDocx(context, student, detail, className, section)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = DOCX_MIME
                                    putExtra(Intent.EXTRA_STREAM, output.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share result card DOCX").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                message = "Result card DOCX created: ${output.fileName}"
                            } catch (e: Exception) {
                                message = "Could not create result card: ${e.localizedMessage ?: e.javaClass.simpleName}"
                            }
                        }) { Text("Result Card DOCX") }
                        TextButton(onClick = { selectedResultStudent = null }) { Text("Close") }
                    }
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
    if (!teacherMode && showClass) { DropdownDialog("Class", listOf("6","7","8"), { className=it; subjectIndex=0; showClass=false }, { showClass=false }) }
    if (!teacherMode && showSection) { DropdownDialog("Section", listOf("A","B"), { section=it; showSection=false }, { showSection=false }) }
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
private fun MonthlyExamScreen(repository: SchoolRepository, teacherMode: Boolean, assignedClass: String, assignedSection: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allStudents by repository.students("").collectAsState(initial = emptyList())
    val students = if (teacherMode) allStudents.filter { it.className == assignedClass && it.section == assignedSection } else allStudents
    var className by rememberSaveable { mutableStateOf(if (teacherMode) assignedClass else "6") }
    var section by rememberSaveable { mutableStateOf(if (teacherMode) assignedSection else "A") }
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
                if (!teacherMode) OutlinedButton(onClick = { showClass = true }, modifier = Modifier.fillMaxWidth()) { Text("Class $className") }
                DropdownMenu(showClass && !teacherMode, { showClass = false }) {
                    listOf("6", "7", "8", "9", "10").forEach { value ->
                        DropdownMenuItem(text = { Text("Class $value") }, onClick = { className = value; subjectIndex = 0; showClass = false })
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                if (!teacherMode) OutlinedButton(onClick = { showSection = true }, modifier = Modifier.fillMaxWidth()) { Text("Section $section") }
                DropdownMenu(showSection && !teacherMode, { showSection = false }) {
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

private val PDF_REPORT_TYPES = listOf(
    "Exam marksheets", "Attendance reports", "Student lists", "SSC student lists", "Monthly exam reports", "Student report cards"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportsScreen(repository: SchoolRepository, teacherMode: Boolean, assignedClass: String, assignedSection: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allStudents by repository.students("").collectAsState(initial = emptyList())
    val sscStudents by repository.sscStudents().collectAsState(initial = emptyList())
    val activeStudents = (allStudents + sscStudents).distinctBy { it.id }.filter { it.activeStatus == "active" }
    val availableClasses = (if (teacherMode) listOf(assignedClass) else activeStudents.map { it.className }.distinct().sortedBy { it.toIntOrNull() ?: 999 }).filter { it.isNotBlank() }
    var reportType by rememberSaveable { mutableStateOf(PDF_REPORT_TYPES.first()) }
    var className by rememberSaveable { mutableStateOf(if (teacherMode) assignedClass else "6") }
    var section by rememberSaveable { mutableStateOf(if (teacherMode) assignedSection else "A") }
    var examType by rememberSaveable { mutableStateOf("Annual") }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var startDate by rememberSaveable { mutableStateOf("${LocalDate.now().year}-01-01") }
    var endDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val filtered = activeStudents.filter { (it.sscYear.isBlank() || reportType == "SSC student lists") && (teacherMode || it.className == className) && (teacherMode || it.section == section) && (!teacherMode || (it.className == assignedClass && it.section == assignedSection)) && (reportType != "SSC student lists" || it.sscYear.isNotBlank()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Professional Reports", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (reportType == "Student report cards") "Generate editable DOCX result cards from the supplied Amtoli Model School Word template. Files are created offline on this device." else "Create print-ready reports with the AMS Manager logo, report title, class details, date range, repeated table headings, page numbers, and signature lines.")
        DropdownField("Report type", reportType, PDF_REPORT_TYPES) { reportType = it }
        if (!teacherMode) {
            DropdownField("Class", className, availableClasses.ifEmpty { listOf("6") }) { className = it }
            val sections = listOf("A", "B", "C", "D") + activeStudents.filter { it.className == className }.map { it.section }.distinct()
            DropdownField("Section", section, sections.distinct().sorted()) { section = it }
        } else Text("Assigned class: $assignedClass • Section: $assignedSection", style = MaterialTheme.typography.bodyMedium)
        if (reportType == "Exam marksheets" || reportType == "Student report cards") DropdownField("Exam", examType, if (reportType == "Student report cards") listOf("First Term", "Second Term") else listOf("First Term", "Second Term", "Annual")) { examType = it }
        if (reportType == "Student report cards" && className !in listOf("6", "7", "8")) Text("The supplied Amtoli result-card template is currently configured for Classes 6–8 only.", color = MaterialTheme.colorScheme.error)
        if (reportType == "Monthly exam reports") OutlinedTextField(value = month, onValueChange = { month = it.take(7) }, label = { Text("Month (YYYY-MM)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (reportType == "Attendance reports") {
            OutlinedTextField(value = startDate, onValueChange = { startDate = it.take(10) }, label = { Text("Start date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = endDate, onValueChange = { endDate = it.take(10) }, label = { Text("End date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Button(enabled = !busy && (!teacherMode || (assignedClass.isNotBlank() && assignedSection.isNotBlank())), onClick = {
            scope.launch {
                busy = true; message = "Preparing DOCX result cards…"
                try {
                    val selectedStudents = filtered.sortedWith(compareBy({ it.studentId.toIntOrNull() ?: Int.MAX_VALUE }, { it.studentName.lowercase() }))
                    if (reportType == "Student report cards") {
                        require(className in listOf("6", "7", "8")) { "The supplied result-card template is currently for Classes 6, 7 and 8 only." }
                        require(examType == "First Term" || examType == "Second Term") { "Choose First Term or Second Term." }
                        require(selectedStudents.isNotEmpty()) { "No students found for the selected class/section." }
                        val aliases = when (examType) {
                            "First Term" -> listOf("First Term", "1st Term")
                            "Second Term" -> listOf("Second Term", "2nd Term")
                            else -> listOf("Annual", "Annual Exam")
                        }
                        val marks = aliases.flatMap { repository.examMarksOnce(it) }
                            .distinctBy { Triple(it.studentId, it.subjectKey, it.examType) }
                            .groupBy { it.studentId to it.subjectKey }
                            .mapValues { (_, list) -> list.firstOrNull { it.examType == examType } ?: list.first() }
                        generateBulkResultCardsDocx(context, selectedStudents, className, section, examType, marks)
                        message = "Saved ${selectedStudents.size} individual DOCX result cards to Downloads/AMS Manager."
                        return@launch
                    }
                    val rows = mutableListOf<List<String>>()
                    val title = reportType
                    val columns: List<String>
                    val heading = if (reportType == "SSC student lists") "SSC-${selectedStudents.firstOrNull()?.sscYear ?: "Year"}" else "Class $className • Section $section"
                    when (reportType) {
                        "Student lists" -> {
                            columns = listOf("Roll", "Student name", "Father's name", "Gender", "Religion", "Mobile")
                            selectedStudents.forEach { rows.add(listOf(it.studentId, it.studentName, it.fatherName, it.gender, it.religion, it.mobileNumber)) }
                        }
                        "SSC student lists" -> {
                            columns = listOf("Roll", "Student name", "Section", "SSC year", "Father's name", "Mobile")
                            selectedStudents.forEach { rows.add(listOf(it.studentId, it.studentName, it.section, it.sscYear, it.fatherName, it.mobileNumber)) }
                        }
                        "Attendance reports" -> {
                            require(runCatching { LocalDate.parse(startDate); LocalDate.parse(endDate); startDate <= endDate }.getOrDefault(false)) { "Enter valid dates in YYYY-MM-DD format, with start date before end date." }
                            columns = listOf("Roll", "Student name", "Present", "Absent", "Attendance %")
                            selectedStudents.forEach { st ->
                                val present = repository.studentPresentCountBetween(st.id, startDate, endDate)
                                val absent = repository.studentAbsentCountBetween(st.id, startDate, endDate)
                                val total = present + absent
                                val percent = if (total == 0) "—" else "${(present * 100.0 / total).toInt()}%"
                                rows.add(listOf(st.studentId, st.studentName, present.toString(), absent.toString(), percent))
                            }
                        }
                        "Monthly exam reports" -> {
                            require(runCatching { YearMonth.parse(month) }.isSuccess) { "Enter month as YYYY-MM." }
                            val exams = repository.monthlyExamsOnce(month).associateBy { it.studentId }
                            val subjects = examSubjects(className)
                            columns = listOf("Roll", "Student name") + subjects.map { it.first } + listOf("Total")
                            selectedStudents.forEach { st ->
                                val ex = exams[st.id]
                                val marks = subjects.map { markFor(ex, it.second) }
                                rows.add(listOf(st.studentId, st.studentName) + marks.map { it?.toString() ?: "—" } + listOf(if (marks.any { it != null }) marks.filterNotNull().sum().toString() else "—"))
                            }
                        }
                        else -> {
                            val aliases = when (examType) { "First Term" -> listOf("First Term", "1st Term"); "Second Term" -> listOf("Second Term", "2nd Term"); "Annual" -> listOf("Annual", "Annual Exam"); else -> listOf(examType) }
                            val allMarks = aliases.flatMap { repository.examMarksOnce(it) }.distinctBy { Triple(it.studentId, it.subjectKey, it.examType) }.groupBy { it.studentId to it.subjectKey }.mapValues { (_, list) -> list.firstOrNull { it.examType == examType } ?: list.first() }
                            val specs = CLASS_6_8_EXAM_SUBJECTS
                            columns = listOf("Roll", "Student name") + specs.map { it.name } + listOf("Total", "Grade")
                            selectedStudents.forEach { st ->
                                val marks = specs.map { spec -> allMarks[st.id to spec.key]?.let { examMarkTotal(it) } }
                                val total = marks.filterNotNull().sum()
                                val max = specs.sumOf { it.max }
                                rows.add(listOf(st.studentId, st.studentName) + marks.map { it?.toString() ?: "—" } + listOf(if (marks.any { it != null }) total.toString() else "—", if (marks.any { it != null }) gradeForExam(total, max) else "—"))
                            }
                        }
                    }
                    val fileName = "AMS_${reportType.replace(' ', '_')}_${LocalDate.now()}.pdf"
                    val result = generateProfessionalTablePdf(context, title, heading, columns, rows, fileName, if (reportType == "Exam marksheets" || reportType == "Monthly exam reports") PdfOrientation.LANDSCAPE else PdfOrientation.PORTRAIT)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, result.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    context.startActivity(Intent.createChooser(shareIntent, "Share $reportType PDF").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    message = "PDF saved to Downloads/AMS Manager: ${result.fileName}"
                } catch (e: Exception) { message = "PDF failed: ${e.localizedMessage ?: e.javaClass.simpleName}" }
                finally { busy = false }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Creating PDF…" else "Generate & Share PDF") }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        Text("Report content is generated from the data currently available on this device. Sync first if you need the latest shared records.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun generateProfessionalTablePdf(
    context: android.content.Context, title: String, subtitle: String, columns: List<String>, rows: List<List<String>>,
    fileName: String, orientation: PdfOrientation
): PdfOutput {
    val document = PdfDocument()
    val pageWidth = if (orientation == PdfOrientation.LANDSCAPE) 842 else 595
    val pageHeight = if (orientation == PdfOrientation.LANDSCAPE) 595 else 842
    val margin = 28f
    val black = android.graphics.Color.rgb(35, 48, 65)
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(195, 205, 216); strokeWidth = 0.7f }
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = black; textSize = 17f; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
    val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = black; textSize = 9f; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL) }
    val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = black; textSize = 7.4f; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(45, 55, 65); textSize = 7.2f }
    val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.DKGRAY; textSize = 7f }
    val logo = runCatching { BitmapFactory.decodeResource(context.resources, com.ams.mgr.R.mipmap.ic_launcher) }.getOrNull()
    val colWidths = run {
        val available = pageWidth - margin * 2
        val weights = columns.map { c -> when (c.lowercase()) { "student name" -> 2.3f; "father's name" -> 1.8f; "mobile" -> 1.45f; "roll", "present", "absent", "total", "grade", "section", "gender", "religion", "ssc year" -> 0.9f; else -> 1f } }
        val total = weights.sum().coerceAtLeast(1f)
        weights.map { available * it / total }
    }
    var pageNo = 0
    var rowIndex = 0
    fun startPage(): Pair<PdfDocument.Page, Float> {
        pageNo++
        val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
        val canvas = page.canvas
        if (logo != null) canvas.drawBitmap(logo, null, android.graphics.RectF(margin, 17f, margin + 35f, 52f), Paint(Paint.ANTI_ALIAS_FLAG))
        canvas.drawText("AMS Manager", margin + 44f, 31f, titlePaint)
        canvas.drawText(title, margin + 44f, 46f, subtitlePaint)
        canvas.drawText(subtitle, margin, 66f, subtitlePaint)
        canvas.drawText("Generated: ${LocalDate.now()}", pageWidth - 155f, 66f, smallPaint)
        canvas.drawLine(margin, 76f, pageWidth - margin, 76f, linePaint)
        var x = margin
        val y = 83f
        val h = 25f
        columns.forEachIndexed { i, name ->
            val rect = android.graphics.RectF(x, y, x + colWidths[i], y + h)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(231, 237, 244); style = Paint.Style.FILL }
            canvas.drawRect(rect, bg); canvas.drawRect(rect, linePaint)
            canvas.drawText(name.take(22), x + 3f, y + 15f, headPaint)
            x += colWidths[i]
        }
        return page to (y + h)
    }
    try {
        do {
            val (page, headerBottom) = startPage()
            val canvas = page.canvas
            var y = headerBottom
            val rowHeight = 22f
            while (rowIndex < rows.size && y + rowHeight <= pageHeight - 38f) {
                val row = rows[rowIndex]
                var x = margin
                columns.forEachIndexed { i, _ ->
                    val rect = android.graphics.RectF(x, y, x + colWidths[i], y + rowHeight)
                    canvas.drawRect(rect, linePaint)
                    val value = row.getOrNull(i).orEmpty()
                    val maxChars = (colWidths[i] / 4.1f).toInt().coerceAtLeast(3)
                    canvas.drawText(value.take(maxChars), x + 3f, y + 14f, bodyPaint)
                    x += colWidths[i]
                }
                y += rowHeight
                rowIndex++
            }
            canvas.drawLine(margin, pageHeight - 28f, pageWidth - margin, pageHeight - 28f, linePaint)
            canvas.drawText("AMS Manager • ${title}", margin, pageHeight - 15f, smallPaint)
            canvas.drawText("Page $pageNo", pageWidth - margin - 42f, pageHeight - 15f, smallPaint)
            document.finishPage(page)
        } while (rowIndex < rows.size)
        if (rows.isEmpty()) {
            // The do/while already produced a header-only page for empty reports.
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AMS Manager")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("Unable to create Downloads/AMS Manager file")
            try {
                resolver.openOutputStream(uri)?.use { document.writeTo(it) } ?: throw java.io.IOException("Unable to open PDF output stream")
                resolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                return PdfOutput(uri, fileName)
            } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        } else {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val directory = File(downloads, "AMS Manager")
            if (!directory.exists() && !directory.mkdirs()) throw java.io.IOException("Unable to create Downloads/AMS Manager")
            val file = File(directory, fileName)
            file.outputStream().use { document.writeTo(it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            return PdfOutput(uri, fileName)
        }
    } finally { logo?.recycle(); document.close() }
}

private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val DOCX_TEMPLATE_ASSET = "Amtoli_Model_School_Result_Card_Template.docx"
private data class DocxOutput(val uri: Uri, val fileName: String)
private data class DocxMarkRow(val name: String, val max: Int, val cq: String, val mcq: String, val total: String, val grade: String)

private fun generateStudentResultCardDocx(
    context: android.content.Context, student: Student, detail: StudentDetail, className: String, section: String
): DocxOutput {
    val rows = CLASS_6_8_EXAM_SUBJECTS.map { spec ->
        val aliases = when (spec.key) {
            "social" -> setOf("Social Sciences", "Social Science")
            "agriculture" -> setOf("Agriculture", "Agriculture/Higher Math", "Higher Math")
            else -> setOf(spec.name)
        }
        val r = detail.subjectRows.firstOrNull { it.name in aliases }
        DocxMarkRow(spec.name, spec.max, r?.cq.orEmpty(), r?.mcq.orEmpty(), r?.takeIf { it.total >= 0 }?.total?.toString().orEmpty(), r?.let { gradeForExam(it.total, it.max) }.orEmpty())
    }
    return writeAmtoliDocx(context, listOf(student to rows), className, section, detail.examType,
        String.format(java.util.Locale.US, "%.2f", detail.monthlyEquivalent))
}

private fun generateBulkResultCardsDocx(
    context: android.content.Context,
    students: List<Student>,
    className: String,
    section: String,
    examType: String,
    marks: Map<Pair<Long, String>, ExamMark>
): DocxOutput {
    var lastOutput: DocxOutput? = null
    students.forEach { student ->
        val rows = CLASS_6_8_EXAM_SUBJECTS.map { spec ->
            val mark = marks[student.id to spec.key]
            val total = if (mark == null) "" else ((mark.cq ?: 0) + (mark.mcq ?: 0)).toString()
            DocxMarkRow(spec.name, spec.max, mark?.cq?.toString().orEmpty(), if (spec.hasMcq) mark?.mcq?.toString().orEmpty() else "—", total,
                if (mark == null) "" else gradeForExam((mark.cq ?: 0) + (mark.mcq ?: 0), spec.max))
        }
        lastOutput = writeAmtoliDocx(context, listOf(student to rows), className, section, examType)
    }
    return lastOutput ?: throw java.io.IOException("No DOCX result cards were created.")
}

private fun writeAmtoliDocx(
    context: android.content.Context,
    studentRows: List<Pair<Student, List<DocxMarkRow>>>,
    className: String,
    section: String,
    examType: String,
    monthlyEvaluationAverage: String = ""
): DocxOutput {
    require(studentRows.isNotEmpty()) { "No students were selected." }
    val firstStudent = studentRows.first().first
    val studentSuffix = if (studentRows.size == 1) "_${firstStudent.studentId}_${firstStudent.studentName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)}" else "_Batch"
    val filename = "AMS_Result_Card_Class_${className}_${section}_${examType.replace(" ", "_")}${studentSuffix}_${LocalDate.now()}.docx"
    val templateBytes = context.assets.open(DOCX_TEMPLATE_ASSET).use { it.readBytes() }
    val zipIn = ZipInputStream(ByteArrayInputStream(templateBytes))
    val entries = linkedMapOf<String, ByteArray>()
    zipIn.use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            if (!entry.isDirectory) entries[entry.name] = input.readBytes()
            input.closeEntry()
        }
    }
    val documentXml = entries["word/document.xml"] ?: throw java.io.IOException("The DOCX template is missing document.xml")
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(documentXml))
    val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    fun children(parent: org.w3c.dom.Node, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        var node = parent.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && node.localName == localName && node.namespaceURI == wNs) result += node as Element
            node = node.nextSibling
        }
        return result
    }
    fun allTables(): List<Element> {
        val list = doc.getElementsByTagNameNS(wNs, "tbl")
        return (0 until list.length).map { list.item(it) as Element }
    }
    fun cellText(cell: Element): String = cell.getElementsByTagNameNS(wNs, "t").let { nodes -> (0 until nodes.length).joinToString("") { nodes.item(it).textContent } }
    fun setCell(cell: Element, value: String) {
        val paragraph = cell.getElementsByTagNameNS(wNs, "p").item(0) as? Element ?: return
        val pPr = children(paragraph, "pPr").firstOrNull()
        while (paragraph.firstChild != null) paragraph.removeChild(paragraph.firstChild)
        if (pPr != null) paragraph.appendChild(pPr)
        if (value.isNotEmpty()) {
            val run = doc.createElementNS(wNs, "w:r")
            val text = doc.createElementNS(wNs, "w:t")
            text.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
            text.textContent = value
            run.appendChild(text)
            paragraph.appendChild(run)
        }
    }
    fun rowCells(table: Element, row: Int): List<Element> = children(table, "tr").getOrNull(row)?.let { children(it, "tc") }.orEmpty()
    val tables = allTables()
    require(tables.size >= 7) { "The supplied Amtoli DOCX template structure has changed." }
    val examTable = tables[2]
    val examCells = rowCells(examTable, 0)
    if (examCells.isNotEmpty()) setCell(examCells[0], "Exam Name: $examType")
    if (examCells.size > 1) setCell(examCells[1], "Date: ${LocalDate.now()}")

    val infoTable = tables[3]
    val student = studentRows.first().first
    val studentDetails = listOf(
        "Student Name: ${student.studentName}", "Father’s Name: ${student.fatherName}", "Class: $className",
        "Section: $section", "Roll No: ${student.studentId}", "Address: ${student.address}"
    )
    val firstStudentRows = studentRows.first().second
    val presentRows = firstStudentRows.filter { it.total.isNotBlank() }
    val obtained = presentRows.sumOf { it.total.toIntOrNull() ?: 0 }
    val gpaRows = studentRows.first().second.filter { it.name != "Oral" && it.total.isNotBlank() }
    val failed = gpaRows.any { it.grade == "F" }
    val gpa = if (gpaRows.isEmpty() || failed) 0.0 else gpaRows.map { gradePointForExam(it.total.toIntOrNull() ?: 0, it.max) }.average()
    val overallGrade = when { gpa >= 5.0 -> "A+"; gpa >= 4.0 -> "A"; gpa >= 3.5 -> "A-"; gpa >= 3.0 -> "B"; gpa >= 2.0 -> "C"; gpa >= 1.0 -> "D"; else -> "F" }
    val infoRows = children(infoTable, "tr")
    for (i in 1..6) {
        val cells = infoRows.getOrNull(i)?.let { children(it, "tc") }.orEmpty()
        if (cells.isNotEmpty()) setCell(cells[0], studentDetails[i - 1])
        if (cells.size > 1) {
            val label = when (i) { 1 -> "Behavior:"; 2 -> "Total Working Days:"; 3 -> "Present:"; 4 -> "Absent:"; 5 -> "GPA:"; else -> "Grade:" }
            val value = when (i) { 5 -> String.format(java.util.Locale.US, "%.2f", gpa); 6 -> overallGrade; else -> "" }
            setCell(cells[1], "$label $value")
        }
    }
    val nextRollCells = rowCells(infoTable, 7)
    if (nextRollCells.size > 1) setCell(nextRollCells[1], "Next Class Roll Number: ")

    val subjectTable = tables[4]
    val byName = studentRows.first().second.associateBy { it.name }
    val aliases = mapOf("Social Sciences" to listOf("Social Sciences", "Social Science"), "Social Science" to listOf("Social Sciences", "Social Science"), "Agriculture" to listOf("Agriculture", "Agriculture/Higher Math", "Higher Math"))
    val specs = CLASS_6_8_EXAM_SUBJECTS
    specs.forEachIndexed { index, spec ->
        val targetRow = index + 1
        val cells = rowCells(subjectTable, targetRow)
        if (cells.size >= 7) {
            val row = byName[spec.name] ?: aliases[spec.name]?.firstNotNullOfOrNull { byName[it] }
            // Subject names and full marks remain as designed in the original template.
            setCell(cells[3], row?.cq.orEmpty())
            setCell(cells[4], row?.mcq.orEmpty())
            setCell(cells[5], row?.total.orEmpty())
            setCell(cells[6], row?.grade.orEmpty())
        }
    }
    val totalCells = rowCells(subjectTable, 12)
    if (totalCells.isNotEmpty()) {
        setCell(totalCells[0], "Total Number:")
        totalCells.lastOrNull()?.takeIf { it !== totalCells.first() }?.let { setCell(it, if (presentRows.isEmpty()) "" else obtained.toString()) }
    }
    val monthlyCells = rowCells(subjectTable, 13)
    if (monthlyCells.isNotEmpty()) {
        setCell(monthlyCells[0], "Monthly Evaluation Average:")
        monthlyCells.lastOrNull()?.takeIf { it !== monthlyCells.first() }?.let { setCell(it, monthlyEvaluationAverage) }
    }
    val grandCells = rowCells(subjectTable, 14)
    if (grandCells.isNotEmpty()) {
        setCell(grandCells[0], "Grand Total Mark:")
        grandCells.lastOrNull()?.takeIf { it !== grandCells.first() }?.let { setCell(it, if (presentRows.isEmpty()) "" else obtained.toString()) }
    }

    // Keep the template's original page layout, styling, logo, grade scale, comments, and signatures.
    val transformer = TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
    }
    val xmlOut = ByteArrayOutputStream()
    transformer.transform(DOMSource(doc), StreamResult(xmlOut))
    entries["word/document.xml"] = xmlOut.toByteArray()

    val resultZip = ByteArrayOutputStream()
    ZipOutputStream(resultZip).use { output ->
        entries.forEach { (name, bytes) ->
            output.putNextEntry(java.util.zip.ZipEntry(name))
            output.write(bytes)
            output.closeEntry()
        }
    }
    val outputBytes = resultZip.toByteArray()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, DOCX_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AMS Manager")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("Unable to create DOCX result card")
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(outputBytes) }
                ?: throw java.io.IOException("Unable to write DOCX result card")
            context.contentResolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return DocxOutput(uri, filename)
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    } else {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AMS Manager")
        if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("Unable to create downloads folder")
        val file = File(dir, filename)
        file.writeBytes(outputBytes)
        return DocxOutput(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), filename)
    }
}

private fun generateStudentResultCardPdf(
    context: android.content.Context, student: Student, detail: StudentDetail, className: String, section: String
): PdfOutput {
    val doc = PdfDocument()
    // A4 landscape; 23 PDF points is approximately 0.8 cm.
    val w = 842; val h = 595; val m = 23f
    val black = android.graphics.Color.BLACK
    val gray = android.graphics.Color.rgb(235, 235, 235)
    fun p(size: Float, bold: Boolean = false, fill: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (fill) gray else black
        textSize = size
        typeface = android.graphics.Typeface.create("serif", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
        strokeWidth = .65f
    }
    val border = p(8f)
    val shade = p(8f, fill = true)
    val text = p(8f, true).apply { style = Paint.Style.FILL }
    val regular = p(8f).apply { style = Paint.Style.FILL }
    val tinyBold = p(6.2f, true).apply { style = Paint.Style.FILL }
    val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
    val c = page.canvas
    try {
        fun box(l: Float, t: Float, r: Float, b: Float, paint: Paint = border) = c.drawRect(l, t, r, b, paint)
        fun txt(value: String, x: Float, y: Float, paint: Paint = regular, maxChars: Int = 40) {
            c.drawText(value.take(maxChars), x, y, paint)
        }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, border)
        box(m, m, w-m, h-m)

        // Header with the school's emblem, smaller title, and monochrome print styling.
        val logo = runCatching { BitmapFactory.decodeResource(context.resources, com.ams.mgr.R.drawable.amtoli_school_logo) }.getOrNull()
        if (logo != null) {
            c.drawBitmap(logo, null, android.graphics.RectF(62f, 27f, 123f, 88f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            logo.recycle()
        } else {
            val appLogo = runCatching { BitmapFactory.decodeResource(context.resources, com.ams.mgr.R.mipmap.ic_launcher) }.getOrNull()
            if (appLogo != null) { c.drawBitmap(appLogo, null, android.graphics.RectF(62f, 27f, 123f, 88f), Paint(Paint.ANTI_ALIAS_FLAG)); appLogo.recycle() }
        }
        txt("AMTOLI MODEL SCHOOL", 235f, 45f, p(22f, true).apply { style = Paint.Style.FILL }, 32)
        txt("Shibganj, Bogura   |   EIIN: 137662", 286f, 65f, p(10f, true).apply { style = Paint.Style.FILL }, 42)
        txt("Knowledge Builds", 680f, 38f, p(8.5f, true).apply { style = Paint.Style.FILL }, 20)
        txt("The Future", 699f, 50f, p(8.5f, true).apply { style = Paint.Style.FILL }, 16)
        line(31f, 111f, 331f, 111f); line(511f, 111f, 811f, 111f)
        box(337f, 84f, 505f, 111f)
        txt("RESULT CARD", 352f, 103f, p(16f, true).apply { style = Paint.Style.FILL }, 18)

        val left = 29f; val leftW = 205f; val right = 239f; val rightW = 574f
        val top = 119f; val bottom = 461f
        c.drawRoundRect(android.graphics.RectF(left, top, left+leftW, bottom), 4f, 4f, border)
        box(left+5, top+5, left+leftW-5, top+24, shade)
        txt("STUDENT INFORMATION", left+9, top+18, text, 25)
        var y = top+40
        fun info(label: String, value: String = "") {
            txt(label, left+9, y, text, 19); txt(":", left+101, y, text)
            if (value.isNotBlank()) txt(value, left+110, y, regular, 17)
            y += 19f
        }
        info("Student Name", student.studentName)
        info("Father's Name", student.fatherName)
        info("Class", className); info("Section", section); info("Roll No", student.studentId)
        info("Date of Birth", ""); info("Address", student.address)
        y += 3f
        box(left+5, y-13, left+leftW-5, y+5, shade)
        txt("OTHER INFORMATION", left+9, y, text, 25); y += 22f
        info("Behavior"); info("Attendance")
        info("GPA", String.format(java.util.Locale.US, "%.2f", detail.gpa))
        val overallGrade = when { detail.gpa >= 5 -> "A+"; detail.gpa >= 4 -> "A"; detail.gpa >= 3.5 -> "A-"; detail.gpa >= 3 -> "B"; detail.gpa >= 2 -> "C"; detail.gpa >= 1 -> "D"; else -> "F" }
        info("Grade", overallGrade)

        c.drawRoundRect(android.graphics.RectF(right, top, right+rightW, bottom), 4f, 4f, border)
        c.drawRoundRect(android.graphics.RectF(right+5, top+5, right+rightW-5, top+25), 4f, 4f, shade)
        // As requested, keep these as empty fields for staff to fill or stamp later.
        txt("Exam Name   :", right+12, top+18, text, 20)
        txt("Date:", right+rightW-83, top+18, text)

        val tableTop = top+31f
        val headerH = 29f
        val tableBottom = bottom-10f
        val gap = 5f
        val leftTableW = 313f
        val rightTableW = rightW-gap-leftTableW
        val leftX = right+6f
        val rightX = leftX+leftTableW+gap
        val leftHeaders = listOf("Sl.", "Subject / Paper", "Full\nMarks", "CQ", "MCQ", "Total", "GPA", "Grade")
        val leftWidths = listOf(22f, 91f, 35f, 30f, 31f, 31f, 29f, 44f)
        // The right-hand table is only 256 pt wide. Keep every column readable;
        // the previous final-width expression left the Grade column just 2 pt wide.
        val rightHeaders = leftHeaders
        val rightWidths = listOf(18f, 62f, 30f, 26f, 28f, 30f, 26f, 36f)

        require(leftWidths.size == leftHeaders.size && leftWidths.all { it > 0f })
        require(rightWidths.size == rightHeaders.size && rightWidths.all { it >= 18f })
        require(kotlin.math.abs(rightWidths.sum() - rightTableW) < 0.1f)

        fun drawHeader(x0: Float, widths: List<Float>, headers: List<String>) {
            var x = x0
            headers.forEachIndexed { i, header ->
                box(x, tableTop, x+widths[i], tableTop+headerH, shade)
                box(x, tableTop, x+widths[i], tableTop+headerH, border)
                val parts = header.split("\n")
                if (parts.size == 1) txt(parts[0], x+2f, tableTop+18f, tinyBold, 12)
                else { txt(parts[0], x+3f, tableTop+12f, tinyBold, 8); txt(parts[1], x+3f, tableTop+23f, tinyBold, 8) }
                x += widths[i]
            }
        }
        drawHeader(leftX, leftWidths, leftHeaders)
        drawHeader(rightX, rightWidths, rightHeaders)

        val rowsByName = detail.subjectRows.associateBy { it.name }
        fun getRow(name: String) = rowsByName[name]
        val logicalLeft = listOf(
            listOf(getRow("Bangla 1st Paper"), getRow("Bangla 2nd Paper")),
            listOf(getRow("English 1st Paper"), getRow("English 2nd Paper")),
            listOf(getRow("Mathematics")),
            listOf(getRow("Social Sciences") ?: getRow("Social Science")),
            listOf(getRow("Science")),
            listOf(getRow("Religion"))
        ).map { group -> group.filterNotNull() }.filter { it.isNotEmpty() }
        val logicalRight = listOf(
            listOf(getRow("ICT")),
            listOf(getRow("Agriculture") ?: getRow("Agriculture/Higher Math") ?: getRow("Higher Math")),
            listOf(getRow("Oral"))
        ).map { group -> group.filterNotNull() }.filter { it.isNotEmpty() }
        val leftPhysicalRows = logicalLeft.sumOf { it.size }.coerceAtLeast(1)
        val usableH = tableBottom-tableTop-headerH
        val rowH = usableH / leftPhysicalRows
        val gradeText: (Int, Int) -> String = { total, max -> gradeForExam(total, max) }

        fun drawGroupedRows(x0: Float, widths: List<Float>, groups: List<List<DetailSubjectRow>>, firstSerial: Int, physicalRowH: Float, withSummary: Boolean) {
            var physicalIndex = 0
            groups.forEachIndexed { groupIndex, group ->
                val groupTop = tableTop+headerH+physicalIndex*physicalRowH
                val groupHeight = physicalRowH*group.size
                // One serial number for the two-paper Bangla/English group.
                box(x0, groupTop, x0+widths[0], groupTop+groupHeight, border)
                val serial = if (x0 == leftX) groupIndex+1 else firstSerial+groupIndex
                txt(serial.toString(), x0+7f, groupTop+groupHeight/2f+3f, text)
                group.forEachIndexed { within, row ->
                    val ry = groupTop+within*physicalRowH
                    var x = x0+widths[0]
                    val subjectLabel = when (row.name) {
                        "Bangla 1st Paper" -> "Bangla (1st Paper)"
                        "Bangla 2nd Paper" -> "Bangla (2nd Paper)"
                        "English 1st Paper" -> "English (1st Paper)"
                        "English 2nd Paper" -> "English (2nd Paper)"
                        "Social Sciences" -> "Social science"
                        "Agriculture/Higher Math" -> "Agriculture"
                        else -> row.name
                    }
                    box(x, ry, x+widths[1], ry+physicalRowH, border)
                    val subjectLines = when (subjectLabel) {
                        "Bangla (1st Paper)" -> listOf("Bangla", "(1st Paper)")
                        "Bangla (2nd Paper)" -> listOf("Bangla", "(2nd Paper)")
                        "English (1st Paper)" -> listOf("English", "(1st Paper)")
                        "English (2nd Paper)" -> listOf("English", "(2nd Paper)")
                        else -> listOf(subjectLabel)
                    }
                    if (subjectLines.size == 2) {
                        txt(subjectLines[0], x+7f, ry+physicalRowH/2f-1f, regular, 17)
                        txt(subjectLines[1], x+7f, ry+physicalRowH/2f+9f, regular, 17)
                    } else txt(subjectLabel, x+7f, ry+physicalRowH/2f+3f, regular, 18)
                    x += widths[1]
                    val values = listOf(row.max.toString(), row.cq, row.mcq)
                    for (i in values.indices) {
                        box(x, ry, x+widths[i+2], ry+physicalRowH, border)
                        txt(if (values[i] == "—") "" else values[i], x+5f, ry+physicalRowH/2f+3f, regular, 8)
                        x += widths[i+2]
                    }
                    val hasMarks = row.cq != "—" || row.mcq != "—"
                    val ownValues = listOf(row.total.toString(), String.format(java.util.Locale.US, "%.1f", row.gpa), gradeText(row.total, row.max))
                    for (i in 0..2) {
                        val col = i+5
                        box(x, ry, x+widths[col], ry+physicalRowH, border)
                        val out = if (!hasMarks) "" else ownValues[i]
                        txt(out, x+3f, ry+physicalRowH/2f+3f, regular, 8)
                        x += widths[col]
                    }
                }
                physicalIndex += group.size
            }
        }
        drawGroupedRows(leftX, leftWidths, logicalLeft, 1, rowH, false)
        val rightRowsH = rowH*2f // leave enough vertical room for each of the three right-side subjects
        drawGroupedRows(rightX, rightWidths, logicalRight, 7, rightRowsH, true)

        // Summary is directly under Oral, with only the three requested labels.
        val summaryTop = tableTop+headerH+logicalRight.size*rightRowsH
        val summaryBottom = tableBottom
        box(rightX, summaryTop, rightX+rightTableW, summaryBottom, shade)
        box(rightX, summaryTop, rightX+rightTableW, summaryBottom, border)
        val summaryLabels = listOf("Total Number", "Monthly Evaluation Average", "Grand Total Mark")
        val summaryValues = listOf(
            detail.examOnlyObtained.toString(),
            String.format(java.util.Locale.US, "%.1f", detail.monthlyEquivalent),
            String.format(java.util.Locale.US, "%.1f", detail.adjustedObtained)
        )
        summaryLabels.forEachIndexed { i, label ->
            val sy = summaryTop+15f+i*18f
            txt("$label:", rightX+13f, sy, text, 28)
            txt(summaryValues[i], rightX+rightTableW-39f, sy, text, 8)
        }

        // Signature/comment strip mirrors the supplied reference.
        val footerTop = 468f; val footerBottom = h-m-5f
        val footerX = m+1f; val footerW = w-2*m-2f; val fw = footerW/4f
        val footerLabels = listOf("Class teacher's comment", "Class teacher's signature", "Head teacher's signature", "Parent's signature")
        footerLabels.forEachIndexed { i, label ->
            val x = footerX+i*fw
            box(x, footerTop, x+fw, footerBottom, border)
            txt(label, x+7f, footerTop+17f, text, 31)
            if (i == 0) repeat(3) { line(x+10f, footerTop+37f+it*13f, x+fw-10f, footerTop+37f+it*13f) }
            else line(x+10f, footerBottom-12f, x+fw-10f, footerBottom-12f)
        }
        doc.finishPage(page)
        val fileName = "AMS_Result_Card_${student.studentId}_${LocalDate.now()}.pdf"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS+"/AMS Manager")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Unable to create result card PDF")
            try {
                context.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) } ?: throw java.io.IOException("Unable to write PDF")
                context.contentResolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                return PdfOutput(uri, fileName)
            } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AMS Manager")
            if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("Unable to create downloads folder")
            val file = File(dir, fileName); file.outputStream().use { doc.writeTo(it) }
            return PdfOutput(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), fileName)
        }
    } finally { doc.close() }
}


private fun generateBulkResultCardsPdf(
    context: android.content.Context,
    students: List<Student>,
    className: String,
    section: String,
    examType: String,
    marks: Map<Pair<Long, String>, ExamMark>
): PdfOutput {
    val document = PdfDocument()
    // A4 portrait page (595 x 842 PDF points), following the supplied Amtoli DOCX template.
    val width = 595
    val height = 842
    val black = android.graphics.Color.BLACK
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = black; style = Paint.Style.STROKE; strokeWidth = 0.65f }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(238, 238, 238); style = Paint.Style.FILL }
    fun paint(size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = black; textSize = size
        typeface = android.graphics.Typeface.create("serif", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }
    val titlePaint = paint(16f, true)
    val head = paint(8f, true)
    val normal = paint(7.2f)
    val small = paint(6.6f)
    val specs = CLASS_6_8_EXAM_SUBJECTS
    val maxTotal = specs.sumOf { it.max }
    val filename = "AMS_Result_Cards_Class_${className}_${section}_${examType.replace(" ", "_")}_${LocalDate.now()}.pdf"
    try {
        students.forEachIndexed { index, student ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
            val c = page.canvas
            fun rect(l: Float, t: Float, r: Float, b: Float, p: Paint = border) = c.drawRect(l, t, r, b, p)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, border)
            fun txt(value: String, x: Float, y: Float, p: Paint = normal, maxChars: Int = 90) {
                c.drawText(value.replace('\n', ' ').take(maxChars), x, y, p)
            }
            rect(22f, 18f, 573f, 824f)
            val logo = runCatching { BitmapFactory.decodeResource(context.resources, com.ams.mgr.R.drawable.amtoli_school_logo) }.getOrNull()
            if (logo != null) {
                c.drawBitmap(logo, null, android.graphics.RectF(34f, 27f, 82f, 75f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                logo.recycle()
            }
            txt("AMTOLI MODEL SCHOOL", 145f, 42f, titlePaint, 32)
            txt("Shibganj, Bogura", 205f, 58f, head, 25)
            txt("EIIN: 137662", 229f, 72f, head, 20)
            line(32f, 88f, 563f, 88f)
            txt("Exam Name: $examType", 35f, 106f, head, 35)
            txt("Date: ${LocalDate.now()}", 405f, 106f, head, 22)
            rect(216f, 116f, 379f, 139f)
            txt("RESULT CARD", 250f, 131f, paint(12f, true), 20)

            // Student details and other information mirror the uploaded template.
            rect(32f, 147f, 303f, 278f)
            rect(32f, 147f, 303f, 166f, fill)
            txt("STUDENT DETAILS", 40f, 160f, head, 24)
            val studentInfo = listOf(
                "Student Name:" to student.studentName,
                "Father's Name:" to student.fatherName,
                "Class:" to className,
                "Section:" to section,
                "Roll No:" to student.studentId,
                "Address:" to student.address
            )
            studentInfo.forEachIndexed { i, pair ->
                val y = 182f + i * 15f
                txt(pair.first, 40f, y, head, 18)
                txt(pair.second.ifBlank { "" }, 125f, y, normal, 27)
            }
            rect(311f, 147f, 563f, 278f)
            rect(311f, 147f, 563f, 166f, fill)
            txt("OTHER INFORMATION", 319f, 160f, head, 25)
            // Keep these rows separate from GPA/Grade so labels never overlap.
            val other = listOf(
                "Behavior:" to "",
                "Total Working Days:" to "",
                "Present:" to "",
                "Absent:" to ""
            )
            other.forEachIndexed { i, pair ->
                val y = 181f + i * 15f
                txt(pair.first, 319f, y, head, 25)
                txt(pair.second, 423f, y, normal, 18)
            }
            txt("Next Class Roll Number:", 319f, 246f, head, 27)
            txt("", 423f, 246f, normal, 18)

            val studentMarks = specs.map { spec ->
                val mark = marks[student.id to spec.key]
                val total = if (mark == null) 0 else (mark.cq ?: 0) + (mark.mcq ?: 0)
                Triple(spec, mark, total)
            }
            val obtained = studentMarks.sumOf { it.third }
            val gpaSubjects = studentMarks.filter { it.first.countsForGpa && it.second != null }
            val failed = gpaSubjects.any { gradeForExam(it.third, it.first.max) == "F" }
            val gpa = if (failed || gpaSubjects.isEmpty()) 0.0 else gpaSubjects.map { gradePointForExam(it.third, it.first.max) }.average()
            val overallGrade = when { gpa >= 5.0 -> "A+"; gpa >= 4.0 -> "A"; gpa >= 3.5 -> "A-"; gpa >= 3.0 -> "B"; gpa >= 2.0 -> "C"; gpa >= 1.0 -> "D"; else -> "F" }
            txt("GPA: ${if (gpaSubjects.isEmpty()) "" else String.format(java.util.Locale.US, "%.2f", gpa)}", 319f, 265f, head)
            txt("Grade: ${if (gpaSubjects.isEmpty()) "" else overallGrade}", 430f, 265f, head)

            txt("SUBJECT / PAPER RESULTS", 32f, 296f, paint(9f, true), 30)
            val tx = 32f
            val tableTop = 304f
            val widths = listOf(27f, 170f, 52f, 58f, 58f, 58f, 108f)
            val headers = listOf("Sl.", "Subject / Paper", "Full Marks", "CQ", "MCQ", "Total", "Grade")
            var x = tx
            headers.forEachIndexed { i, label ->
                rect(x, tableTop, x + widths[i], tableTop + 22f, fill)
                rect(x, tableTop, x + widths[i], tableTop + 22f)
                txt(label, x + 3f, tableTop + 14f, head, 15)
                x += widths[i]
            }
            val rowHeight = 22f
            studentMarks.forEachIndexed { row, tuple ->
                val y = tableTop + 22f + row * rowHeight
                var xx = tx
                val spec = tuple.first
                val mark = tuple.second
                val total = tuple.third
                val values = listOf(
                    (row + 1).toString(), spec.name, spec.max.toString(),
                    mark?.cq?.toString() ?: "", if (spec.hasMcq) mark?.mcq?.toString() ?: "" else "—",
                    if (mark == null) "" else total.toString(),
                    if (mark == null) "" else gradeForExam(total, spec.max)
                )
                values.forEachIndexed { col, value ->
                    rect(xx, y, xx + widths[col], y + rowHeight)
                    txt(value, xx + 3f, y + 14f, if (col == 1) small else normal, if (col == 1) 28 else 15)
                    xx += widths[col]
                }
            }
            val summaryTop = tableTop + 22f + studentMarks.size * rowHeight
            rect(tx, summaryTop, 563f, summaryTop + 48f, fill)
            rect(tx, summaryTop, 563f, summaryTop + 48f)
            txt("Total Number: ${if (studentMarks.any { it.second != null }) obtained.toString() else ""} / $maxTotal", 40f, summaryTop + 15f, head, 34)
            txt("Monthly Evaluation Average: __________________", 40f, summaryTop + 29f, head, 43)
            txt("Grand Total Mark: ${if (studentMarks.any { it.second != null }) obtained.toString() else ""}", 40f, summaryTop + 42f, head, 30)

            val commentTop = summaryTop + 58f
            rect(32f, commentTop, 563f, 748f)
            txt("CLASS TEACHER'S COMMENT:", 40f, commentTop + 17f, head, 30)
            line(40f, commentTop + 42f, 555f, commentTop + 42f)
            line(40f, commentTop + 61f, 555f, commentTop + 61f)
            line(40f, commentTop + 80f, 555f, commentTop + 80f)
            line(47f, 786f, 190f, 786f)
            line(224f, 786f, 367f, 786f)
            line(401f, 786f, 548f, 786f)
            txt("Class Teacher's Signature", 48f, 801f, small, 30)
            txt("Head Teacher's Signature", 224f, 801f, small, 30)
            txt("Parent's Signature", 410f, 801f, small, 24)
            document.finishPage(page)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AMS Manager")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Unable to create result cards PDF")
            try {
                context.contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
                    ?: throw java.io.IOException("Unable to write result cards PDF")
                context.contentResolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                return PdfOutput(uri, filename)
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AMS Manager")
            if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("Unable to create downloads folder")
            val file = File(dir, filename)
            file.outputStream().use { document.writeTo(it) }
            return PdfOutput(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), filename)
        }
    } finally {
        document.close()
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
