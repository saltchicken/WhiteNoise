package com.example.whitenoise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Random

class WhiteNoiseService : Service() {

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    companion object {
        const val ACTION_START = "com.example.whitenoise.ACTION_START"
        const val ACTION_STOP = "com.example.whitenoise.ACTION_STOP"
        const val ACTION_TOGGLE = "com.example.whitenoise.ACTION_TOGGLE"
        const val ACTION_SET_VOLUME = "com.example.whitenoise.ACTION_SET_VOLUME"
        const val EXTRA_VOLUME = "extra_volume"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "white_noise_channel"

        private val _isPlayingState = MutableStateFlow(false)
        val isPlayingState: StateFlow<Boolean> = _isPlayingState.asStateFlow()

        private val _volumeState = MutableStateFlow(0.5f)
        val volumeState: StateFlow<Float> = _volumeState.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, WhiteNoiseService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, WhiteNoiseService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun setVolume(context: Context, volume: Float) {
            val intent = Intent(context, WhiteNoiseService::class.java).apply {
                action = ACTION_SET_VOLUME
                putExtra(EXTRA_VOLUME, volume)
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startPlayback()
            ACTION_STOP -> stopPlayback()
            ACTION_TOGGLE -> {
                if (_isPlayingState.value) {
                    stopPlayback()
                } else {
                    startPlayback()
                }
            }
            ACTION_SET_VOLUME -> {
                val vol = intent.getFloatExtra(EXTRA_VOLUME, _volumeState.value)
                setVolumeInternal(vol)
            }
        }
        return START_STICKY
    }

    private fun requestAudioFocus() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener {
                    // Intentionally ignore audio focus loss to keep playing white noise concurrently
                    // alongside other playing audio as requested.
                }
                .build()
            audioFocusRequest = focusRequest
            audioManager?.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                {
                    // Intentionally ignore audio focus loss to keep playing white noise concurrently
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun startPlayback() {
        if (_isPlayingState.value) return

        requestAudioFocus()
        startForegroundInternal()

        _isPlayingState.value = true

        val sampleRate = 44100
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, sampleRate)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        setVolumeInternal(_volumeState.value)
        track.play()

        playbackJob = serviceScope.launch {
            val pcmBuffer = ShortArray(bufferSize / 2)
            val random = Random()

            while (_isPlayingState.value && (track.playState == AudioTrack.PLAYSTATE_PLAYING)) {
                for (i in pcmBuffer.indices) {
                    pcmBuffer[i] = (random.nextInt(65536) - 32768).toShort()
                }
                track.write(pcmBuffer, 0, pcmBuffer.size)
            }
        }
    }

    private fun setVolumeInternal(vol: Float) {
        val clampedVol = vol.coerceIn(0.0f, 1.0f)
        _volumeState.value = clampedVol
        audioTrack?.setVolume(clampedVol)
    }

    private fun stopPlayback() {
        _isPlayingState.value = false
        playbackJob?.cancel()
        playbackJob = null

        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            } catch (_: Exception) {
            }
        }
        audioTrack = null

        abandonAudioFocus()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
    }

    private fun startForegroundInternal() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                }
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "White Noise Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for background white noise playback"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WhiteNoiseService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("White Noise Generator")
            .setContentText("White noise playing in background")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }
}
