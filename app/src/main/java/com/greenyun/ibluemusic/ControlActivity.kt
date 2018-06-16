package com.greenyun.ibluemusic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.provider.Settings
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.widget.Button
import android.widget.TextView

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.*

class ControlActivity : AppCompatActivity() {

    private val msgConnected = 0x101
    private val msgConnecting = 0x102
    private val msgConnectionFailed = 0x103
    private val msgListeningStandby = 0x201
    private val msgListeningFailed = 0x202
    private val msgDataRead = 0x301

    private val uuidSPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var activityBroadcastReceiver = ActivityBroadcastReceiver(this)
    private var activityHandler = ActivityHandler(this)

    private var connectThread: ConnectThread? = null
    private var listenThread: ListenThread? = null
    private var sendThread: SendThread? = null
    private var testThread = TestThread()

    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothDevice: BluetoothDevice? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var bluetoothDeviceList = ArrayList<BluetoothDevice>()

    private lateinit var textView: TextView

    private var bForceStop = false
    private var output: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (null == bluetoothAdapter) {
            AlertDialog.Builder(this)
                    .setTitle("Bluetooth Error")
                    .setMessage("It seems that the Bluetooth adapter works not fine.")
                    .setPositiveButton("OK", null)
                    .show()
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            bForceStop = true
            finish()
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)

        textView = findViewById(R.id.console)
        val buttonLED = findViewById<Button>(R.id.button_led)
        buttonLED.setOnClickListener {
            testThread.testMode = 1
        }

        val buttonFM = findViewById<Button>(R.id.button_fm)
        buttonFM.setOnClickListener {
            testThread.testMode = 2
        }

        val buttonPlayer = findViewById<Button>(R.id.button_player)
        buttonPlayer.setOnClickListener {
            testThread.testMode = 3
        }

        val buttonClock = findViewById<Button>(R.id.button_clock)
        buttonClock.setOnClickListener {
            testThread.testMode = 4
        }

