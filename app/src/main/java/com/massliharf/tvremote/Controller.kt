package com.massliharf.tvremote

/** A device the remote can drive: the LG webOS TV or an Android TV box (e.g. Xiaomi Mi Box). */
interface Controller {

    enum class State { DISCONNECTED, CONNECTING, CONFIRM_ON_TV, ENTER_PIN, CONNECTED }

    interface Listener {
        fun onStateChanged(controller: Controller, state: State, message: String?)
    }

    val device: Device
    val state: State

    fun connect()
    fun disconnect()

    /** Sends a key by its remote name: POWER, UP, ENTER, BACK, HOME, VOLUMEUP, 0-9, RED, PLAY ... */
    fun sendKey(key: String)

    /** Mirrors an edit of the phone's text box to the focused text field on the TV. */
    fun textChanged(full: String, deleted: Int, inserted: String)
    fun sendEnter()
    fun deleteChar()

    val hasPointer: Boolean get() = false
    fun move(dx: Int, dy: Int) {}
    fun click() {}
    fun scroll(dx: Int, dy: Int) {}

    /** Launchable apps as (title, id) pairs, or an error. */
    fun listApps(callback: (List<Pair<String, String>>?, String?) -> Unit)
    fun launchApp(id: String)

    /** Inputs as (label, id), or null when the device only offers its own picker. */
    fun listInputs(callback: (List<Pair<String, String>>?) -> Unit) = callback(null)
    fun switchInput(id: String) {}

    fun submitPin(pin: String): Boolean = false
}

data class Device(val type: Type, val host: String, val name: String) {
    enum class Type { LG, ANDROID_TV }

    val id get() = "${type.name}:$host"
}
