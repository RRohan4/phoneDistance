package com.rohan.myapplication

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrickPixelsTest {
    private val stand = 0x222222

    @Test
    fun convertsPixelSpansWithTheFixedCalibration() {
        assertEquals(32.114261, calibratedMillimetres(208.0), 1e-9)
        assertEquals(19.037113, calibratedMillimetres(125.0), 1e-9)
    }

    @Test
    fun measuresExactPixelSpansAndIgnoresSpecks() {
        val bounds = checkNotNull(findStackBounds(200, 160) { y, row ->
            for (x in row.indices) {
                val brick = x in 60 until 124 && y in 60 until 92
                val speck = y == 100 && x in 140..141
                row[x] = if (brick || speck) 0x0055aa else stand
            }
        })
        assertEquals(64.0, bounds.length, 0.0)
        assertEquals(32.0, bounds.width, 0.0)
    }

    @Test
    fun enclosesBothColorsOfARotatedStackInOneRectangle() {
        val cos = sqrt(3.0) / 2.0
        val sin = 0.5
        val bounds = checkNotNull(findStackBounds(240, 200) { y, row ->
            for (x in row.indices) {
                val dx = x + 0.5 - 120
                val dy = y + 0.5 - 100
                val along = dx * cos + dy * sin
                val across = -dx * sin + dy * cos
                row[x] = when {
                    abs(along) >= 28 || abs(across) >= 10 -> 0
                    along < 0 -> 0x000080
                    else -> 0x800000
                }
            }
        })
        assertEquals(56.0, bounds.length, 1.5)
        assertEquals(20.0, bounds.width, 1.5)
    }

    @Test
    fun excludesStudsFromTheSideOfARotatedStack() {
        val cos = sqrt(3.0) / 2.0
        val sin = 0.5
        val bounds = checkNotNull(findStackBounds(320, 280) { y, row ->
            for (x in row.indices) {
                val dx = x + 0.5 - 160
                val dy = y + 0.5 - 140
                val along = dx * cos + dy * sin
                val across = -dx * sin + dy * cos
                val body = abs(along) < 40 && abs(across) < 24
                val stud = across >= 24 && across < 32 &&
                    listOf(-30, -10, 10, 30).any { abs(along - it) < 6 }
                row[x] = if (body || stud) 0x0055aa else 0
            }
        })
        assertEquals(80.0, bounds.length, 1.5)
        assertEquals(48.0, bounds.width, 1.5)
    }

    @Test
    fun keepsWholeStackWhenOneColorFillsTheMaskLessThanAnother() {
        val bounds = checkNotNull(findStackBounds(200, 300) { y, row ->
            for (x in row.indices) {
                row[x] = when {
                    x !in 90 until 110 || y !in 90 until 210 -> stand
                    y < 130 -> 0xffcc00
                    (x + y) % 5 == 0 -> stand
                    else -> 0x0055cc
                }
            }
        })
        assertEquals(120.0, bounds.length, 0.0)
        assertEquals(20.0, bounds.width, 0.0)
    }

    @Test
    fun measuresCenteredStackWithTableAndGlareInFrame() {
        val bounds = checkNotNull(findStackBounds(240, 300) { y, row ->
            for (x in row.indices) {
                row[x] = when {
                    x < 90 || x >= 150 -> 0xbca585
                    x in 107 until 133 && y in 143 until 157 ->
                        if (x < 120) 0x0055aa else 0xaa55aa
                    x in 100..103 -> 0xaaaaaa
                    else -> stand
                }
            }
        })
        assertEquals(26.0, bounds.length, 0.0)
        assertEquals(14.0, bounds.width, 0.0)
        assertEquals(107.0, bounds.corners.minOf { it.x }, 0.0)
        assertEquals(143.0, bounds.corners.minOf { it.y }, 0.0)
    }

    @Test
    fun rejectsBlankPhotoAndBrickCrossingTheSearchArea() {
        assertNull(findStackBounds(80, 60) { _, row -> row.fill(stand) })
        assertNull(findStackBounds(80, 60) { y, row ->
            for (x in row.indices) {
                row[x] = if (x < 30 && y in 19 until 35) 0x0055aa else stand
            }
        })
    }
}
