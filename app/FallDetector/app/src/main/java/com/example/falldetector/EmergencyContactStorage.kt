package com.example.falldetector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object EmergencyContactStorage {

    private const val PREFS_NAME = "emergency_contacts"
    private const val KEY_CONTACTS = "contacts"

    fun load(context: Context): List<EmergencyContact> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_CONTACTS, "[]") ?: "[]"

        return try {
            val array = JSONArray(jsonString)
            val result = mutableListOf<EmergencyContact>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                val name = item.optString("name")
                val phone = item.optString("phone")

                if (name.isNotBlank() && phone.isNotBlank()) {
                    result.add(EmergencyContact(name, phone))
                }
            }

            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, contacts: List<EmergencyContact>) {
        val array = JSONArray()

        contacts.forEach { contact ->
            val item = JSONObject()
            item.put("nombre", contact.name)
            item.put("numero", contact.phone)
            array.put(item)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONTACTS, array.toString())
            .apply()
    }
}