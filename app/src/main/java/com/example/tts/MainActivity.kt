package com.example.tts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
    private val API_KEY = "Vinay@1979"

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var currentSpeed = 1.0f
    private var currentPitch = 1.0f
    private var isAudioPaused = false
    private var latestAudioData: ByteArray? = null 

    private lateinit var btnGeneratePlay: Button
    private lateinit var btnPauseResume: Button
    private lateinit var btnStop: Button
    private lateinit var btnSave: Button
    private lateinit var txtPitchValue: TextView

    private var mediaSession: MediaSessionCompat? = null
    private val ACTION_PLAY_PAUSE = "com.example.tts.ACTION_PLAY_PAUSE"

    // Listens for you pressing Play/Pause on the notification
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PLAY_PAUSE) {
                togglePlayPause()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request Permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Setup Notification Channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("tts_channel", "Audio Playback", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        // Register Receiver for Notification Buttons
        val filter = IntentFilter(ACTION_PLAY_PAUSE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // FIX: This must be EXPORTED so the Android System (Notification panel) is allowed to send the play/pause command to your app!
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(notificationReceiver, filter)
        }

        mediaSession = MediaSessionCompat(this, "StoryTTS")

        val editStoryText = findViewById<EditText>(R.id.editStoryText)
        val radioMadhur = findViewById<RadioButton>(R.id.radioMadhur)
        val radioGroupSpeed = findViewById<RadioGroup>(R.id.radioGroupSpeed)
        val radioGroupPitch = findViewById<RadioGroup>(R.id.radioGroupPitch)
        val seekBarPitch = findViewById<SeekBar>(R.id.seekBarPitch)
        
        txtPitchValue = findViewById(R.id.txtPitchValue)
        btnGeneratePlay = findViewById(R.id.btnGeneratePlay)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        btnStop = findViewById(R.id.btnStop)
        btnSave = findViewById(R.id.btnSave)

        radioGroupSpeed.setOnCheckedChangeListener { _, checkedId ->
            currentSpeed = when (checkedId) {
                R.id.speed075 -> 0.75f
                R.id.speed125 -> 1.25f
                R.id.speed150 -> 1.50f
                else -> 1.0f
            }
            applyPlaybackParams()
        }

        radioGroupPitch.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != -1) {
                currentPitch = when (checkedId) {
                    R.id.pitchDeep -> 0.8f
                    R.id.pitchDemon -> 0.6f
                    else -> 1.0f
                }
                seekBarPitch.progress = (currentPitch * 100).toInt()
                txtPitchValue.text = "${currentPitch}x"
                applyPlaybackParams()
            }
        }

        seekBarPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val safeProgress = if (progress < 10) 10 else progress
                    currentPitch = safeProgress / 100f
                    txtPitchValue.text = "${String.format("%.2f", currentPitch)}x"
                    radioGroupPitch.clearCheck()
                    applyPlaybackParams()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnGeneratePlay.setOnClickListener {
            val text = editStoryText.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Please paste story text first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedVoice = if (radioMadhur.isChecked) "hi-IN-MadhurNeural" else "hi-IN-SwaraNeural"

            btnGeneratePlay.isEnabled = false
            btnGeneratePlay.text = "Generating Voice..."
            btnPauseResume.isEnabled = false
            btnStop.isEnabled = false
            btnSave.isEnabled = false

            generateAndPlayAudio(text, selectedVoice)
        }

        btnPauseResume.setOnClickListener { togglePlayPause() }
        btnStop.setOnClickListener { stopAudioPlayback() }
        btnSave.setOnClickListener { saveAudioToDevice() }
    }

    private fun togglePlayPause() {
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
            updateNotification()
        }
    }

    private fun generateAndPlayAudio(text: String, voice: String) {
        val url = "https://kokoro-web-latest-066e.onrender.com/v1/audio/speech"

        val json = JSONObject()
        json.put("model", "tts-1")
        json.put("input", text)
        json.put("voice", voice)

        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $API_KEY").post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Network Error", Toast.LENGTH_LONG).show()
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
                    latestAudioData = audioData
                    runOnUiThread { playAudio(audioData) }
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
                setOnCompletionListener { stopAudioPlayback() }
            }

            isAudioPaused = false
            btnPauseResume.isEnabled = true
            btnPauseResume.text = "Pause"
            btnStop.isEnabled = true
            btnSave.isEnabled = true

            // Set up the MediaSession Metadata for the Notification
            mediaSession?.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Hindi Horror Story")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "StoryTTS")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer!!.duration.toLong())
                    .build()
            )
            mediaSession?.isActive = true
            updateNotification()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyPlaybackParams() {
        mediaPlayer?.let { player ->
            try {
                val isPlaying = player.isPlaying
                player.playbackParams = player.playbackParams.setSpeed(currentSpeed).setPitch(currentPitch)
                if (!isPlaying && !isAudioPaused) player.pause()
                updateNotification()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateNotification() {
        if (mediaPlayer == null) return
        val isPlaying = mediaPlayer!!.isPlaying

        // FIX: Ensure the intent explicitly targets this app so the system doesn't block it
        val playPauseIntent = Intent(ACTION_PLAY_PAUSE).apply {
            setPackage(packageName) 
        }
        val pendingIntent = PendingIntent.getBroadcast(this, 0, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val actionIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val actionTitle = if (isPlaying) "Pause" else "Play"
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                .setState(state, mediaPlayer!!.currentPosition.toLong(), currentSpeed)
                .build()
        )

        val notification = NotificationCompat.Builder(this, "tts_channel")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Hindi Story")
            .setContentText(if (isPlaying) "Playing..." else "Paused")
            .setOngoing(isPlaying)
            .addAction(actionIcon, actionTitle, pendingIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0))
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(this).notify(1, notification)
        }
    }

    private fun stopAudioPlayback() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) player.stop()
            player.release()
        }
        mediaPlayer = null
        isAudioPaused = false
        btnPauseResume.isEnabled = false
        btnPauseResume.text = "Pause"
        btnStop.isEnabled = false
        
        mediaSession?.isActive = false
        NotificationManagerCompat.from(this).cancel(1)
    }

    private fun saveAudioToDevice() {
        val data = latestAudioData
        if (data == null) {
            Toast.makeText(this, "No audio to save!", Toast.LENGTH_SHORT).show()
            return
        }

        // Ask for storage permission on older Android devices
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 102)
                return
            }
        }

        try {
            val fileName = "HorrorStory_${System.currentTimeMillis()}.mp3"
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/StoryTTS")
                }
            }

            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(data)
                }
                Toast.makeText(this, "Audio saved to Music/StoryTTS folder!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioPlayback()
        mediaSession?.release()
        unregisterReceiver(notificationReceiver)
    }
}
