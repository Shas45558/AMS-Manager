package com.ams.mgr.face

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ams.mgr.data.FaceTemplate
import com.ams.mgr.data.SchoolRepository
import com.ams.mgr.data.Student
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.sqrt

@Composable
fun FaceEnrollmentScreen(repository: SchoolRepository, studentId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var student by remember { mutableStateOf<Student?>(null) }
    var registered by remember { mutableStateOf(false) }
    var registrationStarted by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Look at the camera and keep your face inside the frame.") }
    var busy by remember { mutableStateOf(false) }
    val embeddings = remember { mutableListOf<FloatArray>() }
    var lastSampleAt by remember { mutableLongStateOf(0L) }
    val embedder = remember { FaceNetEmbedder(context) }
    DisposableEffect(Unit) { onDispose { embedder.close() } }

    LaunchedEffect(studentId) {
        student = repository.getStudent(studentId)
        registered = repository.getFaceTemplate(studentId) != null
    }

    fun handleFace(bitmap: android.graphics.Bitmap) {
        val now = System.currentTimeMillis()
        if (!registrationStarted || busy || now - lastSampleAt < 850L || embeddings.size >= 8) { bitmap.recycle(); return }
        lastSampleAt = now
        busy = true
        scope.launch {
            try {
                val embedding = withContext(Dispatchers.Default) { embedder.embed(bitmap) }
                embeddings += embedding
                samples = embeddings.size
                status = if (samples < 8) "Good. Hold still… sample $samples/8" else "Saving face template…"
                if (samples >= 8) {
                    val average = averageNormalized(embeddings)
                    repository.saveFaceTemplate(FaceTemplate(studentId = studentId, embedding = FaceMatcher.encode(average)))
                    registered = true
                    status = "Face registered successfully."
                }
            } catch (e: Exception) {
                status = "Face processing failed: ${e.message ?: "unknown error"}"
            } finally {
                busy = false
                bitmap.recycle()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        student?.let { s ->
            Text("Register Face", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
            Text("${s.studentName} • Class ${s.className}-${s.section} • Roll ${s.studentId}", modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (registered && samples == 0) {
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("A face template is already registered. Start a new capture to replace it.")
                }
            }
        }
        if (!registrationStarted) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Ready to register?", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("When you press Ready, the camera will collect 8 clear face samples over several seconds. Keep the face centered and steady.")
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            registrationStarted = true
                            samples = 0
                            embeddings.clear()
                            lastSampleAt = 0L
                            status = "Starting… keep your face centered and steady."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ready — Start Registration")
                    }
                }
            }
        }
        FaceCameraPreview(onFace = ::handleFace, modifier = Modifier.fillMaxWidth().weight(1f))
        Text(status, modifier = Modifier.padding(16.dp))
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Back") }
            if (registered) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            repository.deleteFaceTemplate(studentId)
                            registered = false
                            samples = 0
                            embeddings.clear()
                            status = "Face registration deleted. You can register a new face."
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete Face")
                }
            }
        }
    }
}

