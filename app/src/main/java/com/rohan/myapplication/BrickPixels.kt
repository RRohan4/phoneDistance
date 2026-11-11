package com.rohan.myapplication

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

private const val COLOR_CONTRAST = 48

internal fun calibratedMillimetres(pixels: Double): Double = 0.153602 * pixels - 0.108325

internal data class PixelPoint(val x: Double, val y: Double)

internal data class StackBounds(
    val length: Double,
    val width: Double,
    val corners: List<PixelPoint>
)

internal fun findStackBounds(
    width: Int,
    height: Int,
    readRow: (y: Int, row: IntArray) -> Unit
): StackBounds? {
    if (width < 3 || height < 3) return null

    val pixels = IntArray(width)
    val channelSums = IntArray(3)
    for (y in listOf(0, height - 1)) {
        readRow(y, pixels)
        for (pixel in pixels) {
            channelSums[0] += channel(pixel, 16)
            channelSums[1] += channel(pixel, 8)
            channelSums[2] += channel(pixel, 0)
        }
    }
    val (red, green, blue) = channelSums.map { it / (2 * width) }

    val fullFrame = fitStack(width, height, leftOffset = 0, topOffset = 0) { y, mask ->
        readRow(y, pixels)
        for (x in pixels.indices) {
            val pixel = pixels[x]
            // A saturated brick can contrast in one channel even when it looks dark in grayscale.
            mask[x + 1] = abs(channel(pixel, 16) - red) >= COLOR_CONTRAST ||
                abs(channel(pixel, 8) - green) >= COLOR_CONTRAST ||
                abs(channel(pixel, 0) - blue) >= COLOR_CONTRAST
        }
    }
    if (fullFrame != null) return fullFrame

    // shortcut: cluttered photos need a colorful stack in the center, add a selectable region if framing varies.
    val radius = minOf(width, height) / 12
    if (radius < 2) return null
    val left = width / 2 - radius
    val top = height / 2 - radius
    return fitStack(radius * 2, radius * 2, left, top) { y, mask ->
        readRow(top + y, pixels)
        for (x in 0 until radius * 2) {
            val pixel = pixels[left + x]
            val r = channel(pixel, 16)
            val g = channel(pixel, 8)
            val b = channel(pixel, 0)
            // Glare on the black stand is mostly gray, while the bricks have stronger color.
            mask[x + 1] = maxOf(r, g, b) - minOf(r, g, b) >= COLOR_CONTRAST
        }
    }
}

private fun fitStack(
    width: Int,
    height: Int,
    leftOffset: Int,
    topOffset: Int,
    readMask: (y: Int, mask: BooleanArray) -> Unit
): StackBounds? {
    val outline = mutableListOf<PixelPoint>()
    var above = BooleanArray(width + 2)
    var current = BooleanArray(width + 2)
    var below = BooleanArray(width + 2)
    readMask(0, current)
    for (y in 0 until height) {
        if (y + 1 < height) readMask(y + 1, below) else below.fill(false)
        var left = width
        var right = -1
        for (x in 1..width) {
            if (!current[x]) continue
            // A foreground pixel needs a neighbor to rule out isolated specks.
            val supported = current[x - 1] || current[x + 1] ||
                above[x - 1] || above[x] || above[x + 1] ||
                below[x - 1] || below[x] || below[x + 1]
            if (!supported) continue
            left = minOf(left, x - 1)
            right = x - 1
        }
        if (right >= left) {
            if (left == 0 || right == width - 1 || y == 0 || y == height - 1) return null
            // Use pixel edges so a run of N pixels measures N pixels wide.
            val imageLeft = (leftOffset + left).toDouble()
            val imageRight = leftOffset + right + 1.0
            val imageTop = (topOffset + y).toDouble()
            outline += PixelPoint(imageLeft, imageTop)
            outline += PixelPoint(imageRight, imageTop)
            outline += PixelPoint(imageLeft, imageTop + 1)
            outline += PixelPoint(imageRight, imageTop + 1)
        }
        val reusable = above
        above = current
        current = below
        below = reusable
    }

    if (outline.isEmpty()) return null
    val silhouette = enclosingRectangle(convexHull(outline))
    return fitBody(silhouette, width, height, leftOffset, topOffset, readMask)
}

