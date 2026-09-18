package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayerExactSeekAndroidTest {
    @Test
    fun media3ExactSeekRestoresThePreviousSeekParameters() {
        val calls = mutableListOf<String>()

        withTemporaryExactSeek(
            current = "previous",
            exact = "exact",
            setParameters = { calls += "set:$it" },
            seek = { calls += "seek" },
        )

        assertEquals(listOf("set:exact", "seek", "set:previous"), calls)
    }

    @Test
    fun media3ExactSeekRestoresParametersWhenSeekFails() {
        val calls = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            withTemporaryExactSeek(
                current = "previous",
                exact = "exact",
                setParameters = { calls += "set:$it" },
                seek = {
                    calls += "seek"
                    error("failed")
                },
            )
        }

        assertEquals(listOf("set:exact", "seek", "set:previous"), calls)
    }

    @Test
    fun libmpvExactSeekUsesAbsoluteExactAndClampsNegativePositions() {
        assertContentEquals(
            arrayOf("seek", "12.345", "absolute+exact"),
            mpvAbsoluteSeekArguments(positionMs = 12_345L, exact = true),
        )
        assertContentEquals(
            arrayOf("seek", "0.0", "absolute"),
            mpvAbsoluteSeekArguments(positionMs = -1L, exact = false),
        )
    }
}
