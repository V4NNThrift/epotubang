package com.example.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ScreenshotService : Service() {

    companion object {
        const val NOTIFICATION_ID = 889
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_PROJECTION_DATA = "EXTRA_PROJECTION_DATA"
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val shutterSound = MediaActionSound()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        handlerThread = HandlerThread("ScreenshotCaptureThread")
        handlerThread?.start()
        backgroundHandler = Handler(handlerThread!!.looper)
        
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_STOP

        if (action == ACTION_START) {
            // Extract MediaProjection data first
            val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }

            if (projectionData != null && mediaProjection == null) {
                try {
                    mediaProjection = mediaProjectionManager?.getMediaProjection(Activity.RESULT_OK, projectionData)
                } catch (e: Exception) {
                    Toast.makeText(this, "Gagal menginisialisasi rekaman layar: ${e.message}", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            if (mediaProjection == null) {
                Toast.makeText(this, "Izin perekaman layar tidak ditemukan.", Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }

            // Setup foreground notification only after MediaProjection is securely established
            setupNotification()

            ScreenshotServiceState.isRunning.value = true
            showFloatingView()
        } else if (action == ACTION_STOP) {
            cleanup()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun setupNotification() {
        val channelId = "layar_melayang_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "LayarMelayang Aktif",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keberadaan tombol melayang cuplikan layar"
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("LayarMelayang Aktif")
            .setContentText("Tombol melayang siap mengambil cuplikan layar.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingView() {
        if (floatingView != null) return

        val context = this
        val sizeDp = 56f
        val sizePx = dpToPx(sizeDp).toInt()

        // Pure standard FrameLayout with a rounded GradientDrawable circle background
        val containerView = FrameLayout(context).apply {
            // Elegant glowing neon green circle outline
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#121417")) // Deep slate dark core background
                setStroke(dpToPx(2.5f).toInt(), Color.parseColor("#0DF5A3")) // Vivid Neo Mint stroke ring
            }

            // Beautiful camera icon centered perfectly
            val imageView = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_camera)
                imageTintList = ColorStateList.valueOf(Color.parseColor("#0DF5A3")) // Glowing neon green camera icon
                setPadding(dpToPx(13f).toInt(), dpToPx(13f).toInt(), dpToPx(13f).toInt(), dpToPx(13f).toInt())
            }

            addView(imageView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        floatingView = containerView

        // Floating Layout parameters
        floatingParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - sizePx - dpToPx(20f).toInt()
            y = resources.displayMetrics.heightPixels / 3
        }

        // Attach touch and drag action
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams!!.x
                    initialY = floatingParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    floatingParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                    floatingParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, floatingParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = Math.abs(event.rawX - initialTouchX)
                    val deltaY = Math.abs(event.rawY - initialTouchY)
                    // If did not drag far, count as click
                    if (deltaX < 15 && deltaY < 15) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        floatingView?.setOnClickListener {
            takeScreenshot()
        }

        windowManager?.addView(floatingView, floatingParams)
    }

    private fun takeScreenshot() {
        val viewToHide = floatingView ?: return
        
        // Hide overlay instantly to prevent it from showing on its own screenshot
        viewToHide.visibility = View.INVISIBLE
        
        // Post a small delay to make sure screen has drawn WITHOUT the overlay button
        backgroundHandler?.postDelayed({
            captureScreenProcess()
        }, 150)
    }

    private fun captureScreenProcess() {
        val currentContext = this
        val projection = mediaProjection
        if (projection == null) {
            Handler(Looper.getMainLooper()).post {
                restoreOverlayView()
                Toast.makeText(currentContext, "Gagal mengambil gambar: Izin hilang", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Dynamic dimension extraction
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection.createVirtualDisplay(
                "LayarMelayangCapture",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )

            // Setup single capture trigger on the handler
            val captured = java.util.concurrent.atomic.AtomicBoolean(false)
            imageReader!!.setOnImageAvailableListener({ reader ->
                if (captured.compareAndSet(false, true)) {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        try {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width
                            
                            // Allocate bitmap
                            val bitmapWidth = width + rowPadding / pixelStride
                            val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                            bitmap.copyPixelsFromBuffer(buffer)
                            
                            // Crop coordinates to drop alignment bytes
                            val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                            
                            // Save captured screen
                            val savedFile = saveBitmap(finalBitmap)
                            
                            // Fire feedbacks on UI Thread
                            Handler(Looper.getMainLooper()).post {
                                triggerHapticFeedback()
                                shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                                
                                ScreenshotServiceState.totalCaptured.value += 1
                                ScreenshotServiceState.lastCapturedPath.value = savedFile.absolutePath
                                
                                Toast.makeText(currentContext, "Tangkapan layar tersimpan!", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            image.close()
                            cleanupVirtualDisplay()
                            Handler(Looper.getMainLooper()).post {
                                restoreOverlayView()
                            }
                        }
                    } else {
                        captured.set(false) // Retry
                    }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            e.printStackTrace()
            cleanupVirtualDisplay()
            Handler(Looper.getMainLooper()).post {
                restoreOverlayView()
                Toast.makeText(currentContext, "Gagal meluncurkan rekaman: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveBitmap(bitmap: Bitmap): File {
        val folder = File(filesDir, "screenshots")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(folder, "LayarMelayang_$timestamp.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    private fun restoreOverlayView() {
        floatingView?.visibility = View.VISIBLE
    }

    private fun triggerHapticFeedback() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(80)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cleanupVirtualDisplay() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restoreOverlaySilently() {
        Handler(Looper.getMainLooper()).post {
            restoreOverlayView()
        }
    }

    private fun cleanup() {
        ScreenshotServiceState.isRunning.value = false
        cleanupVirtualDisplay()
        
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            floatingView = null
        }
        
        mediaProjection?.stop()
        mediaProjection = null
        
        handlerThread?.quitSafely()
        handlerThread = null
    }

    override fun onDestroy() {
        cleanup()
        shutterSound.release()
        super.onDestroy()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
