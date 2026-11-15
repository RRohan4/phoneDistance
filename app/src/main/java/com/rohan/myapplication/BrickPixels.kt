package com.rohan.myapplication

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

private const val COLOR_CONTRAST = 48

internal fun calibratedMillimetres(pixels: Double): Double = 0.157556 * pixels - 0.657387

internal data class PixelPoint(val x: Double, val y: Double)

internal data class StackBounds(
    val length: Double,
    val width: Double,
    val corners: List<PixelPoint>
)

internal data class SearchArea(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun findStackBounds(
    width: Int,
    height: Int,
    onSearchArea: (SearchArea) -> Unit = {},
    readRow: (y: Int, row: IntArray) -> Unit
): StackBounds? {
    if (width < 3 || height < 3) return null

    val pixels = IntArray(width)
    val bridge = maxOf(1, minOf(width, height) / 480)

    fun fitColorRegion(area: SearchArea): StackBounds? {
        val (left, top, right, bottom) = area
        if (right - left < 3 || bottom - top < 3) return null
        onSearchArea(area)
        return fitStack(right - left, bottom - top, left, top, bridge) { y, mask ->
            readRow(top + y, pixels)
            for (x in left until right) {
                val pixel = pixels[x]
                val r = channel(pixel, 16)
                val g = channel(pixel, 8)
                val b = channel(pixel, 0)
                mask[x - left + 1] = maxOf(r, g, b) - minOf(r, g, b) >= COLOR_CONTRAST
            }
        }
    }

    val radius = minOf(width, height) / 12
    val centerArea = SearchArea(
        width / 2 - radius, height / 2 - radius, width / 2 + radius, height / 2 + radius
    )
    val centerFit = fitColorRegion(centerArea)

    val portrait = height >= width
    val platformFit = fitColorRegion(
        SearchArea(
            left = (width * if (portrait) 0.30 else 0.25).toInt(),
            top = (height * if (portrait) 0.25 else 0.30).toInt(),
            right = (width * if (portrait) 0.605 else 0.75).toInt(),
            bottom = (height * when {
                portrait && height.toDouble() / width < 1.2 -> 0.84
                portrait -> 0.73
                else -> 0.70
            }).toInt()
        )
    )
    if (centerFit == null) return platformFit
    if (platformFit != null && platformFit.extendsSameStack(centerFit)) return platformFit

    onSearchArea(centerArea)
    return centerFit
}

private fun StackBounds.extendsSameStack(initial: StackBounds): Boolean {
    if (length < initial.length * 1.2 || width !in initial.width * 0.8..initial.width * 1.2) {
        return false
    }
    val allowance = initial.width * 0.1
    return initial.corners.all { point ->
        corners.indices.all { i ->
            val start = corners[i]
            val end = corners[(i + 1) % corners.size]
            val dx = end.x - start.x
            val dy = end.y - start.y
            dx * (point.y - start.y) - dy * (point.x - start.x) >= -allowance * hypot(dx, dy)
        }
    }
}

private fun fitStack(
    width: Int,
    height: Int,
    leftOffset: Int,
    topOffset: Int,
    bridge: Int,
    readMask: (y: Int, mask: BooleanArray) -> Unit
): StackBounds? {
    val blob = mainBlob(width, height, bridge, readMask) ?: return null
    val outline = mutableListOf<PixelPoint>()
    for (y in 0 until height) {
        val row = y * width
        var left = -1
        var right = -1
        for (x in 0 until width) {
            if (!blob[row + x]) continue
            if (left < 0) left = x
            right = x
        }
        if (left < 0) continue
        if (left == 0 || right == width - 1 || y == 0 || y == height - 1) return null
        val imageLeft = (leftOffset + left).toDouble()
        val imageRight = leftOffset + right + 1.0
        val imageTop = (topOffset + y).toDouble()
        outline += PixelPoint(imageLeft, imageTop)
        outline += PixelPoint(imageRight, imageTop)
        outline += PixelPoint(imageLeft, imageTop + 1)
        outline += PixelPoint(imageRight, imageTop + 1)
    }

    if (outline.isEmpty()) return null
    val silhouette = enclosingRectangle(convexHull(outline))
    return fitBody(silhouette, width, height, leftOffset, topOffset) { y, mask ->
        val row = y * width
        for (x in 0 until width) mask[x + 1] = blob[row + x]
    }
}

private fun mainBlob(
    width: Int,
    height: Int,
    bridge: Int,
    readMask: (y: Int, mask: BooleanArray) -> Unit
): BooleanArray? {
    val foreground = BooleanArray(width * height)
    val row = BooleanArray(width + 2)
    for (y in 0 until height) {
        readMask(y, row)
        row.copyInto(foreground, y * width, startIndex = 1, endIndex = width + 1)
    }
    if (foreground.none { it }) return null

    val joined = dilate(foreground, width, height, bridge)
    val labels = IntArray(width * height)
    var stack = IntArray(1024)
    var nextLabel = 0
    var bestLabel = 0
    var bestSize = 0
    for (start in labels.indices) {
        if (!joined[start] || labels[start] != 0) continue
        nextLabel++
        labels[start] = nextLabel
        var top = 0
        stack[top++] = start
        var size = 0
        while (top > 0) {
            val i = stack[--top]
            if (foreground[i]) size++
            val x = i % width
            val y = i / width
            for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
                for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                    val j = ny * width + nx
                    if (!joined[j] || labels[j] != 0) continue
                    labels[j] = nextLabel
                    if (top == stack.size) stack = stack.copyOf(stack.size * 2)
                    stack[top++] = j
                }
            }
        }
        if (size > bestSize) {
            bestSize = size
            bestLabel = nextLabel
        }
    }
    for (i in foreground.indices) foreground[i] = foreground[i] && labels[i] == bestLabel
    return foreground
}

private fun dilate(source: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
    val horizontal = BooleanArray(source.size)
    for (y in 0 until height) {
        val row = y * width
        var count = 0
        for (x in 0 until minOf(radius, width)) if (source[row + x]) count++
        for (x in 0 until width) {
            val entering = x + radius
            if (entering < width && source[row + entering]) count++
            val leaving = x - radius - 1
            if (leaving >= 0 && source[row + leaving]) count--
            horizontal[row + x] = count > 0
        }
    }
    val result = BooleanArray(source.size)
    for (x in 0 until width) {
        var count = 0
        for (y in 0 until minOf(radius, height)) if (horizontal[y * width + x]) count++
        for (y in 0 until height) {
            val entering = y + radius
            if (entering < height && horizontal[entering * width + x]) count++
            val leaving = y - radius - 1
            if (leaving >= 0 && horizontal[leaving * width + x]) count--
            result[y * width + x] = count > 0
        }
    }
    return result
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
    val coverage = IntArray(hits.size) { i ->
        hits.getOrElse(i - 1) { 0 } + hits[i] + hits.getOrElse(i + 1) { 0 }
    }
    val rows = coverage.filter { it > 0 }.sorted().ifEmpty { return 0.0..span }
    val median = (rows[(rows.size - 1) / 2] + rows[rows.size / 2]) / 2.0
    val dense = median
    val first = coverage.indexOfFirst { it >= dense }.toDouble()
    val last = minOf(span, coverage.indexOfLast { it >= dense } + 1.0)
    return first..last
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
