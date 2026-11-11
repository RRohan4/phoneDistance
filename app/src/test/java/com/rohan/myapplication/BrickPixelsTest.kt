package com.rohan.myapplication

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrickPixelsTest {
    @Test
    fun convertsOriginalPixelSpansWithTheFixedCalibration() {
        val bounds = checkNotNull(findStackBounds(240, 140) { y, row ->
            for (x in row.indices) {
                row[x] = if (x in 20 until 220 && y in 20 until 120) 0xffffff else 0
            }
        })

        assertEquals(30.612075, calibratedMillimetres(bounds.length), 1e-9)
        assertEquals(15.251875, calibratedMillimetres(bounds.width), 1e-9)
    }

    @Test
    fun measuresOriginalPixelSpansOnEitherBackground() {
        for ((background, brick) in listOf(0 to 0xffffff, 0xffffff to 0)) {
            val bounds = checkNotNull(findStackBounds(80, 60) { y, row ->
                for (x in row.indices) {
                    val insideBrick = x in 22 until 54 && y in 19 until 35
                    val speck = x == 10 && y == 10
                    row[x] = if (insideBrick || speck) brick else background
                }
            })
            assertEquals(32.0, bounds.length, 0.0)
            assertEquals(16.0, bounds.width, 0.0)
        }
    }

    @Test
    fun enclosesBothColorsOfARotatedStackInOneRectangle() {
        val cos = sqrt(3.0) / 2.0
        val sin = 0.5
        val bounds = checkNotNull(findStackBounds(120, 100) { y, row ->
            for (x in row.indices) {
                val dx = x + 0.5 - 60
                val dy = y + 0.5 - 50
                val along = dx * cos + dy * sin
                val across = -dx * sin + dy * cos
                row[x] = when {
                    abs(along) >= 28 || abs(across) >= 10 -> 0
                    along < 0 -> 0x000080
                    else -> 0x800000
                }
            }
        })
        // The raster silhouette adds up to roughly one pixel at a rotated edge.
        assertEquals(56.0, bounds.length, 1.5)
        assertEquals(20.0, bounds.width, 1.5)
    }

    @Test
    fun excludesStudsFromTheSideOfARotatedStack() {
        val cos = sqrt(3.0) / 2.0
        val sin = 0.5
        val bounds = checkNotNull(findStackBounds(160, 140) { y, row ->
            for (x in row.indices) {
                val dx = x + 0.5 - 80
                val dy = y + 0.5 - 70
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
    fun measuresCenteredStackWithTableAndGlareInFrame() {
        val bounds = checkNotNull(findStackBounds(240, 300) { y, row ->
            for (x in row.indices) {
                row[x] = when {
                    x < 90 || x >= 150 -> 0xbca585
                    x in 107 until 133 && y in 143 until 157 ->
                        if (x < 120) 0x0055aa else 0xaa55aa
                    x in 100..103 -> 0xaaaaaa
                    else -> 0x222222
                }
            }
        })
        assertEquals(26.0, bounds.length, 0.0)
        assertEquals(14.0, bounds.width, 0.0)
        assertEquals(107.0, bounds.corners.minOf { it.x }, 0.0)
        assertEquals(143.0, bounds.corners.minOf { it.y }, 0.0)
    }

    @Test
    fun rejectsBlankPhotoAndBrickClippedAtEdge() {
        assertNull(findStackBounds(80, 60) { _, row -> row.fill(0) })
        assertNull(findStackBounds(80, 60) { y, row ->
            for (x in row.indices) {
                row[x] = if (x < 20 && y in 19 until 35) 0xffffff else 0
            }
        })
    }
}
