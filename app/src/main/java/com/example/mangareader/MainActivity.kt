package com.example.mangareader

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPref: SharedPreferences
    private val PREF_NAME = "mangareader_prefs"
    private val KEY_COLOR = "rect_color"
    private val KEY_ALPHA = "rect_alpha"
    private val KEY_OCR_ENABLED = "ocr_enabled"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val btnStart = findViewById<Button>(R.id.btnStartService)
        val btnStop = findViewById<Button>(R.id.btnStopService)
        val seekBar = findViewById<SeekBar>(R.id.seekBarAlpha)
        val tvAlpha = findViewById<TextView>(R.id.tvAlphaValue)
        val btnColor = findViewById<Button>(R.id.btnChangeColor)

        // Load saved preferences
        val savedColor = sharedPref.getInt(KEY_COLOR, Color.RED)
        val savedAlpha = sharedPref.getInt(KEY_ALPHA, 180)
        val savedOcrEnabled = sharedPref.getBoolean(KEY_OCR_ENABLED, true)
        seekBar.progress = savedAlpha
        tvAlpha.text = "Transparence: $savedAlpha/255"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvAlpha.text = "Transparence: $progress/255"
                sharedPref.edit().putInt(KEY_ALPHA, progress).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnStart.setOnClickListener {
            val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val isEnabled = enabledServices?.contains(packageName) == true

            if (!isEnabled) {
                Toast.makeText(this, "Veuillez d'abord activer l'accessibilité pour MangaReader", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                sharedPref.edit().putBoolean(KEY_OCR_ENABLED, true).apply()
                Toast.makeText(this, "Zone de lecture affichée", Toast.LENGTH_SHORT).show()
            }
        }

        btnStop.setOnClickListener {
            sharedPref.edit().putBoolean(KEY_OCR_ENABLED, false).apply()
            Toast.makeText(this, "Zone de lecture masquée", Toast.LENGTH_SHORT).show()
        }

        btnColor.setOnClickListener {
            val newColor = if (sharedPref.getInt(KEY_COLOR, Color.RED) == Color.RED) Color.BLUE else Color.RED
            sharedPref.edit().putInt(KEY_COLOR, newColor).apply()
            btnColor.text = if (newColor == Color.RED) "Changer la couleur en bleu" else "Changer la couleur en rouge"
            Toast.makeText(this, "Couleur mise à jour", Toast.LENGTH_SHORT).show()
        }

        // Initialiser le texte du bouton couleur
        btnColor.text = if (sharedPref.getInt(KEY_COLOR, Color.RED) == Color.RED) "Changer la couleur en bleu" else "Changer la couleur en rouge"
    }
}