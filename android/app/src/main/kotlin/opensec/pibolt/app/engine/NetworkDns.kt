package opensec.pibolt.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Keeps `PREFIX/etc/resolv.conf` matching the default network. Android keeps name servers in netd, not in a file, and
 * glibc needs one. The default network is the VPN when one is up, so its servers are used then. glibc notices a
 * changed resolv.conf by itself, so running engines follow network changes.
 *
 * Private DNS (DNS over TLS) is not used by glibc: queries go to the network's plain resolvers.
 */
class NetworkDns(context: Context, private val etc: File) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val resolv = File(etc, "resolv.conf")

    @Volatile
    private var written: String? = null

    @Volatile
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // In order, on the callback's own thread (one small file): two quick changes (Wi-Fi to mobile data) written
            // from parallel coroutines could land the old network's servers last, and DNS would fail until the next change.
            write(linkProperties)
        }
    }

    /** Write the current servers and follow changes from now on. Blocking; call off the main thread. */
    @Synchronized
    fun start() {
        val hosts = File(etc, "hosts")
        if (!hosts.isFile) EnginePrefix.writeAtomically(hosts, ETC_HOSTS)
        val network = connectivity?.activeNetwork
        network?.let { connectivity.getLinkProperties(it) }?.let(::write)
        if (registered || connectivity == null) return
        registered = runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess
    }

    @Synchronized
    private fun write(properties: LinkProperties) {
        val servers = properties.dnsServers.mapNotNull { it.hostAddress }
        // Offline: keep the last servers; the callback writes new ones when a network comes up.
        if (servers.isEmpty()) return
        val text = resolvConf(servers, properties.domains)
        if (text == written) return
        runCatching { EnginePrefix.writeAtomically(resolv, text) }
            .onSuccess {
                written = text
                val privateDns = if (Build.VERSION.SDK_INT >= 28 && properties.isPrivateDnsActive) " (Private DNS is on; the engine uses plain DNS)" else ""
                Log.i(LocalEngine.TAG, "resolv.conf: ${servers.size} server(s)$privateDns")
            }
            .onFailure { Log.w(LocalEngine.TAG, "resolv.conf not written: $it") }
    }
}
