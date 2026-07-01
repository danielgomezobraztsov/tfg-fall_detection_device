package com.example.falldetector

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdnsNsd {

    private lateinit var context: Context
    private lateinit var events: ServiceEventCollector

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        grantForegroundServiceTestPermissions(context)

        events = ServiceEventCollector(context).start()
    }

    @After
    fun tearDown() {
        stopFallDetectionService(context)

        events.close()
    }

    @Test
    fun connectWithMdns_resolvesRegisteredNsdService_andOpensWebSocket() {
        val fakeArduino = FakeMappableWebSocketServer(
            resetClientAfterHandshake = false
        ).start()

        val nsdService = RegisteredNsdTestService(
            context = context,
            port = fakeArduino.port,
            requestedServiceName = "FallDetectorTest-${System.currentTimeMillis()}"
        ).register()

        try {
            startFallDetectionService(
                context = context,
                address = FallDetectionService.DEFAULT_DEVICE_ADDRESS
            )

            events.awaitLog(
                text = "Connecting to Arduino using ${FallDetectionService.DEFAULT_DEVICE_ADDRESS}",
                timeoutMs = 10_000
            )

            events.awaitLog(
                text = "Searching for Arduino mDNS service _fallws._tcp.",
                timeoutMs = 10_000
            )

            events.awaitLog(
                text = "Found ${nsdService.registeredServiceName}; resolving address...",
                timeoutMs = 20_000
            )

            events.awaitLogMatching(
                regex = Regex("mDNS resolved .*:${fakeArduino.port}"),
                timeoutMs = 20_000
            )

            events.awaitLogMatching(
                regex = Regex("Opening WebSocket ws://.*:${fakeArduino.port}/"),
                timeoutMs = 20_000
            )

            assertTrue(
                "The fake Arduino WebSocket server did not receive a WebSocket handshake. Events:\n${events.dump()}",
                fakeArduino.awaitHandshakeCount(
                    minimumCount = 1,
                    timeoutMs = 20_000
                )
            )

            events.awaitLog(
                text = "Connected to Arduino:",
                timeoutMs = 20_000
            )
        } finally {
            nsdService.close()
            fakeArduino.close()
        }
    }

    @Test
    fun droppedMdnsWebSocketConnection_schedulesReconnect_andRetriesConnection() {
        val fakeArduino = FakeMappableWebSocketServer(
            resetClientAfterHandshake = true
        ).start()

        val nsdService = RegisteredNsdTestService(
            context = context,
            port = fakeArduino.port,
            requestedServiceName = "FallDetectorReconnectTest-${System.currentTimeMillis()}"
        ).register()

        try {
            startFallDetectionService(
                context = context,
                address = FallDetectionService.DEFAULT_DEVICE_ADDRESS
            )

            assertTrue(
                "The fake Arduino WebSocket server did not receive the initial handshake. Events:\n${events.dump()}",
                fakeArduino.awaitHandshakeCount(
                    minimumCount = 1,
                    timeoutMs = 20_000
                )
            )

            events.awaitLog(
                text = "Connected to Arduino:",
                timeoutMs = 20_000
            )

            events.awaitLog(
                text = "Reconectando en 3 seconds",
                timeoutMs = 20_000
            )

            events.awaitLogCountAtLeast(
                text = "Connecting to Arduino using ${FallDetectionService.DEFAULT_DEVICE_ADDRESS}",
                minimumCount = 2,
                timeoutMs = 25_000
            )

            assertTrue(
                "The fake Arduino WebSocket server did not receive a second handshake after reconnect. Events:\n${events.dump()}",
                fakeArduino.awaitHandshakeCount(
                    minimumCount = 2,
                    timeoutMs = 30_000
                )
            )
        } finally {
            nsdService.close()
            fakeArduino.close()
        }
    }
}