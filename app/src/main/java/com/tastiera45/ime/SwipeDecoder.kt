package com.tastiera45.ime

import android.content.Context
import android.graphics.PointF
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.hypot
import kotlin.math.min

class SwipeDecoder(context: Context) {

    private val dictionary = mutableListOf<String>()

    init {
        try {
            val input = context.resources.openRawResource(R.raw.italian_words)
            BufferedReader(InputStreamReader(input)).useLines { lines ->
                lines.map { it.trim().lowercase() }
                    .filter { it.length >= 2 }
                    .forEach { dictionary.add(it) }
            }
        } catch (_: Exception) {
        }
    }

    fun decode(
        pathPoints: List<PointF>,
        keyCenters: Map<Char, PointF>,
        keyWidth: Float
    ): List<String> {
        if (pathPoints.size < 3 || keyCenters.isEmpty()) return emptyList()

        val startPt = pathPoints.first()
        val endPt = pathPoints.last()
        val resampled = resample(pathPoints, 24)
        val maxDist = keyWidth * 1.45f

        val scored = mutableListOf<Pair<String, Float>>()

        for (word in dictionary) {
            val firstChar = word.first()
            val lastChar = word.last()
            val startKey = keyCenters[firstChar] ?: continue
            val endKey = keyCenters[lastChar] ?: continue

            val dStart = hypot(startPt.x - startKey.x, startPt.y - startKey.y)
            val dEnd = hypot(endPt.x - endKey.x, endPt.y - endKey.y)
            if (dStart > maxDist || dEnd > maxDist) continue

            val idealPoints = word.mapNotNull { keyCenters[it] }
            if (idealPoints.size != word.length) continue
            val idealResampled = resample(idealPoints, 24)

            var pathError = 0f
            for (i in resampled.indices) {
                pathError += hypot(
                    resampled[i].x - idealResampled[i].x,
                    resampled[i].y - idealResampled[i].y
                )
            }
            val avgError = pathError / resampled.size
            val totalScore = avgError + (dStart * 0.9f) + (dEnd * 0.9f)
            scored.add(word to totalScore)
        }

        return scored
            .sortedBy { it.second }
            .take(3)
            .map { it.first }
    }

    private fun resample(points: List<PointF>, targetCount: Int): List<PointF> {
        if (points.size == 1) return List(targetCount) { points[0] }
        var totalLength = 0f
        for (i in 1 until points.size) {
            totalLength += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        if (totalLength < 1f) return List(targetCount) { points[0] }

        val step = totalLength / (targetCount - 1)
        val result = mutableListOf(PointF(points[0].x, points[0].y))
        var accumulated = 0f
        var i = 1

        while (i < points.size && result.size < targetCount) {
            val p1 = points[i - 1]
            val p2 = points[i]
            val segLen = hypot(p2.x - p1.x, p2.y - p1.y)
            if (segLen > 0f && accumulated + segLen >= step) {
                val ratio = (step - accumulated) / segLen
                val nx = p1.x + ratio * (p2.x - p1.x)
                val ny = p1.y + ratio * (p2.y - p1.y)
                val np = PointF(nx, ny)
                result.add(np)
                accumulated = 0f
                // continua dal nuovo punto
                val remaining = mutableListOf(np)
                remaining.addAll(points.subList(i, points.size))
                return finishResample(result, remaining, step, targetCount)
            } else {
                accumulated += segLen
                i++
            }
        }
        while (result.size < targetCount) {
            result.add(PointF(points.last().x, points.last().y))
        }
        return result.take(min(targetCount, result.size))
    }

    private fun finishResample(
        current: MutableList<PointF>,
        pts: List<PointF>,
        step: Float,
        targetCount: Int
    ): List<PointF> {
        var acc = 0f
        var idx = 1
        while (idx < pts.size && current.size < targetCount) {
            val a = pts[idx - 1]
            val b = pts[idx]
            val d = hypot(b.x - a.x, b.y - a.y)
            if (d > 0f && acc + d >= step) {
                val t = (step - acc) / d
                val np = PointF(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
                current.add(np)
                acc = 0f
                val sub = mutableListOf(np)
                sub.addAll(pts.subList(idx, pts.size))
                return finishResample(current, sub, step, targetCount)
            } else {
                acc += d
                idx++
            }
        }
        while (current.size < targetCount) {
            current.add(PointF(pts.last().x, pts.last().y))
        }
        return current
    }
}
