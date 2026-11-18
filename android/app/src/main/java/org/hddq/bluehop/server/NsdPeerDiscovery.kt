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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BlueHopNsdDiscovery"

data class DiscoveredBlueHopPeer(
    val serviceName: String,
    val host: String,
    val port: Int,
    val deviceId: String?,
    val displayName: String,
    val pairingAvailable: Boolean,
    val connectedBluetoothAddress: String?,
    val connectedBluetoothAddresses: Set<String> = connectedBluetoothAddress
        ?.let { setOf(it.uppercase(Locale.ROOT)) }
        ?: emptySet(),
) {
    val stableKey: String
        get() = deviceId?.takeIf { it.isNotBlank() }
            ?: "$serviceName@${PairingClient.formatHostPort(host, port)}"
}

class NsdPeerDiscovery(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val callbackExecutor = Dispatchers.IO.asExecutor()

    private val _peers = MutableStateFlow<List<DiscoveredBlueHopPeer>>(emptyList())
    val peers: StateFlow<List<DiscoveredBlueHopPeer>> = _peers.asStateFlow()

    private val resolvedPeers = ConcurrentHashMap<String, DiscoveredBlueHopPeer>()
    private val serviceCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var excludedDeviceId: String? = null
    private var pairingOnlyFilter: Boolean = false

    @Synchronized
    fun start(
        excludeDeviceId: String? = null,
        pairingOnly: Boolean = false,
    ) {
        stop()
        excludedDeviceId = excludeDeviceId
        pairingOnlyFilter = pairingOnly

        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Skipping mDNS discovery: ACCESS_LOCAL_NETWORK not granted")
            return
        }

        val manager = nsdManager ?: return
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "Started mDNS discovery for $regType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Stopped mDNS discovery for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != NsdAdvertiser.SERVICE_TYPE) return
                registerServiceCallback(manager, serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                serviceCallbacks.remove(name)?.let { callback ->
                    runCatching { manager.unregisterServiceInfoCallback(callback) }
                }
                if (resolvedPeers.remove(name) != null) {
                    publishPeers()
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Start mDNS discovery failed (errorCode=$errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop mDNS discovery failed (errorCode=$errorCode)")
            }
        }

        discoveryListener = listener
        runCatching {
            manager.discoverServices(NsdAdvertiser.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            Log.w(TAG, "Failed to start mDNS discovery", error)
            discoveryListener = null
            releaseMulticastLock()
        }
    }

    @Synchronized
    fun stop() {
        val manager = nsdManager
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null && manager != null) {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        val callbacks = serviceCallbacks.values.toList()
        serviceCallbacks.clear()
        if (manager != null) {
            for (callback in callbacks) {
                runCatching { manager.unregisterServiceInfoCallback(callback) }
            }
        }
        resolvedPeers.clear()
        _peers.value = emptyList()
        releaseMulticastLock()
    }

    @Synchronized
    fun shutdown() {
        stop()
    }

    private fun registerServiceCallback(
        manager: NsdManager,
        serviceInfo: NsdServiceInfo,
    ) {
        val name = serviceInfo.serviceName ?: return
        if (serviceCallbacks.containsKey(name)) return

        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "ServiceInfoCallback registration failed for $name (errorCode=$errorCode)")
                serviceCallbacks.remove(name)
            }

            override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                val parsed = parseDiscoveredPeer(updatedInfo) ?: return
                if (excludedDeviceId != null && parsed.deviceId == excludedDeviceId) {
                    resolvedPeers.remove(name)
                } else if (pairingOnlyFilter && !parsed.pairingAvailable) {
                    resolvedPeers.remove(name)
                } else {
                    resolvedPeers[name] = parsed
                }
                publishPeers()
            }

            override fun onServiceLost() {
                if (resolvedPeers.remove(name) != null) {
                    publishPeers()
                }
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }

        serviceCallbacks[name] = callback
        runCatching {
            manager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
        }.onFailure { error ->
            Log.w(TAG, "Failed to register ServiceInfoCallback for $name", error)
            serviceCallbacks.remove(name)
        }
    }

    private fun publishPeers() {
        _peers.value = resolvedPeers.values
            .distinctBy { it.stableKey }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun acquireMulticastLock() {
        val lock = multicastLock ?: wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
        }.also { multicastLock = it }
        if (lock != null && !lock.isHeld) {
            runCatching { lock.acquire() }
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock
        if (lock != null && lock.isHeld) {
            runCatching { lock.release() }
        }
    }

    companion object {
        private const val MULTICAST_LOCK_TAG = "bluehop:discovery"

        internal fun parseDiscoveredPeer(serviceInfo: NsdServiceInfo): DiscoveredBlueHopPeer? {
            val port = serviceInfo.port
            if (port <= 0) return null
            val hostAddress = selectPreferredHost(serviceInfo.hostAddresses) ?: return null
            val attrs = serviceInfo.attributes
            val deviceId = attrs["id"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            val attrName = attrs["name"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            val pairing = attrs["pair"]?.toString(Charsets.UTF_8) == "1"
            val singleDevice = attrs["device"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            val devicesAttr = attrs["devices"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            val allAddresses = buildSet {
                devicesAttr?.split(',')
                    ?.map { it.trim().uppercase(Locale.ROOT) }
                    ?.filter { it.isNotEmpty() }
                    ?.let(::addAll)
                singleDevice?.split(',')
                    ?.map { it.trim().uppercase(Locale.ROOT) }
                    ?.filter { it.isNotEmpty() }
                    ?.let(::addAll)
            }
            val serviceName = serviceInfo.serviceName.orEmpty()
            return DiscoveredBlueHopPeer(
                serviceName = serviceName,
                host = hostAddress,
                port = port,
                deviceId = deviceId,
                displayName = attrName ?: serviceName.ifBlank { hostAddress },
                pairingAvailable = pairing,
                connectedBluetoothAddress = singleDevice ?: allAddresses.firstOrNull(),
                connectedBluetoothAddresses = allAddresses,
            )
        }

        internal fun selectPreferredHost(addresses: List<InetAddress>): String? {
            val ipv4 = addresses.firstOrNull {
                it is Inet4Address && isUsableLocalIpAddress(it)
            }
            if (ipv4 != null) return ipv4.hostAddress

            val ulaIpv6 = addresses.firstOrNull {
                it is Inet6Address && isUsableLocalIpAddress(it) && isUniqueLocalIpv6(it)
            }
            if (ulaIpv6 != null) return ulaIpv6.hostAddress

            val usableIpv6 = addresses.firstOrNull {
                it is Inet6Address && isUsableLocalIpAddress(it)
            }
            if (usableIpv6 != null) return usableIpv6.hostAddress

            return addresses.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
                ?: addresses.firstOrNull { !it.isLoopbackAddress }?.hostAddress
                ?: addresses.firstOrNull()?.hostAddress
        }
    }
}
