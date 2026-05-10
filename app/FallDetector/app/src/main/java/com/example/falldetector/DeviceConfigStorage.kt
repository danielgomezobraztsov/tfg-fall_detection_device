package com.example.falldetector

import android.content.Context

object DeviceConfigStorage {

    private const val PREFS_NAME = "device_config"
    private const val KEY_DEVICE_IP = "device_ip"
    private const val KEY_AUTO_CONNECT = "auto_connect"

    fun saveDeviceIp(context: Context, ip: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_IP, ip)
            .apply()
    }

    fun loadDeviceIp(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_IP, "192.168.1.100") ?: "192.168.1.100"
    }

    fun setAutoConnect(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CONNECT, enabled)
            .apply()
    }

    fun getAutoConnect(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CONNECT, false)
    }
}