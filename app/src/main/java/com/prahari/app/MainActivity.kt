package com.prahari.app

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("PrahariPrefs", MODE_PRIVATE) }
    private var showCancelDialog by mutableStateOf(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SHOW_SOS_DIALOG") showCancelDialog = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, IntentFilter("SHOW_SOS_DIALOG"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, IntentFilter("SHOW_SOS_DIALOG"))
        }

        val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        permissionLauncher.launch(arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS
        ))

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { AppUI() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    @Composable
    fun AppUI() {
        var number by remember { mutableStateOf(prefs.getString("phone", "") ?: "") }
        var selectedMac by remember { mutableStateOf("") }
        var devices by remember { mutableStateOf(listOf<Pair<String, String>>()) }
        var timeLeft by remember { mutableStateOf(30) }

        LaunchedEffect(Unit) {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            try {
                devices = btManager.adapter.bondedDevices.map { it.name to it.address }
                if (devices.isNotEmpty()) selectedMac = devices[0].second
            } catch (e: SecurityException) { }
        }

        if (showCancelDialog) {
            LaunchedEffect(showCancelDialog) {
                timeLeft = 30
                while (timeLeft > 0 && showCancelDialog) {
                    delay(1000)
                    timeLeft--
                }
            }
            AlertDialog(
                onDismissRequest = { },
                title = { Text("SOS Triggered!") },
                text = { Text("Sending alerts in $timeLeft seconds...") },
                confirmButton = {
                    Button(onClick = { showCancelDialog = false }) { Text("Proceed Now") }
                },
                dismissButton = {
                    Button(onClick = {
                        sendBroadcast(Intent("CANCEL_SOS"))
                        showCancelDialog = false
                    }) { Text("CANCEL") }
                }
            )
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text("Prahari Setup", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            
            OutlinedTextField(
                value = number,
                onValueChange = { number = it; prefs.edit().putString("phone", it).apply() },
                label = { Text("Emergency Number") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(Modifier.height(16.dp))
            Text("Paired Bluetooth Devices:")
            devices.forEach { device ->
                Row {
                    RadioButton(selected = selectedMac == device.second, onClick = { selectedMac = device.second })
                    Text("${device.first} (${device.second})")
                }
            }
            
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val intent = Intent(this@MainActivity, PrahariService::class.java).apply { putExtra("MAC", selectedMac) }
                    startForegroundService(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Start Prahari Service") }
        }
    }
}
