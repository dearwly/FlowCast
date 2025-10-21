package com.dlna.dlnacaster

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.fourthline.cling.android.AndroidUpnpService
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.model.action.ActionInvocation
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.model.meta.Device
import org.fourthline.cling.model.types.UDN
import org.fourthline.cling.model.types.UDAServiceType
import org.fourthline.cling.support.avtransport.callback.Pause
import org.fourthline.cling.support.avtransport.callback.Play
import org.fourthline.cling.support.avtransport.callback.Stop

class PlaybackService : Service() {

    private val TAG = "PlaybackService"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "PlaybackServiceChannel"

    private var upnpService: AndroidUpnpService? = null
    private var avTransportService: org.fourthline.cling.model.meta.Service<*, *>? = null
    private var isPlaying = true

    private var mediaTitle: String? = null
    private var deviceName: String? = null
    private var deviceUdn: UDN? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            upnpService = service as AndroidUpnpService
            Log.d(TAG, "Cling service connected in PlaybackService.")
            deviceUdn?.let {
                val device = upnpService?.registry?.getDevice(it, true)
                if (device != null) {
                    findAvTransportService(device)
                } else {
                    Log.e(TAG, "Device not found after service connected.")
                    stopSelf()
                }
            }
        }
        override fun onServiceDisconnected(className: ComponentName) {
            upnpService = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bindService(Intent(this, AndroidUpnpServiceImpl::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                mediaTitle = intent.getStringExtra("mediaTitle")
                deviceName = intent.getStringExtra("deviceName")
                val udnString = intent.getStringExtra("deviceUdn")
                if (udnString != null) {
                    deviceUdn = UDN.valueOf(udnString)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "POST_NOTIFICATIONS permission not granted. Cannot start foreground service.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_PLAY -> executePlay()
            ACTION_PAUSE -> executePause()
            ACTION_STOP -> executeStop()
        }

        return START_NOT_STICKY
    }

    private fun findAvTransportService(device: Device<*, *, *>) {
        avTransportService = device.findService(UDAServiceType("AVTransport"))
        if (avTransportService == null) {
            Log.e(TAG, "AVTransport service not found on the device!")
            stopSelf()
        }
    }

    private fun executePlay() {
        if (avTransportService == null) return
        val callback = object : Play(avTransportService) {
            override fun success(invocation: ActionInvocation<out org.fourthline.cling.model.meta.Service<*, *>>?) {
                Log.d(TAG, "Service Play command successful.")
                isPlaying = true
                updateNotification()
            }
            override fun failure(invocation: ActionInvocation<out org.fourthline.cling.model.meta.Service<*, *>>?, op: UpnpResponse?, defaultMsg: String?) {
                Log.e(TAG, "Service Play failed: $defaultMsg")
            }
        }
        upnpService?.controlPoint?.execute(callback)
    }

    private fun executePause() {
        if (avTransportService == null) return
        val callback = object : Pause(avTransportService) {
            override fun success(invocation: ActionInvocation<out org.fourthline.cling.model.meta.Service<*, *>>?) {
                Log.d(TAG, "Service Pause command successful.")
                isPlaying = false
                updateNotification()
            }
            override fun failure(invocation: ActionInvocation<out org.fourthline.cling.model.meta.Service<*, *>>?, op: UpnpResponse?, defaultMsg: String?) {
                Log.e(TAG, "Service Pause failed: $defaultMsg")
            }
        }
        upnpService?.controlPoint?.execute(callback)
    }

    private fun executeStop() {
        if (avTransportService == null) {
            clearCastingStateAndStop()
            return
        }
        val callback = object : Stop(avTransportService) {
            override fun success(invocation: ActionInvocation<out org.fourthline.cling.model.meta.Service<*, *>>?) {
                Log.d(TAG, "Service Stop command successful.")
                clearCastingStateAndStop()
            }
            override fun failure(invocation: ActionInvocation<out org.fourthline.cling.model.meta.Service<*, *>>?, op: UpnpResponse?, defaultMsg: String?) {
                Log.e(TAG, "Service Stop failed: $defaultMsg")
                clearCastingStateAndStop()
            }
        }
        upnpService?.controlPoint?.execute(callback)
    }

    private fun clearCastingStateAndStop() {
        val sharedPreferences = getSharedPreferences(PlaybackActivity.CASTING_STATE_PREFS, Context.MODE_PRIVATE)
        sharedPreferences.edit().clear().apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun updateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val playbackActivityIntent = Intent(this, PlaybackActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val contentPendingIntent = PendingIntent.getActivity(this, 0, playbackActivityIntent, pendingIntentFlags)

        val playPauseAction: NotificationCompat.Action
        if (isPlaying) {
            val pauseIntent = Intent(this, PlaybackService::class.java).apply { action = ACTION_PAUSE }
            val pausePendingIntent = PendingIntent.getService(this, 1, pauseIntent, pendingIntentFlags)
            playPauseAction = NotificationCompat.Action(R.drawable.ic_pause, R.string.pause.toString(), pausePendingIntent)
        } else {
            val playIntent = Intent(this, PlaybackService::class.java).apply { action = ACTION_PLAY }
            val playPendingIntent = PendingIntent.getService(this, 2, playIntent, pendingIntentFlags)
            playPauseAction = NotificationCompat.Action(R.drawable.ic_play, R.string.play.toString(), playPendingIntent)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(mediaTitle ?: R.string.cast_playback_isplaying.toString())
            .setContentText(getString(R.string.cast_playback_casting, deviceName ?: R.string.cast_playback_unknown.toString()))
            .setSmallIcon(R.drawable.ic_cast)
            .setContentIntent(contentPendingIntent)
            .addAction(playPauseAction)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (upnpService != null) {
            unbindService(serviceConnection)
        }
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_PLAY = "ACTION_PLAY"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_STOP = "ACTION_STOP"
    }
}