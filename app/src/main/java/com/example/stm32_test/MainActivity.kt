package com.example.stm32_test

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class MainActivity : AppCompatActivity() {

    // ======================================================
    // 🔹 VARIABEL DEKLARASI
    // ======================================================

    // UI Components
    private lateinit var tvStatus: TextView
    private lateinit var tvTerminal: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnLedOn: Button
    private lateinit var btnLedOff: Button
    private lateinit var btnSend: Button
    private lateinit var btnClear: Button
    private lateinit var etCommand: EditText
    private lateinit var scrollView: ScrollView

    // Bluetooth Components
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var hc05Device: BluetoothDevice? = null

    // Coroutine job
    private var readingJob: Job? = null

    // UUID SPP (Serial Port Profile)
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Nama HC-05 yang dipasangkan di Bluetooth Settings
    private val HC05_NAME = "HC-05"

    private val TAG = "BT_TERMINAL"
    private val PERMISSION_REQUEST_CODE = 100
    private var isConnected = false
    private var permissionsGranted = false

    // ======================================================
    // 🔹 LIFECYCLE
    // ======================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "🚀 STM32 Bluetooth Terminal Started")
        Log.d(TAG, "📱 Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.d(TAG, "🤖 Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        initViews()
        setupBluetooth()
        setupListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔴 App closing - disconnecting...")
        disconnect()
    }

    // ======================================================
    // 🔹 INITIALIZATION
    // ======================================================

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvTerminal = findViewById(R.id.tvTerminal)
        btnConnect = findViewById(R.id.btnConnect)
        btnLedOn = findViewById(R.id.btnLedOn)
        btnLedOff = findViewById(R.id.btnLedOff)
        btnSend = findViewById(R.id.btnSend)
        btnClear = findViewById(R.id.btnClear)
        etCommand = findViewById(R.id.etCommand)
        scrollView = findViewById(R.id.scrollView)

        btnConnect.isEnabled = false
        btnSend.isEnabled = false
        btnLedOn.isEnabled = false
        btnLedOff.isEnabled = false

        Log.d(TAG, "✅ UI components initialized")
    }

    private fun setupBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            showError("Perangkat tidak mendukung Bluetooth")
            finish()
            return
        }

        Log.d(TAG, "✅ BluetoothAdapter initialized (API 31+)")
        checkAndRequestPermissions()
    }

    // ======================================================
    // 🔹 BUTTON LISTENERS
    // ======================================================

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            if (isConnected) disconnect() else connectToHC05()
        }

        btnLedOn.setOnClickListener { sendCommand("LED ON") }
        btnLedOff.setOnClickListener { sendCommand("LED OFF") }

        btnSend.setOnClickListener {
            val cmd = etCommand.text.toString().trim()
            if (cmd.isNotEmpty()) {
                sendCommand(cmd)
                etCommand.text.clear()
            } else {
                Toast.makeText(this, "⚠️ Command kosong!", Toast.LENGTH_SHORT).show()
            }
        }

        btnClear.setOnClickListener {
            tvTerminal.text = ""
            appendToTerminal("🗑️ Terminal cleared\n")
        }
    }

    // ======================================================
    // 🔹 PERMISSION HANDLING (FIXED)
    // ======================================================

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            Log.d(TAG, "🔐 Requesting permissions: $permissionsNeeded")
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            Log.d(TAG, "✅ All permissions already granted")
            permissionsGranted = true
            btnConnect.isEnabled = true
        }
    }

    // TAMBAHAN: Handle hasil request permission
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Log.d(TAG, "✅ All permissions granted")
                permissionsGranted = true
                btnConnect.isEnabled = true
                Toast.makeText(this, "✅ Permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "❌ Permissions denied")
                permissionsGranted = false
                showError("❌ Permission Bluetooth tidak diberikan!\nApp tidak bisa jalan tanpa permission ini.")
            }
        }
    }

    // ======================================================
    // 🔹 KONEKSI HC-05 (FIXED)
    // ======================================================

    @SuppressLint("MissingPermission")
    private fun connectToHC05() {
        // Check permission dulu
        if (!permissionsGranted) {
            showError("Permission belum diberikan!")
            return
        }

        // Check Bluetooth aktif
        if (bluetoothAdapter?.isEnabled == false) {
            showError("Bluetooth belum aktif!\nNyalakan Bluetooth dulu di Settings.")
            return
        }

        // Get paired devices
        val pairedDevices = try {
            bluetoothAdapter?.bondedDevices
        } catch (e: SecurityException) {
            showError("Error akses Bluetooth: ${e.message}")
            return
        }

        if (pairedDevices.isNullOrEmpty()) {
            showError("Tidak ada device yang di-pair.\n\n📝 Cara pair:\n1. Buka Settings > Bluetooth\n2. Scan & pair HC-05\n3. PIN: 1234")
            return
        }

        // Find HC-05
        hc05Device = pairedDevices.firstOrNull { it.name == HC05_NAME }
        if (hc05Device == null) {
            val deviceList = pairedDevices.joinToString("\n") { "• ${it.name}" }
            showError("HC-05 tidak ditemukan!\n\nDevice yang di-pair:\n$deviceList\n\nPastikan HC-05 sudah di-pair di Settings > Bluetooth.")
            return
        }

        Log.d(TAG, "✅ Found HC-05: ${hc05Device!!.name} (${hc05Device!!.address})")
        updateStatus("🔄 Connecting...", "#FFC107")
        btnConnect.isEnabled = false
        appendToTerminal("━━━━━━━━━━━━━━━━━━━━━━━━\n🔄 Connecting to ${hc05Device!!.name}...\n📍 Address: ${hc05Device!!.address}\n━━━━━━━━━━━━━━━━━━━━━━━━\n")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Cancel discovery untuk mempercepat koneksi
                bluetoothAdapter?.cancelDiscovery()
                delay(1000) // Delay lebih lama untuk stabilitas

                Log.d(TAG, "🔌 Creating RFCOMM socket...")

                // Try method 1: Standard createRfcommSocketToServiceRecord
                bluetoothSocket = try {
                    hc05Device!!.createRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Standard method failed, trying fallback...")
                    // Fallback: createInsecureRfcommSocketToServiceRecord
                    hc05Device!!.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                }

                Log.d(TAG, "🔌 Connecting to socket...")
                bluetoothSocket!!.connect()

                Log.d(TAG, "✅ Socket connected! Getting streams...")
                outputStream = bluetoothSocket!!.outputStream
                inputStream = bluetoothSocket!!.inputStream

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext

                    isConnected = true
                    updateStatus("🟢 Connected", "#4CAF50")
                    btnConnect.text = "🔌 Disconnect"
                    btnConnect.isEnabled = true
                    btnSend.isEnabled = true
                    btnLedOn.isEnabled = true
                    btnLedOff.isEnabled = true
                    appendToTerminal("✅ CONNECTED!\n━━━━━━━━━━━━━━━━━━━━━━━━\n")
                    Toast.makeText(this@MainActivity, "✅ Connected to HC-05!", Toast.LENGTH_SHORT).show()
                }

                startReading()

            } catch (e: IOException) {
                Log.e(TAG, "❌ Connection failed: ${e.message}", e)

                // Cleanup
                try {
                    bluetoothSocket?.close()
                } catch (_: IOException) { }
                bluetoothSocket = null

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext

                    val errorMsg = """
                        ❌ Koneksi gagal!
                        
                        Kemungkinan penyebab:
                        • HC-05 sudah terkoneksi ke device lain
                        • HC-05 terlalu jauh
                        • HC-05 tidak dalam mode pairing
                        
                        Solusi:
                        • Restart HC-05 (cabut-colok power)
                        • Pastikan LED berkedip (standby)
                        • Coba lagi
                    """.trimIndent()

                    showError(errorMsg)
                    appendToTerminal("❌ Connection failed!\nError: ${e.message}\n━━━━━━━━━━━━━━━━━━━━━━━━\n")
                    updateStatus("⚪ Disconnected", "#FF5252")
                    btnConnect.text = "🔗 Connect to HC-05"
                    btnConnect.isEnabled = true
                }
            }
        }
    }

    // ======================================================
    // 🔹 DISCONNECT
    // ======================================================

    private fun disconnect() {
        readingJob?.cancel()

        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "⚠️ Error closing socket: ${e.message}")
        }

        bluetoothSocket = null
        outputStream = null
        inputStream = null
        isConnected = false

        runOnUiThread {
            updateStatus("⚪ Disconnected", "#FF5252")
            btnConnect.text = "🔗 Connect to HC-05"
            btnConnect.isEnabled = true
            btnSend.isEnabled = false
            btnLedOn.isEnabled = false
            btnLedOff.isEnabled = false
            appendToTerminal("🔴 Disconnected\n")
            Toast.makeText(this, "🔴 Disconnected from STM32", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================================================
    // 🔹 BACA DATA DARI STM32
    // ======================================================

    private fun startReading() {
        val input = inputStream ?: run {
            appendToTerminal("⚠️ InputStream null, skip reading\n")
            return
        }

        readingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(1024)
            try {
                while (isActive && bluetoothSocket?.isConnected == true) {
                    val bytes = input.read(buffer)
                    if (bytes > 0) {
                        val data = String(buffer, 0, bytes)
                        withContext(Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            appendToTerminal("📥 $data")
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "❌ Connection lost: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    appendToTerminal("❌ Connection lost!\n")
                    Toast.makeText(this@MainActivity, "❌ Koneksi terputus", Toast.LENGTH_SHORT).show()
                }
                disconnect()
            }
        }
    }

    // ======================================================
    // 🔹 KIRIM DATA KE STM32 / HC-05
    // ======================================================

    private fun sendCommand(command: String) {
        if (!isConnected || outputStream == null) {
            Toast.makeText(this, "⚠️ Belum terkoneksi!", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val formatted = "$command\r\n"
                outputStream!!.write(formatted.toByteArray())
                outputStream!!.flush()

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    appendToTerminal("SENT: $formatted")
                    Toast.makeText(this@MainActivity, "Sent: $command", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Log.e(TAG, "❌ Send failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    appendToTerminal("❌ Failed to send\n")
                    Toast.makeText(this@MainActivity, "❌ Gagal kirim data", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ======================================================
    // 🔹 UI UTILITIES
    // ======================================================

    private fun updateStatus(status: String, colorHex: String) {
        runOnUiThread {
            tvStatus.text = status
            tvStatus.setTextColor(Color.parseColor(colorHex))
        }
    }

    private fun appendToTerminal(text: String) {
        runOnUiThread {
            tvTerminal.append(text)
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }

            // Limit terminal lines to prevent memory issues
            if (tvTerminal.lineCount > 500) {
                val lines = tvTerminal.text.lines()
                tvTerminal.text = lines.drop(100).joinToString("\n")
            }
        }
    }

    private fun showError(msg: String) {
        Log.e(TAG, msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        appendToTerminal("❌ $msg\n")
    }
}