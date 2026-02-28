package at.planqton.fytfm.uart

/**
 * CANBox Protocol Encoder
 * Supports PQ, MQB and HiWorld protocols for steering wheel button simulation
 */
object CanboxProtocol {

    enum class Protocol {
        PQ, MQB, HIWORLD
    }

    enum class Key(val pqCode: Int, val mqbCode: Int) {
        VOL_UP(0x01, 0x01),
        VOL_DOWN(0x02, 0x02),
        PREV(0x03, 0x04),
        NEXT(0x04, 0x03),
        MODE(0x0A, 0x0A),
        OK(0x09, 0x09),
        VOICE(0x0C, 0x0C),
        MUTE(0x0B, 0x0B),
        PHONE(0x05, 0x05),
        UP(0x06, 0x06),
        DOWN(0x07, 0x07)
    }

    /**
     * Calculate checksum for Raise protocol (PQ/MQB)
     * XOR all bytes XOR 0xFF
     */
    private fun calcChecksumRaise(data: ByteArray): Byte {
        var checksum = 0
        for (b in data) {
            checksum += b.toInt() and 0xFF
        }
        return (0xFF xor checksum and 0xFF).toByte()
    }

    /**
     * Calculate checksum for HiWorld protocol
     * Sum of all bytes - 1
     */
    private fun calcChecksumHiWorld(data: ByteArray): Byte {
        var sum = 0
        for (b in data) {
            sum += b.toInt() and 0xFF
        }
        return ((sum - 1) and 0xFF).toByte()
    }

    /**
     * Build Raise protocol message
     * Format: 0x2E type size payload checksum
     */
    fun buildRaiseMessage(msgType: Int, payload: ByteArray): ByteArray {
        val size = payload.size
        val data = ByteArray(size + 2)
        data[0] = msgType.toByte()
        data[1] = size.toByte()
        System.arraycopy(payload, 0, data, 2, size)

        val checksum = calcChecksumRaise(data)

        val result = ByteArray(size + 4)
        result[0] = 0x2E
        System.arraycopy(data, 0, result, 1, data.size)
        result[result.size - 1] = checksum

        return result
    }

    /**
     * Build HiWorld protocol message
     * Format: 0x5A 0xA5 size type payload checksum
     */
    fun buildHiWorldMessage(msgType: Int, payload: ByteArray): ByteArray {
        val size = payload.size + 1 // size = type + payload
        val data = ByteArray(size + 1)
        data[0] = size.toByte()
        data[1] = msgType.toByte()
        System.arraycopy(payload, 0, data, 2, payload.size)

        val checksum = calcChecksumHiWorld(data)

        val result = ByteArray(payload.size + 5)
        result[0] = 0x5A
        result[1] = 0xA5.toByte()
        System.arraycopy(data, 0, result, 2, data.size)
        result[result.size - 1] = checksum

        return result
    }

    /**
     * Build key press message
     */
    fun buildKeyPress(protocol: Protocol, key: Key, pressed: Boolean): ByteArray? {
        if (protocol == Protocol.HIWORLD) {
            // HiWorld key format not fully documented
            return null
        }

        val keyCode = if (protocol == Protocol.PQ) key.pqCode else key.mqbCode
        val state = if (pressed) 0x01 else 0x00

        return buildRaiseMessage(0x20, byteArrayOf(keyCode.toByte(), state.toByte()))
    }

    /**
     * Build reverse gear message (Type 0x24)
     */
    fun buildReverse(protocol: Protocol, reverse: Boolean, parkBrake: Boolean = false, lights: Boolean = false): ByteArray? {
        if (protocol == Protocol.HIWORLD) return null

        val state = (if (reverse) 1 else 0) or
                   ((if (parkBrake) 1 else 0) shl 1) or
                   ((if (lights) 1 else 0) shl 2)

        return buildRaiseMessage(0x24, byteArrayOf(state.toByte()))
    }

    /**
     * Build radar enable message (Type 0x25)
     */
    fun buildRadarEnable(protocol: Protocol, enabled: Boolean): ByteArray? {
        if (protocol == Protocol.HIWORLD) return null

        val state = if (enabled) 0x02 else 0x00
        return buildRaiseMessage(0x25, byteArrayOf(state.toByte()))
    }

    /**
     * Build front radar sensors message (Type 0x23)
     */
    fun buildRadarFront(protocol: Protocol, sensors: IntArray): ByteArray? {
        if (protocol == Protocol.HIWORLD) return null
        if (sensors.size != 4) return null

        return buildRaiseMessage(0x23, sensors.map { it.toByte() }.toByteArray())
    }

    /**
     * Build rear radar sensors message (Type 0x22)
     */
    fun buildRadarRear(protocol: Protocol, sensors: IntArray): ByteArray? {
        if (protocol == Protocol.HIWORLD) return null
        if (sensors.size != 4) return null

        return buildRaiseMessage(0x22, sensors.map { it.toByte() }.toByteArray())
    }

