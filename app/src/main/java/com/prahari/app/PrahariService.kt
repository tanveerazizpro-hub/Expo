package com.prahari.app

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothManager
import android.content.*
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.telephony.SmsManager
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class PrahariService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var isCancelled = false
    private var recorder: MediaRecorder? = null

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "CANCEL_SOS") isCancelled = true
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver, IntentFilter("CANCEL_SOS"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cancelReceiver, IntentFilter("CANCEL_SOS"))
        }
        createNotificationChannel()
        startForeground(1, Notification.Builder(this, "prahari_chan")
            .setContentTitle("Prahari Active")
            .setContentText("Listening for HC-05...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mac = intent?.getStringExtra("MAC") ?: return START_NOT_STICKY
        startBluetoothListener(mac)
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothListener(mac: String) {
        scope.launch {
            try {
                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val device = btManager.adapter.getRemoteDevice(mac)
                val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                val socket = device.createRfcommSocketToServiceRecord(uuid)
                socket.connect()
                
                while (isActive) {
                    if (socket.inputStream.read().toChar() == 'S') {
                        triggerSOS()
                        break 
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun triggerSOS() {
        isCancelled = false
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        sendBroadcast(Intent("SHOW_SOS_DIALOG"))

        for (i in 1..30) {
            delay(1000)
            if (isCancelled) return
        }

        vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        val phone = getSharedPreferences("PrahariPrefs", MODE_PRIVATE).getString("phone", "") ?: return

        val locManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
            ?: locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        
        val msg = if (loc != null) "SOS! Help me. Location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}" 
                  else "SOS! Help me. (Location unavailable)"

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        smsManager.sendTextMessage(phone, null, msg, null, null)

        startRecording()
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })

        delay(5 * 60 * 1000)
        stopRecording()
    }

    private fun startRecording() {
        val file = File(getExternalFilesDir(null), "sos_audio.3gp")
        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(file.absolutePath)
            prepare(); start()
        }
    }

    private fun stopRecording() {
        try { recorder?.stop(); recorder?.release() } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel("prahari_chan", "Prahari Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        unregisterReceiver(cancelReceiver)
        stopRecording()
    }
}
