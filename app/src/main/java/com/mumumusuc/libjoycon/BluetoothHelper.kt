@file:Suppress("unused", "WeakerAccess")

package com.mumumusuc.libjoycon

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ParcelUuid
import android.util.Log
import com.hiddenramblings.tagmo.eightbit.io.Debug
import com.hiddenramblings.tagmo.eightbit.os.Version
import com.hiddenramblings.tagmo.parcelable
import java.lang.reflect.InvocationTargetException

class BluetoothHelper(private val mAdapter: BluetoothAdapter) : BluetoothProfile.ServiceListener {
    companion object {
        private const val TAG = "BluetoothHelper"
        const val BT_STATE_NONE = 0
        const val BT_STATE_DISCOVERY_ON = 0x10
        const val BT_STATE_DISCOVERY_OFF = 0x11
        const val BT_STATE_FOUND_DEVICE = 0x01
        const val BT_STATE_UNPAIRED = 0x20
        const val BT_STATE_PAIRING = 0x21
        const val BT_STATE_PAIRED = 0x21
        const val BT_STATE_DISCONNECTED = 0x30
        const val BT_STATE_CONNECTING = 0x31
        const val BT_STATE_CONNECTED = 0x32
    }

    private fun debug(msg: String) {
        Debug.verbose(this.javaClass, msg)
    }

