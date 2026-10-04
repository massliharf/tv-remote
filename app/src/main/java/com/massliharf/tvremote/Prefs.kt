package com.massliharf.tvremote

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("tv", Context.MODE_PRIVATE)

    fun clientKey(c: Context, host: String?): String? =
        host?.let { sp(c).getString("key_$it", null) }

    fun setClientKey(c: Context, host: String?, key: String?) {
        if (host == null) return
        sp(c).edit().apply { if (key == null) remove("key_$host") else putString("key_$host", key) }.apply()
    }

    fun lastHost(c: Context): String? = sp(c).getString("last_host", null)

    fun setLastHost(c: Context, host: String?) {
        sp(c).edit().putString("last_host", host).apply()
    }

    fun irMode(c: Context): Boolean = sp(c).getBoolean("ir_mode", false)

    fun setIrMode(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("ir_mode", on).apply()
    }
}
