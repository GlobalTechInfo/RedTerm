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
            try {
                val cls = Class.forName("android.os.SystemProperties")
                val get = cls.getMethod("get", String::class.java, String::class.java)
                for (i in 1..4) {
                    val value = get.invoke(null, "net.dns$i", "") as String
                    if (value.isNotEmpty() && !servers.contains(value)) {
                        servers.add(value)
                    }
                }
            } catch (_: Exception) {}
        }
        return servers
    }
}
