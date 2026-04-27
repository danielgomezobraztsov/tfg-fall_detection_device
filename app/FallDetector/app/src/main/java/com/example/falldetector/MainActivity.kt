package com.example.falldetector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

data class EmergencyContact(
    val name: String,
    val phone: String
)

enum class AppScreen {
    Main,
    EmergencyContacts,
    AddEmergencyContact
}

class MainActivity : ComponentActivity() {

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    private var hasSmsPermission by mutableStateOf(false)
    private var hasCallPermission by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            hasSmsPermission = permissions[Manifest.permission.SEND_SMS] == true
            hasCallPermission = permissions[Manifest.permission.CALL_PHONE] == true
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        refreshPermissionState()

        setContent {
            var currentScreen by remember { mutableStateOf(AppScreen.Main) }

            var ipAddress by remember { mutableStateOf("192.168.1.100") }
            val logs = remember { mutableStateListOf<String>() }

            var contacts by remember {
                mutableStateOf(loadEmergencyContacts())
            }

            var showFallAlert by remember { mutableStateOf(false) }
            var emergencyAlreadyTriggered by remember { mutableStateOf(false) }

            fun addLog(message: String) {
                logs.add(0, message)
            }

            fun requestEmergencyPermissions() {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.CALL_PHONE
                    )
                )
            }

            fun sendEmergencyActions() {
                if (emergencyAlreadyTriggered) return

                emergencyAlreadyTriggered = true

                if (contacts.isEmpty()) {
                    addLog("No emergency contacts available")
                    return
                }

                if (!hasSmsPermission || !hasCallPermission) {
                    addLog("Emergency permissions missing")
                    requestEmergencyPermissions()
                    return
                }

                sendEmergencySmsToContacts(contacts, ::addLog)
                callFirstEmergencyContact(contacts, ::addLog)
            }

            FallDetectorApp(
                currentScreen = currentScreen,
                onScreenChange = { currentScreen = it },

                ipAddress = ipAddress,
                onIpAddressChange = { ipAddress = it },

                logs = logs,
                addLog = ::addLog,

                contacts = contacts,
                onAddContact = { contact ->
                    contacts = contacts + contact
                    saveEmergencyContacts(contacts)
                    addLog("Added emergency contact: ${contact.name}")
                },
                onDeleteContact = { contact ->
                    contacts = contacts.filterNot { it == contact }
                    saveEmergencyContacts(contacts)
                    addLog("Deleted emergency contact: ${contact.name}")
                },

                hasSmsPermission = hasSmsPermission,
                hasCallPermission = hasCallPermission,
                onRequestPermissions = { requestEmergencyPermissions() },

                showFallAlert = showFallAlert,
                onShowFallAlertChange = { showFallAlert = it },

                onConnect = {
                    connectToEsp32(
                        ip = ipAddress,
                        addLog = ::addLog,
                        onFallDetected = {
                            showFallAlert = true
                            emergencyAlreadyTriggered = false
                        }
                    )
                },
                onDisconnect = {
                    disconnect(::addLog)
                },

                onEmergencyCountdownFinished = {
                    showFallAlert = false
                    sendEmergencyActions()
                },

                onFalseAlarm = {
                    showFallAlert = false
                    emergencyAlreadyTriggered = false
                    addLog("Caida falsa (por user)")
                }
            )
        }
    }

    private fun refreshPermissionState() {
        hasSmsPermission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED

        hasCallPermission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadEmergencyContacts(): List<EmergencyContact> {
        val prefs = getSharedPreferences("emergency_contacts", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("contacts", "[]") ?: "[]"

        return try {
            val array = JSONArray(jsonString)
            val result = mutableListOf<EmergencyContact>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                val name = item.optString("name")
                val phone = item.optString("phone")

                if (name.isNotBlank() && phone.isNotBlank()) {
                    result.add(EmergencyContact(name = name, phone = phone))
                }
            }

            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveEmergencyContacts(contacts: List<EmergencyContact>) {
        val array = JSONArray()

        contacts.forEach { contact ->
            val item = JSONObject()
            item.put("name", contact.name)
            item.put("phone", contact.phone)
            array.put(item)
        }

        getSharedPreferences("emergency_contacts", Context.MODE_PRIVATE).edit().putString("contacts", array.toString()).apply()
    }

    private fun connectToEsp32(
        ip: String,
        addLog: (String) -> Unit,
        onFallDetected: () -> Unit
    ) {
        webSocket?.close(1000, "Reconnecting")

        val request = Request.Builder()
            .url("ws://$ip:81/")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    addLog("Connected to $ip")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    try {
                        val json = JSONObject(text)
                        val type = json.optString("type", "msg")
                        val message = json.optString("message", text)

                        addLog("[$type] $message")

                        if (
                            type.equals("fall", ignoreCase = true) ||
                            message.contains("FALL DETECTED", ignoreCase = true)
                        ) {
                            onFallDetected()
                        }

                    } catch (e: Exception) {
                        addLog(text)

                        if (text.contains("FALL DETECTED", ignoreCase = true)) {
                            onFallDetected()
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runOnUiThread {
                    addLog("Binary message received")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    addLog("Closing connection: $reason")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    addLog("Disconnected")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    addLog("Connection error: ${t.message}")
                }
            }
        })
    }

    private fun disconnect(addLog: (String) -> Unit) {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        addLog("Disconnected by user")
    }

    private fun sendEmergencySmsToContacts(
        contacts: List<EmergencyContact>,
        addLog: (String) -> Unit
    ) {
        val message =
            "Caida detectada!! (mensaje automatico de la app)"

        try {
            val smsManager = SmsManager.getDefault()

            contacts.forEach { contact ->
                smsManager.sendTextMessage(
                    contact.phone,
                    null,
                    message,
                    null,
                    null
                )

                addLog("SMS a: ${contact.name}")
            }

        } catch (e: Exception) {
            addLog("SMS error: ${e.message}")
        }
    }

    private fun callFirstEmergencyContact(
        contacts: List<EmergencyContact>,
        addLog: (String) -> Unit
    ) {
        val firstContact = contacts.firstOrNull()

        if (firstContact == null) {
            addLog("No hay contactos a llamar")
            return
        }

        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${firstContact.phone}")
            }

            startActivity(intent)
            addLog("Llamando a ${firstContact.name}")

        } catch (e: Exception) {
            addLog("Error: ${e.message}")
        }
    }

    override fun onDestroy() {
        webSocket?.close(1000, "App closed")
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}

