package com.example.gomofrancamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // 1. 격자 페인트 (흰색 실선)
    private val gridPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
        alpha = 255
    }

    // 2. 가이드 페인트 (빨간색 점선 - 목표)
    private val guidePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8.0f
        pathEffect = DashPathEffect(floatArrayOf(40f, 20f), 0f)
    }

    // 3. 감지된 물체 페인트 (초록색 점선 - 현재 내 위치)
    private val detectedPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8.0f
        pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
    }

    private var isGridVisible = false
    private var currentGuide: GuideItem? = null
    private var detectedRect: RectF? = null

    fun setGridVisible(isVisible: Boolean) {
        isGridVisible = isVisible
        invalidate()
    }

    fun setGuide(guide: GuideItem?) {
        currentGuide = guide
        if (guide == null) {
            detectedRect = null
        }
        invalidate()
    }

    fun setDetectedRect(rect: RectF?) {
        detectedRect = rect
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // 1. 격자 그리기
        if (isGridVisible) {
            canvas.drawLine(viewWidth / 3, 0f, viewWidth / 3, viewHeight, gridPaint)
            canvas.drawLine(viewWidth * 2 / 3, 0f, viewWidth * 2 / 3, viewHeight, gridPaint)
            canvas.drawLine(0f, viewHeight / 3, viewWidth, viewHeight / 3, gridPaint)
            canvas.drawLine(0f, viewHeight * 2 / 3, viewWidth, viewHeight * 2 / 3, gridPaint)
        }

        // 2. AI 가이드(목표) 그리기
        currentGuide?.let { guide ->
            if (guide.type != GuideType.NONE) {
                val rect = RectF(
                    guide.targetRect.left * viewWidth,
                    guide.targetRect.top * viewHeight,
                    guide.targetRect.right * viewWidth,
                    guide.targetRect.bottom * viewHeight
                )

                if (guide.type == GuideType.OVAL) {
                    canvas.drawOval(rect, guidePaint)
                } else if (guide.type == GuideType.RECT) {
                    canvas.drawRect(rect, guidePaint)
                }
            }
        }

        // 3. 감지된 물체 그리기
        if (currentGuide != null && detectedRect != null) {
            val rect = RectF(
                detectedRect!!.left * viewWidth,
                detectedRect!!.top * viewHeight,
                detectedRect!!.right * viewWidth,
                detectedRect!!.bottom * viewHeight
            )
            canvas.drawRect(rect, detectedPaint)
        }
    }
}