package com.fastsubtitle.app

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class SubtitleService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_TEXT_COLOR = "text_color"
        const val EXTRA_TEXT_SIZE = "text_size"
        const val EXTRA_BG_ALPHA = "bg_alpha"

        const val ACTION_STOP = "stop"

        private const val CHANNEL_ID = "fast_subtitle"
        private const val NOTIFICATION_ID = 179
    }

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var webSocket: WebSocket? = null
    private var translator: Translator? = null

    private var running = false

    private lateinit var windowManager: WindowManager
    private var overlayText: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var apiKey = ""
    private var language = "zh"
    private var textColor = "yellow"
    private var textSizeSp = 25f
    private var backgroundAlpha = 178

    private var lastTranslateTime = 0L
    private val translationSequence = AtomicLong(0)

    private val preferences by lazy {
        getSharedPreferences("fast_subtitle", MODE_PRIVATE)
    }

    private val httpClient =
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) {
            return START_STICKY
        }

        apiKey = intent
            ?.getStringExtra(EXTRA_API_KEY)
            ?.trim()
            .orEmpty()

        language =
            intent?.getStringExtra(EXTRA_LANGUAGE) ?: "zh"

        textColor =
            intent?.getStringExtra(EXTRA_TEXT_COLOR) ?: "yellow"

        textSizeSp =
            intent?.getFloatExtra(EXTRA_TEXT_SIZE, 25f) ?: 25f

        backgroundAlpha =
            intent?.getIntExtra(EXTRA_BG_ALPHA, 178) ?: 178

        startForegroundServiceNotification()

        val resultCode =
            intent?.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
            ) ?: Activity.RESULT_CANCELED

        @Suppress("DEPRECATION")
        val resultData =
            intent?.getParcelableExtra<Intent>(
                EXTRA_RESULT_DATA
            )

        if (
            resultCode != Activity.RESULT_OK ||
            resultData == null
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        showOverlay()
        prepareTranslator()

        val projectionManager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        projection =
            projectionManager.getMediaProjection(
                resultCode,
                resultData
            )

        projection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    shutdown()
                    stopSelf()
                }
            },
            null
        )

        connectDeepgram()

        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setContentTitle("Fast Subtitle")
                .setContentText("Subtitle sedang aktif")
                .setOngoing(true)
                .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val notificationManager =
                getSystemService(
                    NOTIFICATION_SERVICE
                ) as NotificationManager

            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Fast Subtitle",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun prepareTranslator() {
        val sourceLanguage =
            when (language) {
                "en" -> TranslateLanguage.ENGLISH
                "ja" -> TranslateLanguage.JAPANESE
                "ko" -> TranslateLanguage.KOREAN
                else -> TranslateLanguage.CHINESE
            }

        val options =
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(
                    TranslateLanguage.INDONESIAN
                )
                .build()

        translator =
            Translation.getClient(options)

        updateOverlay(
            "Menyiapkan terjemahan…"
        )

        translator
            ?.downloadModelIfNeeded()
            ?.addOnSuccessListener {
                updateOverlay(
                    "Mendengarkan…"
                )
            }
            ?.addOnFailureListener {
                updateOverlay(
                    "Model terjemahan gagal diunduh."
                )
            }
    }

    private fun connectDeepgram() {
        if (apiKey.isBlank()) {
            updateOverlay(
                "API Deepgram kosong."
            )
            return
        }

        val url =
            "wss://api.deepgram.com/v1/listen" +
                "?model=nova-3" +
                "&language=$language" +
                "&encoding=linear16" +
                "&sample_rate=16000" +
                "&channels=1" +
                "&interim_results=true" +
                "&smart_format=true" +
                "&punctuate=true" +
                "&endpointing=300" +
                "&utterance_end_ms=1000"

        val request =
            Request.Builder()
                .url(url)
                .header(
                    "Authorization",
                    "Token $apiKey"
                )
                .build()

        webSocket =
            httpClient.newWebSocket(
                request,
                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response
                    ) {
                        startAudioCapture()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String
                    ) {
                        handleDeepgramResult(text)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        updateOverlay(
                            "Koneksi subtitle terputus."
                        )
                    }
                }
            )
    }

    private fun startAudioCapture() {
        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            updateOverlay(
                "Izin audio tidak tersedia."
            )
            return
        }

        val mediaProjection =
            projection ?: return

        val captureConfiguration =
            AudioPlaybackCaptureConfiguration
                .Builder(mediaProjection)
                .addMatchingUsage(
                    AudioAttributes.USAGE_MEDIA
                )
                .addMatchingUsage(
                    AudioAttributes.USAGE_GAME
                )
                .addMatchingUsage(
                    AudioAttributes.USAGE_UNKNOWN
                )
                .build()

        val audioFormat =
            AudioFormat.Builder()
                .setEncoding(
                    AudioFormat.ENCODING_PCM_16BIT
                )
                .setSampleRate(16000)
                .setChannelMask(
                    AudioFormat.CHANNEL_IN_MONO
                )
                .build()

        val minimumBuffer =
            AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        val bufferSize =
            maxOf(
                minimumBuffer * 2,
                4096
            )

        recorder =
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(
                    captureConfiguration
                )
                .build()

        recorder?.startRecording()

        running = true

        thread(
            name = "FastSubtitleAudio"
        ) {
            val buffer =
                ByteArray(bufferSize)

            while (running) {
                val bytesRead =
                    recorder?.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_BLOCKING
                    ) ?: break

                if (bytesRead > 0) {
                    webSocket?.send(
                        ByteString.of(
                            buffer,
                            0,
                            bytesRead
                        )
                    )
                }
            }
        }
    }

    private fun handleDeepgramResult(
        json: String
    ) {
        try {
            val root =
                JSONObject(json)

            if (
                root.optString("type") !=
                "Results"
            ) {
                return
            }

            val channel =
                root.optJSONObject(
                    "channel"
                ) ?: return

            val alternatives =
                channel.optJSONArray(
                    "alternatives"
                ) ?: return

            if (alternatives.length() == 0) {
                return
            }

            val transcript =
                alternatives
                    .getJSONObject(0)
                    .optString("transcript")
                    .trim()

            if (transcript.isBlank()) {
                return
            }

            val isFinal =
                root.optBoolean(
                    "is_final",
                    false
                )

            val now =
                System.currentTimeMillis()

            if (
                !isFinal &&
                now - lastTranslateTime < 250
            ) {
                return
            }

            lastTranslateTime = now

            translateTranscript(
                transcript
            )
        } catch (_: Exception) {
        }
    }

    private fun translateTranscript(
        sourceText: String
    ) {
        val currentSequence =
            translationSequence
                .incrementAndGet()

        val translationEngine =
            translator ?: return

        translationEngine
            .translate(sourceText)
            .addOnSuccessListener { translated ->

                if (
                    currentSequence !=
                    translationSequence.get()
                ) {
                    return@addOnSuccessListener
                }

                val result =
                    translated.trim()

                if (result.isNotBlank()) {
                    updateOverlay(result)
                }
            }
    }

    private fun showOverlay() {
        windowManager =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        overlayText =
            TextView(this).apply {

                text =
                    "Mendengarkan…"

                gravity =
                    Gravity.CENTER

                setTextColor(
                    if (textColor == "white") {
                        Color.WHITE
                    } else {
                        Color.rgb(
                            255,
                            221,
                            0
                        )
                    }
                )

                textSize =
                    textSizeSp

                setShadowLayer(
                    5f,
                    2f,
                    2f,
                    Color.BLACK
                )

                setPadding(
                    dp(18),
                    dp(10),
                    dp(18),
                    dp(10)
                )

                maxLines = 3

                background =
                    GradientDrawable().apply {
                        setColor(
                            Color.argb(
                                backgroundAlpha,
                                0,
                                0,
                                0
                            )
                        )

                        cornerRadius =
                            dp(12).toFloat()
                    }
            }

        overlayParams =
            WindowManager.LayoutParams(
                (
                    resources
                        .displayMetrics
                        .widthPixels *
                        0.90f
                    ).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {

                gravity =
                    Gravity.TOP or
                    Gravity.CENTER_HORIZONTAL

                x =
                    preferences.getInt(
                        "overlay_x",
                        0
                    )

                y =
                    preferences.getInt(
                        "overlay_y",
                        dp(300)
                    )
            }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0

        overlayText
            ?.setOnTouchListener { _, event ->

                val params =
                    overlayParams
                        ?: return@setOnTouchListener false

                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {

                        downX =
                            event.rawX

                        downY =
                            event.rawY

                        startX =
                            params.x

                        startY =
                            params.y

                        true
                    }

                    MotionEvent.ACTION_MOVE -> {

                        params.x =
                            startX +
                                (
                                    event.rawX -
                                        downX
                                    ).toInt()

                        params.y =
                            startY +
                                (
                                    event.rawY -
                                        downY
                                    ).toInt()

                        try {
                            windowManager
                                .updateViewLayout(
                                    overlayText,
                                    params
                                )
                        } catch (_: Exception) {
                        }

                        true
                    }

                    MotionEvent.ACTION_UP -> {

                        preferences
                            .edit()
                            .putInt(
                                "overlay_x",
                                params.x
                            )
                            .putInt(
                                "overlay_y",
                                params.y
                            )
                            .apply()

                        true
                    }

                    else ->
                        false
                }
            }

        windowManager.addView(
            overlayText,
            overlayParams
        )
    }

    private fun updateOverlay(
        text: String
    ) {
        overlayText?.post {
            if (text.isNotBlank()) {
                overlayText?.text =
                    text
            }
        }
    }

    private fun shutdown() {
        running = false

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        recorder?.release()
        recorder = null

        try {
            webSocket?.close(
                1000,
                "stop"
            )
        } catch (_: Exception) {
        }

        webSocket = null

        translator?.close()
        translator = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null

        overlayText?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        overlayText = null

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }

    private fun dp(
        value: Int
    ): Int {
        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }
}
