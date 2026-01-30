package com.example.secondbrainassistive

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
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
import java.io.*
import java.util.*
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var tts: TextToSpeech
    private var started = false
    private var mediaPlayer: MediaPlayer? = null // 🔥 Added to prevent Garbage Collection

    private val serverUrl = "https://flannelly-taneka-fleetingly.ngrok-free.dev/process"

    private val sampleRate = 16000
    private val silenceThreshold = 1500
    private val silenceTimeoutMs = 2000L

    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        status.text = "Second Brain Ready"

        requestPermissions()

        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts.language = Locale.ENGLISH
                tts.speak("Second Brain is ready. Tap to speak.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }

        startCamera()

        status.setOnClickListener {
            tryStart()
        }
    }

    private fun requestPermissions() {
        val perms = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val missing = perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing) ActivityCompat.requestPermissions(this, perms, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            status.text = "Tap to speak"
        } else {
            status.text = "Permissions required"
            tts.speak("Please allow microphone and camera", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun tryStart() {
        if (!hasMicPermission()) {
            tts.speak("Microphone permission required", TextToSpeech.QUEUE_FLUSH, null, null)
            return
        }
        if (!started) {
            started = true
            startVoiceRecording()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture!!)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureImage(onDone: (File?) -> Unit) {
        val capture = imageCapture ?: run { onDone(null); return }
        val imageFile = File(cacheDir, "vision.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(imageFile).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onDone(imageFile)
            override fun onError(exc: ImageCaptureException) = onDone(null)
        })
    }

    @SuppressLint("MissingPermission")
    private fun startVoiceRecording() {
        runOnUiThread {
            status.text = "Listening"
            tts.speak("Listening", TextToSpeech.QUEUE_FLUSH, null, null)
        }

        Thread {
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (bufferSize <= 0) { restart(); return@Thread }

            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            val pcmFile = File(cacheDir, "speech.pcm")
            val wavFile = File(cacheDir, "speech.wav")
            val pcmOut = FileOutputStream(pcmFile)
            val buffer = ShortArray(bufferSize)
            var lastVoice = System.currentTimeMillis()

            recorder.startRecording()
            while (true) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcmOut.write(shortsToBytes(buffer, read))
                    val amp = buffer.take(read).maxOf { abs(it.toInt()) }
                    if (amp > silenceThreshold) lastVoice = System.currentTimeMillis()
                    if (System.currentTimeMillis() - lastVoice > silenceTimeoutMs) break
                }
            }
            recorder.stop(); recorder.release(); pcmOut.close()
            pcmToWav(pcmFile, wavFile)

            runOnUiThread {
                status.text = "Processing"
                tts.speak("Processing", TextToSpeech.QUEUE_FLUSH, null, null)
            }

            sendToServer(wavFile)
        }.start()
    }

    private fun sendToServer(audio: File, image: File? = null) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS) // Increased for longer detailed responses
            .build()

        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "speech.wav", audio.asRequestBody("audio/wav".toMediaType()))

        if (image != null) {
            bodyBuilder.addFormDataPart("image", "vision.jpg", image.asRequestBody("image/jpeg".toMediaType()))
        }

        val request = Request.Builder().url(serverUrl).post(bodyBuilder.build()).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { tts.speak("Network error.", TextToSpeech.QUEUE_FLUSH, null, null) }
                restart()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body ?: return
                    val contentType = response.header("Content-Type") ?: ""

                    if (contentType.contains("application/json")) {
                        val text = body.string()
                        if (text.contains("need_image")) {
                            runOnUiThread { captureImage { img -> if (img != null) sendToServer(audio, img) else restart() } }
                            return
                        }
                    }

                    val bytes = body.bytes()
                    val reply = File(cacheDir, "reply.mp3")
                    FileOutputStream(reply).use { it.write(bytes) }

                    runOnUiThread { playReply(reply) }
                } catch (e: Exception) {
                    restart()
                }
            }
        })
    }

    private fun playReply(file: File) {
        try {
            // 🔥 Reset and reuse the same player instance to ensure callbacks trigger
            if (mediaPlayer == null) mediaPlayer = MediaPlayer()
            mediaPlayer?.apply {
                reset()
                setDataSource(file.absolutePath)
                setOnPreparedListener { start() }
                setOnCompletionListener { restart() }
                setOnErrorListener { _, _, _ -> restart(); true }
                prepareAsync()
            }
        } catch (e: Exception) {
            restart()
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
            it.writeBytes("RIFF"); it.writeIntLE(data.size + 36); it.writeBytes("WAVEfmt ")
            it.writeIntLE(16); it.writeShortLE(1); it.writeShortLE(1); it.writeIntLE(sampleRate)
            it.writeIntLE(sampleRate * 2); it.writeShortLE(2); it.writeShortLE(16)
            it.writeBytes("data"); it.writeIntLE(data.size); it.write(data)
        }
    }

    private fun DataOutputStream.writeIntLE(v: Int) { write(v); write(v shr 8); write(v shr 16); write(v shr 24) }
    private fun DataOutputStream.writeShortLE(v: Int) { write(v); write(v shr 8) }

    override fun onDestroy() {
        mediaPlayer?.release()
        tts.shutdown()
        super.onDestroy()
    }
}