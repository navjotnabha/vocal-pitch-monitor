package com.example.pitchmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val SAMPLE_RATE = 44100
private const val FRAME_SIZE = 2048
private const val HOP_SIZE = 512 // ~11.6 ms hop (~86 FPS update rate)
private const val MIN_HZ = 55f
private const val MAX_HZ = 1000f
private const val HISTORY_SIZE = 1200 // ~14s rolling history buffer
private const val DEFAULT_VISIBLE_POINTS = 280f // 1.0x speed (~3.2s window)
private const val MIN_VISIBLE_POINTS = 70f // 4.0x fast speed (~0.8s window)
private const val MAX_VISIBLE_POINTS = 1120f // 0.25x slow speed (~13s window)
private const val NOISE_GATE_RMS = 0.004f

private val BgDarkSlate = Color(0xFF0F172A)
private val BarSlate = Color(0xFF1E293B)
private val TraceSkyBlue = Color(0xFF38BDF8)
private val GreenAccent = Color(0xFF4ADE80)
private val AmberRef = Color(0xFFF59E0B)
private val GridLineColor = Color(0xFF334155)
private val GridLineSubtleColor = Color(0xFF1E293B)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colorScheme = darkColorScheme(
                background = BgDarkSlate,
                surface = BarSlate,
                primary = TraceSkyBlue,
                secondary = AmberRef
            )
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BgDarkSlate
                ) {
                    LinearPitchMonitorScreen()
                }
            }
        }
    }
}

