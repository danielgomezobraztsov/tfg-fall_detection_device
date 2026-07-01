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
class CallNoService {

    private lateinit var context: Context
    private lateinit var events: ServiceEventCollector
    private lateinit var fakeArduino: FakeArduinoWebSocketServer
    private lateinit var contactsBackup: EmergencyContactsBackup

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        grantForegroundServiceTestPermissions(context)

        denyRealCallsAndSmsForTest(context)

        contactsBackup = EmergencyContactsBackup(context)

        saveSingleEmergencyContactForTest(
            context = context,
            name = "Test Contact",
            phone = "5551234"
        )

        events = ServiceEventCollector(context).start()
        fakeArduino = FakeArduinoWebSocketServer().start()
    }

    @After
    fun tearDown() {
        stopFallDetectionService(context)

        fakeArduino.close()
        events.close()
        contactsBackup.close()

    }

    @Test
    fun fallEmergencyAction_whenPhoneCallIsUnavailable_doesNotCrashApp() {
        startFallDetectionService(context, "127.0.0.1:${fakeArduino.port}")

        events.awaitLog("Connected to Arduino:")

        assertTrue(
            "Fake Arduino WebSocket did not connect. Events:\n${events.dump()}",
            fakeArduino.awaitConnected()
        )

        fakeArduino.sendFallDetected()

        events.awaitLog("[fall] FALL DETECTED")
        events.awaitLog("Fall detected. Empieza el countdown.")

        events.awaitLog(
            text = "Countdown acabadso",
            timeoutMs = 25_000
        )

        events.awaitLog("Preparando acciones de emergencia")
        events.awaitLog("Loaded 1 emergency contacts")

        events.awaitLog(
            text = "Missing SMS permission",
            timeoutMs = 15_000
        )

        events.awaitLog("Call permission granted: false")
        events.awaitLog("Missing call permission")
    }
}