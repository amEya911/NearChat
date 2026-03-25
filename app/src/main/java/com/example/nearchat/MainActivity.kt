package com.example.nearchat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.nearchat.data.datasource.LocalUserDataSource
import com.example.nearchat.navigation.AppNavigation
import com.example.nearchat.ui.component.BluetoothRequiredOverlay
import com.example.nearchat.ui.theme.NearChatTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var localUserDataSource: LocalUserDataSource

    private var bluetoothEnabled by mutableStateOf(false)

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
                bluetoothEnabled = state == BluetoothAdapter.STATE_ON
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check initial Bluetooth state
        bluetoothEnabled = bluetoothAdapter?.isEnabled == true

        // Register for Bluetooth state changes
        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )

        setContent {
            NearChatTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(localUserDataSource = localUserDataSource)

                    // Show blocking overlay if BT is off and user is logged in
                    if (!bluetoothEnabled && localUserDataSource.isLoggedIn()) {
                        BluetoothRequiredOverlay(
                            onEnableClicked = {
                                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check Bluetooth state on every resume
        bluetoothEnabled = bluetoothAdapter?.isEnabled == true
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) {}
    }
}