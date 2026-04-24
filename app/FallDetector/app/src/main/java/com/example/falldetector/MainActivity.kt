package com.example.falldetector

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var contacts by remember {
                mutableStateOf(loadEmergencyContacts())
            }

            FallDetectorApp(
                contacts = contacts,
                onAddContact = { contact ->
                    contacts = contacts + contact
                    saveEmergencyContacts(contacts)
                },
                onDeleteContact = { contact ->
                    contacts = contacts.filterNot { it == contact }
                    saveEmergencyContacts(contacts)
                },
                onConnect = { ip, addLog, onFallDetected ->
                    connectToEsp32(ip, addLog, onFallDetected)
                },
                onDisconnect = { addLog ->
                    disconnect(addLog)
                }
            )
        }
    }

    private fun loadEmergencyContacts(): List<EmergencyContact> {
        val prefs = getSharedPreferences("emergency_contacts", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("contacts", "[]") ?: "[]"

        return try {
            val array = JSONArray(jsonString)
            val contacts = mutableListOf<EmergencyContact>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                contacts.add(
                    EmergencyContact(
                        name = item.optString("name"),
                        phone = item.optString("phone")
                    )
                )
            }

            contacts
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

        getSharedPreferences("emergency_contacts", Context.MODE_PRIVATE)
            .edit()
            .putString("contacts", array.toString())
            .apply()
    }

    private fun connectToEsp32(
        ip: String,
        addLog: (String) -> Unit,
        onFallDetected: () -> Unit
    ) {
        webSocket?.close(1000, null)

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
                    addLog("Closing: $reason")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    addLog("Disconnected")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    addLog("Error: ${t.message}")
                }
            }
        })
    }

    private fun disconnect(addLog: (String) -> Unit) {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        addLog("Disconnected by user")
    }

    override fun onDestroy() {
        webSocket?.close(1000, "App closed")
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}

@Composable
fun FallDetectorApp(
    contacts: List<EmergencyContact>,
    onAddContact: (EmergencyContact) -> Unit,
    onDeleteContact: (EmergencyContact) -> Unit,
    onConnect: (String, (String) -> Unit, () -> Unit) -> Unit,
    onDisconnect: ((String) -> Unit) -> Unit
) {
    var currentScreen by remember { mutableStateOf(AppScreen.Main) }

    when (currentScreen) {
        AppScreen.Main -> FallDetectorScreen(
            contacts = contacts,
            onOpenContacts = {
                currentScreen = AppScreen.EmergencyContacts
            },
            onConnect = onConnect,
            onDisconnect = onDisconnect
        )

        AppScreen.EmergencyContacts -> EmergencyContactsScreen(
            contacts = contacts,
            onBack = {
                currentScreen = AppScreen.Main
            },
            onAddContactClicked = {
                currentScreen = AppScreen.AddEmergencyContact
            },
            onDeleteContact = onDeleteContact
        )

        AppScreen.AddEmergencyContact -> AddEmergencyContactScreen(
            onBack = {
                currentScreen = AppScreen.EmergencyContacts
            },
            onSaveContact = { contact ->
                onAddContact(contact)
                currentScreen = AppScreen.EmergencyContacts
            }
        )
    }
}

@Composable
fun FallDetectorScreen(
    contacts: List<EmergencyContact>,
    onOpenContacts: () -> Unit,
    onConnect: (String, (String) -> Unit, () -> Unit) -> Unit,
    onDisconnect: ((String) -> Unit) -> Unit
) {
    var ipAddress by remember { mutableStateOf("192.168.1.100") }
    val logs = remember { mutableStateListOf<String>() }
    var showFallAlert by remember { mutableStateOf(false) }

    fun addLog(message: String) {
        logs.add(0, message)
    }

    MaterialTheme {
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

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                        label = { Text("ESP32 IP Address") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                onConnect(ipAddress, ::addLog) {
                                    showFallAlert = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Connect")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { onDisconnect(::addLog) },
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
                    AlertDialog(
                        onDismissRequest = { },
                        title = {
                            Text(
                                text = "FALL DETECTED",
                                fontSize = 28.sp
                            )
                        },
                        text = {
                            if (contacts.isEmpty()) {
                                Text("No emergency contacts have been added yet.")
                            } else {
                                Column {
                                    Text("Emergency contacts:")
                                    Spacer(modifier = Modifier.height(8.dp))

                                    contacts.forEach { contact ->
                                        Text("${contact.name}: ${contact.phone}")
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { showFallAlert = false }
                            ) {
                                Text("False alarm")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun EmergencyContactsScreen(
    contacts: List<EmergencyContact>,
    onBack: () -> Unit,
    onAddContactClicked: () -> Unit,
    onDeleteContact: (EmergencyContact) -> Unit
) {
    MaterialTheme {
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
}

@Composable
fun AddEmergencyContactScreen(
    onBack: () -> Unit,
    onSaveContact: (EmergencyContact) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    MaterialTheme {
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
                        Text("Guardar")
                    }
                }
            }
        }
    }
}