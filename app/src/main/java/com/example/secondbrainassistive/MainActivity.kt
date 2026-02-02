package com.example.secondbrainassistive

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.*
import android.os.*
import android.speech.tts.TextToSpeech
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
    private var started = false
    private var mediaPlayer: MediaPlayer? = null

    private val serverUrl = "https://flannelly-taneka-fleetingly.ngrok-free.dev/process"

    private val sampleRate = 16000

    private val baseSilenceThreshold = 1200
    private val silenceTimeoutMs = 2500L
    private val minSpeechMs = 1200L
    private val maxRecordMs = 25000L

    private var imageCapture: ImageCapture? = null

    // ✅ NEW (SAFE)
    private var onboardingTriggered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        status.text = "Tap to speak"

        requestPermissions()

        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts.language = Locale.ENGLISH
                tts.speak(
                    "Second Brain is ready.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    null
                )
            }
        }

        startCamera()
        status.setOnClickListener { tryStart() }

        // ✅ NEW: trigger language onboarding ONCE (no mic, no camera, no TTS)
        Handler(Looper.getMainLooper()).postDelayed({
            triggerLanguageOnboarding()
        }, 1500)
    }

    // 🔥 SAFE: server speaks the language question
    private fun triggerLanguageOnboarding() {
        if (onboardingTriggered) return
        onboardingTriggered = true

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        // IMPORTANT: non-empty multipart (prevents crash)
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
                // silently ignore
            }

            override fun onResponse(call: Call, response: Response) {
                val bytes = response.body!!.bytes()
                val reply = File(cacheDir, "reply.mp3")
                FileOutputStream(reply).use { it.write(bytes) }

                runOnUiThread {
                    status.text = "Speaking..."
                    playReply(reply)
                }
            }
        })
    }

    private fun requestPermissions() {
        val perms = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val missing = perms.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) ActivityCompat.requestPermissions(this, perms, 1)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun tryStart() {
        if (!hasMicPermission()) return
        if (!started) {
            started = true
            startVoiceRecording()
        }
    }

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

    // ❗ EVERYTHING BELOW IS UNCHANGED (YOUR WORKING LOGIC)

    @SuppressLint("MissingPermission")
    private fun startVoiceRecording() {
        runOnUiThread { status.text = "Listening..." }

        Thread {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recorder.audioSessionId)
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(recorder.audioSessionId)

            val pcmFile = File(cacheDir, "speech.pcm")
            val wavFile = File(cacheDir, "speech.wav")
            val pcmOut = FileOutputStream(pcmFile)
            val buffer = ShortArray(bufferSize)

            val startTime = System.currentTimeMillis()
            var lastVoiceTime = startTime
            var firstVoiceTime = -1L

            recorder.startRecording()

            while (true) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcmOut.write(shortsToBytes(buffer, read))
                    val amp = buffer.take(read).maxOf { abs(it.toInt()) }

                    if (amp > baseSilenceThreshold) {
                        lastVoiceTime = System.currentTimeMillis()
                        if (firstVoiceTime == -1L) firstVoiceTime = System.currentTimeMillis()
                    }

                    val now = System.currentTimeMillis()
                    val spokeEnough =
                        firstVoiceTime != -1L && (now - firstVoiceTime) > minSpeechMs
                    val silentEnough = (now - lastVoiceTime) > silenceTimeoutMs
                    val tooLong = (now - startTime) > maxRecordMs

                    if ((spokeEnough && silentEnough) || tooLong) break
                }
            }

            recorder.stop()
            recorder.release()
            pcmOut.close()

            pcmToWav(pcmFile, wavFile)
            sendToServer(wavFile)
        }.start()
    }

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
                restart()
            }

            override fun onResponse(call: Call, response: Response) {
                val contentType = response.header("Content-Type") ?: ""

                // 🔥 CRITICAL FIX: HANDLE VISION REQUEST
                if (contentType.contains("application/json")) {
                    val json = JSONObject(response.body!!.string())
                    if (json.optBoolean("need_image")) {
                        captureImage { img ->
                            if (img != null) {
                                sendToServer(audio, img)
                            } else {
                                restart()
                            }
                        }
                        return
                    }
                }

                // NORMAL AUDIO RESPONSE
                val bytes = response.body!!.bytes()
                val reply = File(cacheDir, "reply.mp3")
                FileOutputStream(reply).use { it.write(bytes) }

                runOnUiThread {
                    status.text = "Speaking..."
                    playReply(reply)
                }
            }
        })
    }

    private fun captureImage(onDone: (File?) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onDone(null)
            return
        }

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
                    exc.printStackTrace()
                    onDone(null)
                }
            }
        )
    }



    private fun playReply(file: File) {
        if (mediaPlayer == null) mediaPlayer = MediaPlayer()
        mediaPlayer?.apply {
            reset()
            setDataSource(file.absolutePath)
            setOnPreparedListener { start() }
            setOnCompletionListener { restart() }
            prepareAsync()
        }
    }

    private fun restart() {
        runOnUiThread {
            started = false
            status.text = "Tap to speak"
        }
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
