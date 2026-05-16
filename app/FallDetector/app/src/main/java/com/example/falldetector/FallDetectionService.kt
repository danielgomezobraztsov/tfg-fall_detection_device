package com.example.falldetector

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import android.util.Log
import android.location.Location

class FallDetectionService : Service() {

    companion object {
        const val ACTION_CONNECT = "com.example.falldetector.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.falldetector.ACTION_DISCONNECT"
        const val ACTION_CANCEL_ALERT = "com.example.falldetector.ACTION_CANCEL_ALERT"

        const val EXTRA_IP = "extra_ip"

        const val BROADCAST_LOG = "com.example.falldetector.BROADCAST_LOG"
        const val BROADCAST_FALL_ALERT = "com.example.falldetector.BROADCAST_FALL_ALERT"
        const val BROADCAST_ALERT_COUNTDOWN = "com.example.falldetector.BROADCAST_ALERT_COUNTDOWN"
        const val BROADCAST_ALERT_FINISHED = "com.example.falldetector.BROADCAST_ALERT_FINISHED"

        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_SECONDS_REMAINING = "extra_seconds_remaining"

        private const val CHANNEL_ID = "fall_detector_monitoring"
        private const val NOTIFICATION_ID = 1001

        private const val ALERT_COUNTDOWN_SECONDS = 15
    }

    private enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var webSocket: WebSocket? = null
    private var currentIp: String? = null

    private var connectionState = ConnectionState.DISCONNECTED
    private var reconnectAttempts = 0
    private var userDisconnected = false

    /*
     * connectionGeneration prevents old WebSocket callbacks from affecting
     * the current connection.
     *
     * Example:
     * 1. Old socket closes.
     * 2. New socket is already connected.
     * 3. Old onClosed() fires late.
     *
     * Without this guard, the old callback could schedule a reconnect and
     * destabilize the new connection.
     */
    private var connectionGeneration = 0

    private var reconnectRunnable: Runnable? = null

