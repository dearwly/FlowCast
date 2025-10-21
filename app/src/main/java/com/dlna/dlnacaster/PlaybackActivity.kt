package com.dlna.dlnacaster

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import org.fourthline.cling.android.AndroidUpnpService
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.model.action.ActionInvocation
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.model.meta.Device
import org.fourthline.cling.model.meta.Service
import org.fourthline.cling.model.types.UDN
import org.fourthline.cling.model.types.UDAServiceType
import org.fourthline.cling.support.avtransport.callback.*
import org.fourthline.cling.support.model.PositionInfo
import org.fourthline.cling.support.renderingcontrol.callback.GetVolume
import org.fourthline.cling.support.renderingcontrol.callback.SetVolume
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

class PlaybackActivity : AppCompatActivity() {

    private val TAG = "PlaybackActivity"
    private var upnpService: AndroidUpnpService? = null
    private var avTransportService: Service<*, *>? = null
    private var renderingControlService: Service<*, *>? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isTrackingTouch = false
    private var isUiPlaying = true

    private lateinit var mediaTitle: TextView
    private lateinit var deviceName: TextView
    private lateinit var playPauseButton: FloatingActionButton
    private lateinit var stopButton: ImageButton
    private lateinit var volumeButton: ImageButton
    private lateinit var volumeControlsContainer: LinearLayout
    private lateinit var playbackControlsContainer: LinearLayout
    private lateinit var volumeUpButton: ImageButton
    private lateinit var volumeDownButton: ImageButton
    private lateinit var volumeSlider: Slider
    private lateinit var progressSlider: Slider
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var currentVolumeText: TextView

    private val uiUpdateServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            upnpService = service as AndroidUpnpService
            Log.d(TAG, "UI Update Cling service connected.")
            val sharedPreferences = getSharedPreferences(CASTING_STATE_PREFS, Context.MODE_PRIVATE)
            val udnString = sharedPreferences.getString(KEY_DEVICE_UDN, null)

