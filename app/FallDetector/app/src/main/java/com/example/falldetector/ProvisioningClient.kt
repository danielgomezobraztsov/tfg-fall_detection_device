package com.example.falldetector

import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

data class WifiCredentialInput(
    val ssid: String,
    val password: String
)

object ProvisioningClient {

    private val client = OkHttpClient()

    private const val SETUP_BASE_URL = "http://192.168.4.1"

    fun checkSetupDevice(
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val request = Request.Builder()
            .url("$SETUP_BASE_URL/status")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("No se ha podido conectar: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    onSuccess(body)
                } else {
                    onError("Setup problema/fallo: ${response.code}")
                }
            }
        })
    }

    fun provisionWifi(
        credentials: List<WifiCredentialInput>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val validCredentials = credentials
            .map { credential ->
                credential.copy(ssid = credential.ssid.trim())
            }
            .filter { credential ->
                credential.ssid.isNotBlank()
            }
            .take(5)

        if (validCredentials.isEmpty()) {
            onError("Add at least one WiFi network")
            return
        }

        val formBuilder = FormBody.Builder()
            .add("count", validCredentials.size.toString())

        validCredentials.forEachIndexed { index, credential ->
            formBuilder.add("ssid$index", credential.ssid)
            formBuilder.add("password$index", credential.password)
        }

        val request = Request.Builder()
            .url("$SETUP_BASE_URL/provision")
            .post(formBuilder.build())
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Provisioning failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    onSuccess(body)
                } else {
                    onError("Provisioning failed: ${response.code} $body")
                }
            }
        })
    }
}