@Composable
fun FaceAttendanceScreen(
    repository: SchoolRepository,
    className: String,
    section: String,
    date: String,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allStudents by repository.students("").collectAsState(initial = emptyList())
    // Keep the complete active-student list for recognition. The selected class/section
    // is applied after a face is identified; filtering before matching can make a valid
    // registered template look like an "Unknown student" when formatting differs slightly.
    val activeStudents = remember(allStudents) {
        allStudents.filter { it.activeStatus.trim().equals("active", ignoreCase = true) }
    }
    val students = remember(activeStudents, className, section) {
        activeStudents.filter {
            it.className.trim().equals(className.trim(), ignoreCase = true) &&
            it.section.trim().equals(section.trim(), ignoreCase = true)
        }
    }
    var templates by remember { mutableStateOf<List<FaceTemplate>>(emptyList()) }
    var templateStudents by remember { mutableStateOf<Map<Long, Student>>(emptyMap()) }
    val marked = remember { mutableStateListOf<Long>() }
    var lastResult by remember { mutableStateOf("Scanning…") }
    var lastScore by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var lastRecognizedAt by remember { mutableLongStateOf(0L) }
    val embedder = remember { FaceNetEmbedder(context) }
    DisposableEffect(Unit) { onDispose { embedder.close() } }

    LaunchedEffect(activeStudents, date) {
        // Match against all active registered faces, then enforce the selected class/section.
        // This prevents a valid face from being reported as unknown simply because the
        // class/section text has different capitalization or whitespace.
        val snapshot = activeStudents.associateBy { it.id }
        val loadedTemplates = activeStudents.mapNotNull { repository.getFaceTemplate(it.id) }
        templates = loadedTemplates
        templateStudents = snapshot
        students.forEach { if (repository.attendanceForDate(it.id, date)?.present == true && !marked.contains(it.id)) marked += it.id }
    }

    fun handleFace(bitmap: android.graphics.Bitmap) {
        val now = System.currentTimeMillis()
        if (busy || now - lastRecognizedAt < 900L) { bitmap.recycle(); return }
        busy = true
        scope.launch {
            try {
                val embedding = withContext(Dispatchers.Default) { embedder.embed(bitmap) }
                val match = FaceMatcher.bestMatch(embedding, templates, minimumSimilarity = 0.80f)
                if (match == null) {
                    lastResult = "Face detected, but no registered student matched (best confidence below 80%)."
                    lastScore = 0f
                } else {
                    // Use the same student snapshot that was used to load the matched
                    // template. The live roster Flow can refresh during camera scanning;
                    // looking up against the newer list can otherwise produce a false
                    // "student record is missing" even with a strong face match.
                    val student = templateStudents[match.studentId] ?: repository.getStudent(match.studentId)
                    if (student == null) {
                        lastResult = "Face matched, but the student record is not available on this device. Sync students or re-register the face."
                        lastScore = match.similarity
                    } else {
                        val sameClass = student.className.trim().equals(className.trim(), ignoreCase = true)
                        val sameSection = student.section.trim().equals(section.trim(), ignoreCase = true)
                        lastScore = match.similarity
                        if (!sameClass || !sameSection) {
                            lastResult = "${student.studentName} — not in Class $className-$section"
                        } else {
                            lastRecognizedAt = now
                            val alreadyPresent = repository.attendanceForDate(student.id, date)?.present == true
                            if (!alreadyPresent) repository.markAttendance(student.id, date, true)
                            if (!marked.contains(student.id)) marked += student.id
                            lastResult = if (alreadyPresent) "${student.studentName} — already present" else "${student.studentName} — Present"
                        }
                    }
                }
            } catch (e: Exception) {
                lastResult = "Face processing error: ${e.message ?: "unknown error"}"
            } finally {
                busy = false
                bitmap.recycle()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text("Face Attendance", style = MaterialTheme.typography.headlineSmall)
            Text("Class $className-$section • $date")
            Text("Registered faces: ${templates.size}/${students.size} • Present: ${marked.size}", style = MaterialTheme.typography.bodySmall)
        }
        FaceCameraPreview(onFace = ::handleFace, modifier = Modifier.fillMaxWidth().weight(1f))
        Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (lastResult.contains("Present")) Icons.Default.CheckCircle else Icons.Default.Face, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(lastResult, style = MaterialTheme.typography.titleMedium)
                }
                if (lastScore > 0f) Text("Similarity ${(lastScore * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Finish") }
    }
}

@Composable
private fun FaceCameraPreview(
    onFace: (android.graphics.Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var useFrontCamera by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (!permission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdownNow() }
    }

    DisposableEffect(permission, lifecycleOwner, previewView, useFrontCamera) {
        if (!permission) return@DisposableEffect onDispose { }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        val analyzer = FaceCameraAnalyzer(onFace = onFace)
        var disposed = false
        val listener = Runnable {
            if (disposed) {
                analyzer.close()
                return@Runnable
            }
            runCatching {
                val provider = providerFuture.get()
                val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) error("Selected camera is not available on this device")
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor, analyzer)
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                cameraReady = true
                cameraError = null
            }.onFailure {
                cameraReady = false
                cameraError = it.message ?: "Could not open camera"
            }
        }
        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            analyzer.close()
            // Never block Compose disposal with Future.get(). CameraX will release
            // lifecycle-bound use cases; if the provider is already ready, unbind it.
            if (providerFuture.isDone) {
                runCatching { providerFuture.get().unbindAll() }
            }
        }
    }

    Box(modifier.background(Color.Black)) {
        if (permission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            IconButton(
                onClick = { useFrontCamera = !useFrontCamera },
                enabled = cameraReady,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), shape = androidx.compose.foundation.shape.CircleShape)
            ) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Switch front/back camera", tint = Color.White)
            }
            if (cameraError != null) {
                Card(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.72f))
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text(cameraError!!, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { useFrontCamera = !useFrontCamera }) { Text("Try other camera") }
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White)
                Text("Camera permission is required.", color = Color.White)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
    }
}