            if (udnString != null) {
                val udn = UDN.valueOf(udnString)
                val device = upnpService?.registry?.getDevice(udn, true)
                if (device != null) {
                    findServices(device)
                    startPolling()
                    getCurrentVolume()
                } else {
                    Toast.makeText(this@PlaybackActivity, R.string.cast_playback_lost_connection, Toast.LENGTH_SHORT).show()
                    stopCastingAndFinish()
                }
            }
        }
        override fun onServiceDisconnected(className: ComponentName) {
            upnpService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_playback)

        window?.let {
            val layoutParams = WindowManager.LayoutParams()
            layoutParams.copyFrom(it.attributes)
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.gravity = Gravity.CENTER
            it.attributes = layoutParams
        }

        bindViews()
        setupListeners()
        progressSlider.isEnabled = false

        val sharedPreferences = getSharedPreferences(CASTING_STATE_PREFS, Context.MODE_PRIVATE)
        mediaTitle.text = sharedPreferences.getString(KEY_MEDIA_TITLE, R.string.loading.toString())
        deviceName.text = getString(R.string.cast_playback_casting, sharedPreferences.getString(KEY_DEVICE_NAME, R.string.loading.toString()))


        bindService(Intent(this, AndroidUpnpServiceImpl::class.java), uiUpdateServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun play() {
        sendCommandToService(PlaybackService.ACTION_PLAY)
        isUiPlaying = true
        playPauseButton.setImageResource(R.drawable.ic_pause)
    }
    private fun pause() {
        sendCommandToService(PlaybackService.ACTION_PAUSE)
        isUiPlaying = false
        playPauseButton.setImageResource(R.drawable.ic_play)
    }
    private fun stopCastingAndFinish() {
        stopPolling()
        sendCommandToService(PlaybackService.ACTION_STOP)
        finish()
    }
    private fun sendCommandToService(action: String) {
        val intent = Intent(this, PlaybackService::class.java)
        intent.action = action
        startService(intent)
    }

    private fun setupListeners() {
        playPauseButton.setImageResource(R.drawable.ic_pause)

        playPauseButton.setOnClickListener {
            if (isUiPlaying) {
                pause()
            } else {
                play()
            }
        }
        stopButton.setOnClickListener { stopCastingAndFinish() }

        volumeButton.setOnClickListener { toggleVolumeControls() }
        volumeUpButton.setOnClickListener { changeVolume(volumeSlider.value.toInt() + 5) }
        volumeDownButton.setOnClickListener { changeVolume(volumeSlider.value.toInt() - 5) }
        volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) changeVolume(value.toInt())
            updateVolumeUI(value.toInt())
        }

        progressSlider.setLabelFormatter { value: Float ->
            val seconds = value.roundToLong()
            val minutes = TimeUnit.SECONDS.toMinutes(seconds)
            val remainingSeconds = seconds - TimeUnit.MINUTES.toSeconds(minutes)
            String.format("%d:%02d", minutes, remainingSeconds)
        }

        progressSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isTrackingTouch = true
            }
            override fun onStopTrackingTouch(slider: Slider) {
                isTrackingTouch = false
                seek(secondsToSeekString(slider.value.toLong()))
            }
        })
    }

    private fun seek(time: String) {
        if (avTransportService == null) return
        val callback = object : Seek(avTransportService, time) {
            override fun success(invocation: ActionInvocation<out Service<*, *>>?) { Log.d(TAG, "Seek successful.") }
            override fun failure(invocation: ActionInvocation<out Service<*, *>>?, op: UpnpResponse?, defaultMsg: String?) { Log.e(TAG, "Seek failed: $defaultMsg") }
        }
        upnpService?.controlPoint?.execute(callback)
    }
    private fun changeVolume(newVolume: Int) {
        if (renderingControlService == null) return
        val volume = newVolume.coerceIn(0, 100)
        val callback = object : SetVolume(renderingControlService, volume.toLong()) {
            override fun success(invocation: ActionInvocation<out Service<*, *>>?) {}
            override fun failure(invocation: ActionInvocation<out Service<*, *>>?, op: UpnpResponse?, defaultMsg: String?) { Log.e(TAG, "Set volume failed: $defaultMsg") }
        }
        upnpService?.controlPoint?.execute(callback)
    }
    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        if (upnpService != null) {
            unbindService(uiUpdateServiceConnection)
        }
    }
    private fun bindViews() {
        mediaTitle = findViewById(R.id.tv_media_title)
        deviceName = findViewById(R.id.tv_device_name)
        playPauseButton = findViewById(R.id.btn_play_pause)
        stopButton = findViewById(R.id.btn_stop)
        volumeButton = findViewById(R.id.btn_volume)
        volumeControlsContainer = findViewById(R.id.volume_controls_container)
        playbackControlsContainer = findViewById(R.id.playback_controls_container)
        volumeUpButton = findViewById(R.id.btn_volume_up)
        volumeDownButton = findViewById(R.id.btn_volume_down)
        volumeSlider = findViewById(R.id.slider_volume)
        progressSlider = findViewById(R.id.slider_progress)
        currentTimeText = findViewById(R.id.tv_current_time)
        totalTimeText = findViewById(R.id.tv_total_time)
        currentVolumeText = findViewById(R.id.tv_current_volume)
    }
    private fun findServices(device: Device<*, *, *>) {
        avTransportService = device.findService(UDAServiceType("AVTransport"))
        renderingControlService = device.findService(UDAServiceType("RenderingControl"))
    }
    private val positionPollingRunnable = object : Runnable {
        override fun run() {
            getPositionInfo()
            if (isDestroyed || isFinishing) return
            handler.postDelayed(this, 1000)
        }
    }
    private fun startPolling() {
        handler.post(positionPollingRunnable)
    }
    private fun stopPolling() {
        handler.removeCallbacks(positionPollingRunnable)
    }
    private fun getPositionInfo() {
        if (avTransportService == null) return
        val callback = object : GetPositionInfo(avTransportService) {
            override fun received(invocation: ActionInvocation<out Service<*, *>>?, positionInfo: PositionInfo) {
                runOnUiThread { updatePositionUI(positionInfo) }
            }
            override fun failure(invocation: ActionInvocation<out Service<*, *>>?, op: UpnpResponse?, defaultMsg: String?) {
                Log.w(TAG, "Get position info failed: $defaultMsg")
            }
        }
        upnpService?.controlPoint?.execute(callback)
    }
    private fun getCurrentVolume() {
        if (renderingControlService == null) return
        val callback = object : GetVolume(renderingControlService) {
            override fun received(invocation: ActionInvocation<out Service<*,*>>?, currentVolume: Int) {
                runOnUiThread { updateVolumeUI(currentVolume) }
            }
            override fun failure(invocation: ActionInvocation<out Service<*,*>>?, op: UpnpResponse?, defaultMsg: String?) {
                Log.w(TAG, "Get volume failed: $defaultMsg")
            }
        }
        upnpService?.controlPoint?.execute(callback)
    }
    private fun updatePositionUI(positionInfo: PositionInfo) {
        val totalDurationSeconds = positionInfo.trackDurationSeconds
        val elapsedSeconds = positionInfo.trackElapsedSeconds
        if (totalDurationSeconds > 0) {
            if (!progressSlider.isEnabled) {
                progressSlider.isEnabled = true
            }
            progressSlider.valueTo = totalDurationSeconds.toFloat()
            if (!isTrackingTouch) {
                progressSlider.value = elapsedSeconds.toFloat()
            }
        } else {
            progressSlider.isEnabled = false
        }
        currentTimeText.text = secondsToTimeString(elapsedSeconds)
        totalTimeText.text = secondsToTimeString(totalDurationSeconds)
    }
    private fun updateVolumeUI(volume: Int) {
        if (!volumeSlider.isPressed) {
            volumeSlider.value = volume.toFloat()
        }
        currentVolumeText.text = volume.toString()
        when {
            volume == 0 -> volumeButton.setImageResource(R.drawable.ic_volume_mute)
            volume < 50 -> volumeButton.setImageResource(R.drawable.ic_volume_down)
            else -> volumeButton.setImageResource(R.drawable.ic_volume_up)
        }
    }
    private fun toggleVolumeControls() {
        val isVolumeVisible = volumeControlsContainer.isVisible
        if (isVolumeVisible) {
            volumeControlsContainer.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_out))
            playbackControlsContainer.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in))
            volumeControlsContainer.visibility = View.GONE
            playbackControlsContainer.visibility = View.VISIBLE
        } else {
            getCurrentVolume()
            volumeControlsContainer.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in))
            playbackControlsContainer.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_out))
            volumeControlsContainer.visibility = View.VISIBLE
            playbackControlsContainer.visibility = View.GONE
        }
    }
    private fun secondsToTimeString(seconds: Long): String {
        if (seconds < 0) return "00:00"
        val hours = TimeUnit.SECONDS.toHours(seconds)
        val minutes = TimeUnit.SECONDS.toMinutes(seconds) - TimeUnit.HOURS.toMinutes(hours)
        val secs = seconds - TimeUnit.MINUTES.toSeconds(minutes) - TimeUnit.HOURS.toSeconds(hours)
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%02d:%02d", minutes, secs)
        }
    }
    private fun secondsToSeekString(seconds: Long): String {
        if (seconds < 0) return "00:00:00"
        val hours = TimeUnit.SECONDS.toHours(seconds)
        val minutes = TimeUnit.SECONDS.toMinutes(seconds) - TimeUnit.HOURS.toMinutes(hours)
        val secs = seconds - TimeUnit.MINUTES.toSeconds(minutes) - TimeUnit.HOURS.toSeconds(hours)
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
    companion object {
        const val CASTING_STATE_PREFS = "CastingStatePrefs"
        const val KEY_IS_CASTING = "is_casting"
        const val KEY_DEVICE_UDN = "device_udn"
        const val KEY_MEDIA_TITLE = "media_title"
        const val KEY_DEVICE_NAME = "device_name"
    }
}