package dev.pcremote.app.data

data class ComputerCredentials(val id: String, val name: String, val host: String, val port: Int, val token: String, val certificateSha256: String)
data class DiscoveredComputer(val id: String, val name: String, val host: String, val port: Int, val certificateSha256: String)
data class RemoteApp(val id: String, val name: String, val iconPngBase64: String?, val windowCount: Int)
data class PairingPayload(val id: String, val name: String, val port: Int, val secret: String, val certificateSha256: String)
