package com.redtermapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network

object DnsHelper {
    fun getAndroidDnsServers(context: Context): List<String> {
        val servers = mutableListOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network: Network? = cm.activeNetwork
            if (network != null) {
                val lp: LinkProperties? = cm.getLinkProperties(network)
                if (lp != null) {
                    for (addr in lp.dnsServers) {
                        val host = addr.hostAddress ?: continue
                        if (!servers.contains(host)) servers.add(host)
                    }
                }
            }
        } catch (_: Exception) {}
        if (servers.isEmpty()) {
            for (fallback in listOf("8.8.8.8", "1.1.1.1", "8.8.4.4", "208.67.222.222")) {
                if (!servers.contains(fallback)) servers.add(fallback)
            }
        }
        return servers
    }
}
