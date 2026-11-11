package com.redtermapp.util

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The one network observation a phone can make about a mosh path: aim a
 * datagram at a port that is surely closed and watch what the kernel says.
 *
 * A host whose firewall admits UDP answers with ICMP "port unreachable", so
 * [MoshUdpProbe.Signal.PortUnreachable] means the UDP path is open and mosh's
 * silence has a different cause. A host or carrier that swallows UDP gives no
 * answer, so [MoshUdpProbe.Signal.NoAnswer] means it is likely blocked.
 *
 * Runs on a worker thread: it blocks up to [deadlineMs] waiting for the
 * kernel's answer, which has no business happening on the main thread.
 */
object UdpHoleProbe {

    /** The observed [MoshUdpProbe.Signal] for [host], bounded by [deadlineMs]. */
    fun probe(host: String, deadlineMs: Long): MoshUdpProbe.Signal =
        probe(host, MoshUdpProbe.CLOSED_PROBE_PORT, deadlineMs)

    fun probe(host: String, port: Int, deadlineMs: Long): MoshUdpProbe.Signal {
        val address: InetAddress = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            return MoshUdpProbe.Signal.Unknown
        }
        val socket = DatagramSocket()
        try {
            socket.soTimeout = deadlineMs.toInt().coerceIn(1, 30_000)
            // One byte is enough: the content is irrelevant, only the
            // kernel's answer (or its absence) is the signal.
            socket.send(DatagramPacket(byteArrayOf(0), 1, address, port))
            val reply = ByteArray(64)
            val received = try {
                socket.receive(DatagramPacket(reply, reply.size))
            } catch (e: java.net.SocketTimeoutException) {
                null
            } catch (e: java.net.SocketException) {
                // The kernel reported the ICMP refusal as an error on the
                // socket. This is the "UDP reached the host" answer.
                return MoshUdpProbe.Signal.PortUnreachable
            }
            return if (received != null) MoshUdpProbe.Signal.Unknown
            else MoshUdpProbe.Signal.NoAnswer
        } catch (_: Exception) {
            return MoshUdpProbe.Signal.Unknown
        } finally {
            socket.close()
        }
    }
}
