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
        if (busy || now - lastSampleAt < 450L || embeddings.size >= 5) { bitmap.recycle(); return }
        lastSampleAt = now
        busy = true
        scope.launch {
            try {
                val embedding = withContext(Dispatchers.Default) { embedder.embed(bitmap) }
                embeddings += embedding
                samples = embeddings.size
                status = if (samples < 5) "Good. Hold still… sample $samples/5" else "Saving face template…"
                if (samples >= 5) {
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
        FaceCameraPreview(onFace = ::handleFace, modifier = Modifier.fillMaxWidth().weight(1f))
        Text(status, modifier = Modifier.padding(16.dp))
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Back") }
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
    val students = remember(allStudents, className, section) { allStudents.filter { it.activeStatus == "active" && it.className == className && it.section == section } }
    var templates by remember { mutableStateOf<List<FaceTemplate>>(emptyList()) }
    val marked = remember { mutableStateListOf<Long>() }
    var lastResult by remember { mutableStateOf("Scanning…") }
    var lastScore by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var lastRecognizedAt by remember { mutableLongStateOf(0L) }
    val embedder = remember { FaceNetEmbedder(context) }
    DisposableEffect(Unit) { onDispose { embedder.close() } }

    LaunchedEffect(students, date) {
        templates = students.mapNotNull { repository.getFaceTemplate(it.id) }
        students.forEach { if (repository.attendanceForDate(it.id, date)?.present == true && !marked.contains(it.id)) marked += it.id }
    }

    fun handleFace(bitmap: android.graphics.Bitmap) {
        val now = System.currentTimeMillis()
        if (busy || now - lastRecognizedAt < 900L) { bitmap.recycle(); return }
        busy = true
        scope.launch {
            try {
                val embedding = withContext(Dispatchers.Default) { embedder.embed(bitmap) }
                val match = FaceMatcher.bestMatch(embedding, templates, minimumSimilarity = 0.72f)
                if (match == null) {
                    lastResult = "Face not recognized"
                    lastScore = 0f
                } else {
                    val student = students.firstOrNull { it.id == match.studentId }
                    if (student == null) {
                        lastResult = "Unknown student"
                    } else {
                        lastRecognizedAt = now
                        lastScore = match.similarity
                        val alreadyPresent = repository.attendanceForDate(student.id, date)?.present == true
                        if (!alreadyPresent) repository.markAttendance(student.id, date, true)
                        if (!marked.contains(student.id)) marked += student.id
                        lastResult = if (alreadyPresent) "${student.studentName} — already present" else "${student.studentName} — Present"
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
private fun FaceCameraPreview(onFace: (android.graphics.Bitmap) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    LaunchedEffect(Unit) { if (!permission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    DisposableEffect(permission, lifecycleOwner, previewView) {
        if (!permission) return@DisposableEffect onDispose { }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val analyzer = FaceCameraAnalyzer(onFace = onFace)
        val listener = Runnable {
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor, analyzer)
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            }
        }
        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            analyzer.close()
        }
    }

    if (permission) {
        AndroidView(factory = { previewView }, modifier = modifier)
    } else {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White)
                Text("Camera permission is required.", color = Color.White)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
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
