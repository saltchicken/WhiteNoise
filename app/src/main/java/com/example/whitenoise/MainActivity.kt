package com.example.whitenoise

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var playToggleButton: MaterialButton
    private lateinit var noiseColorLabel: TextView
    private lateinit var noiseColorSeekBar: SeekBar
    private lateinit var volumeSeekBar: SeekBar

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        WhiteNoiseService.startService(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        statusText = findViewById(R.id.statusText)
        playToggleButton = findViewById(R.id.playToggleButton)
        noiseColorLabel = findViewById(R.id.noiseColorLabel)
        noiseColorSeekBar = findViewById(R.id.noiseColorSeekBar)
        volumeSeekBar = findViewById(R.id.volumeSeekBar)

        playToggleButton.setOnClickListener {
            togglePlayback()
        }

        noiseColorSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val color = progress / 100f
                    WhiteNoiseService.setNoiseColor(this@MainActivity, color)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val volume = progress / 100f
                    WhiteNoiseService.setVolume(this@MainActivity, volume)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        observeServiceState()
    }

    private fun togglePlayback() {
        if (WhiteNoiseService.isPlayingState.value) {
            WhiteNoiseService.stopService(this)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                WhiteNoiseService.startService(this)
            }
        }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    WhiteNoiseService.isPlayingState.collect { isPlaying ->
                        updateUiState(isPlaying)
                    }
                }
                launch {
                    WhiteNoiseService.noiseColorState.collect { color ->
                        val progress = (color * 100).toInt()
                        if (noiseColorSeekBar.progress != progress) {
                            noiseColorSeekBar.progress = progress
                        }
                        val colorName = WhiteNoiseService.getNoiseColorName(this@MainActivity, color)
                        noiseColorLabel.text = getString(R.string.label_noise_color_format, colorName)
                        updateUiState(WhiteNoiseService.isPlayingState.value)
                    }
                }
                launch {
                    WhiteNoiseService.volumeState.collect { volume ->
                        val progress = (volume * 100).toInt()
                        if (volumeSeekBar.progress != progress) {
                            volumeSeekBar.progress = progress
                        }
                    }
                }
            }
        }
    }

    private fun updateUiState(isPlaying: Boolean) {
        val colorName = WhiteNoiseService.getNoiseColorName(this, WhiteNoiseService.noiseColorState.value)
        if (isPlaying) {
            statusText.text = getString(R.string.status_playing_format, colorName)
            playToggleButton.setText(R.string.action_stop)
        } else {
            statusText.setText(R.string.status_stopped)
            playToggleButton.setText(R.string.action_start)
        }
    }
}
