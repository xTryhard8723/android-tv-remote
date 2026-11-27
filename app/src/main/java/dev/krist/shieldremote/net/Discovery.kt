package dev.krist.shieldremote.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

data class DiscoveredTv(val serviceName: String, val host: String, val port: Int)

/** mDNS discovery of `_androidtvremote2._tcp` services via Android NSD. */
class Discovery(context: Context) {
    companion object { const val SERVICE_TYPE = "_androidtvremote2._tcp." }

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private var listener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var onFound: ((DiscoveredTv) -> Unit)? = null
    private var onLost: ((String) -> Unit)? = null

    fun start(onFound: (DiscoveredTv) -> Unit, onLost: (String) -> Unit) {
        stop()
        this.onFound = onFound; this.onLost = onLost
        lock = runCatching { wifi.createMulticastLock("shieldremote-mdns").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.post { enqueue(info) } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post { this@Discovery.onLost?.invoke(info.serviceName) } }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        runCatching { lock?.release() }; lock = null
        resolveQueue.clear(); resolving = false
        onFound = null; onLost = null
    }

    private fun enqueue(info: NsdServiceInfo) { resolveQueue.addLast(info); pump() }

    // NsdManager only allows one resolve at a time on older releases; serialize them.
    @Suppress("DEPRECATION")
    private fun pump() {
        if (resolving || listener == null) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val done = { tv: DiscoveredTv? ->
            main.post {
                resolving = false
                tv?.let { onFound?.invoke(it) }
                pump()
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val cb = object : NsdManager.ServiceInfoCallback {
                var delivered = false
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { done(null) }
                override fun onServiceUpdated(si: NsdServiceInfo) {
                    val addr = si.hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: si.hostAddresses.firstOrNull()
                    val host = addr?.hostAddress
                    if (host != null && !delivered) {
                        delivered = true
                        done(DiscoveredTv(si.serviceName, host, si.port))
                        runCatching { nsd.unregisterServiceInfoCallback(this) }
                    }
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            runCatching { nsd.registerServiceInfoCallback(info, resolveExecutor, cb) }.onFailure { done(null) }
            main.postDelayed({ if (!cb.delivered) { cb.delivered = true; runCatching { nsd.unregisterServiceInfoCallback(cb) }; done(null) } }, 4000)
        } else {
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) { done(null) }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val h = si.host?.hostAddress
                        done(if (h != null) DiscoveredTv(si.serviceName, h, si.port) else null)
                    }
                })
            }.onFailure { done(null) }
        }
    }
}
