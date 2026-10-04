package com.massliharf.tvremote

import android.content.Context
import android.hardware.ConsumerIrManager

/**
 * Sends LG NEC infrared codes through the phone's IR blaster (if it has one,
 * e.g. many Xiaomi/Redmi/POCO phones). This works with the TV off and without Wi-Fi,
 * so it is the only way to switch the 2014 model back on.
 */
class IrRemote(context: Context) {

    private val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val available: Boolean
        get() = try { ir?.hasIrEmitter() == true } catch (_: Exception) { false }

    /** Sends an LG command (NEC address 0x04). Returns false if there is no IR emitter. */
    fun send(command: Int): Boolean {
        val mgr = ir ?: return false
        if (!available) return false
        return try {
            mgr.transmit(38000, necPattern(0x04, command))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun necPattern(address: Int, command: Int): IntArray {
        val out = ArrayList<Int>()
        out += 9000; out += 4500
        for (byte in intArrayOf(address, address.inv() and 0xFF, command, command.inv() and 0xFF)) {
            for (bit in 0 until 8) {
                out += 560
                out += if ((byte shr bit) and 1 == 1) 1690 else 560
            }
        }
        out += 560
        return out.toIntArray()
    }

    companion object {
        /** LG IR command codes, keyed by the same names used for the network buttons. */
        val CODES = mapOf(
            "POWER" to 0x08,
            "VOLUMEUP" to 0x02, "VOLUMEDOWN" to 0x03, "MUTE" to 0x09,
            "CHANNELUP" to 0x00, "CHANNELDOWN" to 0x01,
            "UP" to 0x40, "DOWN" to 0x41, "LEFT" to 0x07, "RIGHT" to 0x06, "ENTER" to 0x44,
            "BACK" to 0x28, "EXIT" to 0x5B, "HOME" to 0x7C, "MENU" to 0x43, "QMENU" to 0x45,
            "INPUT" to 0x0B, "INFO" to 0xAA, "GUIDE" to 0xA9,
            "RED" to 0x72, "GREEN" to 0x71, "YELLOW" to 0x63, "BLUE" to 0x61,
            "PLAY" to 0xB0, "PAUSE" to 0xBA, "STOP" to 0xB1, "REWIND" to 0x8F, "FASTFORWARD" to 0x8E,
            "0" to 0x10, "1" to 0x11, "2" to 0x12, "3" to 0x13, "4" to 0x14,
            "5" to 0x15, "6" to 0x16, "7" to 0x17, "8" to 0x18, "9" to 0x19,
        )
    }
}
