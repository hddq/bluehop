package org.hddq.bluehop.server

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

private const val TAG = "BlueHopNsd"

class NsdAdvertiser(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val callbackExecutor = Dispatchers.IO.asExecutor()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start(
        port: Int,
        deviceAddress: String? = null,
        deviceId: String? = null,
        deviceName: String? = null,
        pairing: Boolean = false,
    ) {
        if (port <= 0) return
        stop()

        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Skipping mDNS registration: ACCESS_LOCAL_NETWORK permission not granted")
            return
        }

        val manager = nsdManager
        if (manager == null) {
            Log.w(TAG, "NsdManager unavailable; cannot advertise $SERVICE_TYPE")
            return
        }

        acquireMulticastLock()

        val resolvedServiceName = buildServiceName(deviceName, pairing)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = resolvedServiceName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("v", "1")
            if (!deviceId.isNullOrBlank()) {
                setAttribute("id", deviceId)
            }
            if (!deviceName.isNullOrBlank()) {
                setAttribute("name", deviceName)
            }
            if (!deviceAddress.isNullOrBlank()) {
                setAttribute("device", deviceAddress)
            }
            if (pairing) {
                setAttribute("pair", "1")
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                Log.i(
                    TAG,
                    "Registered mDNS service name=${registeredInfo.serviceName} type=$SERVICE_TYPE port=$port",
                )
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed (errorCode=$errorCode)")
            }

            override fun onServiceUnregistered(unregisteredInfo: NsdServiceInfo) {
                Log.i(TAG, "Unregistered mDNS service name=${unregisteredInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed (errorCode=$errorCode)")
            }
        }

        registrationListener = listener
        runCatching {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, callbackExecutor, listener)
        }.onFailure { error ->
            Log.w(TAG, "Failed to register mDNS service", error)
            registrationListener = null
            releaseMulticastLock()
        }
    }

    @Synchronized
    fun stop() {
        val listener = registrationListener
        registrationListener = null
        if (listener != null && nsdManager != null) {
            runCatching {
                nsdManager.unregisterService(listener)
            }.onFailure { error ->
                Log.w(TAG, "Failed to unregister mDNS service", error)
            }
        }
        releaseMulticastLock()
    }

    @Synchronized
    fun shutdown() {
        stop()
    }

    private fun acquireMulticastLock() {
        val lock = multicastLock ?: wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
        }.also { multicastLock = it }
        if (lock != null && !lock.isHeld) {
            runCatching {
                lock.acquire()
                Log.i(TAG, "Acquired WifiManager.MulticastLock")
            }.onFailure { error ->
                Log.w(TAG, "Failed to acquire WifiManager.MulticastLock", error)
            }
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock
        if (lock != null && lock.isHeld) {
            runCatching {
                lock.release()
                Log.i(TAG, "Released WifiManager.MulticastLock")
            }.onFailure { error ->
                Log.w(TAG, "Failed to release WifiManager.MulticastLock", error)
            }
        }
    }

    companion object {
        const val SERVICE_NAME = "BlueHop"
        const val SERVICE_TYPE = "_bluehop._tcp."
        private const val MULTICAST_LOCK_TAG = "bluehop:mdns"
        private const val MAX_SERVICE_NAME_LENGTH = 50

        internal fun buildServiceName(deviceName: String?, pairing: Boolean): String {
            val base = if (!deviceName.isNullOrBlank()) {
                "$SERVICE_NAME - ${deviceName.trim()}"
            } else {
                SERVICE_NAME
            }
            val full = if (pairing) "$base (pair)" else base
            return full.take(MAX_SERVICE_NAME_LENGTH)
        }
    }
}