    private val mReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        debug("discovery started")
                        mCallback?.onStateChanged(null, null, BT_STATE_DISCOVERY_ON)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        debug("discovery finished")
                        mCallback?.onStateChanged(null, null, BT_STATE_DISCOVERY_OFF)
                    }
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev =
                            intent.parcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        debug("device found -> $dev")
                        mCallback?.onStateChanged(dev?.name, dev?.address, BT_STATE_FOUND_DEVICE)
                    }
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                        val dev = intent.parcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        debug("connection state change -> device = $dev, state = $state")
                        when (state) {
                            BluetoothAdapter.STATE_CONNECTED -> {
                                debug("$dev connected")
                                mCallback?.onStateChanged(dev?.name, dev?.address, BT_STATE_CONNECTED)
                            }
                            BluetoothAdapter.STATE_DISCONNECTED -> {
                                debug("$dev disconnected")
                                mCallback?.onStateChanged(
                                    dev?.name,
                                    dev?.address,
                                    BT_STATE_DISCONNECTED
                                )
                            }
                            BluetoothAdapter.STATE_CONNECTING -> {
                                debug("$dev connecting")
                                mCallback?.onStateChanged(
                                    dev?.name,
                                    dev?.address,
                                    BT_STATE_CONNECTING
                                )
                            }
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        val dev = intent.parcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        debug("bond state change -> device = $dev, state = $state")
                        when (state) {
                            BluetoothDevice.BOND_BONDED -> {
                                debug("$dev paired")
                                mCallback?.onStateChanged(dev?.name, dev?.address, BT_STATE_PAIRED)
                            }
                            BluetoothDevice.BOND_NONE -> {
                                debug("$dev unpaired")
                                mCallback?.onStateChanged(dev?.name, dev?.address, BT_STATE_UNPAIRED)
                            }
                            BluetoothDevice.BOND_BONDING -> {
                                debug("$dev pairing")
                                mCallback?.onStateChanged(dev?.name, dev?.address, BT_STATE_PAIRING)
                            }
                        }
                    }
                    BluetoothHidHost.ACTION_REPORT -> {
                        val report = intent.getByteArrayExtra(BluetoothHidHost.EXTRA_REPORT)
                        //val report = intent.getCharArrayExtra(BluetoothHidHost.EXTRA_REPORT)
                        if (report == null)
                            debug("receive null report")
                        else
                            debug("received report: " + String(report))
                    }
                    BluetoothHidHost.ACTION_HANDSHAKE -> {
                        val status = intent.getIntExtra(BluetoothHidHost.EXTRA_STATUS, -1)
                        val dev = intent.parcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        debug("ACTION_HANDSHAKE -> device = $dev, status = $status")
                    }
                }
            }
        }
    }

    private var mHidHost: BluetoothHidHost? = null
    private var mCallback: StateChangedCallback? = null

    fun getHidHost(): BluetoothHidHost? {
        return mHidHost
    }

    private fun checkOrThrow() {
        if (!mAdapter.isEnabled) throw RuntimeException("Bluetooth adapter unavailable!")
    }

    override fun onServiceConnected(p0: Int, p1: BluetoothProfile) {
        if (p0 == 4) {
            Log.d(TAG, "connected to hid host proxy")
            mHidHost = BluetoothHidHost(p1)
        }
    }

    override fun onServiceDisconnected(p0: Int) {
        if (p0 == 4) {
            Log.d(TAG, "disconnected from hid host proxy")
            mHidHost = null
        }
    }

    fun register(context: Context, callback: StateChangedCallback?) {
        mCallback = callback
        // mLocation = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val filter = IntentFilter()
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        filter.addAction(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        filter.addAction(BluetoothHidHost.ACTION_REPORT)
        filter.addAction(BluetoothHidHost.ACTION_HANDSHAKE)
        context.registerReceiver(mReceiver, filter)
        mAdapter.getProfileProxy(context, this, 4)
    }

    fun unregister(context: Context) {
        mCallback = null
        context.unregisterReceiver(mReceiver)
        mAdapter.closeProfileProxy(4, mHidHost?.asBluetoothProfile())
    }

    fun getBluetoothDevice(address: String): BluetoothDevice {
        return mAdapter.getRemoteDevice(address)
    }

    fun discovery(on: Boolean) {
        checkOrThrow()
        if (mAdapter.isDiscovering && !on) {
            mAdapter.cancelDiscovery()
            return
        }
        if (!mAdapter.isDiscovering && on) {
            mAdapter.startDiscovery()
            return
        }
    }

    fun pair(dev: BluetoothDevice) {
        checkOrThrow()
        if (Version.isKitKat && dev.bondState == BluetoothDevice.BOND_NONE) {
            dev.createBond()
        }
    }

    fun pair(address: String) {
        pair(mAdapter.getRemoteDevice(address))
    }

    fun connectL2cap(dev: BluetoothDevice) {
        try {
            val createInsecureL2capSocket =
                dev::class.java.getDeclaredMethod("createInsecureL2capSocket", Int::class.java)
            val sock_crtl = createInsecureL2capSocket.invoke(dev, 17) as BluetoothSocket
            val sock_intr = createInsecureL2capSocket.invoke(dev, 19) as BluetoothSocket
            Thread {
                try {
                    val getBluetoothService = mAdapter::class.java.declaredMethods.first {
                        it.name == "getBluetoothService"
                    }
                    getBluetoothService.isAccessible = true
                    val bluetoothProxy: Any = try {
                        getBluetoothService.invoke(mAdapter, null)
                    } catch (ex: IllegalArgumentException) {
                        getBluetoothService.invoke(mAdapter)
                    }
                    val getSocketManager =
                        bluetoothProxy::class.java.getDeclaredMethod("getSocketManager")
                    getSocketManager.isAccessible = true
                    val bsm = getSocketManager.invoke(bluetoothProxy)
                    val connectSocket = bsm::class.java.getDeclaredMethod(
                        "connectSocket",
                        BluetoothDevice::class.java,
                        Int::class.java,
                        ParcelUuid::class.java,
                        Int::class.java,
                        Int::class.java
                    )
                    connectSocket.isAccessible = true
                    val SEC_FLAG_ENCRYPT = 1
                    val SEC_FLAG_AUTH = 2
                    val BTSOCK_FLAG_NO_SDP = 4
                    val SEC_FLAG_AUTH_MITM = 8
                    val SEC_FLAG_AUTH_16_DIGIT = 16
                    if (Version.isKitKat) dev.setPin(byteArrayOf(0, 0, 0, 0))
                    val pfd: ParcelFileDescriptor = try {
                        connectSocket.invoke(
                            bsm, dev, 3, null, 17, 0
                        ) as ParcelFileDescriptor
                    } catch (ex: InvocationTargetException) {
                        connectSocket.invoke(
                            bsm, dev, 3, dev.uuids[0], 17, 0
                        ) as ParcelFileDescriptor
                    }
                    debug("pfd = $pfd, fd = ${pfd.fileDescriptor}")
                    try {
                        connectSocket.invoke(
                            bsm, dev, 3, null, 19, 0
                        ) as ParcelFileDescriptor
                    } catch (ex: InvocationTargetException) {
                        connectSocket.invoke(
                            bsm, dev, 3, dev.uuids[0], 19, 0
                        ) as ParcelFileDescriptor
                    }
                } catch (e: Exception) { Debug.error(e) }
            }.start()
        } catch (e: Exception) {
            Debug.error(e)
        }
    }

    fun connect(dev: BluetoothDevice, on: Boolean) {
        checkOrThrow()
        mHidHost?.let { host ->
            val state = host.getConnectionState(dev)
            if (state == BluetoothAdapter.STATE_CONNECTED && !on) {
                host.disconnect(dev)
                return
            }
            if (state == BluetoothAdapter.STATE_DISCONNECTED && on) {
                if (mAdapter.isDiscovering) {
                    discovery(false)
                }
                host.connect(dev)
                return
            }
            debug("device state not ready")
            return
        }
        throw RuntimeException("hid host not ready")
    }

    fun connect(address: String, on: Boolean) {
        connect(mAdapter.getRemoteDevice(address), on)
    }

    fun getDeviceState(dev: BluetoothDevice): Int {
        if (mHidHost?.getConnectionState(dev) == BluetoothAdapter.STATE_CONNECTED)
            return BT_STATE_CONNECTED
        if (dev.bondState == BluetoothDevice.BOND_BONDED)
            return BT_STATE_PAIRED
        return BT_STATE_NONE
    }

    fun getDeviceState(address: String): Int {
        return getDeviceState(mAdapter.getRemoteDevice(address))
    }

    fun getPairedDevices(): Set<BluetoothDevice> {
        return mAdapter.bondedDevices
    }

    fun getConnectedDevices(): Set<BluetoothDevice> {
        val host = mHidHost
        return host?.getConnectedDevices()?.toSet() ?: setOf()
    }

    interface StateChangedCallback {
        fun onStateChanged(name: String?, address: String?, state: Int)
    }
}