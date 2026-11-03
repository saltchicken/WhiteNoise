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
        volumeSeekBar = findViewById(R.id.volumeSeekBar)

        playToggleButton.setOnClickListener {
            togglePlayback()
        }

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
        if (isPlaying) {
            statusText.setText(R.string.status_playing)
            playToggleButton.setText(R.string.action_stop)
        } else {
            statusText.setText(R.string.status_stopped)
            playToggleButton.setText(R.string.action_start)
        }
    }
}
