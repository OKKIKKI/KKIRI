package com.okkikki.kkiri.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.sin

/**
 * 광장 배경(꾸미기 "광장" 탭). 내 화면에만 적용하고 서버로 보내지 않는다(명세 v1.16).
 * 광장 그림([SpaceView])과 꾸미기 탭 견본이 같은 그리기를 쓴다. 고정 무늬라 애니메이션이 없다.
 * 영역 안에 [l,t,r,b]를 채우고, 아래쪽(도로 부근)에 소품 그림을 둔다.
 */
object PlazaTheme {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    fun draw(canvas: Canvas, id: String, l: Float, t: Float, r: Float, b: Float) {
        val w = r - l
        val h = b - t
        val tint = when (id) {
            "camping" -> 0x40335A3A
            "sea" -> 0x4036649A
            "nightsky" -> 0x66102050
            "cherry" -> 0x40B0507A
            "snow" -> 0x3A9FB8D8
            else -> return
        }
        paint.style = Paint.Style.FILL
        paint.color = tint
        canvas.drawRect(l, t, r, b, paint)
        when (id) {
            "camping" -> {
                paint.color = 0xCC2F4B35.toInt()
                tent(canvas, l + w * 0.14f, b - h * 0.34f, h * 0.2f)
                tent(canvas, r - w * 0.16f, b - h * 0.3f, h * 0.16f)
                paint.color = 0xFFFF9A3D.toInt()
                path.reset(); path.moveTo(l + w * 0.5f, b - h * 0.2f); path.quadTo(l + w * 0.5f + h * 0.04f, b - h * 0.14f, l + w * 0.5f, b - h * 0.1f); path.quadTo(l + w * 0.5f - h * 0.04f, b - h * 0.14f, l + w * 0.5f, b - h * 0.2f); canvas.drawPath(path, paint)
            }
            "sea" -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = 0x66CFE8FF
                for (k in 0 until 4) {
                    val y = b - h * (0.08f + 0.07f * k)
                    path.reset(); path.moveTo(l, y)
                    var x = l
                    while (x < r) { path.quadTo(x + 30f, y - 10f, x + 60f, y); x += 60f }
                    canvas.drawPath(path, paint)
                }
            }
            "nightsky" -> dots(canvas, l, t, w, h * 0.7f, 28, 0xFFFFF4C8.toInt(), 1.6f, 3.4f)
            "cherry" -> dots(canvas, l, t, w, h, 22, 0xAAFFC2D9.toInt(), 4f, 7f)
            "snow" -> dots(canvas, l, t, w, h, 30, 0xCCFFFFFF.toInt(), 2.5f, 5f)
        }
        paint.style = Paint.Style.FILL
    }

    private fun tent(canvas: Canvas, cx: Float, base: Float, s: Float) {
        paint.style = Paint.Style.FILL
        path.reset(); path.moveTo(cx - s, base); path.lineTo(cx, base - s * 1.1f); path.lineTo(cx + s, base); path.close()
        canvas.drawPath(path, paint)
    }

    /** 정해진 규칙으로 흩뿌린 점(실행마다 같은 자리) */
    private fun dots(canvas: Canvas, l: Float, t: Float, w: Float, h: Float, n: Int, color: Int, rMin: Float, rMax: Float) {
        paint.style = Paint.Style.FILL
        paint.color = color
        for (i in 0 until n) {
            val fx = (sin(i * 12.9898f) * 43758.547f).let { it - kotlin.math.floor(it) }
            val fy = (sin(i * 78.233f) * 12345.678f).let { it - kotlin.math.floor(it) }
            val fr = (sin(i * 39.346f) * 9876.54f).let { it - kotlin.math.floor(it) }
            canvas.drawCircle(l + fx * w, t + fy * h, rMin + (rMax - rMin) * fr, paint)
        }
    }

    /** 견본(꾸미기 카드 썸네일): 어두운 면 위에 배경을 올린다 */
    fun swatch(canvas: Canvas, id: String, w: Float, h: Float, base: Int) {
        rect.set(0f, 0f, w, h)
        paint.style = Paint.Style.FILL
        paint.color = base
        canvas.drawRect(rect, paint)
        draw(canvas, id, 0f, 0f, w, h)
    }
}
