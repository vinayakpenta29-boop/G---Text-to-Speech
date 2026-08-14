package com.example.tts

import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
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

    // Your secret password configured in your Render environment variables
    private val API_KEY = "my_secret_key_123"

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val editStoryText = findViewById<EditText>(R.id.editStoryText)
        val radioMadhur = findViewById<RadioButton>(R.id.radioMadhur)
        val btnPlay = findViewById<Button>(R.id.btnPlay)

        btnPlay.setOnClickListener {
            val text = editStoryText.text.toString().trim()

            if (text.isEmpty()) {
                Toast.makeText(this, "Please paste story text first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Check which voice is selected
            val selectedVoice = if (radioMadhur.isChecked) {
                "hi-IN-MadhurNeural"
            } else {
                "hi-IN-SwaraNeural"
            }

            btnPlay.isEnabled = false
            btnPlay.text = "Generating Voice..."

            generateAndPlayAudio(text, selectedVoice, btnPlay)
        }
    }

    private fun generateAndPlayAudio(text: String, voice: String, btnPlay: Button) {
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
                    btnPlay.isEnabled = true
                    btnPlay.text = "Play Story"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "API Error: ${response.code}", Toast.LENGTH_LONG).show()
                        btnPlay.isEnabled = true
                        btnPlay.text = "Play Story"
                    }
                    return
                }

                val audioData = response.body?.bytes()
                if (audioData != null) {
                    playAudio(audioData)
                }

                runOnUiThread {
                    btnPlay.isEnabled = true
                    btnPlay.text = "Play Story"
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
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
