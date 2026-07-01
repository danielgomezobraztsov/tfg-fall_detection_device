package com.example.falldetector

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun grantForegroundServiceTestPermissions(context: Context) {
    grantRuntimePermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    grantRuntimePermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        grantRuntimePermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }
}

fun denyRealCallsAndSmsForTest(context: Context) {
    revokeRuntimePermission(context, Manifest.permission.CALL_PHONE)
    revokeRuntimePermission(context, Manifest.permission.SEND_SMS)

    assertTrue(
        "CALL_PHONE must be denied so this test cannot place a real phone call.",
        !isRuntimePermissionGranted(context, Manifest.permission.CALL_PHONE)
    )

    assertTrue(
        "SEND_SMS must be denied so this test cannot send a real SMS.",
        !isRuntimePermissionGranted(context, Manifest.permission.SEND_SMS)
    )
}

fun isRuntimePermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        permission
    ) == PackageManager.PERMISSION_GRANTED
}

fun grantRuntimePermission(context: Context, permission: String) {
    try {
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(context.packageName, permission)
    } catch (_: Exception) {
        executePermissionShellCommand("pm grant ${context.packageName} $permission")
    }
}

fun revokeRuntimePermission(context: Context, permission: String) {
    try {
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .revokeRuntimePermission(context.packageName, permission)
    } catch (_: Exception) {
        executePermissionShellCommand("pm revoke ${context.packageName} $permission")
    }
}

private fun executePermissionShellCommand(command: String) {
    try {
        val result = InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)

        result.close()
    } catch (_: Exception) {
    }
}

fun startFallDetectionService(context: Context, address: String) {
    val intent = Intent(context, FallDetectionService::class.java).apply {
        action = FallDetectionService.ACTION_CONNECT
        putExtra(FallDetectionService.EXTRA_IP, address)
    }

    ContextCompat.startForegroundService(context, intent)
}

fun cancelFallAlert(context: Context) {
    val intent = Intent(context, FallDetectionService::class.java).apply {
        action = FallDetectionService.ACTION_CANCEL_ALERT
    }

    try {
        context.startService(intent)
    } catch (_: IllegalStateException) {
        ContextCompat.startForegroundService(context, intent)
    }
}

fun stopFallDetectionService(context: Context) {
    val intent = Intent(context, FallDetectionService::class.java).apply {
        action = FallDetectionService.ACTION_DISCONNECT
    }

    try {
        context.startService(intent)
    } catch (_: IllegalStateException) {
        ContextCompat.startForegroundService(context, intent)
    }
}

fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs

    while (SystemClock.elapsedRealtime() < deadline) {
        if (condition()) return true
        SystemClock.sleep(50)
    }

    return condition()
}

fun saveSingleEmergencyContactForTest(
    context: Context,
    name: String = "Test Contact",
    phone: String = "5551234"
) {
    EmergencyContactStorage.save(
        context,
        listOf(
            EmergencyContact(
                name = name,
                phone = phone
            )
        )
    )

    assertTrue(
        "The test emergency contact was not saved correctly.",
        waitUntil(timeoutMs = 3_000) {
            val contacts = EmergencyContactStorage.load(context)

            contacts.size == 1 &&
                    contacts[0].name == name &&
                    contacts[0].phone == phone
        }
    )
}

class EmergencyContactsBackup(
    private val context: Context
) : Closeable {

    private val originalContacts: List<EmergencyContact> =
        EmergencyContactStorage.load(context)

    fun clearContactsForSafety() {
        EmergencyContactStorage.save(context, emptyList())

        assertTrue(
            "Emergency contacts were not cleared for the test.",
            waitUntil(timeoutMs = 3_000) {
                EmergencyContactStorage.load(context).isEmpty()
            }
        )
    }

    override fun close() {
        EmergencyContactStorage.save(context, originalContacts)
    }
}

