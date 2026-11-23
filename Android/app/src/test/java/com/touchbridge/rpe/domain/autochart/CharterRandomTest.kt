package com.touchbridge.rpe.domain.autochart

import org.junit.Assert.assertEquals
import org.junit.Test

class CharterRandomTest {
    @Test
    fun matchesPythonFloatAndIntegerDraws() {
        val r0 = CharterRandom(0L)
        val expected = listOf(0.8444218515250481, 0.7579544029403025, 0.420571580830845, 0.25891675029296335, 0.5112747213686085)
        assertEquals(expected, expected.map { r0.nextDouble() })

        val r42 = CharterRandom(42L)
        assertEquals(listOf(0, 0, 2, 2, 1, 0), listOf(1, 2, 3, 4, 6, 1).map(r42::nextInt))
        assertEquals(listOf(-450, -150), r42.sampleTwo(listOf(-450, -300, -150)))
        assertEquals(0.6766994874229113, r42.nextDouble(), 0.0)
        assertEquals(0.5534639983914199, CharterRandom(Long.MIN_VALUE).nextDouble(), 0.0)
        val negative = CharterRandom(-42L)
        assertEquals(0.6394267984578837, negative.nextDouble(), 0.0)
    }
}