@Composable
fun FallDetectorApp(
    currentScreen: AppScreen,
    onScreenChange: (AppScreen) -> Unit,

    ipAddress: String,
    onIpAddressChange: (String) -> Unit,

    logs: List<String>,
    addLog: (String) -> Unit,

    contacts: List<EmergencyContact>,
    onAddContact: (EmergencyContact) -> Unit,
    onDeleteContact: (EmergencyContact) -> Unit,

    hasSmsPermission: Boolean,
    hasCallPermission: Boolean,
    onRequestPermissions: () -> Unit,

    showFallAlert: Boolean,
    onShowFallAlertChange: (Boolean) -> Unit,

    onConnect: () -> Unit,
    onDisconnect: () -> Unit,

    onEmergencyCountdownFinished: () -> Unit,
    onFalseAlarm: () -> Unit
) {
    MaterialTheme {
        when (currentScreen) {
            AppScreen.Main -> FallDetectorScreen(
                ipAddress = ipAddress,
                onIpAddressChange = onIpAddressChange,
                logs = logs,
                contacts = contacts,
                hasSmsPermission = hasSmsPermission,
                hasCallPermission = hasCallPermission,
                onRequestPermissions = onRequestPermissions,
                onOpenContacts = {
                    onScreenChange(AppScreen.EmergencyContacts)
                },
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                showFallAlert = showFallAlert,
                onEmergencyCountdownFinished = onEmergencyCountdownFinished,
                onFalseAlarm = onFalseAlarm
            )

            AppScreen.EmergencyContacts -> EmergencyContactsScreen(
                contacts = contacts,
                onBack = {
                    onScreenChange(AppScreen.Main)
                },
                onAddContactClicked = {
                    onScreenChange(AppScreen.AddEmergencyContact)
                },
                onDeleteContact = onDeleteContact
            )

            AppScreen.AddEmergencyContact -> AddEmergencyContactScreen(
                onBack = {
                    onScreenChange(AppScreen.EmergencyContacts)
                },
                onSaveContact = { contact ->
                    onAddContact(contact)
                    onScreenChange(AppScreen.EmergencyContacts)
                }
            )
        }
    }
}

