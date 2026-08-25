package com.smartvision.gallery.lan.smb

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 局域网 SMB 设备发现。
 *
 * 主路径：手动添加（[ManualDiscovery]）
 * 辅助发现：NetBIOS Name Service (UDP 137) 广播查询
 * 次要发现：mDNS (UDP 5353)
 *
 * 注意：不保证覆盖所有设备。
 * - NetBIOS 仅限 Windows 设备
 * - mDNS 覆盖 macOS/Linux SMB 服务器
 * - Android 厂商/Roaming 可能限制 UDP 广播
 * - 现代网络（IPv6 优先）可能禁用 NetBIOS
 */
class SmbDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "SmbDiscovery"
        private const val NETBIOS_PORT = 137
        private const val MDNS_PORT = 5353
        private const val TIMEOUT_MS = 2000
    }

    data class DiscoveredHost(
        val hostName: String,
        val ipAddress: String,
        val source: String, // "netbios", "mdns", "manual"
    )

    /**
     * 通过 NetBIOS Name Service 查询局域网中的 Windows 设备。
     * 发送 NBT 状态查询广播到 192.168.1.255:137。
     * 返回响应设备列表。
     */
    suspend fun discoverNetbios(): List<DiscoveredHost> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val result = mutableListOf<DiscoveredHost>()
        try {
            val broadcastAddr = getBroadcastAddress() ?: return@withContext result
            val socket = DatagramSocket()
            socket.soTimeout = TIMEOUT_MS
            socket.broadcast = true

            // NBT 状态查询请求包（48 字节）
            val requestData = ByteArray(48).apply {
                // Transaction ID
                this[0] = 0x00; this[1] = 0x00
                // Flags: 0x0010 (standard query, no recursion)
                this[2] = 0x00; this[3] = 0x10
                // Questions: 1
                this[4] = 0x00; this[5] = 0x01
                // Answer RRs: 0
                this[6] = 0x00; this[7] = 0x00
                // Authority RRs: 0
                this[8] = 0x00; this[9] = 0x00
                // Additional RRs: 0
                this[10] = 0x00; this[11] = 0x00
                // Query name: "*<00>" (NetBIOS name type 00 = Workstation)
                // Encoded as 0x20 + 16 bytes of space-padded name + 0x00
                this[12] = 0x20 // length prefix
                // "*" + 15 spaces
                for (i in 0 until 16) {
                    this[13 + i] = 0x20 // space (0x20)
                }
                this[13] = 0x43 // 'C' — NetBIOS wildcard first byte
                this[29] = 0x00 // null terminator for name
                // Type: NB (0x0020) = NetBIOS general name service
                this[30] = 0x00; this[31] = 0x20
                // Class: IN (0x0001)
                this[32] = 0x00; this[33] = 0x01
            }

            val packet = DatagramPacket(requestData, requestData.size, broadcastAddr, NETBIOS_PORT)
            socket.send(packet)

            // 收集响应
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < TIMEOUT_MS) {
                try {
                    val responseBuf = ByteArray(1024)
                    val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
                    socket.receive(responsePacket)

                    val responseData = responsePacket.data
                    if (responseData.size < 60) continue

                    // 解析响应中的 NetBIOS 名称
                    val nameBytes = responseData.copyOfRange(57, 73)
                    val name = String(nameBytes, Charsets.UTF_8).trim()
                    if (name.isNotBlank()) {
                        val ip = responsePacket.address.hostAddress ?: continue
                        result.add(DiscoveredHost(
                            hostName = name,
                            ipAddress = ip,
                            source = "netbios",
                        ))
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
            }
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "NetBIOS discovery failed", e)
        }
        result.distinctBy { it.ipAddress }
    }

    private fun getBroadcastAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.interfaceAddresses) {
                    val broadcast = addr.broadcast ?: continue
                    return broadcast
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getBroadcastAddress failed", e)
        }
        return null
    }
}