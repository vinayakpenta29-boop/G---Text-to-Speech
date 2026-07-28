package com.example.tts

import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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

class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient()
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val editApiKey = findViewById<EditText>(R.id.editApiKey)
        val editStoryText = findViewById<EditText>(R.id.editStoryText)
        val btnPlay = findViewById<Button>(R.id.btnPlay)

        btnPlay.setOnClickListener {
            val apiKey = editApiKey.text.toString().trim()
            val text = editStoryText.text.toString().trim()

            if (apiKey.isEmpty() || text.isEmpty()) {
                Toast.makeText(this, "Please enter API Key and Text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnPlay.isEnabled = false
            btnPlay.text = "Generating Voice..."
            
            generateAndPlayAudio(apiKey, text, btnPlay)
        }
    }

    private fun generateAndPlayAudio(apiKey: String, text: String, btnPlay: Button) {
        // "21m00Tcm4TlvDq8ikWAM" is the voice ID for "Rachel". 
        // You can swap this with any voice ID from your ElevenLabs dashboard.
        val voiceId = "21m00Tcm4TlvDq8ikWAM"
        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"

        val json = JSONObject()
        json.put("text", text)
        json.put("model_id", "eleven_multilingual_v2")

        val body = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
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
