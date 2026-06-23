package com.example.synergyclient.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Discovery class that automatically scans for Synergy servers (`_synergy._tcp`) on the local network
 * using native Android mDNS (NsdManager).
 */
class SynergyServerDiscovery(
    context: Context,
    private val onServerFound: (host: String, port: Int) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (discoveryListener != null) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("SynergyDiscovery", "Discovery failed to start: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("SynergyDiscovery", "Discovery failed to stop: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.i("SynergyDiscovery", "mDNS discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.i("SynergyDiscovery", "mDNS discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo ?: return
                Log.i("SynergyDiscovery", "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        Log.e("SynergyDiscovery", "Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                        resolvedInfo ?: return
                        val host = resolvedInfo.host?.hostAddress ?: return
                        val port = resolvedInfo.port
                        Log.i("SynergyDiscovery", "Resolved server at $host:$port")
                        onServerFound(host, port)
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.i("SynergyDiscovery", "Service lost: ${serviceInfo?.serviceName}")
            }
        }

        try {
            nsdManager.discoverServices("_synergy._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e("SynergyDiscovery", "Error starting NSD: ${e.message}")
        }
    }

    fun stop() {
        val listener = discoveryListener ?: return
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (_: Exception) {}
        discoveryListener = null
    }
}
