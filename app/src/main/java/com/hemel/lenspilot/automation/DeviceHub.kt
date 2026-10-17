package com.hemel.lenspilot.automation

/** এখন চালু থাকা ডিভাইস-সংযোগ (একসাথে একটাই)। AgentActivity বানায়, DeviceConsoleActivity দেখায়/বন্ধ করে। */
object DeviceHub {
    class Connection(
        val agentId: String,
        val transport: DeviceTransport,
        val session: DeviceSession,
        val proxy: LocalProxyServer,
        val connector: WifiConnector?,
        val entryUrl: String
    )

    @Volatile var current: Connection? = null
        private set

    @Synchronized
    fun set(c: Connection) {
        disconnect()
        current = c
    }

    @Synchronized
    fun disconnect() {
        current?.let {
            runCatching { it.proxy.stop() }
            runCatching { it.transport.close() }
            runCatching { it.connector?.release() }
        }
        current = null
    }
}