    /**
     * Build HiWorld radar message (Type 0x41)
     */
    fun buildRadarHiWorld(rear: IntArray, front: IntArray): ByteArray {
        val data = ByteArray(12)
        for (i in 0..3) {
            data[i] = rear.getOrElse(i) { 0 }.toByte()
            data[i + 4] = front.getOrElse(i) { 0 }.toByte()
        }
        return buildHiWorldMessage(0x41, data)
    }

    /**
     * Build doors status message (Type 0x41 for Raise, 0x12 for HiWorld)
     */
    fun buildDoors(protocol: Protocol, fl: Boolean, fr: Boolean, rl: Boolean, rr: Boolean,
                   tailgate: Boolean = false, bonnet: Boolean = false): ByteArray {
        val state = (if (fl) 1 else 0) or
                   ((if (fr) 1 else 0) shl 1) or
                   ((if (rl) 1 else 0) shl 2) or
                   ((if (rr) 1 else 0) shl 3) or
                   ((if (tailgate) 1 else 0) shl 4) or
                   ((if (bonnet) 1 else 0) shl 5)

        return if (protocol == Protocol.HIWORLD) {
            buildHiWorldMessage(0x12, byteArrayOf(0, 0, state.toByte(), 0, 0, 0, 0))
        } else {
            buildRaiseMessage(0x41, byteArrayOf(0x01, state.toByte()))
        }
    }

    /**
     * Build wheel angle message
     * angle: -100 to +100 (left to right percentage)
     */
    fun buildWheelAngle(protocol: Protocol, angle: Int): ByteArray {
        return when (protocol) {
            Protocol.PQ -> {
                // PQ: Type 0x26, Range -540 to 540
                val scaled = angle * 54 / 10
                buildRaiseMessage(0x26, byteArrayOf(
                    (scaled and 0xFF).toByte(),
                    ((scaled shr 8) and 0xFF).toByte()
                ))
            }
            Protocol.MQB -> {
                // MQB: Type 0x29, Range -19980 to 19980
                val scaled = angle * 1998 / 10
                buildRaiseMessage(0x29, byteArrayOf(
                    (scaled and 0xFF).toByte(),
                    ((scaled shr 8) and 0xFF).toByte()
                ))
            }
            Protocol.HIWORLD -> {
                // HiWorld: Type 0x11
                val scaled = angle * 54 / 10
                buildHiWorldMessage(0x11, byteArrayOf(
                    0x20, 0x00, 0x00, 0x00, 0x03, 0x00,
                    ((scaled shr 8) and 0xFF).toByte(),
                    (scaled and 0xFF).toByte(),
                    0x00, 0x00
                ))
            }
        }
    }

    /**
     * Build vehicle info message (PQ only, Type 0x41)
     */
    fun buildVehicleInfo(rpm: Int, speed: Int, voltage: Float, temp: Int, odometer: Int): ByteArray {
        val speedVal = (speed * 100)
        val voltVal = (voltage * 100).toInt()
        val tempVal = temp * 10

        return buildRaiseMessage(0x41, byteArrayOf(
            0x02,
            ((rpm shr 8) and 0xFF).toByte(),
            (rpm and 0xFF).toByte(),
            ((speedVal shr 8) and 0xFF).toByte(),
            (speedVal and 0xFF).toByte(),
            ((voltVal shr 8) and 0xFF).toByte(),
            (voltVal and 0xFF).toByte(),
            ((tempVal shr 8) and 0xFF).toByte(),
            (tempVal and 0xFF).toByte(),
            ((odometer shr 16) and 0xFF).toByte(),
            ((odometer shr 8) and 0xFF).toByte(),
            (odometer and 0xFF).toByte(),
            0x00
        ))
    }

    /**
     * Build AC message (PQ only, Type 0x21)
     */
    fun buildAC(acOn: Boolean, fanSpeed: Int, tempLeft: Float, tempRight: Float): ByteArray {
        val b0 = if (acOn) 0x40 else 0x00
        val b1 = fanSpeed and 0x07
        val b2 = (tempLeft.toInt() shl 1) or (if (tempLeft % 1 >= 0.5) 1 else 0)
        val b3 = (tempRight.toInt() shl 1) or (if (tempRight % 1 >= 0.5) 1 else 0)

        return buildRaiseMessage(0x21, byteArrayOf(
            b0.toByte(), b1.toByte(), b2.toByte(), b3.toByte(), 0x00
        ))
    }

    /**
     * Format byte array as hex string for display
     */
    fun formatHex(data: ByteArray): String {
        return data.joinToString(" ") { String.format("%02X", it) }
    }
}
