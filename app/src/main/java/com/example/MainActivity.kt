package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.service.ScreenshotService
import com.example.service.ScreenshotServiceState
import com.example.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    // Permission launcher for dynamic notifications (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            triggerScreenCapturePermission()
        } else {
            Toast.makeText(this, "Izin notifikasi dibutuhkan agar tombol melayang tetap aktif.", Toast.LENGTH_LONG).show()
            triggerScreenCapturePermission()
        }
    }

    // Media projection screen capture permissions flow (each time started)
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
                action = ScreenshotService.ACTION_START
                putExtra(ScreenshotService.EXTRA_PROJECTION_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Jendela melayang diaktifkan!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Izin rekam layar ditolak atau dibatalkan.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold"),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    DashboardScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        onToggleService = { active -> handleServiceToggle(active) },
                        onRequestOverlay = { requestOverlayPermission() }
                    )
                }
            }
        }
    }

    private fun handleServiceToggle(isStarting: Boolean) {
        if (isStarting) {
            // Step 1: Check standard overlay draw permission limit
            if (!hasOverlayPermission()) {
                Toast.makeText(this, "Silakan aktifkan izin 'Tampilkan di atas aplikasi lain' terlebih dahulu.", Toast.LENGTH_LONG).show()
                requestOverlayPermission()
                return
            }

            // Step 2: Check notification constraints on Tiramisu+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasNotificationPerm = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!hasNotificationPerm) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
            }

            // Step 3: Trigger real media projection query
            triggerScreenCapturePermission()
        } else {
            // Stop background service
            val stopIntent = Intent(this, ScreenshotService::class.java).apply {
                action = ScreenshotService.ACTION_STOP
            }
            startService(stopIntent)
            Toast.makeText(this, "Jendela melayang dimatikan.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerScreenCapturePermission() {
        val intent = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onToggleService: (Boolean) -> Unit,
    onRequestOverlay: () -> Unit
) {
    val context = LocalContext.current
    val isRunning by ScreenshotServiceState.isRunning.collectAsState()
    val totalCaptured by ScreenshotServiceState.totalCaptured.collectAsState()
    
    // UI Local state files list
    var filesList by remember { mutableStateOf(emptyList<File>()) }
    var fileToPreview by remember { mutableStateOf<File?>(null) }
    var showHowToDialog by remember { mutableStateOf(false) }

    // State for interactive Quick Settings from Bold Typography design
    var captureMode by remember { mutableStateOf("WINDOW") }
    var captureDelay by remember { mutableStateOf("3 SEC") }

    // Read files safely
    fun readSavedFiles() {
        val folder = File(context.filesDir, "screenshots")
        if (folder.exists()) {
            filesList = folder.listFiles { file -> file.isFile && file.name.endsWith(".png") }
                ?.sortedByDescending { it.lastModified() }
                ?.toList() ?: emptyList()
        }
    }

    // Refresh layout each time capture count updates from background or on launch
    LaunchedEffect(totalCaptured) {
        readSavedFiles()
    }

    // Display width/height safely
    val metrics = context.resources.displayMetrics
    val widthPx = metrics.widthPixels
    val heightPx = metrics.heightPixels

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .background(DesignBg)
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 32.dp)
    ) {
        // App Header Section
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "SHOTS",
                    color = DesignPrimaryPurple,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.5).sp
                )
                Text(
                    text = "LAYAR MELAYANG",
                    color = DesignTextDark.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }
            
            // Simulates the user avatar circle from the Bold Typography theme with initial letters "LM"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(
                    onClick = { showHowToDialog = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(DesignSecondaryPurple, CircleShape)
                        .border(2.dp, DesignPrimaryPurple, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                        contentDescription = "Panduan Penggunaan",
                        tint = DesignPrimaryPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(DesignSecondaryPurple)
                        .clickable { showHowToDialog = true }
                        .border(2.dp, DesignPrimaryPurple, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "LM",
                        color = DesignTextDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Main Viewfinder container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // High Contrast Selection Viewfinder Box
            ViewfinderBox(
                widthPx = widthPx,
                heightPx = heightPx,
                onManualCapture = {
                    if (isRunning) {
                        Toast.makeText(context, "Memicu tangkapan layar dari panel...", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Aktifkan STATUS LAYANAN terlebih dahulu!", Toast.LENGTH_SHORT).show()
                        onToggleService(true)
                    }
                }
            )

            // Status Card Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, DesignBorder, RoundedCornerShape(24.dp)),
                colors = CardDefaults.cardColors(containerColor = DesignWhite),
                shape = RoundedCornerShape(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Text(
                            text = "STATUS LAYANAN",
                            color = DesignTextDark.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isRunning) DesignPrimaryPurple else Color(0xFFFF5252))
                                    .border(1.dp, DesignTextDark, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRunning) "Jendela Melayang Aktif" else "Layanan Nonaktif",
                                color = DesignTextDark,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Switch(
                        checked = isRunning,
                        onCheckedChange = { onToggleService(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DesignWhite,
                            checkedTrackColor = DesignPrimaryPurple,
                            uncheckedThumbColor = DesignTextDark.copy(alpha = 0.4f),
                            uncheckedTrackColor = DesignSecondaryPurple
                        ),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .testTag("service_toggle")
                    )
                }
            }

            // Quick Settings grid from the Bold Typography theme html
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Mode selector
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            captureMode = if (captureMode == "WINDOW") "FULLSCREEN" else "WINDOW"
                            Toast.makeText(context, "Mode diubah ke: $captureMode", Toast.LENGTH_SHORT).show()
                        }
                        .border(2.dp, DesignPrimaryPurple, RoundedCornerShape(28.dp)),
                    colors = CardDefaults.cardColors(containerColor = DesignSecondaryPurple),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text(
                            text = "MODE",
                            color = DesignTextDark.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = captureMode,
                            color = DesignTextDark,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                // Delay selector
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            captureDelay = when (captureDelay) {
                                "NONE" -> "3 SEC"
                                "3 SEC" -> "5 SEC"
                                else -> "NONE"
                            }
                            Toast.makeText(context, "Timer capture diatur: $captureDelay", Toast.LENGTH_SHORT).show()
                        }
                        .border(2.dp, DesignBorder, RoundedCornerShape(28.dp)),
                    colors = CardDefaults.cardColors(containerColor = DesignWhite),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text(
                            text = "DELAY",
                            color = DesignTextDark.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = captureDelay,
                            color = DesignTextDark,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            // Overlay permission warning block
            if (!hasOverlayPermission(context)) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, DesignPrimaryPurple, RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = DesignSurfaceCard),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = "Pemberitahuan",
                                tint = DesignPrimaryPurple,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Izin Menggambar Di Atas Aplikasi",
                                color = DesignTextDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tombol instan melayang membutuhkan izin penampilan agar dapat digunakan saat Anda membuka game atau aplikasi lain.",
                            color = DesignTextDark.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onRequestOverlay,
                            colors = ButtonDefaults.buttonColors(containerColor = DesignPrimaryPurple),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                            modifier = Modifier
                                .align(Alignment.End)
                                .testTag("request_overlay_button")
                        ) {
                            Text(
                                text = "AKTIFKAN IZIN",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Section Title: Gallery Tangkapan Layar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = "Histori",
                        tint = DesignPrimaryPurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "HISTORI TANGKAPAN",
                        color = DesignTextDark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
                
                Text(
                    text = "${filesList.size} BERKAS",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .background(DesignPrimaryPurple, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            // High Typography Screenshots list/grid representation
            if (filesList.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .border(2.dp, DesignBorder, RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = DesignWhite),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Text(
                                text = "BELUM ADA CUPLIKAN",
                                color = DesignTextDark.copy(alpha = 0.4f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Nyalakan layanan melayang dan tekan tombol capture di atas untuk mencobanya!",
                                color = DesignTextDark.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            } else {
                // To keep performance high inside a scrollable layout, 
                // we chunk the screenshots into pairs of rows manually so that they combine perfectly with verticalScroll
                val chunkedList = filesList.chunked(2)
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("screenshots_grid"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    chunkedList.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowItems.forEach { file ->
                                Box(modifier = Modifier.weight(1f)) {
                                    ScreenshotCard(
                                        file = file,
                                        onClick = { fileToPreview = file },
                                        onShare = { shareScreenshot(context, file) },
                                        onDelete = {
                                            deleteScreenshot(file)
                                            readSavedFiles()
                                        }
                                    )
                                }
                            }
                            // Add a placeholder spacer for asymmetric odd rows
                            if (rowItems.size < 2) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Navigation & Embedded FAB to mimic the original HTML theme design precisely
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, DesignBorder, RoundedCornerShape(32.dp)),
                colors = CardDefaults.cardColors(containerColor = DesignWhite),
                shape = RoundedCornerShape(32.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // History Indicator
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable {
                                Toast.makeText(context, "Histori aktif: ${filesList.size} cuplikan layar", Toast.LENGTH_SHORT).show()
                            }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderZip,
                            contentDescription = "Histori",
                            tint = DesignTextDark,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "HISTORY",
                            color = DesignTextDark,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }

                    // Main Action Capture button (FAB lookalike from HTML layout)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.offset(y = (-10).dp)
                    ) {
                        Card(
                            modifier = Modifier
                                .size(76.dp)
                                .clickable {
                                    if (isRunning) {
                                        Toast.makeText(context, "Membuka tangkapan lewat jendela melayang!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Harap aktifkan STATUS LAYANAN terlebih dahulu.", Toast.LENGTH_LONG).show()
                                        onToggleService(true)
                                    }
                                }
                                .border(3.dp, DesignBorder, RoundedCornerShape(26.dp)),
                            colors = CardDefaults.cardColors(containerColor = DesignPrimaryPurple),
                            shape = RoundedCornerShape(26.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                // Nested double circle ring
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .border(4.dp, Color.White.copy(alpha = 0.45f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(DesignWhite)
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "CAPTURE",
                            color = DesignPrimaryPurple,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }

                    // Simulated Editor / Guide Indicator
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { showHowToDialog = true }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = "Guide",
                            tint = DesignPrimaryPurple,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "GUIDE",
                            color = DesignPrimaryPurple,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }

    // Modal Previews & Info Dialogs
    fileToPreview?.let { file ->
        ScreenshotPreviewDialog(
            file = file,
            onClose = { fileToPreview = null },
            onShare = { shareScreenshot(context, file) },
            onDelete = {
                deleteScreenshot(file)
                readSavedFiles()
                fileToPreview = null
            }
        )
    }

    if (showHowToDialog) {
        SimpleGuideDialog(
            onDismiss = { showHowToDialog = false }
        )
    }
}

@Composable
fun ViewfinderBox(
    modifier: Modifier = Modifier,
    widthPx: Int,
    heightPx: Int,
    onManualCapture: () -> Unit
) {
    // We can animate the scanner bar moving up and down infinitely!
    val infiniteTransition = rememberInfiniteTransition(label = "scanner")
    val scannerOffset by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(DesignSurfaceCard)
            .border(4.dp, DesignPrimaryPurple, RoundedCornerShape(32.dp))
            .padding(16.dp)
    ) {
        // Laser scanning line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .fillMaxHeight(scannerOffset)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.5.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                DesignPrimaryPurple.copy(alpha = 0.15f),
                                DesignPrimaryPurple,
                                DesignPrimaryPurple.copy(alpha = 0.15f)
                            )
                        )
                    )
            )
        }

        // Overlay labels
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(DesignPrimaryPurple, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "AUTO-DETECT",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }
            Text(
                text = "$widthPx × $heightPx",
                color = DesignPrimaryPurple,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )
        }

        // Corner brackets simulating viewfinder focus ticks
        // Top right L-bracket
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(36.dp)
                .drawBehind {
                    drawLine(
                        color = DesignPrimaryPurple,
                        start = androidx.compose.ui.geometry.Offset(size.width - 24f, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        strokeWidth = 12f
                    )
                    drawLine(
                        color = DesignPrimaryPurple,
                        start = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width, 24f),
                        strokeWidth = 12f
                    )
                }
        )

        // Bottom left L-bracket
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(36.dp)
                .drawBehind {
                    drawLine(
                        color = DesignPrimaryPurple,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height),
                        end = androidx.compose.ui.geometry.Offset(24f, size.height),
                        strokeWidth = 12f
                    )
                    drawLine(
                        color = DesignPrimaryPurple,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height - 24f),
                        end = androidx.compose.ui.geometry.Offset(0f, size.height),
                        strokeWidth = 12f
                    )
                }
        )

        // Mock Interactive Window in Viewfinder (drawn exactly like HTML design)
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .width(220.dp)
                .height(118.dp)
                .clickable { onManualCapture() },
            colors = CardDefaults.cardColors(containerColor = DesignWhite),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, DesignBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            ) {
                // Mock title bar with macOS color dots
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFF5F56)))
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFFBD2E)))
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF27C93F)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "overlay_window.exe",
                        color = DesignTextDark.copy(alpha = 0.5f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                
                Box(modifier = Modifier.height(1.dp).fillMaxWidth().background(DesignBorder.copy(alpha = 0.5f)))
                Spacer(modifier = Modifier.height(8.dp))
                // Simulated loading skeleton strings
                Box(modifier = Modifier.height(8.dp).fillMaxWidth().background(DesignSurfaceCard, RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.height(6.dp))
                Box(modifier = Modifier.height(8.dp).fillMaxWidth(0.85f).background(DesignSurfaceCard, RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.height(6.dp))
                Box(modifier = Modifier.height(8.dp).fillMaxWidth(0.6f).background(DesignSurfaceCard, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
fun ScreenshotCard(
    file: File,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(file) {
        try {
            val date = Date(file.lastModified())
            SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            "..."
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(2.dp, DesignBorder, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = DesignWhite),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
                    .background(DesignTextDark)
            ) {
                AsyncImage(
                    model = file,
                    contentDescription = "Thumbnail cuplikan",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                
                // Share action icon layered clean
                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(30.dp)
                        .background(DesignWhite.copy(alpha = 0.9f), CircleShape)
                        .align(Alignment.TopEnd)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Bagikan",
                        tint = DesignPrimaryPurple,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = file.name,
                    color = DesignTextDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateStr,
                        color = DesignTextDark.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Hapus",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotPreviewDialog(
    file: File,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF20C0E12)), // High contrast glass backdrop
            color = Color.Transparent
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // Main High-Res Image
                AsyncImage(
                    model = file,
                    contentDescription = "Cuplikan layar penuh",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 110.dp, top = 70.dp)
                        .align(Alignment.Center)
                )

                // Close Header button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .padding(24.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        .border(1.5.dp, Color.White, CircleShape)
                        .align(Alignment.TopStart)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Tutup",
                        tint = Color.White
                    )
                }

                // Title label info
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 28.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .border(1.5.dp, DesignPrimaryPurple, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = file.name,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Action Footer bottom bar with Bold Design system borders
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .border(3.dp, DesignPrimaryPurple, RoundedCornerShape(24.dp))
                        .align(Alignment.BottomCenter),
                    colors = CardDefaults.cardColors(containerColor = DesignBg),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onShare,
                            colors = ButtonDefaults.buttonColors(containerColor = DesignPrimaryPurple),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "Bagikan",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "BAGIKAN",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Button(
                            onClick = onDelete,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "Hapus",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "HAPUS",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SimpleGuideDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "PANDUAN SHOTS",
                color = DesignTextDark,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                letterSpacing = 1.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.padding(top = 8.dp)
            ) {
                GuideStep(num = "1", text = "Aktifkan izin Menggambar di Atas Aplikasi.")
                Spacer(modifier = Modifier.height(12.dp))
                GuideStep(num = "2", text = "Nyalakan Status Layanan di tombol utama.")
                Spacer(modifier = Modifier.height(12.dp))
                GuideStep(num = "3", text = "Seret & letakkan bulatan melayang ke sudut layar mana pun.")
                Spacer(modifier = Modifier.height(12.dp))
                GuideStep(num = "4", text = "Ketuk tombol bulat hijau tersebut di luar untuk instan screenshot!")
                Spacer(modifier = Modifier.height(12.dp))
                GuideStep(num = "5", text = "Lihat & bagikan hasil tangkapan Anda secara nyaman di bawah.")
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = DesignPrimaryPurple),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "MENGERTI",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
            }
        },
        containerColor = DesignWhite,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.border(3.dp, DesignPrimaryPurple, RoundedCornerShape(28.dp))
    )
}

@Composable
fun GuideStep(num: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(DesignSecondaryPurple)
                .border(1.5.dp, DesignPrimaryPurple, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = num,
                color = DesignPrimaryPurple,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            color = DesignTextDark.copy(alpha = 0.75f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp
        )
    }
}

private fun hasOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

private fun shareScreenshot(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Bagikan Cuplikan Layar"))
    } catch (e: Exception) {
        Toast.makeText(context, "Gagal membagikan berkas: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun deleteScreenshot(file: File): Boolean {
    return try {
        file.delete()
    } catch (e: Exception) {
        false
    }
}