        testThread.start()
    }

    override fun onPause() {
        unregisterReceiver(activityBroadcastReceiver)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(activityBroadcastReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(activityBroadcastReceiver, IntentFilter(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED))
        registerReceiver(activityBroadcastReceiver, IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED))
        registerReceiver(activityBroadcastReceiver, IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
        registerReceiver(activityBroadcastReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
    }

    private fun onBluetoothConnectionStateChanged(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_CONNECTED -> {
                listenThread!!.start()
                sendThread!!.start()
            }
            BluetoothAdapter.STATE_CONNECTING -> cPrint("Connecting...\n")
            BluetoothAdapter.STATE_DISCONNECTED -> {
                bluetoothDevice = null
                cPrint("Disconnected\n")
            }
            BluetoothAdapter.STATE_DISCONNECTING -> cPrint("Disconnecting...\n")
        }
    }

    private fun onBluetoothStateChanged(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_ON -> startBluetoothDiscovery()
            else -> {
            }
        }
    }

    private fun connectBluetoothDevice() {
        if (null != connectThread) {
            connectThread?.disconnect(bluetoothSocket)
            connectThread = null
        }
        if (null != bluetoothSocket) {
            try {
                bluetoothSocket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        connectThread = ConnectThread()
        if (null == connectThread) {
            cPrint("Connect thread error.\n")
            return
        }
        for (device in bluetoothDeviceList) {
            if (device.address == "DC:0D:30:00:0B:CE")
                bluetoothDevice = device
        }
        if (null == bluetoothDevice)
            cPrint("No iBlueMusic device found")
        else
            connectThread!!.start()
    }

    private fun cPrint(str: String) {
        textView.text = String.format("%s%s", textView.text, str)
    }

    private fun startBluetoothDiscovery() {
        if (!bluetoothAdapter!!.isDiscovering) {
            bluetoothDeviceList.clear()
//            refreshDeviceListView()
            bluetoothAdapter!!.startDiscovery()
        }
    }

    private class ActivityBroadcastReceiver internal constructor(activity: ControlActivity) : BroadcastReceiver() {
        private var activityWeakReference = WeakReference<ControlActivity>(activity)

        override fun onReceive(context: Context?, intent: Intent?) {
            val activity = activityWeakReference.get()
            val intentAction = intent?.action
            if (null != intentAction) {
                when (intentAction) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                        activity!!.onBluetoothStateChanged(state)
                    }
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                        activity!!.onBluetoothConnectionStateChanged(state)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        activity!!.cPrint("Device discovery finished.\n")
                        activity.connectBluetoothDevice()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        activity!!.cPrint("Device discovering.\n")
                    }
                    BluetoothDevice.ACTION_FOUND -> {
                        val bluetoothDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        activity!!.cPrint("Device found ${bluetoothDevice.name} (${bluetoothDevice.address})\n")
                        if (bluetoothDevice !in activity.bluetoothDeviceList) {
                            activity.bluetoothDeviceList.add(bluetoothDevice)
                        }
                    }
                }
            }
        }
    }

    private class ActivityHandler internal constructor(activity: ControlActivity) : Handler() {
        private var activityWeakReference = WeakReference<ControlActivity>(activity)

        override fun handleMessage(msg: Message) {
            val activity = activityWeakReference.get()
            when (msg.what) {
                activity?.msgConnected -> {
                    activity.onBluetoothConnectionStateChanged(BluetoothAdapter.STATE_CONNECTED)
                    activity.cPrint("Device connected.\n")
                }
                activity?.msgConnecting -> {
                    activity.onBluetoothConnectionStateChanged(BluetoothAdapter.STATE_CONNECTING)
                    activity.cPrint("Device connecting.\n")
                }
                activity?.msgConnectionFailed -> {
                    activity.onBluetoothConnectionStateChanged(-1)
                    activity.cPrint("Device connection failed.\n")
                }
                activity?.msgListeningStandby -> {
                }
                activity?.msgListeningFailed -> {
                }
                activity?.msgDataRead -> {
                    val mode = msg.arg1
                    val data = msg.obj as IntArray
                    when (mode) {
                        0x11 -> {
                            val brightness = data[1]

                            when (data[0]) {
                                0x00ff -> {
                                    activity.cPrint("Controlled successfully. Current brightness: $brightness.\n")
                                }
                                0xff00 -> {
                                    activity.cPrint("Controlled failed. Current brightness: $brightness.\n")
                                }
                                0xAA00 -> {
                                    activity.cPrint("Current state is off.\n")
                                }
                                0xAA01 -> {
                                    activity.cPrint("Current state is on with brightness: $brightness.\n")
                                }
                            }
                        }
                        0x22 -> {
                            val frequency = data[1] / 10.0

                            when (data[0]) {
                                0x0000 -> {
                                    activity.cPrint("Adjustment failed. Current frequency: $frequency.\n")
                                }
                                0x000f -> {
                                    activity.cPrint("Adjustment successfully. Current frequency: $frequency.\n")
                                }
                                0x00f0 -> {
                                    activity.cPrint("Auto mode. Current frequency: $frequency.\n")
                                }
                                0x0f00 -> {
                                    activity.cPrint("Auto scanning found frequency: $frequency.\n")
                                }
                                0xf000 -> {
                                    activity.cPrint("Current frequency: $frequency.\n")
                                }
                            }
                        }
                        0x33 -> {
                            when (data[0]) {
                                0x000f -> activity.cPrint("Now playing.\n")
                                0xf000 -> activity.cPrint("Now paused.\n")
                            }
                        }
                        0x44 -> {
                            val hour = data[1]
                            val minute = data[2]
                            val second = data[3]

                            when (data[0]) {
                                0x0f -> {
                                    activity.cPrint("Set time at $hour:$minute:$second.\n")
                                    activity.testThread.setClock = 1
                                }
                                0x00 -> activity.cPrint("set time failed.\n")
                                else -> {
                                    if (data[0] >= 0xf0) {
                                        val alarm = data[0] % 16
                                        if (hour == 0xff)
                                            activity.cPrint("set alarm $alarm failed.\n")
                                        else
                                            activity.cPrint("Set alarm $alarm at $hour:$minute:$second.\n")
                                    }
                                }
                            }
                        }
                    }
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private inner class ConnectThread : Thread() {
        private var socket: BluetoothSocket? = bluetoothDevice?.createRfcommSocketToServiceRecord(uuidSPP)

        override fun run() {
            if (null == socket) {
                connectFinish(1)
                return
            }
            activityHandler.obtainMessage(msgConnecting).sendToTarget()
            try {
                socket!!.connect()
            } catch (e: IOException) {
                e.printStackTrace()
                disconnect(socket)
                connectFinish(1)
                return
            }
            connectFinish(0)
        }

        fun disconnect(socket: BluetoothSocket?) {
            try {
                socket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        private fun connectFinish(code: Int) {
            when (code) {
                0 -> {
                    bluetoothSocket = socket
                    activityHandler.obtainMessage(msgConnected).sendToTarget()
                }
                else -> {
                    bluetoothSocket = null
                    activityHandler.obtainMessage(msgConnectionFailed).sendToTarget()
                }
            }
            synchronized(this@ControlActivity) {
                connectThread = null
            }
        }
    }

    private inner class ListenThread : Thread() {
        private var inputStream: InputStream? = null

        init {
            try {
                inputStream = bluetoothSocket?.inputStream
            } catch (e: IOException) {
                e.printStackTrace()
                inputStream = null
                synchronized(this@ControlActivity) {
                    listenThread = null
                }
            }
            if (null != inputStream)
                activityHandler.obtainMessage(msgListeningStandby).sendToTarget()
        }

        override fun run() {
            val bufferLength = 6
            val dataLength = 4

            val buffer = IntArray(bufferLength)
            val data = IntArray(dataLength)
            var i = 0
            val dataInputStream = DataInputStream(inputStream)
            while (true) {
                try {
                    while (0 == i) {
                        if (buffer[0] in arrayOf(0x11, 0x22, 0x33, 0x44)) {
                            ++i
                            break
                        }
                        buffer[0] = dataInputStream.readUnsignedByte()
                    }
                    while (i < bufferLength) {
                        buffer[i] = dataInputStream.readUnsignedByte()
                        ++i
                    }
                    if (0xff == buffer[bufferLength - 1]) {
                        if (buffer[0] == 0x44)
                            for (j in 0 until 4)
                                data[j] = buffer[j + 1]
                        else
                            for (j in 0 until dataLength)
                                data[j] = buffer[j * 2 + 1] * 256 + buffer[j * 2 + 2]
                        activityHandler.obtainMessage(msgDataRead, buffer[0], -1, data).sendToTarget()
                        i = 0
                        buffer[0] = 0
                    } else {
                        var m: Int = bufferLength - 1
                        while (m > 0) {
                            if (0xff == buffer[m])
                                break
                            --m
                        }
                        i = 0
                        buffer[0] = 0
                        while (m in 1..(bufferLength - 1)) {
                            buffer[i] = buffer[m]
                            ++i
                            ++m
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    break
                }

            }
            activityHandler.obtainMessage(msgListeningFailed, -1, -1, -1).sendToTarget()
        }
    }

    private inner class SendThread : Thread() {
        private var outputStream: OutputStream? = null

        init {
            try {
                outputStream = bluetoothSocket?.outputStream
            } catch (e: IOException) {
                e.printStackTrace()
                outputStream = null
                synchronized(this@ControlActivity) {
                    sendThread = null
                }
            }
        }

        override fun run() {
            try {
                while (true) {
                    if (null != output)
                        outputStream!!.write(output)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private inner class TestThread : Thread() {
        var testMode = 0
        var setClock = 0

        override fun run() {
            while (true) {
                when (testMode) {
                    1 -> {
                        output = byteArrayOf(0x11, 0xf0.toByte(), 0xf0.toByte(), 0, 0, 0xff.toByte())
                        sleep(100)
                        output = byteArrayOf(0x11, 0xff.toByte(), 0, 0, 0, 0xff.toByte())
                        sleep(1000)
                        output = byteArrayOf(0x11, 0, 0xff.toByte(), 0, 0, 0xff.toByte())
                        sleep(1000)
                        output = byteArrayOf(0x11, 0x0f, 0x0f, 0, 0x08, 0xff.toByte())
                        sleep(1000)
                        testMode = 0
                    }
                    2 -> {
                        output = byteArrayOf(0x22, 0xf0.toByte(), 0, 0, 0, 0xff.toByte())
                        sleep(100)
                        output = byteArrayOf(0x22, 0, 0x0f, 0x04, 0x13, 0xff.toByte())
                        sleep(1000)
                        output = byteArrayOf(0x22, 0, 0xf0.toByte(), 0, 0xff.toByte(), 0xff.toByte())
                        sleep(1000)
                        output = byteArrayOf(0x22, 0x0f, 0, 0, 0, 0xff.toByte())
                        sleep(1000)
                        testMode = 0
                    }
                    3 -> {
                        output = byteArrayOf(0x33, 0xf0.toByte(), 0, 0, 0, 0xff.toByte())
                        sleep(1000)
                        output = byteArrayOf(0x33, 0, 0x0f, 0, 0, 0xff.toByte())
                        sleep(1000)
                        output = byteArrayOf(0x33, 0xf0.toByte(), 0x0f, 0, 0, 0xff.toByte())
                        sleep(1000)
                        output = byteArrayOf(0x33, 0x0f, 0x0f, 0, 0, 0xff.toByte())
                        sleep(1000)
                        output = byteArrayOf(0x33, 0xff.toByte(), 0, 0, 0, 0xff.toByte())
                        sleep(1000)
                        output = byteArrayOf(0x33, 0, 0xff.toByte(), 0, 0, 0xff.toByte())
                        sleep(1000)
                        testMode = 0
                    }
                    4 -> {
                        var calendar = Calendar.getInstance()
                        var hour = calendar.get(Calendar.HOUR_OF_DAY)
                        var minute = calendar.get(Calendar.MINUTE)
                        var second = calendar.get(Calendar.SECOND)
                        var lastClock = calendar.timeInMillis
                        var latency1: Long
                        var latency2: Long

                        setClock = 0
                        output = byteArrayOf(0x44, 0x0f, hour.toByte(), minute.toByte(), second.toByte(), 0xff.toByte())
                        while (0 == setClock);
                        calendar = Calendar.getInstance()
                        hour = calendar.get(Calendar.HOUR_OF_DAY)
                        minute = calendar.get(Calendar.MINUTE)
                        second = calendar.get(Calendar.SECOND)
                        latency1 = calendar.timeInMillis - lastClock
                        lastClock = calendar.timeInMillis
                        output = byteArrayOf(0x44, 0x0f, hour.toByte(), minute.toByte(), second.toByte(), 0xff.toByte())
                        while (0 == setClock);
                        calendar = Calendar.getInstance()
                        hour = calendar.get(Calendar.HOUR_OF_DAY)
                        minute = calendar.get(Calendar.MINUTE)
                        second = calendar.get(Calendar.SECOND)
                        latency2 = calendar.timeInMillis - lastClock
                        if ((latency1 + latency2) / 4 > 500)
                            second++
                        output = byteArrayOf(0x44, 0x0f, hour.toByte(), minute.toByte(), second.toByte(), 0xff.toByte())
                        sleep(1000)

                        output = byteArrayOf(0x44, 0xf0.toByte(), hour.toByte(), (minute + 30).toByte(), second.toByte(), 0xff.toByte())
                        sleep(1000)
                        output = byteArrayOf(0x44, 0xf0.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
                        sleep(1000)
                        testMode = 0
                    }
                }
            }
        }
    }
}