@Composable
fun LinearPitchMonitorScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isRecording by remember { mutableStateOf(false) }
    var currentHz by remember { mutableStateOf<Float?>(null) }
    var micLevel by remember { mutableFloatStateOf(0f) }
    var permissionDeniedMessage by remember { mutableStateOf<String?>(null) }

    val pitchHistory = remember {
        mutableStateListOf<Float?>().apply {
            repeat(HISTORY_SIZE) { add(null) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDeniedMessage = null
            isRecording = true
        } else {
            permissionDeniedMessage = "Microphone permission is required to monitor pitch."
            isRecording = false
        }
    }

    // Automatically start microphone on app launch & resume, and stop on background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPermission) {
                        permissionDeniedMessage = null
                        isRecording = true
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    isRecording = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            isRecording = false
        }
    }

    LaunchedEffect(isRecording) {
        if (!isRecording) {
            micLevel = 0f
            currentHz = null
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize, 4096)

            @SuppressLint("MissingPermission")
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                withContext(Dispatchers.Main) {
                    isRecording = false
                }
                audioRecord.release()
                return@withContext
            }

            val hopBuffer = ShortArray(HOP_SIZE)
            val floatBuffer = FloatArray(FRAME_SIZE)
            val prefixSq = FloatArray(FRAME_SIZE + 1)
            var samplesFilled = 0
            var smoothedHz: Float? = null
            var prevCandidateHz: Float? = null
            val recentRaw = FloatArray(3)
            var recentRawCount = 0

            try {
                audioRecord.startRecording()

                while (isActive && isRecording) {
                    val readCount = audioRecord.read(hopBuffer, 0, HOP_SIZE)
                    if (readCount <= 0) continue

                    // Shift sliding window left by readCount and append new samples
                    if (samplesFilled < FRAME_SIZE) {
                        val copyOffset = minOf(samplesFilled, FRAME_SIZE - readCount)
                        if (samplesFilled + readCount > FRAME_SIZE) {
                            System.arraycopy(
                                floatBuffer,
                                samplesFilled + readCount - FRAME_SIZE,
                                floatBuffer,
                                0,
                                copyOffset
                            )
                        }
                        val startIdx = minOf(samplesFilled, FRAME_SIZE - readCount)
                        for (i in 0 until readCount) {
                            floatBuffer[startIdx + i] = hopBuffer[i] / 32768f
                        }
                        samplesFilled = minOf(FRAME_SIZE, samplesFilled + readCount)
                        if (samplesFilled < FRAME_SIZE) continue
                    } else {
                        System.arraycopy(floatBuffer, readCount, floatBuffer, 0, FRAME_SIZE - readCount)
                        val startIdx = FRAME_SIZE - readCount
                        for (i in 0 until readCount) {
                            floatBuffer[startIdx + i] = hopBuffer[i] / 32768f
                        }
                    }

                    // 1. Compute RMS on the latest hop + full frame RMS for reliable voicing gate
                    var hopSumSquares = 0f
                    val hopStart = FRAME_SIZE - readCount
                    for (i in hopStart until FRAME_SIZE) {
                        val s = floatBuffer[i]
                        hopSumSquares += s * s
                    }
                    val rms = sqrt(hopSumSquares / readCount)
                    val uiLevel = (rms * 12f).coerceIn(0f, 1f)

                    // 2. Apply noise gate and MPM normalized autocorrelation (anti-octave-drop)
                    val detectedRawHz = if (rms < NOISE_GATE_RMS) {
                        null
                    } else {
                        detectPitchMPM(floatBuffer, prefixSq, FRAME_SIZE, SAMPLE_RATE)
                    }

                    // 3. Voicing stability gate + 3-point median filter + anti-spike smoothing
                    val nextSmoothedHz = if (detectedRawHz != null) {
                        val prevCand = prevCandidateHz
                        prevCandidateHz = detectedRawHz

                        // Require 2 consecutive consistent frames (within 2.2 semitones)
                        // so subtle onset/offset breaths never cause downward spikes or stray dots
                        if (prevCand == null || abs(12f * log2(detectedRawHz / prevCand)) > 2.2f) {
                            recentRawCount = 0
                            smoothedHz
                        } else {
                            recentRaw[0] = recentRaw[1]
                            recentRaw[1] = recentRaw[2]
                            recentRaw[2] = detectedRawHz
                            if (recentRawCount < 3) recentRawCount++

                            val filteredRaw = if (recentRawCount == 3) {
                                medianOfThree(recentRaw[0], recentRaw[1], recentRaw[2])
                            } else {
                                detectedRawHz
                            }

                            val prev = smoothedHz
                            val updated = if (prev != null) {
                                val semitoneJump = abs(12f * log2(filteredRaw / prev))
                                if (semitoneJump > 2.5f) {
                                    // Genuine note jump: snap cleanly to the new note without drawing a vertical cliff
                                    filteredRaw
                                } else {
                                    prev * 0.7f + filteredRaw * 0.3f
                                }
                            } else {
                                filteredRaw
                            }
                            smoothedHz = updated
                            updated
                        }
                    } else {
                        prevCandidateHz = null
                        recentRawCount = 0
                        smoothedHz = null
                        null
                    }

                    withContext(Dispatchers.Main) {
                        micLevel = uiLevel
                        currentHz = nextSmoothedHz
                        if (pitchHistory.size >= HISTORY_SIZE) {
                            pitchHistory.removeAt(0)
                        }
                        pitchHistory.add(nextSmoothedHz)
                    }
                }
            } finally {
                try {
                    if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop()
                    }
                } catch (_: Exception) {
                }
                audioRecord.release()
            }
        }
    }

    // Selected musical key for graph bars: -1 = Chromatic (all 12 keys), 0..11 = C..B
    var selectedKeyIndex by remember { mutableIntStateOf(-1) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDarkSlate)
            .navigationBarsPadding()
    ) {
        // Top Header with Key Picker Dropdown
        TopHeaderBar(
            currentHz = currentHz,
            micLevel = micLevel,
            selectedKeyIndex = selectedKeyIndex,
            onSelectKey = { selectedKeyIndex = it },
            permissionDeniedMessage = permissionDeniedMessage
        )

        // Full-Screen Center Canvas (Draggable, Zoomable, & Auto-Following Musical Key Graph)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            LinearPitchCanvas(
                pitchHistory = pitchHistory,
                currentHz = currentHz,
                selectedKeyIndex = selectedKeyIndex
            )
        }
    }
}