@Composable
fun FallDetectorScreen(
    ipAddress: String,
    onIpAddressChange: (String) -> Unit,

    logs: List<String>,
    contacts: List<EmergencyContact>,

    hasSmsPermission: Boolean,
    hasCallPermission: Boolean,
    onRequestPermissions: () -> Unit,

    onOpenContacts: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,

    showFallAlert: Boolean,
    onEmergencyCountdownFinished: () -> Unit,
    onFalseAlarm: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {

            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "ESP32 Fall Detector",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onOpenContacts,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Emergency Contacts (${contacts.size})")
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (!hasSmsPermission || !hasCallPermission) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Hacen falta permisos",
                                style = MaterialTheme.typography.titleSmall
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Hacen falta permisos de llamadas y SMS para avisar"
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(onClick = onRequestPermissions) {
                                Text("Dar permisos")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = onIpAddressChange,
                    label = { Text("ESP32 IP Address") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Connect")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Disconnect")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Messages",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs) { log ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = log,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }

            if (showFallAlert) {
                FallDetectedDialog(
                    contacts = contacts,
                    onFalseAlarm = onFalseAlarm,
                    onCountdownFinished = onEmergencyCountdownFinished
                )
            }
        }
    }
}

@Composable
fun FallDetectedDialog(
    contacts: List<EmergencyContact>,
    onFalseAlarm: () -> Unit,
    onCountdownFinished: () -> Unit
) {
    var secondsRemaining by remember { mutableStateOf(15) }
    var active by remember { mutableStateOf(true) }

    LaunchedEffect(active) {
        while (active && secondsRemaining > 0) {
            kotlinx.coroutines.delay(1000)
            secondsRemaining--
        }

        if (active && secondsRemaining == 0) {
            active = false
            onCountdownFinished()
        }
    }

    AlertDialog(
        onDismissRequest = { },
        title = {
            Text(
                text = "FALL DETECTED",
                fontSize = 28.sp
            )
        },
        text = {
            Column {
                Text("AVISOS EN $secondsRemaining SEGUNDOS.")

                Spacer(modifier = Modifier.height(12.dp))

                if (contacts.isEmpty()) {
                    Text("No emergency contacts have been added yet.")
                } else {
                    Text("Contacts a avisar:")

                    Spacer(modifier = Modifier.height(8.dp))

                    contacts.forEach { contact ->
                        Text("${contact.name}: ${contact.phone}")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    active = false
                    onFalseAlarm()
                }
            ) {
                Text("False alarm")
            }
        }
    )
}

@Composable
fun EmergencyContactsScreen(
    contacts: List<EmergencyContact>,
    onBack: () -> Unit,
    onAddContactClicked: () -> Unit,
    onDeleteContact: (EmergencyContact) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Contactos",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Back")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onAddContactClicked,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Add Contacto")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (contacts.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Sin contactos (de momento)",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Click para añadir contactos de emergencia."
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(contacts) { contact ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = contact.name,
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(text = contact.phone)

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedButton(
                                    onClick = { onDeleteContact(contact) }
                                ) {
                                    Text("Borrar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddEmergencyContactScreen(
    onBack: () -> Unit,
    onSaveContact: (EmergencyContact) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Add Contacto",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    errorMessage = null
                },
                label = { Text("Nombre") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = phone,
                onValueChange = {
                    phone = it
                    errorMessage = null
                },
                label = { Text("Numero") },
                modifier = Modifier.fillMaxWidth()
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancelar")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        val trimmedName = name.trim()
                        val trimmedPhone = phone.trim()

                        if (trimmedName.isBlank()) {
                            errorMessage = "no puede estar vacio"
                        } else if (trimmedPhone.isBlank()) {
                            errorMessage = "no puede estar vacio"
                        } else {
                            onSaveContact(
                                EmergencyContact(
                                    name = trimmedName,
                                    phone = trimmedPhone
                                )
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Guardareeewewewewfdgvfd")
                }
            }
        }
    }
}