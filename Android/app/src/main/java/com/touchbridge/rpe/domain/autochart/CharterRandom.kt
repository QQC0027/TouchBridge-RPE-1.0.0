package com.touchbridge.rpe.domain.autochart

/** Instance-local MT19937 with Python's integer seeding and draw semantics. */
internal class CharterRandom(seed: Long) {
    private val state = IntArray(624)
    private var index = state.size

    init {
        val magnitude = if (seed < 0) -seed else seed
        val high = (magnitude ushr 32).toInt()
        val key = if (high == 0) intArrayOf(magnitude.toInt()) else intArrayOf(magnitude.toInt(), high)
        state[0] = 19650218
        for (i in 1 until state.size) {
            val prior = state[i - 1]
            state[i] = 1812433253 * (prior xor (prior ushr 30)) + i
        }
        var i = 1
        var j = 0
        repeat(maxOf(state.size, key.size)) {
            val prior = state[i - 1]
            state[i] = (state[i] xor ((prior xor (prior ushr 30)) * 1664525)) + key[j] + j
            if (++i >= state.size) { state[0] = state.last(); i = 1 }
            if (++j >= key.size) j = 0
        }
        repeat(state.size - 1) {
            val prior = state[i - 1]
            state[i] = (state[i] xor ((prior xor (prior ushr 30)) * 1566083941)) - i
            if (++i >= state.size) { state[0] = state.last(); i = 1 }
        }
        state[0] = Int.MIN_VALUE
    }

    private fun word(): Int {
        if (index >= state.size) {
            for (i in state.indices) {
                val y = (state[i] and Int.MIN_VALUE) or (state[(i + 1) % state.size] and Int.MAX_VALUE)
                state[i] = state[(i + 397) % state.size] xor (y ushr 1) xor
                    (if (y and 1 != 0) 0x9908b0df.toInt() else 0)
            }
            index = 0
        }
        var y = state[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        return y xor (y ushr 18)
    }

    fun nextDouble(): Double = ((word() ushr 5) * 67108864.0 + (word() ushr 6)) / 9007199254740992.0

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        val bits = 32 - Integer.numberOfLeadingZeros(bound)
        var draw: Int
        do { draw = word() ushr (32 - bits) } while (draw >= bound)
        return draw
    }

    fun <T> choice(values: List<T>): T = values[nextInt(values.size)]

    fun <T> sampleTwo(values: List<T>): List<T> {
        val pool = values.toMutableList()
        val firstIndex = nextInt(pool.size)
        val first = pool[firstIndex]
        pool[firstIndex] = pool.last()
        return listOf(first, pool[nextInt(pool.size - 1)])
    }
}
