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
class DobleMensajeCaida {

    private lateinit var context: Context
    private lateinit var events: ServiceEventCollector
    private lateinit var fakeArduino: FakeArduinoWebSocketServer
    private lateinit var contactsBackup: EmergencyContactsBackup

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        grantForegroundServiceTestPermissions(context)

        contactsBackup = EmergencyContactsBackup(context)
        contactsBackup.clearContactsForSafety()

        events = ServiceEventCollector(context).start()
        fakeArduino = FakeArduinoWebSocketServer().start()
    }

    @After
    fun tearDown() {
        cancelFallAlert(context)
        stopFallDetectionService(context)

        fakeArduino.close()
        events.close()
        contactsBackup.close()
    }

    @Test
    fun duplicateFallMessage_whileAlertIsActive_isIgnored() {
        startFallDetectionService(context, "127.0.0.1:${fakeArduino.port}")

        events.awaitLog("Connected to Arduino:")

        assertTrue(
            "Fake Arduino WebSocket did not connect. Events:\n${events.dump()}",
            fakeArduino.awaitConnected()
        )

        fakeArduino.sendFallDetected()

        events.awaitLog("[fall] FALL DETECTED")
        events.awaitLog("Fall detected. Empieza el countdown.")
        events.awaitCountdownBroadcast()

        events.assertLogCount(
            text = "Fall detected. Empieza el countdown.",
            expectedCount = 1
        )

        fakeArduino.sendFallDetected()

        events.awaitLogCountAtLeast(
            text = "[fall] FALL DETECTED",
            minimumCount = 2
        )

        events.awaitLog("Fall alert ya activa. Ignoramdo duplicado.")

        events.assertLogCountRemains(
            text = "Fall detected. Empieza el countdown.",
            expectedCount = 1,
            durationMs = 3_000
        )

        events.assertLogCount(
            text = "Fall alert ya activa. Ignoramdo duplicado.",
            expectedCount = 1
        )

        cancelFallAlert(context)

        events.awaitLog("Fall alert cancelled")
        events.awaitAlertFinishedBroadcast()

        events.assertNoLogsFor(
            durationMs = 3_000,
            forbiddenLogFragments = listOf(
                "Countdown acabadso",
                "Preparando acciones de emergencia",
                "SMS permission granted",
                "Call permission granted",
                "SMS enviado a:",
                "Llamando"
            )
        )
    }
}