package com.okkikki.kkiri.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.okkikki.kkiri.R
import com.okkikki.kkiri.contract.Message
import com.okkikki.kkiri.contract.Session
import com.okkikki.kkiri.ui.theme.Kk

/**
 * 메시지·지도(목업)·도움말 탭 (V2-S11). 설정 탭은 내용이 없어 레일에서 항상 꺼져 있다.
 * - 메시지: 보관 메시지(R-23·R-24 흐름은 MainActivity.loadHeld 그대로). 없으면 한 줄.
 * - 지도: 목업 정지 그림("예시 화면이에요") + 경유지 변경 내역(내 차 것). 길안내·실제 위치 없음.
 * - 도움말: 음성 안내 카드 3개.
 */
object TabScreens {

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun scaffold(context: Context, titleRes: Int, fill: LinearLayout.() -> Unit): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 48))
            addView(Kk.text(context, context.getString(titleRes), Kk.TextStyleKk.TITLE_S_BOLD))
            fill()
        }
        return ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(Kk.color(context, R.color.kk_bg))
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun LinearLayout.addGap(context: Context, v: LinearLayout.LayoutParams.() -> Unit = {}, view: View) {
        addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 24); v()
        })
    }

    private fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Kk.cardBackground(context)
        setPadding(dp(context, 28), dp(context, 24), dp(context, 28), dp(context, 24))
    }

    /** 샘플 화면 표시: 내용 위에 반투명 안내 상자를 가운데 얹는다(눌러도 아무 일 없음) */
    private fun sample(context: Context, content: View): View = android.widget.FrameLayout(context).apply {
        addView(content, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(
            Kk.text(context, context.getString(R.string.sample_notice), Kk.TextStyleKk.TITLE_S_BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(dp(context, 40), dp(context, 24), dp(context, 40), dp(context, 24))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = context.resources.getDimension(R.dimen.kk_r_card)
                    setColor((Kk.color(context, R.color.kk_card) and 0x00FFFFFF) or 0xB0000000.toInt())
                }
            },
            android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    /**
     * 메시지 탭(초안 07 C안 "공간 말풍선", 목업): 도로 위 차마다 최근 메시지 말풍선 + 받는 차 선택 줄 + [음성으로 보내기].
     * 내용은 고정 예시이고 버튼·선택은 동작하지 않는다. [held]는 쓰지 않는다(실제 목록은 기능 구현 때 다시 연결).
     */
    @Suppress("UNUSED_PARAMETER")
    fun messages(context: Context, held: List<Message>): View = sample(context, scaffold(context, R.string.tab_messages) {
        fun bubble(text: String, me: Boolean) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(context, 20).toFloat()
                setColor(Kk.color(context, if (me) R.color.kk_btn else R.color.kk_card))
            }
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12))
            val ink = if (me) R.color.kk_btn_ink else R.color.kk_fg
            addView(Kk.small(context, text, ink))
            addView(Kk.small(context, context.getString(R.string.msg_sample_time), ink).apply { alpha = 0.6f })
        }
        fun col(name: String, text: String?, me: Boolean, faded: Boolean) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            if (text != null) addView(bubble(text, me), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(context, if (me) 72 else 0) })
            addView(android.widget.ImageView(context).apply {
                setImageDrawable(ToyCarDrawable(context, me))
                if (faded) alpha = 0.35f
            }, LinearLayout.LayoutParams(dp(context, 150), dp(context, 150)))
            addView(Kk.small(context, name, R.color.kk_fg).apply { if (faded) alpha = 0.6f })
        }
        addGap(context, view = android.widget.FrameLayout(context).apply {
            addView(Riders.RoadView(context), android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 64), Gravity.BOTTOM).apply { bottomMargin = dp(context, 38) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                addView(col("B차", context.getString(R.string.msg_sample_b1), false, false), lp())
                addView(col("A차 · 나", context.getString(R.string.msg_sample_a1), true, false), lp())
                addView(col("C차", context.getString(R.string.msg_sample_c1), false, false), lp())
                addView(col("D차", null, false, true), lp())
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        addGap(context, view = Kk.small(context, context.getString(R.string.msg_to_label), R.color.kk_fg).apply { alpha = 0.75f })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(context.getString(R.string.msg_to_all), "B차", "C차", "D차").forEachIndexed { i, label ->
                addView(Kk.caption(context, label, if (i == 0) R.color.kk_brand_text else R.color.kk_fg).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(context, 28), 0, dp(context, 28), 0)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(context, 48).toFloat()
                        setColor(Kk.color(context, if (i == 0) R.color.kk_brand_tint else R.color.kk_chip))
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 96)).apply { if (i > 0) marginStart = dp(context, 12) })
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, 12) })
        addGap(context, view = Kk.primaryButton(context, context.getString(R.string.msg_send_voice)) {}, v = { height = Kk.px(context, R.dimen.kk_h_btn) })
    })

    /**
     * 지도 탭(초안 08 A안 "겹쳐 보기", 목업): 범례 + 지도 그림(실제 지도 SDK 자리) + 경유지 요약 카드·[경유지 편집].
     * 지도 그림은 정지 예시다. 차 점은 세션의 차(최대 4대)를, 요약은 내 차 경유지와 세션 목적지를 쓴다.
     * [경유지 편집]은 아직 기능이 없어 "준비 중" 상자를 띄운다.
     */
    fun map(context: Context, session: Session?, myCarId: String?): View = sample(context, scaffold(context, R.string.tab_map) {
        addGap(context, view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            fun legend(colorRes: Int, label: Int, dashed: Boolean) {
                addView(LegendLine(context, Kk.color(context, colorRes), dashed), LinearLayout.LayoutParams(dp(context, 40), dp(context, 16)))
                addView(Kk.small(context, context.getString(label), R.color.kk_fg).apply { alpha = 0.8f }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(context, 10); marginEnd = dp(context, 24) })
            }
            legend(R.color.kk_toy_accent, R.string.map_legend_group, false)
            legend(R.color.kk_route_other, R.string.map_legend_other, true)
        })
        addGap(context, view = MapMockView(context, session, myCarId))
        val mine = session?.waypoints?.filter { it.carId == myCarId }.orEmpty()
        val dest = session?.destination?.name ?: context.getString(R.string.map_no_dest)
        addGap(context, view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Kk.cardBackground(context)
            setPadding(dp(context, 24), dp(context, 20), dp(context, 20), dp(context, 20))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Kk.caption(context, context.getString(R.string.map_summary, mine.size, dest)))
                mine.forEach { w ->
                    addView(Kk.small(context, context.getString(R.string.car05_waypoint_line, w.place.name, w.addedBy.name), R.color.kk_brand_text))
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                Kk.ghostButton(context, context.getString(R.string.map_edit)) {},
                LinearLayout.LayoutParams(dp(context, 260), Kk.px(context, R.dimen.kk_h_btn)).apply { marginStart = dp(context, 16) },
            )
        })
    })

    /** 아직 기능이 없는 화면: 헤더 + 점선 테두리 "준비 중" 상자 */
    fun comingSoonPage(context: Context, titleRes: Int): View = scaffold(context, titleRes) {
        addGap(context, view = comingSoonBox(context))
    }

    /** "기능 구현 전" 표시 상자: 점선 테두리 + 안내 문구 2줄. 다른 화면에서도 쓴다 */
    fun comingSoonBox(context: Context): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(context, 32), dp(context, 48), dp(context, 32), dp(context, 48))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = context.resources.getDimension(R.dimen.kk_r_card)
            setColor(Kk.color(context, R.color.kk_card))
            setStroke(dp(context, 2), Kk.color(context, R.color.kk_line), dp(context, 12).toFloat(), dp(context, 8).toFloat())
        }
        addView(Kk.body(context, context.getString(R.string.coming_soon)).apply { gravity = Gravity.CENTER })
        addView(Kk.small(context, context.getString(R.string.coming_soon_sub), R.color.kk_fg).apply { gravity = Gravity.CENTER; alpha = 0.75f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, 8) })
    }

    /** 범례 선(실선 또는 점선) */
    private class LegendLine(context: Context, private val c: Int, private val dashed: Boolean) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(canvas: Canvas) {
            p.color = c
            p.strokeWidth = height * 0.5f
            p.pathEffect = if (dashed) DashPathEffect(floatArrayOf(width * 0.3f, width * 0.2f), 0f) else null
            canvas.drawLine(p.strokeWidth / 2f, height / 2f, width - p.strokeWidth / 2f, height / 2f, p)
        }
    }

    fun help(context: Context): View = scaffold(context, R.string.tab_help) {
        listOf(R.string.car04_hint_message, R.string.car04_hint_mute, R.string.car04_hint_proposal).forEach {
            addGap(context, view = card(context).apply { addView(Kk.body(context, context.getString(it))) })
        }
    }

    /**
     * 지도 목업(초안 08 A안). 디자인 672×808 좌표를 폭에 맞춰 그린다. 정지 그림이며 실제 위치·길안내가 아니다.
     * 도로 · 일행 경로(보라) · 다른 경로(빨강 점선) · 경유지 핀 1·2 · 추가 제안 핀(+) · 목적지 · 차 점(나는 크게).
     */
    private class MapMockView(context: Context, session: Session?, myCarId: String?) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Kk.typeface(context, 400) }
        private val path = Path()
        private val rect = RectF()

        /** 차 점: 글자, 색, 나인지, 흐리게(끊김) */
        private class Dot(val letter: String, val color: Int, val me: Boolean, val faded: Boolean)
        private val dots: List<Dot>
        // 다른 차 자리(디자인 좌표, 가운데): C · B · D 순
        private val others = listOf(181f to 315f, 370f to 308f, 114f to 553f)
        private val fallback = intArrayOf(0xFFE8453C.toInt(), 0xFF3D8BFD.toInt(), 0xFF7BC86C.toInt())

        init {
            val cars = session?.cars.orEmpty()
            val me = cars.firstOrNull { it.carId == myCarId }
            val list = ArrayList<Dot>()
            list.add(Dot(me?.displayName?.take(1) ?: "A", com.okkikki.kkiri.state.DecoStore.color.value, true, false))
            cars.filter { it.carId != myCarId }.take(3).forEachIndexed { i, c ->
                val col = c.look?.color?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() } ?: fallback[i]
                list.add(Dot(c.displayName.take(1), col, false, c.status == com.okkikki.kkiri.contract.CarStatus.DISCONNECTED))
            }
            dots = list
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, (w * H / W).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val k = width / W
            fun x(v: Float) = v * k
            val r = 24f * k
            val clip = Path().apply { rect.set(0f, 0f, width.toFloat(), height.toFloat()); addRoundRect(rect, r, r, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawColor(Kk.color(context, R.color.kk_map_bg))
            // 도로
            paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.pathEffect = null
            paint.color = Kk.color(context, R.color.kk_road); paint.alpha = 255
            paint.strokeWidth = x(22f)
            fun seg(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x(x1), x(y1), x(x2), x(y2), paint)
            seg(0f, 154f, 672f, 210f); seg(0f, 434f, 672f, 490f); seg(101f, 0f, 135f, 700f); seg(417f, 0f, 471f, 700f); seg(0f, 630f, 672f, 350f)
            // 일행 경로(보라)
            paint.color = Kk.color(context, R.color.kk_toy_accent); paint.strokeWidth = x(12f)
            paint.strokeJoin = Paint.Join.ROUND
            path.reset(); path.moveTo(x(54f), x(616f)); path.lineTo(x(255f), x(399f)); path.lineTo(x(316f), x(364f))
            path.lineTo(x(511f), x(182f)); path.lineTo(x(605f), x(84f))
            canvas.drawPath(path, paint)
            // 다른 경로(빨강 점선)
            paint.color = Kk.color(context, R.color.kk_route_other); paint.strokeWidth = x(10f)
            paint.pathEffect = DashPathEffect(floatArrayOf(x(24f), x(18f)), 0f)
            path.reset(); path.moveTo(x(181f), x(434f)); path.lineTo(x(181f), x(315f)); path.lineTo(x(269f), x(140f)); path.lineTo(x(510f), x(182f))
            canvas.drawPath(path, paint)
            paint.pathEffect = null
            // 경유지 핀 1·2, 제안 핀 +
            pin(canvas, k, 316f, 364f, Kk.color(context, R.color.kk_toy_accent), "1")
            pin(canvas, k, 511f, 182f, Kk.color(context, R.color.kk_toy_accent), "2")
            pin(canvas, k, 269f, 140f, Kk.color(context, R.color.kk_route_other), "+")
            // 목적지
            paint.style = Paint.Style.FILL; paint.color = Kk.color(context, R.color.kk_fg)
            rect.set(x(551f), x(18f), x(659f), x(72f)); canvas.drawRoundRect(rect, x(20f), x(20f), paint)
            text.textSize = x(28f); text.color = Kk.color(context, R.color.kk_bg)
            canvas.drawText(context.getString(R.string.map_dest), rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2f, text)
            // 차 점: 다른 차 먼저, 나는 맨 위에
            dots.drop(1).forEachIndexed { i, d -> carDot(canvas, k, others[i].first, others[i].second, d) }
            dots.firstOrNull()?.let { carDot(canvas, k, 255f, 399f, it) }
            // 목업 표시
            text.textSize = x(26f); text.color = Kk.color(context, R.color.kk_fg)
            val label = context.getString(R.string.map_mock_label)
            val tw = text.measureText(label)
            paint.color = Kk.color(context, R.color.kk_rail); paint.alpha = 230
            rect.set(x(20f), height - x(70f), x(20f) + tw + x(36f), height - x(20f))
            canvas.drawRoundRect(rect, x(16f), x(16f), paint)
            paint.alpha = 255
            canvas.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2f, text)
            canvas.restore()
        }

        private fun pin(canvas: Canvas, k: Float, cx: Float, cy: Float, fillColor: Int, label: String) {
            paint.style = Paint.Style.FILL; paint.color = fillColor; paint.alpha = 255
            canvas.drawCircle(cx * k, cy * k, 26f * k, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f * k; paint.color = Kk.color(context, R.color.kk_toy_ink)
            canvas.drawCircle(cx * k, cy * k, 24f * k, paint)
            text.textSize = 28f * k; text.color = Kk.color(context, R.color.kk_toy_ink)
            canvas.drawText(label, cx * k, cy * k - (text.descent() + text.ascent()) / 2f, text)
        }

        private fun carDot(canvas: Canvas, k: Float, cx: Float, cy: Float, d: Dot) {
            val rr = (if (d.me) 36f else 30f) * k
            val a = if (d.faded) 90 else 255
            paint.style = Paint.Style.FILL; paint.color = d.color; paint.alpha = a
            canvas.drawCircle(cx * k, cy * k, rr, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = (if (d.me) 6f else 4f) * k
            paint.color = Kk.color(context, if (d.me) R.color.kk_toy_cream else R.color.kk_toy_ink); paint.alpha = a
            canvas.drawCircle(cx * k, cy * k, rr - paint.strokeWidth / 2f, paint)
            text.textSize = 32f * k; text.color = Kk.color(context, R.color.kk_toy_ink); text.alpha = a
            canvas.drawText(d.letter, cx * k, cy * k - (text.descent() + text.ascent()) / 2f, text)
            text.alpha = 255
        }

        private companion object {
            const val W = 672f
            const val H = 808f
        }
    }
}
