package com.dlna.dlnacaster

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.fourthline.cling.android.AndroidUpnpService
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.model.action.ActionInvocation
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.model.meta.Device
import org.fourthline.cling.model.meta.LocalDevice
import org.fourthline.cling.model.meta.RemoteDevice
import org.fourthline.cling.model.meta.Service
import org.fourthline.cling.model.types.UDAServiceType
import org.fourthline.cling.registry.DefaultRegistryListener
import org.fourthline.cling.registry.Registry
import org.fourthline.cling.registry.RegistryListener
import org.fourthline.cling.support.avtransport.callback.Play
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI
import org.fourthline.cling.support.contentdirectory.DIDLParser
import org.fourthline.cling.support.model.DIDLContent
import org.fourthline.cling.support.model.ProtocolInfo
import org.fourthline.cling.support.model.Res
import org.fourthline.cling.support.model.item.ImageItem
import org.fourthline.cling.support.model.item.MusicTrack
import org.fourthline.cling.support.model.item.VideoItem
import java.net.InetAddress
import android.view.Gravity
import android.view.Window

class MainActivity : AppCompatActivity() {

    private val TAG = "DlnaCasterApp_CLING"
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var devicesListView: ListView
    private lateinit var deviceListAdapter: ArrayAdapter<DeviceDisplay>
    private var upnpService: AndroidUpnpService? = null
    private lateinit var registryListener: RegistryListener
    private var mediaServer: MediaServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var latestSelectedDevice: Device<*, *, *>? = null

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                Log.d(TAG, "All media permissions granted.")
                showMediaTypeChoiceDialog()
            } else {
                Log.w(TAG, "Media permissions were denied.")
                Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_SHORT).show()
            }
        }

    private val imageVideoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri -> latestSelectedDevice?.let { device -> castMedia(uri, device) } }
            }
        }

    private val musicPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri -> latestSelectedDevice?.let { device -> castMedia(uri, device) } }
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            upnpService = service as AndroidUpnpService
            Log.e(TAG, "--- CLING Service Connected! ---")
            registryListener = BrowseRegistryListener()
            upnpService?.registry?.addListener(registryListener)

            swipeRefreshLayout.isRefreshing = true
            searchDevices()
        }
        override fun onServiceDisconnected(className: ComponentName) {
            upnpService = null
            Log.e(TAG, "--- CLING Service Disconnected ---")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)
        Log.e(TAG, "--- MainActivity onCreate() ---")

        window?.let {
            val layoutParams = WindowManager.LayoutParams()
            layoutParams.copyFrom(it.attributes)

            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.gravity = Gravity.CENTER

            // apple new attributes
            it.attributes = layoutParams
        }


        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("dlnacaster-cling-lock").apply {
            setReferenceCounted(true)
            acquire()
        }
        Log.e(TAG, "--- Multicast Lock Acquired ---")

        setupUI()

        Log.e(TAG, "--- Binding to official Cling Service... ---")
        val intent = Intent(this, AndroidUpnpServiceImpl::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        mediaServer = MediaServer(this)
        mediaServer?.start()
    }

    private fun setupUI() {
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        devicesListView = findViewById(R.id.lv_devices)
        deviceListAdapter = ArrayAdapter(this, R.layout.list_item_device)
        devicesListView.adapter = deviceListAdapter

        swipeRefreshLayout.setOnRefreshListener {
            Log.e(TAG, "--- ACTION: Pull to refresh triggered ---")
            searchDevices()
        }

        devicesListView.setOnItemClickListener { _, _, position, _ ->
            val deviceDisplay = deviceListAdapter.getItem(position) ?: return@setOnItemClickListener
            latestSelectedDevice = deviceDisplay.device
            Toast.makeText(this, getString(R.string.device_selected, deviceDisplay.device.displayString), Toast.LENGTH_SHORT).show()
            checkAndRequestPermissions()
        }
    }

    private fun searchDevices() {
        if (upnpService == null) {
            swipeRefreshLayout.isRefreshing = false
            return
        }
        deviceListAdapter.clear()
        upnpService?.registry?.removeAllRemoteDevices()
        upnpService?.controlPoint?.search()

        Handler(Looper.getMainLooper()).postDelayed({
            if (swipeRefreshLayout.isRefreshing) {
                swipeRefreshLayout.isRefreshing = false
            }
        }, 10000)
    }


    private fun checkAndRequestPermissions() {
        val permissionsNotGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsNotGranted.isEmpty()) {
            showMediaTypeChoiceDialog()
        } else {
            requestPermissionsLauncher.launch(permissionsNotGranted.toTypedArray())
        }
    }

    private fun showMediaTypeChoiceDialog() {
        val options = arrayOf(getString(R.string.media_type_image_video), getString(R.string.media_type_music))
        AlertDialog.Builder(this)
            .setTitle(R.string.media_type_choice_title)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> openImageVideoPicker()
                    1 -> openMusicPicker()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }


    private fun openImageVideoPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }
        imageVideoPickerLauncher.launch(intent)
    }

    private fun openMusicPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        musicPickerLauncher.launch(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.e(TAG, "--- MainActivity onDestroy() ---")
        multicastLock?.takeIf { it.isHeld }?.release()
        mediaServer?.stop()
        if (upnpService != null) {
            try {
                upnpService!!.registry.removeListener(registryListener)
                unbindService(serviceConnection)
                Log.e(TAG, "Successfully unbound Cling Service.")
            } catch (ex: Exception) {
                Log.e(TAG, "Error during unbind/removeListener: ${ex.message}")
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ip = wifiManager.connectionInfo.ipAddress
        return if (ip == 0) null else try {
            InetAddress.getByAddress(
                byteArrayOf(
                    (ip and 0xff).toByte(), (ip shr 8 and 0xff).toByte(),
                    (ip shr 16 and 0xff).toByte(), (ip shr 24 and 0xff).toByte()
                )
            ).hostAddress
        } catch (e: Exception) { null }
    }

    private fun castMedia(mediaUri: Uri, device: Device<*, *, *>) {
        val controlPoint = upnpService?.controlPoint ?: return
        val avTransportService = device.findService(UDAServiceType("AVTransport")) ?: run {
            Toast.makeText(this, R.string.device_not_support_avtransport, Toast.LENGTH_SHORT).show()
            return
        }

        val localIp = getLocalIpAddress()
        if (localIp == null) {
            Toast.makeText(this, R.string.failed_to_get_ip, Toast.LENGTH_LONG).show()
            return
        }
        val mediaUrl = "http://$localIp:${mediaServer?.listeningPort}/${MediaServer.URI_PATH}"
        mediaServer?.setMediaUri(mediaUri)

        val metadata = generateMetadata(mediaUrl, mediaUri)

        val setUriCallback = object : SetAVTransportURI(avTransportService, mediaUrl, metadata) {
            override fun success(invocation: ActionInvocation<out Service<*, *>>?) {
                Log.d(TAG, "SetAVTransportURI successful")
                val playCallback = object : Play(avTransportService) {
                    override fun success(invocation: ActionInvocation<out Service<*, *>>?) {
                        runOnUiThread { Toast.makeText(this@MainActivity, R.string.cast_success, Toast.LENGTH_SHORT).show() }
                    }
                    override fun failure(invocation: ActionInvocation<out Service<*, *>>?, op: UpnpResponse?, defaultMsg: String?) {
                        runOnUiThread { Toast.makeText(this@MainActivity, getString(R.string.cast_failed, defaultMsg), Toast.LENGTH_LONG).show() }
                    }
                }
                controlPoint.execute(playCallback)
            }
            override fun failure(invocation: ActionInvocation<out Service<*, *>>?, op: UpnpResponse?, defaultMsg: String?) {
                runOnUiThread { Toast.makeText(this@MainActivity, getString(R.string.set_uri_failed, defaultMsg), Toast.LENGTH_LONG).show() }
            }
        }
        controlPoint.execute(setUriCallback)
    }

    private fun generateMetadata(mediaUrl: String, mediaUri: Uri): String {
        val mimeType = contentResolver.getType(mediaUri) ?: "application/octet-stream"
        val fileSize = getFileSize(mediaUri)
        val fileName = getFileName(mediaUri)

        val res = Res(ProtocolInfo("http-get:*:$mimeType:*"), fileSize, mediaUrl)
        val didlContent = DIDLContent()

        when {
            mimeType.startsWith("video/") -> {
                val videoItem = VideoItem("1", "0", fileName, "", res)
                didlContent.addItem(videoItem)
            }
            mimeType.startsWith("image/") -> {
                val imageItem = ImageItem("1", "0", fileName, "", res)
                didlContent.addItem(imageItem)
            }
            mimeType.startsWith("audio/") -> {
                val musicTrack = MusicTrack("1", "0", fileName, "", "", "", res)
                didlContent.addItem(musicTrack)
            }
        }

        return try {
            DIDLParser().generate(didlContent, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating metadata", e)
            ""
        }
    }

    private fun getFileSize(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1 && cursor.moveToFirst()) {
                return cursor.getLong(sizeIndex)
            }
        }
        return 0
    }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return "unknown"
    }

    inner class BrowseRegistryListener : DefaultRegistryListener() {
        override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) {
            if (device.type.type == "MediaRenderer") {
                Log.e(TAG, "--- DEVICE DISCOVERED: ${device.displayString} ---")
                deviceAdded(device)
            }
        }
        override fun remoteDeviceRemoved(registry: Registry, device: RemoteDevice) {
            Log.e(TAG, "--- DEVICE REMOVED: ${device.displayString} ---")
            deviceRemoved(device)
        }
        override fun localDeviceAdded(registry: Registry, device: LocalDevice) {
            Log.e(TAG, "--- LOCAL DEVICE ADDED: ${device.displayString} ---")
            deviceAdded(device)
        }
        override fun localDeviceRemoved(registry: Registry, device: LocalDevice) {
            Log.e(TAG, "--- LOCAL DEVICE REMOVED: ${device.displayString} ---")
            deviceRemoved(device)
        }
        fun deviceAdded(device: Device<*, *, *>) {
            runOnUiThread {
                if(swipeRefreshLayout.isRefreshing) {
                    swipeRefreshLayout.isRefreshing = false
                }
                val display = DeviceDisplay(device)
                if (deviceListAdapter.getPosition(display) == -1) {
                    deviceListAdapter.add(display)
                }
            }
        }
        fun deviceRemoved(device: Device<*, *, *>) {
            runOnUiThread { deviceListAdapter.remove(DeviceDisplay(device)) }
        }
    }
}