class ServiceEventCollector(
    private val context: Context
) : Closeable {

    private val monitor = Object()

    private val logMessages = CopyOnWriteArrayList<String>()
    private val countdownValues = CopyOnWriteArrayList<Int>()
    private val alertFinishedEvents = CopyOnWriteArrayList<Long>()

    private var receiver: BroadcastReceiver? = null

    fun start(): ServiceEventCollector {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    FallDetectionService.BROADCAST_LOG -> {
                        val message = intent
                            .getStringExtra(FallDetectionService.EXTRA_MESSAGE)
                            ?: return

                        logMessages.add(message)
                    }

                    FallDetectionService.BROADCAST_ALERT_COUNTDOWN -> {
                        val seconds = intent.getIntExtra(
                            FallDetectionService.EXTRA_SECONDS_REMAINING,
                            -1
                        )

                        countdownValues.add(seconds)
                    }

                    FallDetectionService.BROADCAST_ALERT_FINISHED -> {
                        alertFinishedEvents.add(SystemClock.elapsedRealtime())
                    }
                }

                synchronized(monitor) {
                    monitor.notifyAll()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(FallDetectionService.BROADCAST_LOG)
            addAction(FallDetectionService.BROADCAST_ALERT_COUNTDOWN)
            addAction(FallDetectionService.BROADCAST_ALERT_FINISHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }

        return this
    }

    fun awaitLog(text: String, timeoutMs: Long = 10_000) {
        val found = waitFor(timeoutMs) {
            logMessages.any { it.contains(text) }
        }

        assertTrue(
            "Expected log containing '$text' within ${timeoutMs}ms. Events:\n${dump()}",
            found
        )
    }

    fun awaitLogMatching(regex: Regex, timeoutMs: Long = 10_000) {
        val found = waitFor(timeoutMs) {
            logMessages.any { regex.containsMatchIn(it) }
        }

        assertTrue(
            "Expected log matching '${regex.pattern}' within ${timeoutMs}ms. Events:\n${dump()}",
            found
        )
    }

    fun awaitLogCountAtLeast(
        text: String,
        minimumCount: Int,
        timeoutMs: Long = 10_000
    ) {
        val found = waitFor(timeoutMs) {
            logCount(text) >= minimumCount
        }

        assertTrue(
            "Expected at least $minimumCount log(s) containing '$text' within ${timeoutMs}ms. Events:\n${dump()}",
            found
        )
    }

    fun awaitCountdownBroadcast(timeoutMs: Long = 5_000) {
        val found = waitFor(timeoutMs) {
            countdownValues.isNotEmpty()
        }

        assertTrue(
            "Expected at least one countdown broadcast within ${timeoutMs}ms. Events:\n${dump()}",
            found
        )
    }

    fun awaitAlertFinishedBroadcast(timeoutMs: Long = 5_000) {
        val found = waitFor(timeoutMs) {
            alertFinishedEvents.isNotEmpty()
        }

        assertTrue(
            "Expected an alert-finished broadcast within ${timeoutMs}ms. Events:\n${dump()}",
            found
        )
    }

    fun logCount(text: String): Int {
        return logMessages.count { it.contains(text) }
    }

    fun assertLogCount(
        text: String,
        expectedCount: Int
    ) {
        val actualCount = logCount(text)

        assertTrue(
            "Expected log '$text' to appear $expectedCount time(s), but it appeared $actualCount time(s). Events:\n${dump()}",
            actualCount == expectedCount
        )
    }

    fun assertLogCountRemains(
        text: String,
        expectedCount: Int,
        durationMs: Long
    ) {
        val deadline = SystemClock.elapsedRealtime() + durationMs

        synchronized(monitor) {
            while (true) {
                val actualCount = logCount(text)

                if (actualCount != expectedCount) {
                    fail(
                        "Expected log '$text' to remain at count $expectedCount, but it became $actualCount. Events:\n${dump()}"
                    )
                }

                val remainingMs = deadline - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) return

                monitor.wait(minOf(remainingMs, 250L))
            }
        }
    }

    fun assertNoLogsFor(
        durationMs: Long,
        forbiddenLogFragments: List<String>
    ) {
        val deadline = SystemClock.elapsedRealtime() + durationMs

        synchronized(monitor) {
            while (true) {
                val forbiddenLog = logMessages.firstOrNull { message ->
                    forbiddenLogFragments.any { forbidden ->
                        message.contains(forbidden)
                    }
                }

                if (forbiddenLog != null) {
                    fail(
                        "Forbidden log appeared: '$forbiddenLog'. Events:\n${dump()}"
                    )
                }

                val remainingMs = deadline - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) return

                monitor.wait(minOf(remainingMs, 250L))
            }
        }
    }

    fun dump(): String {
        return buildString {
            appendLine("Logs:")
            appendLine(
                logMessages
                    .takeLast(180)
                    .joinToString(separator = "\n")
            )

            appendLine()
            appendLine("Countdown broadcasts:")
            appendLine(countdownValues.joinToString(separator = ", "))

            appendLine()
            appendLine("Alert-finished broadcast count:")
            appendLine(alertFinishedEvents.size)
        }
    }

    override fun close() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }

        receiver = null
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs

        synchronized(monitor) {
            while (true) {
                if (condition()) return true

                val remainingMs = deadline - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) return false

                monitor.wait(remainingMs)
            }
        }
    }
}

