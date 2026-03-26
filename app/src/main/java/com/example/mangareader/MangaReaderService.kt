package com.example.mangareader
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import kotlin.math.max

class MangaReaderService : AccessibilityService(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastScrollTime = 0L

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var readingZone: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
        Log.d("MangaReader", "Service Connecté")
        setupOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_reading_zone, null)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        layoutParams?.gravity = Gravity.TOP or Gravity.START
        layoutParams?.x = 100
        layoutParams?.y = 200

        windowManager?.addView(overlayView, layoutParams)

        readingZone = overlayView?.findViewById(R.id.reading_zone)
        val resizeHandle = overlayView?.findViewById<View>(R.id.resize_handle)

        // Déplacement
        readingZone?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams?.x ?: 0
                        initialY = layoutParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(overlayView, layoutParams)
                        return true
                    }
                }
                return false
            }
        })

        // Redimensionnement
        resizeHandle?.setOnTouchListener(object : View.OnTouchListener {
            private var initialWidth = 0
            private var initialHeight = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = readingZone?.layoutParams?.width ?: 0
                        initialHeight = readingZone?.layoutParams?.height ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val newWidth = max(200, initialWidth + (event.rawX - initialTouchX).toInt())
                        val newHeight = max(150, initialHeight + (event.rawY - initialTouchY).toInt())
                        
                        val params = readingZone?.layoutParams
                        params?.width = newWidth
                        params?.height = newHeight
                        readingZone?.layoutParams = params

                        // Assurer que le overlay enveloppe la vue redimensionnée
                        windowManager?.updateViewLayout(overlayView, layoutParams)
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScrollTime > 2000) { // 2 seconds debounce
                lastScrollTime = currentTime
                captureAndRead()
            }
        }
    }

    private fun captureAndRead() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Cacher temporairement le rectangle rouge pour la capture
            overlayView?.visibility = View.INVISIBLE
            
            takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    overlayView?.visibility = View.VISIBLE
                    val fullBitmap = Bitmap.wrapHardwareBuffer(screenshotResult.hardwareBuffer, screenshotResult.colorSpace)
                    if (fullBitmap != null) {
                        try {
                            // Extraire la zone correspondante au rectangle (x, y, largeur, hauteur)
                            val x = layoutParams?.x ?: 0
                            val y = layoutParams?.y ?: 0
                            val w = readingZone?.width ?: fullBitmap.width
                            val h = readingZone?.height ?: fullBitmap.height
                            
                            val cropX = max(0, x)
                            val cropY = max(0, y)
                            val cropW = if (cropX + w > fullBitmap.width) fullBitmap.width - cropX else w
                            val cropH = if (cropY + h > fullBitmap.height) fullBitmap.height - cropY else h
                            
                            if (cropW > 0 && cropH > 0) {
                                val croppedBitmap = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
                                processImage(croppedBitmap)
                            }
                        } catch (e: Exception) {
                            Log.e("MangaReader", "Erreur de crop : ${e.message}")
                            processImage(fullBitmap) // Fallback sur toute l'image
                        }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    overlayView?.visibility = View.VISIBLE
                }
            })
        }
    }

    private fun processImage(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (visionText.text.isNotBlank()) {
                    tts?.speak(visionText.text, TextToSpeech.QUEUE_FLUSH, null, "manga_reader")
                }
            }
    }

    override fun onInterrupt() {}
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.FRENCH
    }
    
    override fun onDestroy() {
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
        tts?.shutdown()
        super.onDestroy()
    }
}