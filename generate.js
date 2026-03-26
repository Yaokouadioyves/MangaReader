const fs = require('fs');
const path = require('path');

const projectDir = 'C:\\\\Users\\\\Yves\\\\.openclaw\\\\workspace\\\\MangaReader';

function createFile(relPath, content) {
    const fullPath = path.join(projectDir, relPath);
    const dir = path.dirname(fullPath);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(fullPath, content.trim(), 'utf8');
}

createFile('build.gradle', `
plugins {
    id 'com.android.application' version '8.1.0' apply false
    id 'org.jetbrains.kotlin.android' version '1.8.0' apply false
}
`);

createFile('settings.gradle', `
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MangaReader"
include ':app'
`);

createFile('app/build.gradle', `
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}
android {
    namespace 'com.example.mangareader'
    compileSdk 34
    defaultConfig {
        applicationId "com.example.mangareader"
        minSdk 30 // For takeScreenshot() in AccessibilityService
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = '1.8'
    }
}
dependencies {
    implementation 'androidx.core:core-ktx:1.10.1'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.9.0'
    // ML Kit OCR
    implementation 'com.google.mlkit:text-recognition:16.0.0'
}
`);

createFile('app/src/main/AndroidManifest.xml', `
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat.Light.DarkActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name=".MangaReaderService"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
    </application>
</manifest>
`);

createFile('app/src/main/res/values/strings.xml', `
<resources>
    <string name="app_name">MangaReader</string>
</resources>
`);

createFile('app/src/main/res/xml/accessibility_service_config.xml', `
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeViewScrolled"
    android:accessibilityFeedbackType="feedbackSpoken"
    android:accessibilityFlags="flagDefault"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:description="@string/app_name" />
`);

createFile('app/src/main/java/com/example/mangareader/MainActivity.kt', `
package com.example.mangareader
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val button = Button(this)
        button.text = "Activer le service d'accessibilité"
        button.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Trouvez MangaReader et activez-le", Toast.LENGTH_LONG).show()
        }
        setContentView(button)
    }
}
`);

createFile('app/src/main/java/com/example/mangareader/MangaReaderService.kt', `
package com.example.mangareader
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class MangaReaderService : AccessibilityService(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastScrollTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
        Log.d("MangaReader", "Service Connecté")
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
            takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshotResult.hardwareBuffer, screenshotResult.colorSpace)
                    if (bitmap != null) processImage(bitmap)
                }
                override fun onFailure(errorCode: Int) {}
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
        tts?.shutdown()
        super.onDestroy()
    }
}
`);

createFile('.github/workflows/build_apk.yml', `
name: Build Android APK
on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v3
    - name: set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
        cache: gradle
    - name: Make gradlew executable
      run: chmod +x ./gradlew || echo "No gradlew found"
    - name: Generate Gradle wrapper if missing
      run: gradle wrapper || echo "Gradle wrapper failed"
    - name: Build with Gradle
      run: ./gradlew assembleDebug
    - name: Upload APK
      uses: actions/upload-artifact@v3
      with:
        name: MangaReader-APK
        path: app/build/outputs/apk/debug/app-debug.apk
`);

console.log("Project generated successfully.");