@Composable
private fun TopHeaderBar(
    currentHz: Float?,
    micLevel: Float,
    selectedKeyIndex: Int,
    onSelectKey: (Int) -> Unit,
    permissionDeniedMessage: String?
) {
    val keyNames = remember {
        arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
    var keyMenuExpanded by remember { mutableStateOf(false) }
    var settingsMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BarSlate)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Slot (fixed weight 1f so content changes never push the center dropdown)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "LIVE KEY",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (currentHz != null) {
                            frequencyToKeyName(currentHz)
                        } else {
                            "--"
                        },
                        color = TextPrimary,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (currentHz != null) {
                            frequencyToNoteName(currentHz)
                        } else {
                            ""
                        },
                        color = TraceSkyBlue,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }

            // Center Slot (fixed width so the Key Picker never flicks left or right)
            Column(
                modifier = Modifier.width(144.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SCALE / KEY",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box {
                    val selectedLabel = if (selectedKeyIndex in 0..11) {
                        "Key: ${keyNames[selectedKeyIndex]} \u25BE"
                    } else {
                        "Chromatic \u25BE"
                    }
                    Button(
                        onClick = { keyMenuExpanded = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BgDarkSlate,
                            contentColor = TraceSkyBlue
                        )
                    ) {
                        Text(
                            text = selectedLabel,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    DropdownMenu(
                        expanded = keyMenuExpanded,
                        onDismissRequest = { keyMenuExpanded = false },
                        modifier = Modifier.background(BarSlate)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Chromatic (All Keys)",
                                    color = if (selectedKeyIndex == -1) TraceSkyBlue else TextPrimary,
                                    fontWeight = if (selectedKeyIndex == -1) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                onSelectKey(-1)
                                keyMenuExpanded = false
                            }
                        )
                        for (i in keyNames.indices) {
                            val kName = keyNames[i]
                            val isSelected = selectedKeyIndex == i
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Key of $kName",
                                        color = if (isSelected) TraceSkyBlue else TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    onSelectKey(i)
                                    keyMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Right Slot (fixed weight 1f, houses Context Menu icon on top right)
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box {
                    Button(
                        onClick = { settingsMenuExpanded = true },
                        modifier = Modifier
                            .width(40.dp)
                            .height(40.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BgDarkSlate,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text(
                            text = "\u22EE",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    DropdownMenu(
                        expanded = settingsMenuExpanded,
                        onDismissRequest = { settingsMenuExpanded = false },
                        modifier = Modifier
                            .background(BarSlate)
                            .width(240.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "SETTINGS",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Microphone Input Level",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { micLevel.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = GreenAccent,
                            trackColor = BgDarkSlate
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = String.format(Locale.US, "Level: %d%%", (micLevel * 100f).roundToInt().coerceIn(0, 100)),
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        if (permissionDeniedMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = permissionDeniedMessage,
                color = AmberRef,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun LinearPitchCanvas(
    pitchHistory: List<Float?>,
    currentHz: Float?,
    selectedKeyIndex: Int
) {
    // Center MIDI note of the viewport (default 50f = D3, comfortable vocal middle)
    var centerMidi by remember { mutableFloatStateOf(50f) }
    // Number of semitones (keys) visible vertically at once (default 15 keys for generous room)
    var visibleSemitones by remember { mutableFloatStateOf(15f) }
    // Number of history points visible horizontally at once (default 0.5x speed = 2x DEFAULT_VISIBLE_POINTS)
    var visibleHistoryPoints by remember { mutableFloatStateOf(DEFAULT_VISIBLE_POINTS * 2f) }
    // Automatically pan the graph up or down when singing higher/lower
    var autoFollow by remember { mutableStateOf(true) }

    val latestHz by rememberUpdatedState(currentHz)

    // 60/120 FPS VSYNC-locked smooth camera tracking loop
    LaunchedEffect(autoFollow) {
        if (!autoFollow) return@LaunchedEffect
        var lastNanos = 0L
        var filteredTargetMidi: Float? = null

        while (isActive) {
            withFrameNanos { frameNanos ->
                val dt = if (lastNanos == 0L) {
                    1f / 60f
                } else {
                    ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                }
                lastNanos = frameNanos

                val hz = latestHz
                if (hz != null) {
                    val rawMidi = (12.0 * log2(hz.toDouble() / 440.0) + 69.0).toFloat()
                    val prevTarget = filteredTargetMidi
                    // Low-pass filter target pitch so vibrato doesn't shake the background grid
                    val smoothTarget = if (prevTarget == null) {
                        rawMidi
                    } else {
                        val targetAlpha = 1f - exp(-5.0f * dt)
                        prevTarget + (rawMidi - prevTarget) * targetAlpha
                    }
                    filteredTargetMidi = smoothTarget

                    // Continuous quadratic speed curve: gentle near center, fast when approaching top/bottom
                    val diff = smoothTarget - centerMidi
                    val normalizedDist = (abs(diff) / (visibleSemitones * 0.25f)).coerceIn(0f, 3.0f)
                    val followSpeed = 0.8f + 5.5f * normalizedDist * normalizedDist
                    val cameraAlpha = 1f - exp(-followSpeed * dt)
                    centerMidi = (centerMidi + diff * cameraAlpha).coerceIn(31f, 84f)
                }
            }
        }
    }

    val naturalLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#CBD5E1")
            textSize = 28f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    val sharpLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#64748B")
            textSize = 25f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
    }

    val octaveLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#38BDF8")
            textSize = 29f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    val liveKeyPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#38BDF8")
            textSize = 34f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    val noteNames = remember {
        arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
    val sharpIndices = remember { setOf(1, 3, 6, 8, 10) }
    // Relative major/diatonic scale intervals in semitones from the selected root key
    val scaleIntervals = remember { setOf(0, 2, 4, 5, 7, 9, 11) }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .background(BgDarkSlate)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            val canvasHeight = size.height.toFloat().coerceAtLeast(1f)

                            if (pressed.size == 1) {
                                // Single-finger drag: vertical pan across keys
                                val change = pressed[0]
                                val dy = change.position.y - change.previousPosition.y
                                if (abs(dy) > 0.5f) {
                                    val semitonesPerPx = visibleSemitones / canvasHeight
                                    centerMidi = (centerMidi + dy * semitonesPerPx).coerceIn(31f, 84f)
                                    change.consume()
                                }
                            } else if (pressed.size >= 2) {
                                // Two-finger gesture: independent horizontal pinch (line speed)
                                // and vertical pinch (key spacing) + two-finger vertical pan
                                val p1 = pressed[0]
                                val p2 = pressed[1]

                                val prevDx = abs(p1.previousPosition.x - p2.previousPosition.x)
                                val currDx = abs(p1.position.x - p2.position.x)
                                val prevDy = abs(p1.previousPosition.y - p2.previousPosition.y)
                                val currDy = abs(p1.position.y - p2.position.y)

                                val deltaX = abs(currDx - prevDx)
                                val deltaY = abs(currDy - prevDy)

                                // Horizontal pinch -> zoom time window / control speed of playing line
                                if (prevDx > 35f && currDx > 35f && deltaX >= deltaY * 0.55f) {
                                    val zoomX = (currDx / prevDx).coerceIn(0.85f, 1.18f)
                                    visibleHistoryPoints = (visibleHistoryPoints / zoomX)
                                        .coerceIn(MIN_VISIBLE_POINTS, MAX_VISIBLE_POINTS)
                                }

                                // Vertical pinch -> zoom vertical key spacing
                                if (prevDy > 35f && currDy > 35f && deltaY >= deltaX * 0.55f) {
                                    val zoomY = (currDy / prevDy).coerceIn(0.85f, 1.18f)
                                    visibleSemitones = (visibleSemitones / zoomY)
                                        .coerceIn(8f, 36f)
                                }

                                // Also allow two-finger vertical pan
                                val centerDy = ((p1.position.y + p2.position.y) -
                                    (p1.previousPosition.y + p2.previousPosition.y)) * 0.5f
                                val semitonesPerPx = visibleSemitones / canvasHeight
                                centerMidi = (centerMidi + centerDy * semitonesPerPx).coerceIn(31f, 84f)

                                p1.consume()
                                p2.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            val halfSpan = visibleSemitones / 2f
            val minMidi = centerMidi - halfSpan
            val maxMidi = centerMidi + halfSpan
            val rowHeight = height / visibleSemitones

            fun hzToMidi(hz: Float): Float {
                return (12.0 * log2(hz.toDouble() / 440.0) + 69.0).toFloat()
            }

            fun midiToY(midi: Float): Float {
                return height - ((midi - minMidi) / visibleSemitones) * height
            }

            // 1. Draw horizontal bands & grid lines only for the active scale/key bars
            val firstNote = floor(minMidi).toInt()
            val lastNote = ceil(maxMidi).toInt()

            for (midi in firstNote..lastNote) {
                val noteIndex = ((midi % 12) + 12) % 12

                // Filter bars when a specific key is selected (Chromatic = -1 shows all 12)
                if (selectedKeyIndex in 0..11) {
                    val relativeInterval = ((noteIndex - selectedKeyIndex) % 12 + 12) % 12
                    if (relativeInterval !in scaleIntervals) continue
                }

                val y = midiToY(midi.toFloat())
                val octave = (midi / 12) - 1
                val keyName = noteNames[noteIndex]
                val isSharp = noteIndex in sharpIndices
                val isRootKey = if (selectedKeyIndex in 0..11) {
                    noteIndex == selectedKeyIndex
                } else {
                    noteIndex == 0
                }

                // Subtle piano-roll row shading for sharp keys in Chromatic mode,
                // or root key highlight when a specific key is selected
                if (selectedKeyIndex == -1 && isSharp) {
                    drawRect(
                        color = Color(0xFF131D31),
                        topLeft = Offset(0f, y - rowHeight / 2f),
                        size = androidx.compose.ui.geometry.Size(width, rowHeight)
                    )
                } else if (selectedKeyIndex in 0..11 && isRootKey) {
                    drawRect(
                        color = Color(0xFF17253B),
                        topLeft = Offset(0f, y - rowHeight / 2f),
                        size = androidx.compose.ui.geometry.Size(width, rowHeight)
                    )
                }

                // Grid line at the center of each active key bar
                drawLine(
                    color = when {
                        isRootKey -> Color(0xFF475569)
                        selectedKeyIndex == -1 && isSharp -> GridLineSubtleColor
                        else -> GridLineColor
                    },
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = if (isRootKey) 2.5f else 1.5f
                )

                // Draw key label on the left edge
                if (selectedKeyIndex in 0..11 || !isSharp || rowHeight >= 26f) {
                    val label = if (isRootKey) "$keyName ($keyName$octave)" else "$keyName$octave"
                    val paint = when {
                        isRootKey -> octaveLabelPaint
                        selectedKeyIndex == -1 && isSharp -> sharpLabelPaint
                        else -> naturalLabelPaint
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        16f,
                        y - 8f,
                        paint
                    )
                }
            }

            // 2. Draw scrolling pitch history for the active horizontal zoom window
            if (pitchHistory.isNotEmpty()) {
                val visibleCount = visibleHistoryPoints.roundToInt()
                    .coerceIn(MIN_VISIBLE_POINTS.toInt(), pitchHistory.size)
                val startIndex = (pitchHistory.size - visibleCount).coerceAtLeast(0)
                val stepX = if (visibleCount > 1) width / (visibleCount - 1).toFloat() else width

                val path = Path()
                var isPenDown = false
                var prevX = 0f
                var prevY = 0f
                var prevMidi = 0f
                var lastActiveX = 0f
                var lastActiveY = 0f
                var lastActiveHz: Float? = null

                for (j in 0 until visibleCount) {
                    val hz = pitchHistory[startIndex + j]
                    if (hz != null && hz in MIN_HZ..MAX_HZ) {
                        val x = j * stepX
                        val midi = hzToMidi(hz)
                        val y = midiToY(midi)
                        if (!isPenDown) {
                            path.moveTo(x, y)
                            isPenDown = true
                        } else if (abs(midi - prevMidi) > 2.5f) {
                            // Lift and restart stroke on large note jumps so it never draws a vertical spike
                            path.lineTo(prevX, prevY)
                            path.moveTo(x, y)
                        } else {
                            val midX = (prevX + x) * 0.5f
                            val midY = (prevY + y) * 0.5f
                            path.quadraticTo(prevX, prevY, midX, midY)
                        }
                        prevX = x
                        prevY = y
                        prevMidi = midi
                        lastActiveX = x
                        lastActiveY = y
                        lastActiveHz = hz
                    } else {
                        if (isPenDown) {
                            path.lineTo(prevX, prevY)
                            isPenDown = false
                        }
                    }
                }
                if (isPenDown) {
                    path.lineTo(prevX, prevY)
                }

                drawPath(
                    path = path,
                    color = TraceSkyBlue,
                    style = Stroke(
                        width = 6f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Draw live key indicator badge at the most recent active point
                if (lastActiveHz != null) {
                    val clampedY = lastActiveY.coerceIn(18f, height - 18f)
                    drawCircle(
                        color = TraceSkyBlue,
                        radius = 9f,
                        center = Offset(lastActiveX, clampedY)
                    )
                    val liveKeyText = frequencyToKeyName(lastActiveHz)
                    val textWidth = liveKeyPaint.measureText(liveKeyText)
                    val badgeX = (lastActiveX - textWidth - 16f).coerceAtLeast(16f)
                    val badgeY = if (clampedY > 45f) clampedY - 14f else clampedY + 36f
                    drawContext.canvas.nativeCanvas.drawText(
                        liveKeyText,
                        badgeX,
                        badgeY,
                        liveKeyPaint
                    )
                }
            }
        }

        // Floating controls overlay in top-right of canvas: Speed (horizontal zoom), Auto-Follow, & Center
        val speedMultiplier = DEFAULT_VISIBLE_POINTS / visibleHistoryPoints
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    // Cycle speed presets on tap (1.0x -> 2.0x -> 0.5x -> 1.0x), or pinch horizontally for any speed
                    visibleHistoryPoints = when {
                        speedMultiplier < 0.8f -> DEFAULT_VISIBLE_POINTS // 1.0x
                        speedMultiplier < 1.5f -> DEFAULT_VISIBLE_POINTS / 2f // 2.0x
                        else -> DEFAULT_VISIBLE_POINTS * 2f // 0.5x
                    }
                },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BarSlate.copy(alpha = 0.9f),
                    contentColor = AmberRef
                )
            ) {
                Text(
                    text = String.format(Locale.US, "Speed: %.1fx", speedMultiplier),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Button(
                onClick = { autoFollow = !autoFollow },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (autoFollow) GreenAccent.copy(alpha = 0.2f) else BarSlate.copy(alpha = 0.9f),
                    contentColor = if (autoFollow) GreenAccent else TextSecondary
                )
            ) {
                Text(
                    text = if (autoFollow) "Auto-Follow: ON" else "Auto-Follow: OFF",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Button(
                onClick = {
                    val targetHz = currentHz
                    if (targetHz != null) {
                        centerMidi = (12.0 * log2(targetHz.toDouble() / 440.0) + 69.0)
                            .toFloat()
                            .coerceIn(31f, 84f)
                    } else {
                        centerMidi = 50f
                        visibleSemitones = 15f
                        visibleHistoryPoints = DEFAULT_VISIBLE_POINTS * 2f
                    }
                },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BarSlate.copy(alpha = 0.9f),
                    contentColor = TraceSkyBlue
                )
            ) {
                Text(
                    text = "Center",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun medianOfThree(a: Float, b: Float, c: Float): Float {
    return maxOf(minOf(a, b), minOf(maxOf(a, b), c))
}

/**
 * McLeod Pitch Method (MPM) / Normalized Square Difference Function (NSDF).
 * Prevents octave-down spikes on subtle tones by finding the peak of each positive lobe
 * and selecting the FIRST positive lobe whose height is >= 85% of the global maximum peak.
 */
private fun detectPitchMPM(
    buffer: FloatArray,
    prefixSq: FloatArray,
    size: Int,
    sampleRate: Int
): Float? {
    val minLag = (sampleRate / MAX_HZ).toInt()
    val maxLag = minOf((sampleRate / MIN_HZ).toInt(), size - 2)
    if (minLag >= maxLag) return null

    // Precompute prefix sums of squares in O(N) so window energy is O(1) per lag
    prefixSq[0] = 0f
    for (i in 0 until size) {
        val s = buffer[i]
        prefixSq[i + 1] = prefixSq[i] + s * s
    }
    val totalSq = prefixSq[size]

    val nsdf = FloatArray(maxLag + 2)
    val startLag = maxOf(1, minLag / 2)
    val endLag = minOf(size - 2, maxLag + 1)

    for (lag in startLag..endLag) {
        var sumProd = 0f
        val limit = size - lag
        for (i in 0 until limit) {
            sumProd += buffer[i] * buffer[i + lag]
        }
        val sumSq1 = prefixSq[limit]
        val sumSq2 = totalSq - prefixSq[lag]
        val denom = sumSq1 + sumSq2
        nsdf[lag] = if (denom > 1e-9f) (2f * sumProd) / denom else 0f
    }

    // Collect the highest peak (lag, value) inside each positive lobe after the initial negative dip
    val peakLags = IntArray(32)
    val peakVals = FloatArray(32)
    var peakCount = 0
    var globalMaxPeak = 0f

    var passedFirstZeroCrossing = false
    for (lag in startLag until minLag) {
        if (nsdf[lag] <= 0f) {
            passedFirstZeroCrossing = true
            break
        }
    }

    var inPositiveLobe = false
    var currentLobeBestLag = -1
    var currentLobeBestVal = 0f

    for (lag in minLag..maxLag) {
        val v = nsdf[lag]
        if (!passedFirstZeroCrossing) {
            if (v <= 0f) {
                passedFirstZeroCrossing = true
            }
            continue
        }

        if (v > 0f) {
            if (!inPositiveLobe) {
                inPositiveLobe = true
                currentLobeBestLag = lag
                currentLobeBestVal = v
            } else if (v > currentLobeBestVal) {
                currentLobeBestVal = v
                currentLobeBestLag = lag
            }
        } else if (inPositiveLobe) {
            // Lobe ended at negative zero-crossing -> record lobe peak if it's a valid local maximum
            if (currentLobeBestLag in minLag..maxLag && peakCount < peakLags.size) {
                peakLags[peakCount] = currentLobeBestLag
                peakVals[peakCount] = currentLobeBestVal
                if (currentLobeBestVal > globalMaxPeak) {
                    globalMaxPeak = currentLobeBestVal
                }
                peakCount++
            }
            inPositiveLobe = false
        }
    }
    // If still in a positive lobe at maxLag, only record if it already passed its local peak
    if (inPositiveLobe &&
        currentLobeBestLag in minLag until maxLag &&
        nsdf[currentLobeBestLag] >= nsdf[currentLobeBestLag + 1] &&
        peakCount < peakLags.size
    ) {
        peakLags[peakCount] = currentLobeBestLag
        peakVals[peakCount] = currentLobeBestVal
        if (currentLobeBestVal > globalMaxPeak) {
            globalMaxPeak = currentLobeBestVal
        }
        peakCount++
    }

    // Require strong periodicity clarity (>= 0.72f) to reject unvoiced breath/noise
    if (peakCount == 0 || globalMaxPeak < 0.72f) return null

    // MPM Rule: Select the FIRST positive lobe whose peak is >= 85% of globalMaxPeak.
    // This guarantees locking onto the fundamental period (shortest lag) instead of subharmonics!
    val threshold = globalMaxPeak * 0.85f
    var bestLag = -1
    for (i in 0 until peakCount) {
        if (peakVals[i] >= threshold) {
            bestLag = peakLags[i]
            break
        }
    }

    if (bestLag <= 0) return null

    // Parabolic interpolation around bestLag for sub-Hz precision
    val y1 = nsdf[bestLag - 1]
    val y2 = nsdf[bestLag]
    val y3 = nsdf[bestLag + 1]
    val denom = 2f * (2f * y2 - y1 - y3)
    val delta = if (abs(denom) > 1e-6f) {
        ((y1 - y3) / denom).coerceIn(-0.5f, 0.5f)
    } else {
        0f
    }

    val refinedLag = bestLag + delta
    val frequency = sampleRate / refinedLag
    return if (frequency in MIN_HZ..MAX_HZ) frequency else null
}

/**
 * Converts a frequency in Hz to the musical key name without octave (e.g., "C#", "D", "A").
 */
private fun frequencyToKeyName(hz: Float): String {
    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val midi = (12.0 * log2(hz / 440.0) + 69.0).roundToInt()
    val noteIndex = ((midi % 12) + 12) % 12
    return noteNames[noteIndex]
}

/**
 * Converts a frequency in Hz to the closest musical note name with octave (e.g., "C#3", "A4").
 */
private fun frequencyToNoteName(hz: Float): String {
    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val midi = (12.0 * log2(hz / 440.0) + 69.0).roundToInt()
    val noteIndex = ((midi % 12) + 12) % 12
    val octave = (midi / 12) - 1
    return "${noteNames[noteIndex]}$octave"
}