class FakeArduinoWebSocketServer : Closeable {

    private val serverSocket = ServerSocket(
        0,
        50,
        InetAddress.getByName("127.0.0.1")
    )

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())

    private val handshakeLatch = CountDownLatch(1)
    private val writeLock = Object()

    @Volatile
    private var connectedSocket: Socket? = null

    @Volatile
    private var running = true

    val port: Int = serverSocket.localPort

    fun start(): FakeArduinoWebSocketServer {
        executor.execute { acceptLoop() }
        return this
    }

    fun awaitConnected(timeoutMs: Long = 10_000): Boolean {
        return handshakeLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun sendFallDetected() {
        sendText(
            """
                {"type":"fall","message":"FALL DETECTED"}
            """.trimIndent()
        )
    }

    fun sendText(text: String) {
        if (!awaitConnected()) {
            throw AssertionError("Cannot send message because the WebSocket handshake did not complete.")
        }

        val socket = connectedSocket
            ?: throw AssertionError("Cannot send message because no WebSocket client is connected.")

        synchronized(writeLock) {
            sendTextWebSocketFrame(socket, text)
        }
    }

    override fun close() {
        running = false

        try {
            serverSocket.close()
        } catch (_: Exception) {
            // Ignore cleanup errors.
        }

        synchronized(sockets) {
            sockets.forEach { socket ->
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }

            sockets.clear()
        }

        executor.shutdownNow()
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = serverSocket.accept()
                sockets.add(socket)
                connectedSocket = socket
                executor.execute { handleClient(socket) }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10_000

            val secWebSocketKey = readWebSocketKey(socket)
            val acceptKey = buildWebSocketAcceptKey(secWebSocketKey)

            val response = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: $acceptKey\r\n")
                append("\r\n")
            }

            synchronized(writeLock) {
                socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }

            handshakeLatch.countDown()

            keepSocketOpenUntilStopped(socket)
        } catch (_: Exception) {
        } finally {
            sockets.remove(socket)

            if (connectedSocket == socket) {
                connectedSocket = null
            }
        }
    }

    private fun readWebSocketKey(socket: Socket): String {
        val reader = BufferedReader(
            InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
        )

        var key: String? = null

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break

            if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                key = line.substringAfter(':').trim()
            }
        }

        return key ?: throw IllegalStateException("Missing Sec-WebSocket-Key header")
    }

    private fun buildWebSocketAcceptKey(secWebSocketKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(
            (secWebSocketKey + WEBSOCKET_GUID).toByteArray(Charsets.ISO_8859_1)
        )

        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun sendTextWebSocketFrame(socket: Socket, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)

        require(payload.size <= 125) {
            "This simple fake Arduino server only supports short text frames."
        }

        val frameHeader = byteArrayOf(
            0x81.toByte(),
            payload.size.toByte()
        )

        socket.getOutputStream().write(frameHeader)
        socket.getOutputStream().write(payload)
        socket.getOutputStream().flush()
    }

    private fun keepSocketOpenUntilStopped(socket: Socket) {
        val input = socket.getInputStream()
        socket.soTimeout = 0

        while (running && !socket.isClosed) {
            val readResult = input.read()
            if (readResult == -1) return
        }
    }

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}

