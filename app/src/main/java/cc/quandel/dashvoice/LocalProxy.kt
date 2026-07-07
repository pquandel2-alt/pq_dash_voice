package cc.quandel.dashvoice

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Blinder TCP-Relay auf 127.0.0.1: Chromium (und damit die WebView) erlaubt getUserMedia
 * nur in einem "secure context" (https, oder Host localhost/127.0.0.1) — niemals auf einer
 * rohen LAN-IP wie 192.168.178.101, auch nicht über http. Dieser Proxy leitet alle Bytes 1:1
 * an den echten Add-on-Host weiter, sodass die WebView glaubt, mit 127.0.0.1 zu sprechen.
 * Funktioniert transparent für HTTP-Fetches genauso wie für die WebSocket-Live-Verbindung,
 * da auf TCP-Ebene beides nur ein Byte-Strom ist — kein HTTP-Parsing nötig.
 */
object LocalProxy {
    private var serverSocket: ServerSocket? = null
    private var targetHost: String? = null
    private var targetPort: Int = -1
    private val pool = Executors.newCachedThreadPool()

    @Synchronized
    fun ensureStarted(host: String, port: Int): Int {
        val existing = serverSocket
        if (existing != null && !existing.isClosed && targetHost == host && targetPort == port) {
            return existing.localPort
        }
        existing?.close()
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        targetHost = host
        targetPort = port
        pool.execute { acceptLoop(server, host, port) }
        return server.localPort
    }

    private fun acceptLoop(server: ServerSocket, host: String, port: Int) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            pool.execute { handleConnection(client, host, port) }
        }
    }

    private fun handleConnection(client: Socket, host: String, port: Int) {
        val remote = try {
            Socket(host, port)
        } catch (_: IOException) {
            client.close()
            return
        }
        val f1 = pool.submit { relay(client, remote) }
        val f2 = pool.submit { relay(remote, client) }
        try { f1.get() } catch (_: Exception) {}
        try { f2.get() } catch (_: Exception) {}
        client.close()
        remote.close()
    }

    private fun relay(from: Socket, to: Socket) {
        try {
            val buf = ByteArray(8192)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: IOException) {
            // Verbindung beendet/abgebrochen — normal beim Schließen der WebView.
        } finally {
            try { to.shutdownOutput() } catch (_: Exception) {}
        }
    }
}
