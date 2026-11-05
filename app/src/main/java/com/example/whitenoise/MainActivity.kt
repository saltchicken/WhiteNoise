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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var playToggleButton: MaterialButton
    private lateinit var noiseColorLabel: TextView
    private lateinit var noiseColorSeekBar: SeekBar
    private lateinit var oscillationSwitch: MaterialSwitch
    private lateinit var oscillationRangeLabel: TextView
    private lateinit var oscillationRangeSlider: RangeSlider
    private lateinit var oscillationSpeedLabel: TextView
    private lateinit var oscillationSpeedSeekBar: SeekBar
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

        oscillationSwitch = findViewById(R.id.oscillationSwitch)
        oscillationRangeLabel = findViewById(R.id.oscillationRangeLabel)
        oscillationRangeSlider = findViewById(R.id.oscillationRangeSlider)
        oscillationSpeedLabel = findViewById(R.id.oscillationSpeedLabel)
        oscillationSpeedSeekBar = findViewById(R.id.oscillationSpeedSeekBar)

        volumeSeekBar = findViewById(R.id.volumeSeekBar)

        // Set initial values on range slider
        oscillationRangeSlider.setValues(20f, 80f)

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

        oscillationSwitch.setOnCheckedChangeListener { _, isChecked ->
            WhiteNoiseService.setOscillationEnabled(this, isChecked)
        }

        oscillationRangeSlider.addOnChangeListener { slider, _, fromUser ->
            if (fromUser) {
                val values = slider.values
                if (values.size == 2) {
                    val min = values[0] / 100f
                    val max = values[1] / 100f
                    WhiteNoiseService.setOscillationRange(this@MainActivity, min, max)
                }
            }
        }

        oscillationSpeedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speedSeconds = progress + 3f
                if (fromUser) {
                    WhiteNoiseService.setOscillationSpeed(this@MainActivity, speedSeconds)
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
                    WhiteNoiseService.isOscillationEnabledState.collect { enabled ->
                        if (oscillationSwitch.isChecked != enabled) {
                            oscillationSwitch.isChecked = enabled
                        }
                        noiseColorSeekBar.isEnabled = !enabled
                        updateUiState(WhiteNoiseService.isPlayingState.value)
                    }
                }
                launch {
                    WhiteNoiseService.oscillationMinState.collect { updateOscillationRangeUi() }
                }
                launch {
                    WhiteNoiseService.oscillationMaxState.collect { updateOscillationRangeUi() }
                }
                launch {
                    WhiteNoiseService.oscillationSpeedState.collect { speed ->
                        val speedSec = speed.toInt()
                        oscillationSpeedLabel.text = getString(R.string.label_oscillation_speed_format, speedSec)
                        val progress = (speedSec - 3).coerceIn(0, 27)
                        if (oscillationSpeedSeekBar.progress != progress) {
                            oscillationSpeedSeekBar.progress = progress
                        }
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

    private fun updateOscillationRangeUi() {
        val min = WhiteNoiseService.oscillationMinState.value
        val max = WhiteNoiseService.oscillationMaxState.value
        val minPct = (min * 100).toInt()
        val maxPct = (max * 100).toInt()
        val minName = WhiteNoiseService.getNoiseColorName(this, min)
        val maxName = WhiteNoiseService.getNoiseColorName(this, max)
        oscillationRangeLabel.text = getString(R.string.label_oscillation_range_format, minPct, minName, maxPct, maxName)

        val targetMin = min * 100f
        val targetMax = max * 100f
        val currentValues = oscillationRangeSlider.values
        if (currentValues.size == 2 && (currentValues[0] != targetMin || currentValues[1] != targetMax)) {
            oscillationRangeSlider.setValues(targetMin, targetMax)
        }
        updateUiState(WhiteNoiseService.isPlayingState.value)
    }

    private fun updateUiState(isPlaying: Boolean) {
        if (isPlaying) {
            if (WhiteNoiseService.isOscillationEnabledState.value) {
                val minPct = (WhiteNoiseService.oscillationMinState.value * 100).toInt()
                val maxPct = (WhiteNoiseService.oscillationMaxState.value * 100).toInt()
                statusText.text = getString(R.string.status_playing_oscillation_format, minPct, maxPct)
            } else {
                val colorName = WhiteNoiseService.getNoiseColorName(this, WhiteNoiseService.noiseColorState.value)
                statusText.text = getString(R.string.status_playing_format, colorName)
            }
            playToggleButton.setText(R.string.action_stop)
        } else {
            statusText.setText(R.string.status_stopped)
            playToggleButton.setText(R.string.action_start)
        }
    }
}
