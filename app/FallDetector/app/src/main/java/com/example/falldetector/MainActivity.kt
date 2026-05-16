package com.example.falldetector

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService

enum class AppScreen {
    Main,
    EmergencyContacts,
    AddEmergencyContact,
    DeviceSetup
}

class MainActivity : ComponentActivity() {

    private var hasSmsPermission by mutableStateOf(false)
    private var hasCallPermission by mutableStateOf(false)
    private var hasNotificationPermission by mutableStateOf(false)
    private var hasLocationPermission by mutableStateOf(false)

    private var logReceiver: BroadcastReceiver? = null
    private var fallReceiver: BroadcastReceiver? = null
    private var countdownReceiver: BroadcastReceiver? = null
    private var alertFinishedReceiver: BroadcastReceiver? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        refreshPermissionState()

        setContent {
            val logs = remember { mutableStateListOf<String>() }

            var currentScreenName by rememberSaveable {
                mutableStateOf(AppScreen.Main.name)
            }

            val currentScreen = AppScreen.valueOf(currentScreenName)

            var ipAddress by rememberSaveable {
                mutableStateOf(DeviceConfigStorage.loadDeviceIp(this))
            }

            var contacts by remember {
                mutableStateOf(EmergencyContactStorage.load(this))
            }

            var showFallAlert by rememberSaveable {
                mutableStateOf(false)
            }

            var secondsRemaining by rememberSaveable {
                mutableStateOf(15)
            }

            DisposableEffect(Unit) {
                registerReceivers(
                    addLog = { message ->
                        logs.add(0, message)
                    },
                    onFallAlert = {
                        showFallAlert = true
                    },
                    onCountdown = { seconds ->
                        secondsRemaining = seconds
                        showFallAlert = true
                    },
                    onAlertFinished = {
                        showFallAlert = false
                        secondsRemaining = 15
                    }
                )

                onDispose {
                    unregisterReceivers()
                }
            }

            FallDetectorApp(
                currentScreen = currentScreen,
                onScreenChange = {
                    currentScreenName = it.name
                },

                ipAddress = ipAddress,
                onIpAddressChange = {
                    ipAddress = it
                    DeviceConfigStorage.saveDeviceIp(this, it)
                },

                logs = logs,

                contacts = contacts,
                onAddContact = { contact ->
                    contacts = contacts + contact
                    EmergencyContactStorage.save(this, contacts)
                    logs.add(0, "Contacto added: ${contact.name}")
                },
                onDeleteContact = { contact ->
                    contacts = contacts.filterNot { it == contact }
                    EmergencyContactStorage.save(this, contacts)
                    logs.add(0, "Contacto borrado: ${contact.name}")
                },

                hasSmsPermission = hasSmsPermission,
                hasCallPermission = hasCallPermission,
                hasNotificationPermission = hasNotificationPermission,
                hasLocationPermission = hasLocationPermission,
                onRequestPermissions = {
                    requestPermissions()
                },

                onConnect = {
                    DeviceConfigStorage.saveDeviceIp(this, ipAddress)
                    DeviceConfigStorage.setAutoConnect(this, true)
                    startMonitoringService(ipAddress)
                    logs.add(0, "Start monitoring requested")
                },
                onDisconnect = {
                    DeviceConfigStorage.setAutoConnect(this, false)
                    stopMonitoringService()
                    logs.add(0, "Stop monitoring requested")
                },

                showFallAlert = showFallAlert,
                secondsRemaining = secondsRemaining,
                onFalseAlarm = {
                    showFallAlert = false
                    secondsRemaining = 15
                    cancelFallAlert()
                    logs.add(0, "False alarm enviada al servicio")
                }
            )
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun refreshPermissionState() {
        hasSmsPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
                    PackageManager.PERMISSION_GRANTED

        hasCallPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                    PackageManager.PERMISSION_GRANTED

        hasNotificationPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED

        hasLocationPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
    }

    private fun startMonitoringService(ip: String) {
        val intent = Intent(this, FallDetectionService::class.java).apply {
            action = FallDetectionService.ACTION_CONNECT
            putExtra(FallDetectionService.EXTRA_IP, ip)
        }

        startForegroundService(this, intent)
    }

    private fun stopMonitoringService() {
        val intent = Intent(this, FallDetectionService::class.java).apply {
            action = FallDetectionService.ACTION_DISCONNECT
        }

        startService(intent)
    }

    private fun cancelFallAlert() {
        val intent = Intent(this, FallDetectionService::class.java).apply {
            action = FallDetectionService.ACTION_CANCEL_ALERT
        }

        startService(intent)
    }

    private fun registerReceivers(
        addLog: (String) -> Unit,
        onFallAlert: () -> Unit,
        onCountdown: (Int) -> Unit,
        onAlertFinished: () -> Unit
    ) {
        logReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val message =
                    intent?.getStringExtra(FallDetectionService.EXTRA_MESSAGE)
                        ?: return

                addLog(message)
            }
        }

        fallReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                onFallAlert()
            }
        }

        countdownReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val seconds =
                    intent?.getIntExtra(
                        FallDetectionService.EXTRA_SECONDS_REMAINING,
                        15
                    ) ?: 15

                onCountdown(seconds)
            }
        }

        alertFinishedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                onAlertFinished()
            }
        }

        ContextCompat.registerReceiver(
            this,
            logReceiver,
            IntentFilter(FallDetectionService.BROADCAST_LOG),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            fallReceiver,
            IntentFilter(FallDetectionService.BROADCAST_FALL_ALERT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            countdownReceiver,
            IntentFilter(FallDetectionService.BROADCAST_ALERT_COUNTDOWN),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            alertFinishedReceiver,
            IntentFilter(FallDetectionService.BROADCAST_ALERT_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterReceivers() {
        logReceiver?.let { unregisterReceiver(it) }
        fallReceiver?.let { unregisterReceiver(it) }
        countdownReceiver?.let { unregisterReceiver(it) }
        alertFinishedReceiver?.let { unregisterReceiver(it) }

        logReceiver = null
        fallReceiver = null
        countdownReceiver = null
        alertFinishedReceiver = null
    }
}

@Composable
fun FallDetectorApp(
    currentScreen: AppScreen,
    onScreenChange: (AppScreen) -> Unit,

    ipAddress: String,
    onIpAddressChange: (String) -> Unit,

    logs: List<String>,

    contacts: List<EmergencyContact>,
    onAddContact: (EmergencyContact) -> Unit,
    onDeleteContact: (EmergencyContact) -> Unit,

    hasSmsPermission: Boolean,
    hasCallPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasLocationPermission: Boolean,
    onRequestPermissions: () -> Unit,

    onConnect: () -> Unit,
    onDisconnect: () -> Unit,

    showFallAlert: Boolean,
    secondsRemaining: Int,
    onFalseAlarm: () -> Unit
) {
    MaterialTheme {
        when (currentScreen) {
            AppScreen.Main -> MainScreen(
                ipAddress = ipAddress,
                onIpAddressChange = onIpAddressChange,
                logs = logs,
                contacts = contacts,
                hasSmsPermission = hasSmsPermission,
                hasCallPermission = hasCallPermission,
                hasNotificationPermission = hasNotificationPermission,
                hasLocationPermission = hasLocationPermission,
                onRequestPermissions = onRequestPermissions,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onOpenContacts = {
                    onScreenChange(AppScreen.EmergencyContacts)
                },
                onOpenSetup = {
                    onScreenChange(AppScreen.DeviceSetup)
                },
                showFallAlert = showFallAlert,
                secondsRemaining = secondsRemaining,
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

            AppScreen.DeviceSetup -> DeviceSetupScreen(
                onBack = {
                    onScreenChange(AppScreen.Main)
                }
            )
        }
    }
}

@Composable
fun MainScreen(
    ipAddress: String,
    onIpAddressChange: (String) -> Unit,

    logs: List<String>,
    contacts: List<EmergencyContact>,

    hasSmsPermission: Boolean,
    hasCallPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasLocationPermission: Boolean,
    onRequestPermissions: () -> Unit,

    onConnect: () -> Unit,
    onDisconnect: () -> Unit,

    onOpenContacts: () -> Unit,
    onOpenSetup: () -> Unit,

    showFallAlert: Boolean,
    secondsRemaining: Int,
    onFalseAlarm: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DETECTOR DE CAIDAS",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onOpenSetup,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Setup")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onOpenContacts,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Emergency Contacts (${contacts.size})")
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (!hasSmsPermission || !hasCallPermission || !hasNotificationPermission || !hasLocationPermission) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Hacen falta permisos!!",
                                style = MaterialTheme.typography.titleSmall
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Se necesitan permisos de llamada, SMS, GPS y notificaciones."
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
                    label = { Text("Arduino IP Address") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Empezar")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Parar")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Mensajes",
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
                        Column {
                            Text("Acciones de emergencia en $secondsRemaining s.")

                            Spacer(modifier = Modifier.height(12.dp))

                            if (contacts.isEmpty()) {
                                Text("No hay contactos.")
                            } else {
                                Text("Contactos a notificar:")

                                Spacer(modifier = Modifier.height(8.dp))

                                contacts.forEach { contact ->
                                    Text("${contact.name}: ${contact.phone}")
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = onFalseAlarm) {
                            Text("False alarm")
                        }
                    }
                )
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
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Contactos de emergencia",
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
                            text = "No hay contactos",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Tap para meter contactos a notificar en caso de caida"
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
                                    Text("Delete")
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
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

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
                            errorMessage = "Nombre no puede estar vacio"
                        } else if (trimmedPhone.isBlank()) {
                            errorMessage = "Telefono no puede estar vacio"
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
                    Text("Save")
                }
            }
        }
    }
}

@Composable
fun DeviceSetupScreen(
    onBack: () -> Unit
) {
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable {
        mutableStateOf("Conectar movil a red wifi: FallDetector-Setup")
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Setup",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text("1. Enciende el detector de caidas.")
            Text("2. Creara su propia red wifi:")
            Text("   FallDetector-Setup")
            Text("3. Conectarse a esa red wifi.")
            Text("4. Poner credenciales de la red wifi de tu casa.")

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("wifi SSID") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("wifi password") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (ssid.isBlank()) {
                        status = "Escribe el SSID de la wifi"
                        return@Button
                    }

                    status = "Comprobando setup del dispositivo..."

                    ProvisioningClient.checkSetupDevice(
                        onSuccess = {
                            status = "Dispositivo encontrado. Enviando credenciales..."

                            ProvisioningClient.provisionWifi(
                                ssid = ssid.trim(),
                                password = password,
                                onSuccess = {
                                    status =
                                        "Exito. Arduino esta rebooting. Reconectarse a su red wifi de casa."
                                },
                                onError = { error ->
                                    status = error
                                }
                            )
                        },
                        onError = { error ->
                            status = error
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enviar wifi credenciales")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(status)

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back")
            }
        }
    }
}