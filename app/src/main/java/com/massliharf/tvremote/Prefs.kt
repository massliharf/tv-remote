package com.massliharf.tvremote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("tv", Context.MODE_PRIVATE)

    fun clientKey(c: Context, host: String?): String? =
        host?.let { sp(c).getString("key_$it", null) }

    fun setClientKey(c: Context, host: String?, key: String?) {
        if (host == null) return
        sp(c).edit().apply { if (key == null) remove("key_$host") else putString("key_$host", key) }.apply()
    }

    fun irMode(c: Context): Boolean = sp(c).getBoolean("ir_mode", false)

    fun setIrMode(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("ir_mode", on).apply()
    }

    // ---- Saved devices ----

    fun devices(c: Context): List<Device> {
        val raw = sp(c).getString("devices", null)
        if (raw == null) {
            // Migrate the single LG TV saved by the first version.
            val legacy = sp(c).getString("last_host", null) ?: return emptyList()
            return listOf(Device(Device.Type.LG, legacy, "LG TV")).also { saveDevices(c, it) }
        }
        val arr = try { JSONArray(raw) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val type = try { Device.Type.valueOf(o.optString("type")) } catch (_: Exception) { return@mapNotNull null }
            Device(type, o.optString("host"), o.optString("name"))
        }
    }

    private fun saveDevices(c: Context, list: List<Device>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("type", it.type.name).put("host", it.host).put("name", it.name)) }
        sp(c).edit().putString("devices", arr.toString()).apply()
    }

    fun addDevice(c: Context, d: Device) {
        saveDevices(c, devices(c).filter { it.id != d.id } + d)
    }

    fun removeDevice(c: Context, d: Device) {
        saveDevices(c, devices(c).filter { it.id != d.id })
        if (activeId(c) == d.id) setActiveId(c, null)
    }

    fun activeId(c: Context): String? = sp(c).getString("active", null)

    fun setActiveId(c: Context, id: String?) {
        sp(c).edit().putString("active", id).apply()
    }

    fun activeDevice(c: Context): Device? {
        val all = devices(c)
        return all.firstOrNull { it.id == activeId(c) } ?: all.firstOrNull()
    }
}