class FakeMappableWebSocketServer(
    private val resetClientAfterHandshake: Boolean = false
) : Closeable {
    private val serverSocket = ServerSocket(0)

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())

    private val monitor = Object()
    private val handshakeCount = AtomicInteger(0)

    @Volatile
    private var running = true

    val port: Int = serverSocket.localPort

    fun start(): FakeMappableWebSocketServer {
        executor.execute { acceptLoop() }
        return this
    }

    fun awaitHandshakeCount(
        minimumCount: Int,
        timeoutMs: Long = 20_000
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs

        synchronized(monitor) {
            while (true) {
                if (handshakeCount.get() >= minimumCount) return true

                val remainingMs = deadline - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) return false

                monitor.wait(remainingMs)
            }
        }
    }

    override fun close() {
        running = false

        try {
            serverSocket.close()
        } catch (_: Exception) {
        }

        synchronized(sockets) {
            sockets.forEach { socket ->
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }

            sockets.clear()
        }

        executor.shutdownNow()
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = serverSocket.accept()
                sockets.add(socket)
                executor.execute { handleClient(socket) }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10_000

            val secWebSocketKey = readWebSocketKey(socket)
            val acceptKey = buildWebSocketAcceptKey(secWebSocketKey)

            val response = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: $acceptKey\r\n")
                append("\r\n")
            }

            socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()

            handshakeCount.incrementAndGet()

            synchronized(monitor) {
                monitor.notifyAll()
            }

            if (resetClientAfterHandshake) {
                Thread.sleep(500)
                socket.setSoLinger(true, 0)
                socket.close()
            } else {
                keepSocketOpenUntilStopped(socket)
            }
        } catch (_: Exception) {
        } finally {
            sockets.remove(socket)
        }
    }

    private fun readWebSocketKey(socket: Socket): String {
        val reader = BufferedReader(
            InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
        )

        var key: String? = null

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break

            if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                key = line.substringAfter(':').trim()
            }
        }

        return key ?: throw IllegalStateException("Missing Sec-WebSocket-Key header")
    }

    private fun buildWebSocketAcceptKey(secWebSocketKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(
            (secWebSocketKey + WEBSOCKET_GUID).toByteArray(Charsets.ISO_8859_1)
        )

        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun keepSocketOpenUntilStopped(socket: Socket) {
        val input = socket.getInputStream()
        socket.soTimeout = 0

        while (running && !socket.isClosed) {
            val readResult = input.read()
            if (readResult == -1) return
        }
    }

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}

class RegisteredNsdTestService(
    private val context: Context,
    private val port: Int,
    private val requestedServiceName: String
) : Closeable {

    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var listener: NsdManager.RegistrationListener? = null

    lateinit var registeredServiceName: String
        private set

    fun register(timeoutMs: Long = 15_000): RegisteredNsdTestService {
        val latch = CountDownLatch(1)
        var registrationFailure: Int? = null

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = requestedServiceName
            serviceType = "_fallws._tcp."
            port = this@RegisteredNsdTestService.port

            setAttribute("source", "androidTest")
        }

        listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registeredServiceName = serviceInfo.serviceName
                latch.countDown()
            }

            override fun onRegistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int
            ) {
                registrationFailure = errorCode
                latch.countDown()
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int
            ) = Unit
        }

        val registrationListener = listener
            ?: throw AssertionError("NSD listener was not created")

        nsdManager.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener
        )

        assertTrue(
            "Timed out registering fake NSD service '$requestedServiceName' on port $port.",
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        )

        registrationFailure?.let { errorCode ->
            fail("Fake NSD service registration failed with error code $errorCode.")
        }

        return this
    }

    override fun close() {
        listener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (_: IllegalArgumentException) {
            }
        }

        listener = null
    }
}