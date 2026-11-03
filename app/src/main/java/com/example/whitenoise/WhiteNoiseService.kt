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

    enum class NoiseType(val displayName: String) {
        WHITE("White Noise"),
        PINK("Pink Noise"),
        BROWN("Brown Noise")
    }

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Pink noise filter variables (Paul Kellet algorithm)
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var b3 = 0f
    private var b4 = 0f
    private var b5 = 0f
    private var b6 = 0f

    // Brown noise filter variable (Leaky integrator)
    private var lastBrownSample = 0f

    companion object {
        const val ACTION_START = "com.example.whitenoise.ACTION_START"
        const val ACTION_STOP = "com.example.whitenoise.ACTION_STOP"
        const val ACTION_TOGGLE = "com.example.whitenoise.ACTION_TOGGLE"
        const val ACTION_SET_VOLUME = "com.example.whitenoise.ACTION_SET_VOLUME"
        const val ACTION_SET_NOISE_TYPE = "com.example.whitenoise.ACTION_SET_NOISE_TYPE"
        const val EXTRA_VOLUME = "extra_volume"
        const val EXTRA_NOISE_TYPE = "extra_noise_type"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "white_noise_channel"

        private val _isPlayingState = MutableStateFlow(false)
        val isPlayingState: StateFlow<Boolean> = _isPlayingState.asStateFlow()

        private val _volumeState = MutableStateFlow(0.1f)
        val volumeState: StateFlow<Float> = _volumeState.asStateFlow()

        private val _noiseTypeState = MutableStateFlow(NoiseType.PINK)
        val noiseTypeState: StateFlow<NoiseType> = _noiseTypeState.asStateFlow()

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

        fun setNoiseType(context: Context, noiseType: NoiseType) {
            val intent = Intent(context, WhiteNoiseService::class.java).apply {
                action = ACTION_SET_NOISE_TYPE
                putExtra(EXTRA_NOISE_TYPE, noiseType.name)
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
            ACTION_SET_NOISE_TYPE -> {
                val typeName = intent.getStringExtra(EXTRA_NOISE_TYPE)
                val noiseType = NoiseType.entries.find { it.name == typeName } ?: NoiseType.WHITE
                setNoiseTypeInternal(noiseType)
            }
        }
        return START_STICKY
    }

    private fun setNoiseTypeInternal(noiseType: NoiseType) {
        _noiseTypeState.value = noiseType
        resetFilters()
        updateNotification()
    }

    private fun resetFilters() {
        b0 = 0f
        b1 = 0f
        b2 = 0f
        b3 = 0f
        b4 = 0f
        b5 = 0f
        b6 = 0f
        lastBrownSample = 0f
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
                    // Intentionally ignore audio focus loss to keep playing noise concurrently
                    // alongside other playing audio as requested.
                }
                .build()
            audioFocusRequest = focusRequest
            audioManager?.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                {
                    // Intentionally ignore audio focus loss to keep playing noise concurrently
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
                val currentNoiseType = _noiseTypeState.value
                for (i in pcmBuffer.indices) {
                    val sampleFloat = when (currentNoiseType) {
                        NoiseType.WHITE -> random.nextFloat() * 2f - 1f
                        NoiseType.PINK -> generatePinkSample(random)
                        NoiseType.BROWN -> generateBrownSample(random)
                    }
                    pcmBuffer[i] = (sampleFloat * 32767f).toInt().toShort()
                }
                track.write(pcmBuffer, 0, pcmBuffer.size)
            }
        }
    }

    private fun generatePinkSample(random: Random): Float {
        val white = random.nextFloat() * 2f - 1f
        b0 = 0.99886f * b0 + white * 0.0555179f
        b1 = 0.99332f * b1 + white * 0.0750759f
        b2 = 0.96900f * b2 + white * 0.1538520f
        b3 = 0.86650f * b3 + white * 0.3104856f
        b4 = 0.55000f * b4 + white * 0.5329522f
        b5 = -0.7616f * b5 - white * 0.0168980f
        val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f
        b6 = white * 0.115926f
        return (pink * 0.11f).coerceIn(-1.0f, 1.0f)
    }

    private fun generateBrownSample(random: Random): Float {
        val white = random.nextFloat() * 2f - 1f
        lastBrownSample = (lastBrownSample + (0.02f * white)) / 1.02f
        return (lastBrownSample * 3.5f).coerceIn(-1.0f, 1.0f)
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

    private fun updateNotification() {
        if (_isPlayingState.value) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
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
        val noiseTypeName = _noiseTypeState.value.displayName
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
            .setContentTitle(noiseTypeName)
            .setContentText("$noiseTypeName playing in background")
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