private fun fitBody(
    silhouette: StackBounds,
    width: Int,
    height: Int,
    leftOffset: Int,
    topOffset: Int,
    readMask: (y: Int, mask: BooleanArray) -> Unit
): StackBounds {
    val (origin, side, _, end) = silhouette.corners
    val spanU = hypot(side.x - origin.x, side.y - origin.y)
    val spanV = hypot(end.x - origin.x, end.y - origin.y)
    val ux = (side.x - origin.x) / spanU
    val uy = (side.y - origin.y) / spanU
    val uHits = IntArray(ceil(spanU).toInt())
    val vHits = IntArray(ceil(spanV).toInt())
    val left = maxOf(0, floor(silhouette.corners.minOf { it.x } - leftOffset).toInt())
    val right = minOf(width, ceil(silhouette.corners.maxOf { it.x } - leftOffset).toInt())
    val top = maxOf(0, floor(silhouette.corners.minOf { it.y } - topOffset).toInt())
    val bottom = minOf(height, ceil(silhouette.corners.maxOf { it.y } - topOffset).toInt())
    val mask = BooleanArray(width + 2)
    for (y in top until bottom) {
        readMask(y, mask)
        val dy = topOffset + y + 0.5 - origin.y
        for (x in left until right) {
            if (!mask[x + 1]) continue
            val dx = leftOffset + x + 0.5 - origin.x
            val u = dx * ux + dy * uy
            val v = -dx * uy + dy * ux
            if (u < 0 || u >= spanU || v < 0 || v >= spanV) continue
            uHits[u.toInt()]++
            vHits[v.toInt()]++
        }
    }

    val u = bodyRange(uHits, spanU)
    val v = bodyRange(vHits, spanV)
    val bodyU = u.endInclusive - u.start
    val bodyV = v.endInclusive - v.start
    fun corner(a: Double, b: Double) =
        PixelPoint(origin.x + a * ux - b * uy, origin.y + a * uy + b * ux)

    return StackBounds(
        length = maxOf(bodyU, bodyV),
        width = minOf(bodyU, bodyV),
        corners = listOf(
            corner(u.start, v.start), corner(u.endInclusive, v.start),
            corner(u.endInclusive, v.endInclusive), corner(u.start, v.endInclusive)
        )
    )
}

private fun bodyRange(hits: IntArray, span: Double): ClosedFloatingPointRange<Double> {
    // Smooth the projected rows. Studs occupy less of each row than the continuous body.
    val coverage = IntArray(hits.size) { i ->
        hits.getOrElse(i - 1) { 0 } + hits[i] + hits.getOrElse(i + 1) { 0 }
    }
    val dense = coverage.max() * 0.84
    val first = coverage.indexOfFirst { it >= dense }.toDouble()
    val last = minOf(span, coverage.indexOfLast { it >= dense } + 1.0)
    // Keep the silhouette edge when the inset is only a few pixels from rasterization or tilt.
    val minimumInset = maxOf(2.0, span * 0.025)
    val start = if (first > minimumInset) first else 0.0
    val end = if (span - last > minimumInset) last else span
    return start..end
}

private fun channel(pixel: Int, shift: Int) = (pixel ushr shift) and 255

private fun convexHull(points: List<PixelPoint>): List<PixelPoint> {
    val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))

    fun halfHull(ordered: List<PixelPoint>): List<PixelPoint> {
        val hull = mutableListOf<PixelPoint>()
        for (point in ordered) {
            while (hull.size >= 2) {
                val a = hull[hull.lastIndex - 1]
                val b = hull.last()
                val turn = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
                if (turn > 0) break
                hull.removeAt(hull.lastIndex)
            }
            hull += point
        }
        return hull.dropLast(1)
    }

    return halfHull(sorted) + halfHull(sorted.asReversed())
}

private fun enclosingRectangle(hull: List<PixelPoint>): StackBounds {
    var bestArea = Double.POSITIVE_INFINITY
    var best: StackBounds? = null

    // A minimum-area enclosing rectangle has a side parallel to a convex hull edge.
    for (i in hull.indices) {
        val start = hull[i]
        val end = hull[(i + 1) % hull.size]
        val edgeLength = hypot(end.x - start.x, end.y - start.y)
        val ux = (end.x - start.x) / edgeLength
        val uy = (end.y - start.y) / edgeLength
        var minU = Double.POSITIVE_INFINITY
        var maxU = Double.NEGATIVE_INFINITY
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        for (point in hull) {
            val u = point.x * ux + point.y * uy
            val v = -point.x * uy + point.y * ux
            minU = minOf(minU, u)
            maxU = maxOf(maxU, u)
            minV = minOf(minV, v)
            maxV = maxOf(maxV, v)
        }
        val spanU = maxU - minU
        val spanV = maxV - minV
        val area = spanU * spanV
        if (area >= bestArea) continue

        fun corner(u: Double, v: Double) = PixelPoint(u * ux - v * uy, u * uy + v * ux)

        bestArea = area
        best = StackBounds(
            length = maxOf(spanU, spanV),
            width = minOf(spanU, spanV),
            corners = listOf(
                corner(minU, minV), corner(maxU, minV), corner(maxU, maxV), corner(minU, maxV)
            )
        )
    }
    return checkNotNull(best)
}
