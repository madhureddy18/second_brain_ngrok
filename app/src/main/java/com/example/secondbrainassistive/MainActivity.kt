package com.example.secondbrainassistive

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.*
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var tts: TextToSpeech
    private lateinit var micButton: Button
    private lateinit var waveLayout: LinearLayout
    private lateinit var eqLayout: LinearLayout
    private lateinit var infoCard: TextView
    private lateinit var langBadge: TextView

    private var onboardingTriggered = false
    private lateinit var prefs: android.content.SharedPreferences
    private var started = false
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var toneGen: ToneGenerator

    private val serverUrl =
        "https://netravaani-517230782740.us-central1.run.app/process"

    private val sampleRate = 16000
    private val silenceTimeoutMs = 1200L
    private val minSpeechMs = 1300L
    private val maxRecordMs = 10000L

    private var imageCapture: ImageCapture? = null

    private val LONG_PRESS_MS = 1200L
    private var volumeDownTime = 0L

    // ---------------- LIFECYCLE ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)
        waveLayout = findViewById(R.id.waveLayout)
        eqLayout = findViewById(R.id.eqLayout)
        infoCard = findViewById(R.id.infoCard)
        langBadge = findViewById(R.id.langBadge)

        toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        prefs = getSharedPreferences("second_brain", MODE_PRIVATE)

        requestPermissions()
        startCamera()
        initTTS()

        setStateIdle()

        Handler(Looper.getMainLooper()).postDelayed({
            triggerLanguageOnboarding()
        }, 1500)
    }

    // ---------------- UI STATES ----------------

    private fun setStateIdle() {
        runOnUiThread {
            started = false
            micButton.text = "🎤"
            micButton.setBackgroundResource(R.drawable.mic_button)
            status.text = "Long press Volume Up to talk"
            status.setTextColor(getColor(R.color.text_accent))
            waveLayout.visibility = View.GONE
            eqLayout.visibility = View.GONE
            infoCard.visibility = View.GONE
            langBadge.visibility = View.GONE
        }
    }

    private fun setStateListening() {
        runOnUiThread {
            micButton.text = "🎤"
            micButton.setBackgroundResource(R.drawable.mic_listening)
            status.text = "Listening..."
            status.setTextColor(getColor(R.color.mic_listening))
            waveLayout.visibility = View.VISIBLE
            eqLayout.visibility = View.GONE
            infoCard.visibility = View.GONE
            val lang = prefs.getString("lang", "") ?: ""
            if (lang.isNotEmpty()) {
                langBadge.text = "🌐 ${lang.uppercase()}"
                langBadge.visibility = View.VISIBLE
            } else {
                langBadge.visibility = View.GONE
            }
        }
    }

    private fun setStateSpeaking() {
        runOnUiThread {
            micButton.text = "🔊"
            micButton.setBackgroundResource(R.drawable.mic_speaking)
            status.text = "Speaking..."
            status.setTextColor(getColor(R.color.text_accent))
            waveLayout.visibility = View.GONE
            eqLayout.visibility = View.VISIBLE
            val lang = prefs.getString("lang", "en") ?: "en"
            langBadge.text = "🌐 ${lang.uppercase()}"
            langBadge.visibility = View.VISIBLE
        }
    }

    private fun setStateVision() {
        runOnUiThread {
            micButton.text = "📷"
            micButton.setBackgroundResource(R.drawable.mic_vision)
            status.text = "Capturing image..."
            status.setTextColor(getColor(R.color.badge_text))
            waveLayout.visibility = View.GONE
            eqLayout.visibility = View.GONE
            infoCard.text = "Vision mode active.\nDescribe object detected"
            infoCard.visibility = View.VISIBLE
            langBadge.text = "👁 Vision intent"
            langBadge.visibility = View.VISIBLE
        }
    }

    // ---------------- VOLUME UP LONG PRESS ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (volumeDownTime == 0L) {
                        volumeDownTime = System.currentTimeMillis()
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - volumeDownTime
                    volumeDownTime = 0L
                    if (duration >= LONG_PRESS_MS && !started) {
                        started = true
                        playBeep()
                        setStateListening()
                        startVoiceRecording()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- TTS ----------------

    private fun initTTS() {
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts.language = Locale.ENGLISH
                tts.speak(
                    "Second Brain is ready. Long press volume up to talk.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    null
                )
            }
        }
    }

    // ---------------- ONBOARDING ----------------

    private fun triggerLanguageOnboarding() {
        if (onboardingTriggered) return
        onboardingTriggered = true

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("init", "true")
            .build()

        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tts.speak(
                        "Could not connect to server. Please check your internet.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        null
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val reply = File(cacheDir, "reply.mp3")
                FileOutputStream(reply).use {
                    it.write(response.body!!.bytes())
                }
                runOnUiThread {
                    // Show onboarding prompt in info card
                    infoCard.text = "Say \"English\", \"Hindi\" or \"Telugu\" to set language"
                    infoCard.visibility = View.VISIBLE
                    playReply(reply)
                }
            }
        })
    }

    // ---------------- NOISE FLOOR ----------------

    private fun measureNoiseFloor(
        recorder: AudioRecord,
        buffer: ShortArray,
        durationMs: Long = 400
    ): Int {
        val end = System.currentTimeMillis() + durationMs
        var maxAmp = 0
        while (System.currentTimeMillis() < end) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read > 0) {
                val amp = buffer.take(read).maxOf { abs(it.toInt()) }
                if (amp > maxAmp) maxAmp = amp
            }
        }
        return maxAmp
    }

    // ---------------- VOICE RECORDING ----------------

    @SuppressLint("MissingPermission")
    private fun startVoiceRecording() {
        Thread {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (NoiseSuppressor.isAvailable())
                NoiseSuppressor.create(recorder.audioSessionId)
            if (AutomaticGainControl.isAvailable())
                AutomaticGainControl.create(recorder.audioSessionId)

            val pcmFile = File(cacheDir, "speech.pcm")
            val wavFile = File(cacheDir, "speech.wav")
            val pcmOut = FileOutputStream(pcmFile)
            val buffer = ShortArray(bufferSize)

            recorder.startRecording()

            val noiseFloor = measureNoiseFloor(recorder, buffer)
            val silenceThreshold = noiseFloor + 1200

            val startTime = System.currentTimeMillis()
            var lastVoiceTime = startTime
            var firstVoiceTime = -1L

            while (true) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcmOut.write(shortsToBytes(buffer, read))
                    val amp = buffer.take(read).maxOf { abs(it.toInt()) }

                    if (amp > silenceThreshold) {
                        lastVoiceTime = System.currentTimeMillis()
                        if (firstVoiceTime == -1L)
                            firstVoiceTime = System.currentTimeMillis()
                    }

                    val now = System.currentTimeMillis()
                    val spokeEnough =
                        firstVoiceTime != -1L && (now - firstVoiceTime) > minSpeechMs
                    val silentEnough = (now - lastVoiceTime) > silenceTimeoutMs
                    val forceStop =
                        firstVoiceTime != -1L && (now - firstVoiceTime) > 4500
                    val tooLong = (now - startTime) > maxRecordMs

                    if ((spokeEnough && silentEnough) || forceStop || tooLong) break
                }
            }

            recorder.stop()
            recorder.release()
            pcmOut.close()

            pcmToWav(pcmFile, wavFile)
            sendToServer(wavFile)
        }.start()
    }

    // ---------------- SERVER ----------------

    private fun sendToServer(audio: File, image: File? = null) {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                "speech.wav",
                audio.asRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("lang", prefs.getString("lang", "") ?: "")

        if (image != null) {
            bodyBuilder.addFormDataPart(
                "image",
                "vision.jpg",
                image.asRequestBody("image/jpeg".toMediaType())
            )
        }

        val request = Request.Builder()
            .url(serverUrl)
            .post(bodyBuilder.build())
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    status.text = "No internet connection"
                    status.setTextColor(getColor(R.color.mic_listening))
                    tts.speak(
                        "No internet connection. Please check your network.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        null
                    )
                }
                Handler(Looper.getMainLooper()).postDelayed({ setStateIdle() }, 3000)
            }

            override fun onResponse(call: Call, response: Response) {
                val contentType = response.header("Content-Type") ?: ""
                val detectedLang = response.header("X-Lang") ?: ""
                if (detectedLang.isNotEmpty()) {
                    prefs.edit().putString("lang", detectedLang).apply()
                }

                if (contentType.contains("application/json")) {
                    val json = JSONObject(response.body!!.string())
                    if (json.optBoolean("need_image")) {
                        setStateVision()
                        captureImage { img ->
                            if (img != null) sendToServer(audio, img)
                            else setStateIdle()
                        }
                        return
                    }
                }

                val reply = File(cacheDir, "reply.mp3")
                FileOutputStream(reply).use {
                    it.write(response.body!!.bytes())
                }

                runOnUiThread {
                    setStateSpeaking()
                    playReply(reply)
                }
            }
        })
    }

    // ---------------- CAMERA ----------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageCapture!!
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureImage(onDone: (File?) -> Unit) {
        val capture = imageCapture ?: run { onDone(null); return }

        val imageFile = File(cacheDir, "vision.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(imageFile).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onDone(imageFile)
                }
                override fun onError(exc: ImageCaptureException) {
                    onDone(null)
                }
            }
        )
    }

    // ---------------- PLAYBACK ----------------

    private fun playReply(file: File) {
        if (mediaPlayer == null) mediaPlayer = MediaPlayer()
        mediaPlayer?.apply {
            reset()
            setDataSource(file.absolutePath)
            setOnPreparedListener { start() }
            setOnCompletionListener {
                playBeep()
                setStateIdle()
            }
            prepareAsync()
        }
    }

    // ---------------- PERMISSIONS ----------------

    private fun requestPermissions() {
        val perms = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        val missing = perms.any {
            ContextCompat.checkSelfPermission(this, it) !=
                    PackageManager.PERMISSION_GRANTED
        }
        if (missing) ActivityCompat.requestPermissions(this, perms, 1)
    }

    // ---------------- UTILS ----------------

    private fun playBeep() {
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    private fun shortsToBytes(data: ShortArray, len: Int): ByteArray {
        val b = ByteArray(len * 2)
        for (i in 0 until len) {
            b[i * 2] = (data[i].toInt() and 0xFF).toByte()
            b[i * 2 + 1] = ((data[i].toInt() shr 8) and 0xFF).toByte()
        }
        return b
    }

    private fun pcmToWav(pcm: File, wav: File) {
        val data = pcm.readBytes()
        DataOutputStream(FileOutputStream(wav)).use {
            it.writeBytes("RIFF")
            it.writeIntLE(data.size + 36)
            it.writeBytes("WAVEfmt ")
            it.writeIntLE(16)
            it.writeShortLE(1)
            it.writeShortLE(1)
            it.writeIntLE(sampleRate)
            it.writeIntLE(sampleRate * 2)
            it.writeShortLE(2)
            it.writeShortLE(16)
            it.writeBytes("data")
            it.writeIntLE(data.size)
            it.write(data)
        }
    }

    private fun DataOutputStream.writeIntLE(v: Int) {
        write(v); write(v shr 8); write(v shr 16); write(v shr 24)
    }

    private fun DataOutputStream.writeShortLE(v: Int) {
        write(v); write(v shr 8)
    }
}