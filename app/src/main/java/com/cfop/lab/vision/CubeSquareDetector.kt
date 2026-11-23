package com.cfop.lab.vision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class FaceSampleResult(
    val rgbGrid: List<IntArray>, // 9 个贴纸的 [R, G, B] 均值（按行优先 0..8）
    val detectedSquare: Boolean
)

/**
 * 基于 OpenCV Android 的单帧寻方、透视拉正裁剪与 9 格采色引擎。
 * 思路源自开源项目 kkoomen/qbr 与 dwalton76/rubiks-cube-tracker：
 * 1) Canny 边缘 + 形态学膨胀 + findContours 寻找近似正方形小块轮廓；
 * 2) 若检测到 >=4 个贴纸方格则取其外接正交边界并做透视裁剪，否则回退居中 64% 正方形；
 * 3) 在拉正后的 300x300 正方形内，仅采样每格内芯 40% 的中值 RGB（滤除反光高光）；
 * 4) 所有原生 C++ Mat 均在 finally 块中立即 release()，确保零内存堆积。
 */
object CubeSquareDetector {

    fun detectAndSampleFace(bitmap: Bitmap): FaceSampleResult {
        val rgba = Mat()
        val bgr = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val dilated = Mat()
        val hierarchy = Mat()
        val warped = Mat()

        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 35.0, 110.0)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.dilate(edges, dilated, kernel)
            kernel.release()

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                dilated, contours, hierarchy,
                Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE
            )

            val imgMin = min(bgr.cols(), bgr.rows()).toDouble()
            val minArea = (imgMin * 0.04) * (imgMin * 0.04)
            val maxArea = (imgMin * 0.34) * (imgMin * 0.34)

            val stickerBoxes = mutableListOf<Rect>()
            for (cnt in contours) {
                val area = Imgproc.contourArea(cnt)
                if (area in minArea..maxArea) {
                    val peri = Imgproc.arcLength(MatOfPoint2f(*cnt.toArray()), true)
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(MatOfPoint2f(*cnt.toArray()), approx, 0.06 * peri, true)
                    if (approx.total() == 4L) {
                        val rect = Imgproc.boundingRect(cnt)
                        val ratio = rect.width.toDouble() / max(1, rect.height).toDouble()
                        if (ratio in 0.78..1.28) {
                            stickerBoxes.add(rect)
                        }
                    }
                    approx.release()
                }
                cnt.release()
            }

            // 计算魔方外接正方形框（优先用检测到的方格簇，否则取中心 64% 正方形）
            val (cropRect, found) = computeCubeBoundingSquare(stickerBoxes, bgr.cols(), bgr.rows())

            // 透视裁剪拉正为标准 300x300 正方形
            val srcPts = MatOfPoint2f(
                Point(cropRect.x.toDouble(), cropRect.y.toDouble()),
                Point((cropRect.x + cropRect.width).toDouble(), cropRect.y.toDouble()),
                Point((cropRect.x + cropRect.width).toDouble(), (cropRect.y + cropRect.height).toDouble()),
                Point(cropRect.x.toDouble(), (cropRect.y + cropRect.height).toDouble())
            )
            val dstPts = MatOfPoint2f(
                Point(0.0, 0.0), Point(300.0, 0.0),
                Point(300.0, 300.0), Point(0.0, 300.0)
            )
            val matrix = Imgproc.getPerspectiveTransform(srcPts, dstPts)
            Imgproc.warpPerspective(bgr, warped, matrix, Size(300.0, 300.0))
            srcPts.release()
            dstPts.release()
            matrix.release()

            // 在 300x300 正方形内采样 3x3 中心区域（去反光截尾均值）
            val rgbGrid = sample9CellsTrimmedRgb(warped)
            return FaceSampleResult(rgbGrid = rgbGrid, detectedSquare = found)
        } finally {
            rgba.release()
            bgr.release()
            gray.release()
            blurred.release()
            edges.release()
            dilated.release()
            hierarchy.release()
            warped.release()
        }
    }

    private fun computeCubeBoundingSquare(boxes: List<Rect>, w: Int, h: Int): Pair<Rect, Boolean> {
        if (boxes.size >= 4) {
            // 按面积中位数过滤离群误检轮廓
            val sortedAreas = boxes.map { it.width * it.height }.sorted()
            val medianArea = sortedAreas[sortedAreas.size / 2]
            val valid = boxes.filter {
                val a = it.width * it.height
                a in (medianArea * 0.45).toInt()..(medianArea * 1.8).toInt()
            }
            if (valid.size >= 4) {
                val minX = valid.minOf { it.x }
                val minY = valid.minOf { it.y }
                val maxX = valid.maxOf { it.x + it.width }
                val maxY = valid.maxOf { it.y + it.height }
                val bw = maxX - minX
                val bh = maxY - minY
                val ratio = bw.toDouble() / max(1, bh).toDouble()
                if (ratio in 0.72..1.38 && bw > min(w, h) * 0.28) {
                    val side = max(bw, bh)
                    val cx = (minX + maxX) / 2
                    val cy = (minY + maxY) / 2
                    val x0 = (cx - side / 2).coerceIn(0, max(0, w - side))
                    val y0 = (cy - side / 2).coerceIn(0, max(0, h - side))
                    val safeSide = min(side, min(w - x0, h - y0))
                    return Rect(x0, y0, safeSide, safeSide) to true
                }
            }
        }
        val side = (min(w, h) * 0.64).toInt()
        val x0 = (w - side) / 2
        val y0 = (h - side) / 2
        return Rect(x0, y0, side, side) to false
    }

    private fun sample9CellsTrimmedRgb(warpedBgr: Mat): List<IntArray> {
        val result = mutableListOf<IntArray>()
        val cell = 100
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val xStart = col * cell + 30
                val yStart = row * cell + 30
                val pixels = mutableListOf<Triple<Int, Int, Int>>()
                for (y in yStart until (yStart + 40) step 2) {
                    for (x in xStart until (xStart + 40) step 2) {
                        val bgr = warpedBgr.get(y, x) ?: continue
                        pixels.add(Triple(bgr[2].toInt(), bgr[1].toInt(), bgr[0].toInt()))
                    }
                }
                if (pixels.isEmpty()) {
                    result.add(intArrayOf(128, 128, 128))
                    continue
                }
                pixels.sortBy { 0.299 * it.first + 0.587 * it.second + 0.114 * it.third }
                val lo = (pixels.size * 0.2).toInt()
                val hi = max(lo + 1, (pixels.size * 0.8).toInt())
                var sr = 0; var sg = 0; var sb = 0; var cnt = 0
                for (i in lo until hi) {
                    sr += pixels[i].first
                    sg += pixels[i].second
                    sb += pixels[i].third
                    cnt++
                }
                result.add(intArrayOf(sr / cnt, sg / cnt, sb / cnt))
            }
        }
        return result
    }
}
