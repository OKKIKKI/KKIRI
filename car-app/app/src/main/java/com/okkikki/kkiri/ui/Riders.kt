package com.okkikki.kkiri.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.okkikki.kkiri.R
import com.okkikki.kkiri.contract.Car
import com.okkikki.kkiri.contract.Session
import com.okkikki.kkiri.ui.theme.Kk
import kotlin.math.min

/**
 * 차에 탄 사람(운전자 + 같은 차 참가자)을 그리는 도구. Figma 확정본의 프로필 아바타(avatar/1~6)를 쓴다.
 * - 세로 창 차량 줄: 동그란 아바타를 겹쳐 놓는다([stack]).
 * - 가로 창 차 카드·세션 대기: 아바타를 풍선처럼 차 위에 띄우고 줄로 이어 그린다([CarWithBalloons]).
 */
object Riders {
    private val AVATARS = intArrayOf(
        R.color.kk_avatar_1, R.color.kk_avatar_2, R.color.kk_avatar_3,
        R.color.kk_avatar_4, R.color.kk_avatar_5, R.color.kk_avatar_6,
    )

    /** 이 차에 탄 사람 이름: 운전자가 맨 앞, 이어서 같은 차 참가자(이름이 같으면 한 번만) */
    fun names(s: Session, c: Car): List<String> {
        val driver = c.driverName.trim()
        val others = s.participants.filter { it.carId == c.carId }.map { it.name.trim() }
        return (listOf(driver) + others).filter { it.isNotEmpty() }.distinct()
    }

    /** 같은 이름은 항상 같은 색 */
    fun colorOf(context: Context, name: String): Int =
        Kk.color(context, AVATARS[(name.hashCode() and 0x7fffffff) % AVATARS.size])

    fun initial(name: String): String = name.trim().take(1)

    /** 동그란 아바타 한 개(지름 sizePx) */
    fun circle(context: Context, name: String, sizePx: Int): TextView =
        Kk.text(context, initial(name), Kk.TextStyleKk.SMALL, R.color.kk_avatar_ink).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * 0.4f)
            setLineSpacing(0f, 1f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorOf(context, name))
            }
        }

    /** 아바타를 겹쳐 가로로 늘어놓는다(최대 3명). 운전자가 맨 왼쪽, 뒤 사람이 앞쪽에 겹친다(Figma와 같음) */
    fun stack(context: Context, names: List<String>, sizePx: Int, overlapPx: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        names.take(3).forEachIndexed { i, name ->
            addView(circle(context, name, sizePx), LinearLayout.LayoutParams(sizePx, sizePx).apply {
                if (i > 0) marginStart = -overlapPx
            })
        }
    }

    /**
     * 장난감 차 + 탑승자 풍선(확정본 "일행 · 출발 후 / L-2W" 시안): 차 뒤쪽(풍선 슬롯 자리)에서 줄이 나와
     * 오른쪽 위로 퍼지고, 줄 끝에 탑승자 아바타 풍선이 달린다. 차는 아래 가운데, 정지 그림이다.
     * 탑승자가 있으면 장난감 차 자체의 장식 풍선은 그리지 않는다(겹침 방지).
     */
    class CarWithBalloons(context: Context, private val toy: ToyCarDrawable, private val names: List<String>) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val string = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Kk.color(context, R.color.kk_fg) // 흰 배경에서도 보이도록 테마 글자색
            alpha = 230
        }
        private val bow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kk.color(context, R.color.kk_toy_accent) }
        private val bowPath = android.graphics.Path()
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Kk.color(context, R.color.kk_avatar_ink)
            textAlign = Paint.Align.CENTER
            typeface = Kk.typeface(context, 500)
        }

        init { if (names.isNotEmpty()) toy.balloon = false }

        /** 풍선 매듭 자리(x, y)에 리본 모양: 나비 날개 두 개 + 가운데 매듭 */
        private fun drawBow(canvas: Canvas, x: Float, y: Float, k: Float) {
            bowPath.reset()
            bowPath.moveTo(x, y + k * 0.3f)
            bowPath.lineTo(x - k * 1.3f, y - k * 0.5f)
            bowPath.lineTo(x - k * 1.3f, y + k * 1.1f)
            bowPath.close()
            bowPath.moveTo(x, y + k * 0.3f)
            bowPath.lineTo(x + k * 1.3f, y - k * 0.5f)
            bowPath.lineTo(x + k * 1.3f, y + k * 1.1f)
            bowPath.close()
            canvas.drawPath(bowPath, bow)
            canvas.drawCircle(x, y + k * 0.3f, k * 0.4f, bow)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            // 차 상자: 높이의 62%, 가로는 가운데보다 살짝 왼쪽(풍선이 오른쪽으로 퍼질 자리)
            val side = min(h * 0.68f, w * 0.74f)
            val left = w * 0.46f - side / 2f
            val top = h - side
            toy.setBounds(left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt())
            toy.draw(canvas)
            val list = names.take(4)
            if (list.isEmpty()) return
            // 줄이 나오는 곳: 장난감 차 150 격자의 (113, 79) — 몸 뒤쪽 위
            val ax = left + side * 113f / 150f
            val ay = top + side * 79f / 150f
            val r = side * 0.16f
            string.strokeWidth = resources.displayMetrics.density * 2.6f
            label.textSize = r * 0.9f
            val n = list.size
            list.forEachIndexed { i, name ->
                // 각도: 위(-100°)에서 오른쪽 위(-35°)까지 고르게, 길이는 번갈아 길고 짧게
                val t = if (n == 1) 0.5f else i / (n - 1f)
                val deg = -112f + 56f * t
                val len = side * (if (i % 2 == 0) 0.82f else 0.66f)
                val rad = Math.toRadians(deg.toDouble())
                var cx = ax + (len * Math.cos(rad)).toFloat()
                var cy = ay + (len * Math.sin(rad)).toFloat()
                cx = cx.coerceIn(r + 2f, w - r - 2f)
                cy = cy.coerceAtLeast(r + 2f)
                canvas.drawLine(ax, ay, cx, cy + r + r * 0.35f, string)
                fill.color = colorOf(context, name)
                canvas.drawCircle(cx, cy, r, fill)
                drawBow(canvas, cx, cy + r, r * 0.42f)
                canvas.drawText(initial(name), cx, cy - (label.descent() + label.ascent()) / 2f, label)
            }
        }
    }

    /** 세션 대기 가로 창의 도로 띠: 면 kk_road + 점선 차선(Figma "도로") */
    class RoadView(context: Context) : View(context) {
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kk.color(context, R.color.kk_road) }
        private val lane = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Kk.color(context, R.color.kk_line)
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRect(rect, bar)
            lane.strokeWidth = 4f * d
            lane.pathEffect = DashPathEffect(floatArrayOf(44f * d, 26f * d), 0f)
            val y = height / 2f
            canvas.drawLine(0f, y, width.toFloat(), y, lane)
        }
    }
}
