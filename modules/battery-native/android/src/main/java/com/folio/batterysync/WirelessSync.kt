package com.folio.batterysync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// 网络连接与读写运行在独立线程，主线程只更新状态和生命周期。
internal class WirelessSync private constructor(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val connector = Executors.newSingleThreadExecutor()
    private val sender = Executors.newSingleThreadExecutor()
    private val credentials = WirelessCredentials(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var peer = credentials.read()
    @Volatile private var generation = 0
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    private var running = false
    private var connecting = false
    private var blocked = false
    private var retrySeconds = 1L
    private var resolved: Pair<String, Int>? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastSnapshot = ""
    private val reconnect = Runnable { connect() }
    private val sendLatest = Runnable { sendSnapshot(false) }
    private val observer: (Map<String, Any?>) -> Unit = {
        if (running && output != null && it["status"] != "reading") {
            main.removeCallbacks(sendLatest); main.postDelayed(sendLatest, 500)
        }
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            sendSnapshot(true)
            main.postDelayed(this, 60_000)
        }
    }

    init { BatteryState.wirelessPaired = peer != null; BatteryState.wirelessStatus = if (peer == null) "unpaired" else "waiting" }

    fun pair(code: String) {
        require(peer == null || peer?.pending == true) { "ALREADY_PAIRED" }
        val candidate = WirelessPeer.fromQR(code)
        stop()
        peer = candidate; blocked = false
        start()
    }
    fun forget() {
        val writer = output
        val clear = Runnable {
            stop(); credentials.clear(); peer = null; resolved = null; blocked = false
            BatteryState.wirelessPaired = false; BatteryState.wirelessLastSync = null
            BatteryState.computerDevices = emptyList(); BatteryState.computerUpdatedAt = null
            state("unpaired")
        }
        if (writer == null) clear.run()
        else sender.execute {
            runCatching { WirelessWire.write(writer, JSONObject().put("v", 1).put("type", "unpair").toString()) }
            main.post(clear)
        }
    }
    fun start() {
        if (running || blocked || peer == null) return
        running = true; retrySeconds = 1; lastSnapshot = ""
        BatteryState.observers.add(observer)
        state(if (peer?.pending == true) "pairing" else "connecting")
        val epoch = generation
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { if (running && epoch == generation) { main.removeCallbacks(reconnect); connect() } } }
            override fun onLost(network: Network) { main.post { if (running && epoch == generation) { runCatching { socket?.close() }; resolved = null; startDiscovery() } } }
        }
        networkCallback = callback
        try {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), callback)
        } catch (_: Exception) {
            stop(); state("unavailable"); return
        }
        startDiscovery()
        main.post(reconnect); main.postDelayed(heartbeat, 60_000)
    }
    fun stop() {
        running = false; generation++; connecting = false
        main.removeCallbacks(reconnect); main.removeCallbacks(heartbeat); main.removeCallbacks(sendLatest)
        BatteryState.observers.remove(observer)
        output = null; runCatching { socket?.close() }; socket = null
        stopDiscovery()
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }; networkCallback = null
        if (peer != null) state("waiting")
    }
    private fun state(value: String) { BatteryState.wirelessStatus = value; BatteryState.publish() }

    private fun connect() {
        if (!running || connecting || output != null || blocked) return
        val config = peer ?: return
        val network = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }
        if (network == null) { state("networkUnavailable"); scheduleRetry(); return }
        connecting = true
        state(if (config.pending) "pairing" else "connecting")
        val current = generation
        val targets = (listOfNotNull(resolved) + config.addresses.map { it to config.port }).distinct()
        connector.execute {
            var connected: SSLSocket? = null
            var rejected = false
            var identityRejected = false
            try {
                for ((host, port) in targets) {
                    if (current != generation) return@execute
                    val plain = network.socketFactory.createSocket()
                    socket = plain
                    try {
                        plain.connect(InetSocketAddress(host, port), 5_000)
                        val tls = tlsContext(config).socketFactory.createSocket(plain, host, port, true) as SSLSocket
                        connected = tls; socket = tls
                        tls.soTimeout = 10_000
                        tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                        tls.startHandshake()
                        break
                    } catch (error: Exception) {
                        if (error is javax.net.ssl.SSLHandshakeException) identityRejected = true
                        runCatching { connected?.close() }; connected = null; runCatching { plain.close() }
                    }
                }
                val tls = connected ?: error("CONNECTION_UNAVAILABLE")
                if (current != generation) { tls.close(); return@execute }
                val input = DataInputStream(tls.inputStream)
                val writer = DataOutputStream(tls.outputStream)
                WirelessWire.write(writer, JSONObject().put("v", 1).put("type", if (config.pending) "pair" else "auth")
                    .put("phoneID", credentials.phoneID).put("token", config.token).toString())
                val reply = message(WirelessWire.read(input))
                if (reply.getString("type") == "rejected") { rejected = true; error("AUTH_REJECTED") }
                require(reply.getString("type") == if (config.pending) "paired" else "ready")
                val saved = if (config.pending) {
                    val token = reply.getString("token")
                    require(android.util.Base64.decode(token, android.util.Base64.NO_WRAP).size == 32 && token.length <= 64)
                    config.copy(token = token, pending = false).also { credentials.save(it) }
                } else config
                tls.soTimeout = 90_000
                main.post {
                    if (current == generation && running) {
                        peer = saved; BatteryState.wirelessPaired = true
                        output = writer; retrySeconds = 1; lastSnapshot = ""; stopDiscovery()
                        state("connected"); sendSnapshot(true)
                    }
                }
                while (current == generation) {
                    val value = message(WirelessWire.read(input))
                    when (value.getString("type")) {
                        "computerDevices" -> {
                            val records = ComputerBatteryStore.parse(value.getJSONArray("devices"))
                            main.post { if (current == generation && running) {
                                BatteryState.wirelessLastSync = System.currentTimeMillis()
                                ComputerBatteryStore.update(records)
                            } }
                        }
                        "refresh" -> main.post { if (current == generation && running) BatteryReader.get(context).refresh() }
                        else -> error("INVALID_MESSAGE")
                    }
                }
            } catch (_: Exception) {
                // 只发布稳定的错误状态，不记录凭据与消息内容。
            } finally {
                runCatching { connected?.close() }
                main.post {
                    if (current == generation && running) {
                        socket = null; output = null; connecting = false
                        if (rejected) {
                            blocked = true
                            if (peer?.pending == true) { peer = null; BatteryState.wirelessPaired = false }
                            state("pairingRequired")
                        } else {
                            state(if (identityRejected) "identityRejected" else "reconnecting")
                            startDiscovery(); scheduleRetry()
                        }
                    }
                }
            }
        }
    }
    private fun scheduleRetry() {
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, retrySeconds * 1000 + (0..500).random())
        retrySeconds = (retrySeconds * 2).coerceAtMost(30)
    }
    private fun sendSnapshot(force: Boolean) {
        val writer = output ?: return
        val current = generation
        val snapshot = BatteryState.snapshot()
        if (snapshot["status"] == "reading") return
        val fields = snapshot.filterKeys { it in SNAPSHOT_FIELDS }
        val key = JSONObject(fields).toString()
        if (!force && lastSnapshot == key) return
        lastSnapshot = key
        val payload = JSONObject(fields)
        val updated = snapshot["updatedAt"] as? Long
        payload.remove("updatedAt")
        if (updated != null) payload.put("wearableAgeMs", (System.currentTimeMillis() - updated).coerceAtLeast(0))
        val value = JSONObject().put("v", 1).put("type", "snapshot").put("snapshot", payload).toString()
        sender.execute {
            if (current == generation) runCatching { WirelessWire.write(writer, value) }.onFailure { if (current == generation) runCatching { socket?.close() } }
        }
    }
    private fun message(value: String): JSONObject = JSONObject(value).also {
        require(it.get("v") == 1 && it.get("type") is String) { "INVALID_MESSAGE" }
    }
    private fun tlsContext(peer: WirelessPeer): SSLContext {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw java.security.cert.CertificateException() }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || !WirelessWire.matchesFingerprint(peer.fingerprint, chain[0].encoded)) {
                    throw java.security.cert.CertificateException("IDENTITY_MISMATCH")
                }
                chain[0].checkValidity()
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
    }
    private fun startDiscovery() {
        if (!running || blocked || discovery != null) return
        val config = peer ?: return
        val current = generation
        var resolving = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { main.post { if (discovery === this) discovery = null } }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                main.post {
                    if (current != generation || resolving || !running || !service.serviceName.startsWith("BatMate-${config.id.take(8)}")) return@post
                    resolving = true
                    runCatching {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, error: Int) { main.post { resolving = false } }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                main.post resolvedResult@{
                                    resolving = false
                                    if (current != generation || !running) return@resolvedResult
                                    val id = info.attributes["id"]?.toString(Charsets.UTF_8)
                                    if (id != config.id) return@resolvedResult
                                    @Suppress("DEPRECATION") val host = info.host?.hostAddress ?: return@resolvedResult
                                    resolved = host to info.port
                                    if (!connecting && output == null) { main.removeCallbacks(reconnect); connect() }
                                }
                            }
                        })
                    }.onFailure { resolving = false }
                }
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices("_batmate._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { discovery = null }
    }
    private fun stopDiscovery() { discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null }
    companion object {
        @Volatile private var instance: WirelessSync? = null
        fun get(context: Context): WirelessSync = instance ?: synchronized(this) {
            instance ?: WirelessSync(context.applicationContext).also { instance = it }
        }
        private val SNAPSHOT_FIELDS = setOf("status", "phoneName", "phoneLevel", "phoneCharging", "wearableName",
            "wearableLevel", "wearableCharging", "updatedAt", "backgroundRunning")
    }
}