    private var countdownTimer: CountDownTimer? = null
    private var alertActive = false
    private var secondsRemaining = ALERT_COUNTDOWN_SECONDS

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            buildNormalNotification("Monitorizacion inactiva")
        )

        broadcastLog("FallDetectionService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val ip = intent.getStringExtra(EXTRA_IP)

                if (ip.isNullOrBlank()) {
                    broadcastLog("Cannot connect: IP vacia")
                } else {
                    userDisconnected = false
                    connect(ip)
                }
            }

            ACTION_DISCONNECT -> {
                userDisconnected = true
                disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_CANCEL_ALERT -> {
                cancelFallAlert()
            }

            else -> {
                broadcastLog("Service started without explicit action")
            }
        }

        return START_STICKY
    }

    private fun connect(ip: String) {
        if (
            currentIp == ip &&
            (connectionState == ConnectionState.CONNECTED ||
                    connectionState == ConnectionState.CONNECTING ||
                    connectionState == ConnectionState.RECONNECTING)
        ) {
            broadcastLog("Ya conectado a $ip. Se ignora.")
            return
        }

        cancelScheduledReconnect()

        currentIp = ip
        connectionState = ConnectionState.CONNECTING
        connectionGeneration++

        val myGeneration = connectionGeneration

        broadcastLog("Connecting to $ip")
        updateNotification(buildNormalNotification("Connecting a Arduino: $ip"))

        webSocket?.close(1000, "Se remplaza la conn")
        webSocket = null

        val request = Request.Builder()
            .url("ws://$ip:81/")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post {
                    if (myGeneration != connectionGeneration) return@post

                    connectionState = ConnectionState.CONNECTED
                    reconnectAttempts = 0

                    broadcastLog("Connected to $ip")
                    updateNotification(buildNormalNotification("Connected to Arduino: $ip"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                mainHandler.post {
                    if (myGeneration != connectionGeneration) return@post
                    handleEsp32Message(text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                mainHandler.post {
                    if (myGeneration != connectionGeneration) return@post
                    broadcastLog("Msg binario")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post {
                    if (myGeneration != connectionGeneration) return@post

                    connectionState = ConnectionState.DISCONNECTED
                    broadcastLog("Disconnected: $reason")

                    if (!userDisconnected) {
                        scheduleReconnect()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post {
                    if (myGeneration != connectionGeneration) return@post

                    connectionState = ConnectionState.DISCONNECTED
                    broadcastLog("Error de conn: ${t.message}")

                    if (!userDisconnected) {
                        scheduleReconnect()
                    }
                }
            }
        })
    }

    private fun handleEsp32Message(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "msg")
            val message = json.optString("message", text)

            broadcastLog("[$type] $message")

            if (
                type.equals("fall", ignoreCase = true) ||
                message.contains("FALL DETECTED", ignoreCase = true)
            ) {
                startFallAlertCountdown()
            }

        } catch (e: Exception) {
            broadcastLog(text)

            if (text.contains("FALL DETECTED", ignoreCase = true)) {
                startFallAlertCountdown()
            }
        }
    }

    private fun startFallAlertCountdown() {
        if (alertActive) {
            broadcastLog("Fall alert ya activa. Ignoramdo duplicado.")
            return
        }

        alertActive = true
        secondsRemaining = ALERT_COUNTDOWN_SECONDS

        broadcastLog("Fall detected. Empieza el countdown.")
        broadcastFallAlert()
        broadcastCountdown(secondsRemaining)

        updateNotification(
            buildAlertNotification("Fall detected. Acciones de emergencia en $secondsRemaining s.")
        )

        countdownTimer?.cancel()

        countdownTimer = object : CountDownTimer(
            ALERT_COUNTDOWN_SECONDS * 1000L,
            1000L
        ) {
            override fun onTick(millisUntilFinished: Long) {
                secondsRemaining = (millisUntilFinished / 1000L).toInt()

                broadcastCountdown(secondsRemaining)
                broadcastLog("Acciones de emergencia en $secondsRemaining s")

                updateNotification(
                    buildAlertNotification(
                        "Fall detected. Acciones de emergencia en $secondsRemaining s."
                    )
                )
            }

            override fun onFinish() {
                secondsRemaining = 0
                broadcastCountdown(0)

                alertActive = false
                countdownTimer = null

                broadcastLog("Countdown acabadso")
                updateNotification(buildNormalNotification("Exec de acciones de emergencia"))

                executeEmergencyActions()

                broadcastAlertFinished()
                updateNotification(buildNormalNotification("Connected to Arduino"))
            }
        }.start()
    }

    private fun cancelFallAlert() {
        if (!alertActive) {
            broadcastLog("Se ha tocado el boton de falsa alarma sin que haya accion de caida.")
            updateNotification(
                buildNormalNotification(
                    if (connectionState == ConnectionState.CONNECTED) {
                        "Connected to Arduino"
                    } else {
                        "Monitoring active"
                    }
                )
            )
            return
        }

        countdownTimer?.cancel()
        countdownTimer = null

        alertActive = false
        secondsRemaining = ALERT_COUNTDOWN_SECONDS

        broadcastLog("Fall alert cancelled")
        broadcastAlertFinished()

        updateNotification(
            buildNormalNotification(
                if (connectionState == ConnectionState.CONNECTED) {
                    "Connected to esp32"
                } else {
                    "Monitoring active"
                }
            )
        )
    }

    private fun executeEmergencyActions() {
        broadcastLog("Preparando acciones de emergencia")

        val contacts = EmergencyContactStorage.load(this)

        broadcastLog("Loaded ${contacts.size} emergency contacts")

        contacts.forEachIndexed { index, contact ->
            broadcastLog("Contacto $index: Nombre='${contact.name}', Tel='${contact.phone}'")
        }

        if (contacts.isEmpty()) {
            broadcastLog("Sin contactos")
            return
        }

        val locationPermissionGranted = EmergencyLocationProvider.hasLocationPermission(this)
        broadcastLog("Loc permission granted: $locationPermissionGranted")

        broadcastLog("Missing Loc permission")

        EmergencyLocationProvider.getEmergencyLocation(this) { location ->
            if (location != null) {
                broadcastLog(
                    "Localizacion: lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}"
                )
            } else {
                broadcastLog("Loc no disponible")
            }

            val emergencyMessage = buildEmergencySmsMessage(location)

            sendEmergencySms(
                contacts = contacts,
                message = emergencyMessage
            )

            callFirstContact(contacts)
        }
    }

    private fun buildEmergencySmsMessage(location: android.location.Location?): String {
        val baseMessage =
            "Caida detectada!! Mensaje automatico de la app Fall Detector."

        if (location == null) {
            return "$baseMessage\n\nUbicacion no disponible."
        }

        val mapsLink = EmergencyLocationProvider.buildMapsLink(location)

        val accuracyText =
            if (location.hasAccuracy()) {
                "\nPrecision aproximada: ±${location.accuracy.toInt()} m"
            } else {
                ""
            }

        return "$baseMessage\n\nUbicacion aproximada:\n$mapsLink$accuracyText"
    }

    private fun sendEmergencySms(
        contacts: List<EmergencyContact>,
        message: String
    ) {
        val hasPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
                    PackageManager.PERMISSION_GRANTED

        broadcastLog("SMS permission granted: $hasPermission")

        if (!hasPermission) {
            broadcastLog("Missing SMS permission")
            return
        }

        try {
            val smsManager = SmsManager.getDefault()

            contacts.forEach { contact ->
                val messageParts = smsManager.divideMessage(message)

                if (messageParts.size > 1) {
                    smsManager.sendMultipartTextMessage(
                        contact.phone,
                        null,
                        messageParts,
                        null,
                        null
                    )
                } else {
                    smsManager.sendTextMessage(
                        contact.phone,
                        null,
                        message,
                        null,
                        null
                    )
                }

                broadcastLog("SMS enviado a: ${contact.name}")
            }

        } catch (e: Exception) {
            broadcastLog("SMS error: ${e.message}")
        }
    }

    private fun callFirstContact(contacts: List<EmergencyContact>) {
        val hasPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                    PackageManager.PERMISSION_GRANTED

        broadcastLog("Call permission granted: $hasPermission")

        if (!hasPermission) {
            broadcastLog("Missing call permission")
            return
        }

        val first = contacts.firstOrNull()

        if (first == null) {
            broadcastLog("Sin contactos")
            return
        }

        val phoneNumber = normalizePhoneNumber(first.phone)

        if (phoneNumber.isBlank()) {
            broadcastLog("No se puede llamar a ${first.name}: numero invalido")
            return
        }

        try {
            val telecomManager = getSystemService(TelecomManager::class.java)

            if (telecomManager == null) {
                broadcastLog("Call error: No hay servicio/cobertura??")
                return
            }

            val callUri = Uri.fromParts("tel", phoneNumber, null)
            val extras = Bundle().apply {
                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            }

            telecomManager.placeCall(callUri, extras)
            broadcastLog("Llamando ${first.name} at $phoneNumber")
            updateNotification(buildNormalNotification("Llamando ${first.name}"))

        } catch (e: SecurityException) {
            broadcastLog("Call security error: ${e.message}")
        } catch (e: Exception) {
            broadcastLog("Call error: ${e.message}")
        }
    }

    private fun normalizePhoneNumber(raw: String): String {
        val trimmed = raw.trim()
        val firstDialable = trimmed.firstOrNull { char ->
            char.isDigit() || char == '+' || char == '*' || char == '#'
        }
        val hasLeadingPlus = firstDialable == '+'
        val dialableWithoutPlus = trimmed.filter { char ->
            char.isDigit() || char == '*' || char == '#' || char == ',' || char == ';'
        }

        return if (hasLeadingPlus) {
            "+$dialableWithoutPlus"
        } else {
            dialableWithoutPlus
        }
    }

    private fun scheduleReconnect() {
        val ip = currentIp ?: return

        if (userDisconnected) return

        cancelScheduledReconnect()

        connectionState = ConnectionState.RECONNECTING

        reconnectAttempts++
        val delayMs = minOf(30000L, reconnectAttempts * 3000L)

        broadcastLog("Reconectando en ${delayMs / 1000} seconds")
        updateNotification(buildNormalNotification("Conn lost. Reconectando..."))

        reconnectRunnable = Runnable {
            if (!userDisconnected && currentIp == ip) {
                connectionState = ConnectionState.DISCONNECTED
                connect(ip)
            }
        }

        mainHandler.postDelayed(reconnectRunnable!!, delayMs)
    }

    private fun cancelScheduledReconnect() {
        reconnectRunnable?.let {
            mainHandler.removeCallbacks(it)
        }

        reconnectRunnable = null
    }

    private fun disconnect() {
        cancelScheduledReconnect()

        countdownTimer?.cancel()
        countdownTimer = null
        alertActive = false

        connectionGeneration++
        connectionState = ConnectionState.DISCONNECTED

        webSocket?.close(1000, "Usuario desconectado")
        webSocket = null

        broadcastLog("Desconectado por user")
    }

    private fun broadcastLog(message: String) {
        Log.d("FallService", message)
        val intent = Intent(BROADCAST_LOG).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESSAGE, message)
        }

        sendBroadcast(intent)
    }

    private fun broadcastFallAlert() {
        val intent = Intent(BROADCAST_FALL_ALERT).apply {
            setPackage(packageName)
        }

        sendBroadcast(intent)
    }

    private fun broadcastCountdown(seconds: Int) {
        val intent = Intent(BROADCAST_ALERT_COUNTDOWN).apply {
            setPackage(packageName)
            putExtra(EXTRA_SECONDS_REMAINING, seconds)
        }

        sendBroadcast(intent)
    }

    private fun broadcastAlertFinished() {
        val intent = Intent(BROADCAST_ALERT_FINISHED).apply {
            setPackage(packageName)
        }

        sendBroadcast(intent)
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNormalNotification(content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)

        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, FallDetectionService::class.java).apply {
            action = ACTION_DISCONNECT
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Fall Detector")
            .setContentText(content)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun buildAlertNotification(content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)

        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = Intent(this, FallDetectionService::class.java).apply {
            action = ACTION_CANCEL_ALERT
        }

        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("FALL DETECTED")
            .setContentText(content)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "False alarm",
                cancelPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fall Detector Monitoring",
            NotificationManager.IMPORTANCE_HIGH
        )

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        cancelScheduledReconnect()

        webSocket?.close(1000, "Service destroyed")
        webSocket = null

        countdownTimer?.cancel()
        countdownTimer = null

        client.dispatcher.executorService.shutdown()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}