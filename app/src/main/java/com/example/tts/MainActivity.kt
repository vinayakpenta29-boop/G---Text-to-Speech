package com.example.tts

import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // Keep your working password here
    private val API_KEY = "YOUR_REAL_PASSWORD_HERE"

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var currentSpeed = 1.0f
    private var currentPitch = 1.0f
    private var isAudioPaused = false

    private lateinit var btnGeneratePlay: Button
    private lateinit var btnPauseResume: Button
    private lateinit var btnStop: Button
    private lateinit var txtPitchValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val editStoryText = findViewById<EditText>(R.id.editStoryText)
        val radioMadhur = findViewById<RadioButton>(R.id.radioMadhur)
        
        val radioGroupSpeed = findViewById<RadioGroup>(R.id.radioGroupSpeed)
        val radioGroupPitch = findViewById<RadioGroup>(R.id.radioGroupPitch)
        
        val seekBarPitch = findViewById<SeekBar>(R.id.seekBarPitch)
        txtPitchValue = findViewById(R.id.txtPitchValue)

        btnGeneratePlay = findViewById(R.id.btnGeneratePlay)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        btnStop = findViewById(R.id.btnStop)

        // Speed selection listener
        radioGroupSpeed.setOnCheckedChangeListener { _, checkedId ->
            currentSpeed = when (checkedId) {
                R.id.speed075 -> 0.75f
                R.id.speed125 -> 1.25f
                R.id.speed150 -> 1.50f
                else -> 1.0f
            }
            applyPlaybackParams()
        }

        // Pitch RadioGroup listener
        radioGroupPitch.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != -1) { // Only run if a button is actually checked
                currentPitch = when (checkedId) {
                    R.id.pitchDeep -> 0.8f
                    R.id.pitchDemon -> 0.6f
                    else -> 1.0f
                }
                
                // Automatically move the SeekBar to match the button
                seekBarPitch.progress = (currentPitch * 100).toInt()
                txtPitchValue.text = "${currentPitch}x"
                
                applyPlaybackParams()
            }
        }

        // Live Pitch SeekBar listener
        seekBarPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Prevent pitch from hitting exactly 0.0, which crashes MediaPlayer
                    val safeProgress = if (progress < 10) 10 else progress
                    currentPitch = safeProgress / 100f
                    
                    txtPitchValue.text = "${String.format("%.2f", currentPitch)}x"
                    
                    // Uncheck the preset buttons because the user is sliding manually
                    radioGroupPitch.clearCheck()
                    
                    applyPlaybackParams()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Generate & Play button
        btnGeneratePlay.setOnClickListener {
            val text = editStoryText.text.toString().trim()

            if (text.isEmpty()) {
                Toast.makeText(this, "Please paste story text first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedVoice = if (radioMadhur.isChecked) {
                "hi-IN-MadhurNeural"
            } else {
                "hi-IN-SwaraNeural"
            }

            btnGeneratePlay.isEnabled = false
            btnGeneratePlay.text = "Generating Voice..."
            btnPauseResume.isEnabled = false
            btnStop.isEnabled = false

            generateAndPlayAudio(text, selectedVoice)
        }

        // Pause / Resume button
        btnPauseResume.setOnClickListener {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    isAudioPaused = true
                    btnPauseResume.text = "Resume"
                } else if (isAudioPaused) {
                    player.start()
                    applyPlaybackParams()
                    isAudioPaused = false
                    btnPauseResume.text = "Pause"
                }
            }
        }

        // Stop button
        btnStop.setOnClickListener {
            stopAudioPlayback()
        }
    }

    private fun generateAndPlayAudio(text: String, voice: String) {
        val url = "https://kokoro-web-latest-066e.onrender.com/v1/audio/speech"

        val json = JSONObject()
        json.put("model", "tts-1")
        json.put("input", text)
        json.put("voice", voice)

        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Network Error: ${e.message}", Toast.LENGTH_LONG).show()
                    btnGeneratePlay.isEnabled = true
                    btnGeneratePlay.text = "Generate & Play"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "API Error: ${response.code}", Toast.LENGTH_LONG).show()
                        btnGeneratePlay.isEnabled = true
                        btnGeneratePlay.text = "Generate & Play"
                    }
                    return
                }

                val audioData = response.body?.bytes()
                if (audioData != null) {
                    runOnUiThread {
                        playAudio(audioData)
                    }
                }

                runOnUiThread {
                    btnGeneratePlay.isEnabled = true
                    btnGeneratePlay.text = "Generate & Play"
                }
            }
        })
    }

    private fun playAudio(audioData: ByteArray) {
        try {
            val tempFile = File.createTempFile("story", ".mp3", cacheDir)
            tempFile.deleteOnExit()
            val fos = FileOutputStream(tempFile)
            fos.write(audioData)
            fos.close()

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                playbackParams = playbackParams.setSpeed(currentSpeed).setPitch(currentPitch)
                start()

                setOnCompletionListener {
                    stopAudioPlayback()
                }
            }

            isAudioPaused = false
            btnPauseResume.isEnabled = true
            btnPauseResume.text = "Pause"
            btnStop.isEnabled = true

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyPlaybackParams() {
        mediaPlayer?.let { player ->
            try {
                val isPlaying = player.isPlaying
                player.playbackParams = player.playbackParams.setSpeed(currentSpeed).setPitch(currentPitch)
                if (!isPlaying && !isAudioPaused) {
                    player.pause()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopAudioPlayback() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.stop()
            }
            player.release()
        }
        mediaPlayer = null
        isAudioPaused = false
        btnPauseResume.isEnabled = false
        btnPauseResume.text = "Pause"
        btnStop.isEnabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
