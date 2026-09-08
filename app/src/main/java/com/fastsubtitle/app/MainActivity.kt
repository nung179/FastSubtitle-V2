package com.fastsubtitle.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var apiKey: EditText
    private lateinit var sourceSpinner: Spinner
    private lateinit var colorSpinner: Spinner
    private lateinit var sizeSpinner: Spinner
    private lateinit var opacitySpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var statusText: TextView

    private val prefs by lazy {
        getSharedPreferences("fast_subtitle", MODE_PRIVATE)
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                saveSettings()

                val serviceIntent = Intent(this, SubtitleService::class.java).apply {
                    putExtra(SubtitleService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(SubtitleService.EXTRA_RESULT_DATA, result.data)
                    putExtra(SubtitleService.EXTRA_API_KEY, apiKey.text.toString().trim())
                    putExtra(SubtitleService.EXTRA_LANGUAGE, sourceCode())
                    putExtra(
                        SubtitleService.EXTRA_TEXT_COLOR,
                        if (colorSpinner.selectedItemPosition == 0) "yellow" else "white"
                    )
                    putExtra(SubtitleService.EXTRA_TEXT_SIZE, selectedTextSize())
                    putExtra(SubtitleService.EXTRA_BG_ALPHA, selectedBgAlpha())
                }

                ContextCompat.startForegroundService(this, serviceIntent)
                startButton.text = "BERHENTI"
                statusText.text = "Aktif • buka video/audio yang ingin diterjemahkan"
            } else {
                statusText.text = "Izin menangkap audio dibatalkan."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val bg = Color.rgb(11, 11, 13)
        val card = Color.rgb(28, 28, 32)
        val yellow = Color.rgb(255, 214, 0)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(28))
            setBackgroundColor(bg)
        }

        root.addView(TextView(this).apply {
            text = "Fast Subtitle"
            textSize = 31f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Subtitle cepat • hasil Bahasa Indonesia"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(22))
        })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(18).toFloat()
            }
        }

        panel.addView(TextView(this).apply {
            text = "API Deepgram"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, dp(6))
        })

        apiKey = EditText(this).apply {
            hint = "Tempel API Key / Secret di sini"
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            isSingleLine = true
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD

            setPadding(dp(14), 0, dp(14), 0)

            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 18, 21))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(60, 60, 66))
            }

            setText(prefs.getString("api_key", "") ?: "")
        }

        panel.addView(
            apiKey,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            )
        )

        sourceSpinner = addChoice(
            panel,
            "Bahasa suara",
            arrayOf("Mandarin", "Inggris", "Jepang", "Korea")
        )

        addFixed(panel, "Terjemahan", "Bahasa Indonesia")

        colorSpinner = addChoice(
            panel,
            "Warna subtitle",
            arrayOf("Kuning", "Putih")
        )

        sizeSpinner = addChoice(
            panel,
            "Ukuran teks",
            arrayOf("Kecil", "Sedang", "Besar")
        )

        opacitySpinner = addChoice(
            panel,
            "Kotak subtitle",
            arrayOf("Transparan 55%", "Transparan 70%", "Transparan 85%")
        )

        sourceSpinner.setSelection(prefs.getInt("lang", 0))
        colorSpinner.setSelection(prefs.getInt("color", 0))
        sizeSpinner.setSelection(prefs.getInt("size", 1))
        opacitySpinner.setSelection(prefs.getInt("opacity", 1))

        root.addView(
            panel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        startButton = Button(this).apply {
            text = "MULAI"
            textSize = 17f
            setTextColor(Color.BLACK)

            background = GradientDrawable().apply {
                setColor(yellow)
                cornerRadius = dp(16).toFloat()
            }

            setOnClickListener {
                if (text == "BERHENTI") stopSubtitle() else startSubtitle()
            }
        }

        root.addView(
            startButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
            ).apply {
                topMargin = dp(20)
            }
        )

        statusText = TextView(this).apply {
            text = if (apiKey.text.toString().trim().isEmpty()) {
                "Masukkan API Key Deepgram."
            } else {
                "Siap • Online"
            }
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(0, dp(15), 0, 0)
        }

        root.addView(statusText)
        scroll.addView(root)

        return scroll
    }

    private fun addChoice(
        parent: LinearLayout,
        title: String,
        items: Array<String>
    ): Spinner {

        parent.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(12), 0, dp(4))
        })

        return Spinner(this).also { spinner ->
            spinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                items
            )

            parent.addView(
                spinner,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48)
                )
            )
        }
    }

    private fun addFixed(
        parent: LinearLayout,
        title: String,
        value: String
    ) {

        parent.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(12), 0, dp(4))
        })

        parent.addView(
            TextView(this).apply {
                text = value
                textSize = 17f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )
    }

    private fun startSubtitle() {
        val key = apiKey.text.toString().trim()

        if (key.isEmpty()) {
            Toast.makeText(
                this,
                "Masukkan API Key Deepgram dulu.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
            return
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                101
            )
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Aktifkan izin tampil di atas aplikasi lain.",
                Toast.LENGTH_LONG
            ).show()

            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        saveSettings()

        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        projectionLauncher.launch(
            manager.createScreenCaptureIntent()
        )
    }

    private fun stopSubtitle() {
        startService(
            Intent(this, SubtitleService::class.java).apply {
                action = SubtitleService.ACTION_STOP
            }
        )

        startButton.text = "MULAI"
        statusText.text = "Dihentikan."
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode == 100 &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startSubtitle()
        }
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("api_key", apiKey.text.toString().trim())
            .putInt("lang", sourceSpinner.selectedItemPosition)
            .putInt("color", colorSpinner.selectedItemPosition)
            .putInt("size", sizeSpinner.selectedItemPosition)
            .putInt("opacity", opacitySpinner.selectedItemPosition)
            .apply()
    }

    private fun sourceCode(): String {
        return when (sourceSpinner.selectedItemPosition) {
            1 -> "en"
            2 -> "ja"
            3 -> "ko"
            else -> "zh"
        }
    }

    private fun selectedTextSize(): Float {
        return when (sizeSpinner.selectedItemPosition) {
            0 -> 20f
            2 -> 30f
            else -> 25f
        }
    }

    private fun selectedBgAlpha(): Int {
        return when (opacitySpinner.selectedItemPosition) {
            0 -> 140
            2 -> 217
            else -> 178
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
