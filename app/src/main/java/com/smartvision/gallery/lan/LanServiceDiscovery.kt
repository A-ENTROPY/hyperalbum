package com.smartvision.gallery.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 基于 Android NSDManager 的服务发现。
 *
 * 职责：
 * 1. 注册本机 HTTP 照片服务（让局域网其他设备发现我们）
 * 2. 发现局域网中其他设备的 HTTP 照片服务
 *
 * 服务类型：`_http._tcp`（标准 HTTP 服务）
 * 服务名称格式：`Liquid Gallery - {deviceModel}`
 */
class LanServiceDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "LanServiceDiscovery"
        private const val SERVICE_TYPE = "_http._tcp"
        /** TXT 记录 key，用于标识这是 Liquid Gallery 服务 */
        private const val TXT_APP_KEY = "app"
        private const val TXT_APP_VALUE = "liquid_gallery"
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** 发现的服务列表，线程安全 */
    private val _discoveredDevices = CopyOnWriteArrayList<LanPhotoDevice>()
    val discoveredDevices: List<LanPhotoDevice> get() = _discoveredDevices.toList()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serviceResolved: ((LanPhotoDevice) -> Unit)? = null
    private var serviceLost: ((String) -> Unit)? = null
    private var isRegistered = false
    private var isDiscovering = false

    /**
     * 注册本机为 HTTP 照片服务。
     * @param port  NanoHTTPD 监听的端口
     * @param deviceName 设备显示名称
     */
    fun registerService(port: Int, deviceName: String = Build.MODEL) {
        if (isRegistered) return
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "Liquid Gallery - $deviceName"
                serviceType = SERVICE_TYPE
                this.port = port
                // TXT 记录标识此服务
                setAttribute(TXT_APP_KEY, TXT_APP_VALUE)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo?) {
                    isRegistered = true
                    Log.i(TAG, "Service registered: ${info?.serviceName} port=${info?.port}")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "Unregistration failed: errorCode=$errorCode")
                    isRegistered = false
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "Registration failed: errorCode=$errorCode")
                    isRegistered = false
                }
                override fun onServiceUnregistered(info: NsdServiceInfo?) {
                    isRegistered = false
                    Log.i(TAG, "Service unregistered: ${info?.serviceName}")
                }
            }
            registrationListener = listener
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "registerService failed", e)
        }
    }

    /**
     * 开始发现局域网中的 Liquid Gallery HTTP 服务。
     * @param onDeviceFound 发现新设备时回调
     * @param onDeviceLost  设备离线时回调
     */
    fun startDiscovery(
        onDeviceFound: (LanPhotoDevice) -> Unit,
        onDeviceLost: (String) -> Unit = {},
    ) {
        if (isDiscovering) return
        this.serviceResolved = onDeviceFound
        this.serviceLost = onDeviceLost
        try {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String?) {
                    isDiscovering = true
                    Log.i(TAG, "Discovery started: $regType")
                }
                override fun onDiscoveryStopped(serviceType: String?) {
                    isDiscovering = false
                    Log.i(TAG, "Discovery stopped: $serviceType")
                }
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.w(TAG, "Start discovery failed: errorCode=$errorCode")
                    isDiscovering = false
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.w(TAG, "Stop discovery failed: errorCode=$errorCode")
                }
                override fun onServiceFound(info: NsdServiceInfo?) {
                    if (info == null) return
                    Log.d(TAG, "Service found: ${info.serviceName} type=${info.serviceType}")
                    // 只关心 _http._tcp 服务
                    if (info.serviceType != SERVICE_TYPE) return
                    // 解析服务详细信息
                    resolveService(info)
                }
                override fun onServiceLost(info: NsdServiceInfo?) {
                    if (info == null) return
                    Log.d(TAG, "Service lost: ${info.serviceName}")
                    _discoveredDevices.removeAll { it.deviceName == info.serviceName }
                    serviceLost?.invoke(info.serviceName)
                }
            }
            discoveryListener = listener
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "startDiscovery failed", e)
        }
    }

    /** 解析服务详细信息（IP、端口） */
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "Resolve failed: ${info?.serviceName} errorCode=$errorCode")
                }
                override fun onServiceResolved(info: NsdServiceInfo?) {
                    if (info == null) return
                    val host = info.host?.hostAddress ?: return
                    val device = LanPhotoDevice(
                        deviceName = info.serviceName,
                        host = host,
                        port = info.port,
                    )
                    // 去重：相同 host:port 视为同一设备
                    _discoveredDevices.removeAll { it.host == host && it.port == info.port }
                    _discoveredDevices.add(device)
                    Log.i(TAG, "Service resolved: ${device.deviceName} @ ${device.host}:${device.port}")
                    serviceResolved?.invoke(device)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "resolveService failed", e)
        }
    }

    /** 停止服务注册 */
    fun unregisterService() {
        if (!isRegistered) return
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Log.w(TAG, "unregisterService failed", e)
        }
        registrationListener = null
        isRegistered = false
    }

    /** 停止发现服务 */
    fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "stopDiscovery failed", e)
        }
        discoveryListener = null
        isDiscovering = false
        _discoveredDevices.clear()
    }

    /** 完全清理（取消注册 + 停止发现） */
    fun destroy() {
        stopDiscovery()
        unregisterService()
    }
}