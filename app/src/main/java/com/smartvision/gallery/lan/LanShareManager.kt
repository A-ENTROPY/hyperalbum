package com.smartvision.gallery.lan

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.Formatter
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网共享管理器，协调服务器、发现和客户端。
 *
 * 单例模式，通过 [getInstance] 获取。
 * 生命周期：
 *  1. [start] — 启动 HTTP 服务器 + 注册 NSD 服务 + 开始发现
 *  2. 运行中 — 服务器响应请求，发现收集设备列表
 *  3. [stop] — 停止所有服务
 */
class LanShareManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LanShareManager"
        @Volatile private var instance: LanShareManager? = null

        fun getInstance(context: Context): LanShareManager {
            return instance ?: synchronized(this) {
                instance ?: LanShareManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val deviceName = Build.MODEL
    private val server: LanPhotoServer
    private val discovery: LanServiceDiscovery
    val client: LanPhotoClient = LanPhotoClient()

    @Volatile var isRunning: Boolean = false; private set
    @Volatile var serverPort: Int = 0; private set
    @Volatile var serverHost: String = "0.0.0.0"; private set

    init {
        server = LanPhotoServer(context, LanPhotoServer.DEFAULT_PORT, deviceName)
        discovery = LanServiceDiscovery(context)
    }

    /** 获取本机局域网 IP 地址 */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        // 排除 0.0.0.0 和 127.0.0.1
                        if (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getLocalIpAddress failed", e)
        }
        // fallback
        return try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifi.connectionInfo.ipAddress
            Formatter.formatIpAddress(ipInt)
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    /** 启动服务器和发现 */
    fun start() {
        if (isRunning) return
        try {
            serverHost = getLocalIpAddress()

            // 启动 NanoHTTPD
            server.start()
            serverPort = server.listeningPort
            android.util.Log.i(TAG, "Server started at $serverHost:$serverPort")

            // 注册 NSD 服务
            discovery.registerService(serverPort, deviceName)

            // 开始发现其他设备
            discovery.startDiscovery(
                onDeviceFound = { device ->
                    android.util.Log.i(TAG, "Device found: ${device.deviceName} @ ${device.host}:${device.port}")
                },
                onDeviceLost = { name ->
                    android.util.Log.i(TAG, "Device lost: $name")
                }
            )

            isRunning = true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "start failed", e)
            stop()
        }
    }

    /** 停止服务器和发现 */
    fun stop() {
        isRunning = false
        try {
            server.stop()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "stop server failed", e)
        }
        discovery.destroy()
        android.util.Log.i(TAG, "LanShareManager stopped")
    }

    /** 获取当前已发现的设备列表 */
    fun getDiscoveredDevices(): List<LanPhotoDevice> = discovery.discoveredDevices

    /** 获取本机设备信息 */
    fun getOwnDevice(): LanPhotoDevice = LanPhotoDevice(
        deviceName = "本机 - $deviceName",
        host = serverHost,
        port = serverPort,
    )
}