@Composable
fun FaceCheckScreen(repository: SchoolRepository, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allStudents by repository.students("").collectAsState(initial = emptyList())
    val activeStudents = remember(allStudents) {
        allStudents.filter { it.activeStatus.trim().equals("active", ignoreCase = true) }
    }
    var templates by remember { mutableStateOf<List<FaceTemplate>>(emptyList()) }
    var templateStudents by remember { mutableStateOf<Map<Long, Student>>(emptyMap()) }
    var lastResult by remember { mutableStateOf("Look at a student's face to check identity.") }
    var lastScore by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var lastInferenceAt by remember { mutableLongStateOf(0L) }
    var lastMatchedId by remember { mutableStateOf<Long?>(null) }
    val embedder = remember { FaceNetEmbedder(context) }
    DisposableEffect(Unit) { onDispose { embedder.close() } }

    LaunchedEffect(activeStudents) {
        val snapshot = activeStudents.associateBy { it.id }
        templates = activeStudents.mapNotNull { repository.getFaceTemplate(it.id) }
        templateStudents = snapshot
    }

    fun handleFace(bitmap: android.graphics.Bitmap) {
        val now = System.currentTimeMillis()
        if (busy || now - lastInferenceAt < 700L) { bitmap.recycle(); return }
        lastInferenceAt = now
        busy = true
        scope.launch {
            try {
                val embedding = withContext(Dispatchers.Default) { embedder.embed(bitmap) }
                val match = FaceMatcher.bestMatch(embedding, templates, minimumSimilarity = 0.80f)
                if (match == null) {
                    lastResult = "Face detected, but no registered student matched."
                    lastScore = 0f
                    lastMatchedId = null
                } else {
                    val student = templateStudents[match.studentId] ?: repository.getStudent(match.studentId)
                    lastScore = match.similarity
                    if (student == null) {
                        lastResult = "Face matched, but the student record is missing on this device."
                        lastMatchedId = null
                    } else {
                        lastResult = "${student.studentName} • Class ${student.className} • Section ${student.section}"
                        lastMatchedId = student.id
                    }
                }
            } catch (e: Exception) {
                lastResult = "Face check error: ${e.message ?: "unknown error"}"
                lastScore = 0f
                lastMatchedId = null
            } finally {
                busy = false
                bitmap.recycle()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text("Face Check", style = MaterialTheme.typography.headlineSmall)
            Text("Recognize a registered student without marking attendance.")
        }
        FaceCameraPreview(onFace = ::handleFace, modifier = Modifier.fillMaxWidth().weight(1f))
        Card(
            Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (lastMatchedId != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (lastMatchedId != null) Icons.Default.CheckCircle else Icons.Default.Face,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(lastResult, style = MaterialTheme.typography.titleMedium)
                }
                if (lastScore > 0f) Text("Similarity ${(lastScore * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Finish") }
    }
}

private fun averageNormalized(vectors: List<FloatArray>): FloatArray {
    require(vectors.isNotEmpty())
    val out = FloatArray(vectors.first().size)
    vectors.forEach { v -> for (i in out.indices) out[i] += v[i] }
    val scale = 1f / vectors.size
    for (i in out.indices) out[i] *= scale
    var norm = 0.0
    out.forEach { norm += it * it }
    val n = sqrt(norm).toFloat().coerceAtLeast(1e-12f)
    for (i in out.indices) out[i] /= n
    return out
}
