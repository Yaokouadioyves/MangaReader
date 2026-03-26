package com.example.mangareader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
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

class MangaReaderService : AccessibilityService(), TextToSpeech.OnInitListener, SharedPreferences.OnSharedPreferenceChangeListener {
    private var tts: TextToSpeech? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastScrollTime = 0L

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var readingZone: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    
    private lateinit var sharedPref: SharedPreferences
    private val PREF_NAME = "mangareader_prefs"
    private val KEY_COLOR = "rect_color"
    private val KEY_ALPHA = "rect_alpha"
    private val KEY_OCR_ENABLED = "ocr_enabled"

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
        sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sharedPref.registerOnSharedPreferenceChangeListener(this)
        Log.d("MangaReader", "Service Connecté")
        setupOverlay()
        updateOverlayVisibility()
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
        val closeHandle = overlayView?.findViewById<View>(R.id.close_handle)
        
        applyColorAndAlpha()

        closeHandle?.setOnClickListener {
            sharedPref.edit().putBoolean(KEY_OCR_ENABLED, false).apply()
            updateOverlayVisibility()
        }

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

                        windowManager?.updateViewLayout(overlayView, layoutParams)
                        return true
                    }
                }
                return false
            }
        })
    }
    
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == KEY_COLOR || key == KEY_ALPHA) {
            applyColorAndAlpha()
        } else if (key == KEY_OCR_ENABLED) {
            updateOverlayVisibility()
        }
    }
    
    private fun updateOverlayVisibility() {
        val isEnabled = sharedPref.getBoolean(KEY_OCR_ENABLED, true)
        overlayView?.visibility = if (isEnabled) View.VISIBLE else View.GONE
    }
    
    private fun applyColorAndAlpha() {
        val color = sharedPref.getInt(KEY_COLOR, Color.RED)
        val alpha = sharedPref.getInt(KEY_ALPHA, 180)
        val argb = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        readingZone?.setBackgroundColor(argb)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!sharedPref.getBoolean(KEY_OCR_ENABLED, true)) return
        
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScrollTime > 2000) { 
                lastScrollTime = currentTime
                captureAndRead()
            }
        }
    }

    private fun captureAndRead() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overlayView?.visibility = View.INVISIBLE
            
            takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    if (sharedPref.getBoolean(KEY_OCR_ENABLED, true)) {
                        overlayView?.visibility = View.VISIBLE
                    }
                    val fullBitmap = Bitmap.wrapHardwareBuffer(screenshotResult.hardwareBuffer, screenshotResult.colorSpace)
                    if (fullBitmap != null) {
                        try {
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
                            processImage(fullBitmap)
                        }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    if (sharedPref.getBoolean(KEY_OCR_ENABLED, true)) {
                        overlayView?.visibility = View.VISIBLE
                    }
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
        if (::sharedPref.isInitialized) {
            sharedPref.unregisterOnSharedPreferenceChangeListener(this)
        }
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
        tts?.shutdown()
        super.onDestroy()
    }
}