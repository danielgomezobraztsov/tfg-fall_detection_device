package com.example.falldetector

import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

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
        ssid: String,
        password: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val formBody = FormBody.Builder()
            .add("ssid", ssid)
            .add("password", password)
            .build()

        val request = Request.Builder()
            .url("$SETUP_BASE_URL/provision")
            .post(formBody)
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