package com.okkikki.kkiri.ui

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.okkikki.kkiri.R
import com.okkikki.kkiri.contract.CarLook
import com.okkikki.kkiri.contract.CarStatus
import com.okkikki.kkiri.state.DecoStore
import com.okkikki.kkiri.ui.theme.Kk
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * "공간" 광장 그림 (Figma 확정본: CAR-05 정차 중 / CAR-04 주행 중, P-1W 기준 672×756).
 * - 디자인 좌표(dp)로 그리고 창 크기에 맞춰 한 번에 키운다. 4대까지는 확정본 좌표, 그보다 많으면 링 위에 같은 간격, 9대부터는 "+n".
 * - 내 차 = 가운데 앞쪽, [setTilt]로만 기울인다(핸들 각도 연동, 정차 중에만 MainActivity가 값을 넣는다).
 * - 이름 칩: 연결 점 + 이름(내 차는 "이름 · 나") + 음소거 아이콘. 발화 중인 차는 칩 테두리 + 소리 막대 말풍선 + "말하는 중".
 * - 정차 중([interactive])에만 일행 차 이름 칩을 눌러 음소거 말풍선을 연다. 주행 중에는 터치를 받지 않는다.
 * - 메시지 내용은 그리지 않는다(주행 중 메시지 본문 비표시 규칙).
 * - 가로(전체) 창에서는 광장·전구 줄·차 간격을 가로로 넓혀 화면 폭을 채운다(차 크기는 그대로).
 * - 애니메이션: 정차 중([interactive])에 누군가 말하면 그 차가 숨 쉬듯 커졌다 작아진다. 주행 중에는 움직임이 없다.
 */
class SpaceView(context: Context) : View(context) {

    /** 광장에 올릴 차 한 대 */
    data class Mark(
        val carId: String,
        val carName: String,
        val driverName: String,
        val isMe: Boolean,
        val status: CarStatus,
        val muted: Boolean,
        val heldCount: Int,
        /** v1.15: 다른 차의 모양(내 차는 DecoStore·핸들 값을 쓴다) */
        val look: CarLook? = null,
    )

    private var marks: List<Mark> = emptyList()
    private var othersTilt = false
    private var speakingCarId: String? = null
    private var interactive = false
    private var tiltDeg = 0f
    /** 말하는 차 맥박(0..1 왕복). 정차 중 발화 때만 돈다 */
    private var pulse = 0f
    private var pulser: ValueAnimator? = null
    private var dw = DESIGN_W
    private var ex = 1f
    private fun fx(x: Float) = dw / 2f + (x - DESIGN_W / 2f) * ex
    private val glowCache = HashMap<Int, RadialGradient>()

    /** 말풍선에서 [음소거]/[음소거 풀기]를 눌렀을 때 (carId, mute) */
    var onMuteToggle: ((String, Boolean) -> Unit)? = null

    private val toys = HashMap<String, ToyCarDrawable>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    // 화면 → 디자인 좌표 변환
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    // 눌림 판정(디자인 좌표)
    private class Hit(val carId: String, val rect: RectF)
    private val chipHits = ArrayList<Hit>()
    private val bubbleRect = RectF()
    private val bubbleButton = RectF()

    private var bubbleCarId: String?
        get() = openBubble
        set(v) { openBubble = v }

    fun setState(list: List<Mark>, speaking: String?, interactive: Boolean) {
        marks = list
        speakingCarId = speaking
        this.interactive = interactive
        if (!interactive || list.none { it.carId == openBubble }) openBubble = null
        updatePulse()
        invalidate()
    }

    private fun updatePulse() {
        val want = interactive && speakingCarId != null && isAttachedToWindow
        if (want && pulser == null) {
            pulser = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (!want && pulser != null) {
            pulser?.cancel(); pulser = null; pulse = 0f
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updatePulse() }
    override fun onDetachedFromWindow() { pulser?.cancel(); pulser = null; super.onDetachedFromWindow() }

    /** 다른 차의 기울임을 움직일지(내 차가 정차 중일 때만, 명세 6.3) */
    fun setOthersTilt(enabled: Boolean) {
        if (enabled == othersTilt) return
        othersTilt = enabled
        invalidate()
    }

    /** 내 차 기울기(도). 양수 = 시계 방향(오른쪽) */
    fun setTilt(deg: Float) {
        if (deg == tiltDeg) return
        tiltDeg = deg
        invalidate()
    }

    private fun color(res: Int) = Kk.color(context, res)
    private fun dim(res: Int) = resources.getDimension(res)

    /** 차 한 대의 자리(디자인 좌표: 가운데와 한 변) */
    private class Placed(val mark: Mark, val cx: Float, val cy: Float, val size: Float)

    private fun layoutCars(): Pair<List<Placed>, Int> {
        val me = marks.firstOrNull { it.isMe }
        val others = marks.filter { !it.isMe }
        val crowded = marks.size > 4
        val base = if (crowded) 120f else 150f
        val shown = others.take(MAX_OTHERS)
        val more = others.size - shown.size
        val out = ArrayList<Placed>()
        fun sizeOf(m: Mark) = if (m.carId == speakingCarId && !m.isMe) base + 50f else base
        if (!crowded) {
            shown.forEachIndexed { i, m ->
                val (cx, cy) = FIXED_SLOTS[i]
                out.add(Placed(m, fx(cx), cy, sizeOf(m)))
            }
        } else {
            val k = shown.size
            shown.forEachIndexed { i, m ->
                val t = if (k == 1) 0.5f else i / (k - 1f)
                val ang = Math.toRadians(200.0 - 220.0 * t)
                val cx = dw / 2f + RING_RX * ex * cos(ang).toFloat()
                val cy = RING_CY - RING_RY * sin(ang).toFloat() - 20f
                out.add(Placed(m, cx, cy, sizeOf(m)))
            }
        }
        if (me != null) out.add(Placed(me, fx(ME_SLOT.first), ME_SLOT.second, base))
        return out to more
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        scale = minOf(w / DESIGN_W, h / DESIGN_H, MAX_SCALE)
        // 가로 창: 높이로 정해진 배율에서 남는 가로를 디자인 폭으로 늘려 광장이 화면 폭을 채우게 한다
        dw = maxOf(DESIGN_W, w / scale)
        ex = (dw / DESIGN_W).coerceAtMost(MAX_STRETCH)
        offX = (w - dw * scale) / 2f
        offY = maxOf(0f, (h - DESIGN_H * scale) / 2f)
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)

        // 내 광장 배경(내 화면에만)
        PlazaTheme.draw(canvas, DecoStore.plaza.value, 0f, 0f, dw, DESIGN_H)
        drawLights(canvas)
        // 광장 바닥과 링
        paint.style = Paint.Style.FILL
        paint.color = color(R.color.kk_road)
        rect.set(fx(16f), 290f, fx(656f), 540f)
        canvas.drawOval(rect, paint)
        // 광장 링: 차선처럼 긴 점선
        strokePaint.color = color(R.color.kk_toy_cream)
        strokePaint.alpha = 150
        strokePaint.strokeWidth = 5f
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.pathEffect = DashPathEffect(floatArrayOf(26f, 18f), 0f)
        rect.set(fx(76f), 330f, fx(596f), 500f)
        canvas.drawOval(rect, strokePaint)
        strokePaint.pathEffect = null
        strokePaint.strokeCap = Paint.Cap.ROUND

        chipHits.clear()
        if (marks.isEmpty()) { canvas.restore(); return }
        val (placed, more) = layoutCars()

        // 뒤(위)에 있는 차부터 그린다. 내 차는 맨 앞
        val ordered = placed.sortedBy { if (it.mark.isMe) Float.MAX_VALUE else it.cy }
        ordered.forEach { drawCar(canvas, it) }
        ordered.forEach { drawChip(canvas, it) }
        placed.firstOrNull { it.mark.carId == speakingCarId }?.let { drawSpeaking(canvas, it) }
        if (more > 0) drawMore(canvas, more)
        bubbleCarId?.let { id -> placed.firstOrNull { it.mark.carId == id }?.let { drawBubble(canvas, it, h) } }
        canvas.restore()
    }

    /** 위쪽 조명: 양 끝에 고정된 늘어진 전선 + 소켓 달린 전구 + 은은한 빛 번짐(크림/보라 번갈아) */
    private fun drawLights(canvas: Canvas) {
        val x0 = fx(16f)
        val x1 = fx(656f)
        val y0 = 56f
        val sag = 116f // 제어점 아래로 처진 정도(곡선 가운데는 그 절반)
        // 전선
        path.reset()
        path.moveTo(x0, y0)
        path.quadTo((x0 + x1) / 2f, y0 + sag, x1, y0)
        strokePaint.color = color(R.color.kk_toy_cream)
        strokePaint.alpha = 170
        strokePaint.strokeWidth = 3.5f
        canvas.drawPath(path, strokePaint)
        // 양 끝 고정 못
        paint.style = Paint.Style.FILL
        paint.color = color(R.color.kk_toy_cream)
        paint.alpha = 220
        canvas.drawCircle(x0, y0, 7f, paint)
        canvas.drawCircle(x1, y0, 7f, paint)
        val n = (10 * ex).roundToInt().coerceIn(10, 22)
        for (i in 0 until n) {
            val t = (i + 0.5f) / n
            val u = 1f - t
            val bx = u * u * x0 + 2f * u * t * ((x0 + x1) / 2f) + t * t * x1
            val by = u * u * y0 + 2f * u * t * (y0 + sag) + t * t * y0
            val c = color(if (i % 2 == 0) R.color.kk_toy_cream else R.color.kk_toy_accent)
            // 빛 번짐
            val glow = glowCache.getOrPut(c) {
                RadialGradient(0f, 0f, 34f, intArrayOf((c and 0x00FFFFFF) or 0x80000000.toInt(), c and 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.save()
            canvas.translate(bx, by + 12f)
            paint.shader = glow
            canvas.drawCircle(0f, 0f, 34f, paint)
            paint.shader = null
            canvas.restore()
            // 소켓
            paint.color = color(R.color.kk_toy_ink)
            paint.alpha = 255
            rect.set(bx - 4f, by - 2f, bx + 4f, by + 8f)
            canvas.drawRoundRect(rect, 2f, 2f, paint)
            // 전구
            paint.color = c
            paint.alpha = 255
            canvas.drawCircle(bx, by + 16f, 9f, paint)
            paint.color = 0x66FFFFFF
            canvas.drawCircle(bx - 3f, by + 12f, 3f, paint)
        }
        paint.alpha = 255
    }

    /** 이름 칩 면의 투명도: 평소에도 반투명, 연결이 끊기면 더, 음소거면 거의 투명 */
    private fun chipAlpha(m: Mark) = when {
        m.muted -> 90
        m.status == CarStatus.DISCONNECTED -> 110
        else -> 170
    }

    private fun alphaOf(status: CarStatus) = when (status) {
        CarStatus.DISCONNECTED -> 90
        CarStatus.PAUSED -> 128
        else -> 255
    }

    private fun drawCar(canvas: Canvas, p: Placed) {
        val toy = toys.getOrPut(p.mark.carId) { ToyCarDrawable(context, p.mark.isMe) }
        val look = p.mark.look
        if (p.mark.isMe) toy.applyMine() else toy.applyLook(look)
        toy.alpha = if (p.mark.muted) 70 else alphaOf(p.mark.status) // 음소거 차는 투명하게
        toy.setBounds((p.cx - p.size / 2f).roundToInt(), (p.cy - p.size / 2f).roundToInt(), (p.cx + p.size / 2f).roundToInt(), (p.cy + p.size / 2f).roundToInt())
        canvas.save()
        // 말하는 차는 바닥 가운데를 축으로 살짝 커졌다 작아진다(정차 중에만 pulse가 돈다)
        if (p.mark.carId == speakingCarId && pulse > 0f) {
            val k = 1f + PULSE_AMP * pulse
            canvas.scale(k, k, p.cx, p.cy + p.size / 2f)
        }
        // 바퀴가 닿는 바닥 가운데를 축으로 기울인다
        val deg = when {
            p.mark.isMe -> tiltDeg
            othersTilt && look != null -> -(look.steerStep.coerceIn(-2, 2) * resources.getInteger(R.integer.kk_steer_tilt_per_step_deg)).toFloat()
            else -> 0f
        }
        if (deg != 0f) canvas.rotate(deg, p.cx, p.cy + p.size / 2f)
        toy.draw(canvas)
        canvas.restore()
    }

    private fun setText(sizeRes: Int, weight: Int, colorRes: Int) {
        textPaint.typeface = Kk.typeface(context, weight)
        textPaint.textSize = dim(sizeRes)
        textPaint.color = color(colorRes)
        textPaint.alpha = 255
    }

    private fun chipLabel(m: Mark): String {
        val name = m.driverName.ifEmpty { m.carName }
        return if (m.isMe) context.getString(R.string.space_chip_me, name) else name
    }

    private fun drawChip(canvas: Canvas, p: Placed) {
        val m = p.mark
        setText(R.dimen.kk_t_small, 400, R.color.kk_fg)
        val label = chipLabel(m)
        val textW = textPaint.measureText(label)
        val iconW = if (m.muted) 38f else 0f
        val cw = 20f + 16f + 10f + textW + iconW + 24f
        val ch = 50f
        val x = p.cx - cw / 2f
        val y = p.cy - p.size / 2f - 30f // 차 머리에서 살짝 위로(확정 후 수정 2026-11-15)
        val chipA = chipAlpha(m)
        val speaking = m.carId == speakingCarId
        rect.set(x, y, x + cw, y + ch)
        paint.style = Paint.Style.FILL
        paint.color = color(R.color.kk_rail)
        paint.alpha = chipA
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        if (speaking) {
            strokePaint.color = color(R.color.kk_speaking)
            strokePaint.alpha = 255
            strokePaint.strokeWidth = 3f
            rect.inset(1.5f, 1.5f)
            canvas.drawRoundRect(rect, 12f, 12f, strokePaint)
        }
        // 연결 점
        paint.color = color(if (m.status == CarStatus.CONNECTED) R.color.kk_online else R.color.kk_fg_muted)
        paint.alpha = if (m.muted) 110 else 230
        canvas.drawCircle(x + 20f + 8f, y + ch / 2f, 8f, paint)
        // 이름
        setText(R.dimen.kk_t_small, 400, R.color.kk_fg)
        val fm = textPaint.fontMetrics
        textPaint.alpha = if (m.muted) 130 else 235
        canvas.drawText(label, x + 46f, y + ch / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
        textPaint.alpha = 255
        if (m.muted) drawMuteIcon(canvas, x + 46f + textW + 10f, y + (ch - 28f) / 2f, 28f, color(R.color.kk_fg))
        if (!m.isMe) chipHits.add(Hit(m.carId, RectF(x, y, x + cw, y + ch)))
    }

    /** 음소거 아이콘: 스피커 + 가위표 (28 기준) */
    private fun drawMuteIcon(canvas: Canvas, x: Float, y: Float, size: Float, colorInt: Int) {
        val k = size / 28f
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(k, k)
        strokePaint.color = colorInt
        strokePaint.alpha = 255
        strokePaint.strokeWidth = 2.1f
        path.reset()
        path.moveTo(5f, 11f); path.lineTo(9f, 11f); path.lineTo(14f, 6.5f); path.lineTo(14f, 21.5f); path.lineTo(9f, 17f); path.lineTo(5f, 17f); path.close()
        canvas.drawPath(path, strokePaint)
        path.reset()
        path.moveTo(19f, 11f); path.lineTo(25f, 17f); path.moveTo(25f, 11f); path.lineTo(19f, 17f)
        canvas.drawPath(path, strokePaint)
        canvas.restore()
    }

    /** 발화 중: 칩 옆 소리 막대 말풍선(76×62) + 차 아래 "말하는 중" 칩 */
    private fun drawSpeaking(canvas: Canvas, p: Placed) {
        setText(R.dimen.kk_t_small, 400, R.color.kk_fg)
        val textW = textPaint.measureText(chipLabel(p.mark))
        val chipW = 20f + 16f + 10f + textW + (if (p.mark.muted) 38f else 0f) + 24f
        val bx = p.cx + chipW / 2f + 4f
        val by = p.cy - p.size / 2f - 16f - 42f
        paint.style = Paint.Style.FILL
        paint.color = color(R.color.kk_speaking)
        rect.set(bx, by, bx + 76f, by + 50f)
        canvas.drawRoundRect(rect, 18f, 18f, paint)
        path.reset()
        path.moveTo(bx + 12f, by + 46f); path.lineTo(bx + 14f, by + 62f); path.lineTo(bx + 30f, by + 46f); path.close()
        canvas.drawPath(path, paint)
        paint.color = color(R.color.kk_bg)
        val bars = floatArrayOf(14f, 26f, 34f, 24f, 16f)
        bars.forEachIndexed { i, bh ->
            val l = bx + 15f + 10f * i
            val t = by + 25f - bh / 2f
            rect.set(l, t, l + 6f, t + bh)
            canvas.drawRoundRect(rect, 3f, 3f, paint)
        }
        // "말하는 중" 칩
        val label = context.getString(R.string.space_speaking)
        setText(R.dimen.kk_t_small, 400, R.color.kk_speaking)
        val tw = textPaint.measureText(label)
        val cw = tw + 32f
        val cx = p.cx - cw / 2f
        val cy = p.cy + p.size / 2f - 38f
        paint.color = color(R.color.kk_rail)
        rect.set(cx, cy, cx + cw, cy + 46f)
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        setText(R.dimen.kk_t_small, 400, R.color.kk_speaking)
        val fm = textPaint.fontMetrics
        canvas.drawText(label, cx + 16f, cy + 23f - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    private fun drawMore(canvas: Canvas, n: Int) {
        val label = context.getString(R.string.space_more, n)
        setText(R.dimen.kk_t_small, 400, R.color.kk_fg)
        val tw = textPaint.measureText(label)
        val cw = tw + 32f
        val x = fx(656f) - cw
        val y = 500f
        paint.style = Paint.Style.FILL
        paint.color = color(R.color.kk_rail)
        rect.set(x, y, x + cw, y + 46f)
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        setText(R.dimen.kk_t_small, 400, R.color.kk_fg)
        val fm = textPaint.fontMetrics
        canvas.drawText(label, x + 16f, y + 23f - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    /** 음소거 말풍선(440×268, r24, 면 kk_rail). 칩을 누른 차 기준으로 광장 아래에 띄운다 */
    private fun drawBubble(canvas: Canvas, p: Placed, viewH: Float) {
        val m = p.mark
        val chipCx = p.cx
        val left = (chipCx - 380f).coerceIn(8f, dw - 440f - 8f)
        var top = 568f
        // 창이 낮으면 아래 끝이 잘리지 않게 끌어올린다
        val maxTopDesign = (viewH - offY) / scale - 268f - 8f
        if (top > maxTopDesign) top = maxTopDesign.coerceAtLeast(0f)
        bubbleRect.set(left, top, left + 440f, top + 268f)
        paint.style = Paint.Style.FILL
        paint.color = color(R.color.kk_rail)
        canvas.drawRoundRect(bubbleRect, 24f, 24f, paint)
        // 꼬리(위)
        val tx = (chipCx - 16f).coerceIn(left + 24f, left + 440f - 24f - 32f)
        path.reset()
        path.moveTo(tx, top + 1f); path.lineTo(tx + 16f, top - 17f); path.lineTo(tx + 32f, top + 1f); path.close()
        canvas.drawPath(path, paint)

        val title = if (m.muted) context.getString(R.string.space_mute_title, m.carName, m.driverName) else "${m.carName} · ${m.driverName}"
        var textX = left + 28f
        if (m.muted) {
            drawMuteIcon(canvas, left + 28f, top + 28f + 7f, 32f, color(R.color.kk_fg))
            textX += 42f
        }
        setText(R.dimen.kk_t_body, 400, R.color.kk_fg)
        val fmB = textPaint.fontMetrics
        canvas.drawText(title, textX, top + 28f + 23f - (fmB.ascent + fmB.descent) / 2f, textPaint)
        if (m.muted) {
            setText(R.dimen.kk_t_small, 400, R.color.kk_fg)
            textPaint.alpha = 191
            val fm = textPaint.fontMetrics
            canvas.drawText(context.getString(R.string.space_held, m.heldCount), left + 28f, top + 86f + 19f - (fm.ascent + fm.descent) / 2f, textPaint)
        }
        // 버튼
        bubbleButton.set(left + 28f, top + 136f, left + 412f, top + 240f)
        paint.color = color(R.color.kk_btn)
        canvas.drawRoundRect(bubbleButton, 32f, 32f, paint)
        setText(R.dimen.kk_t_body, 400, R.color.kk_btn_ink)
        textPaint.textAlign = Paint.Align.CENTER
        val fm2 = textPaint.fontMetrics
        canvas.drawText(
            context.getString(if (m.muted) R.string.space_unmute else R.string.car05_mute),
            bubbleButton.centerX(), bubbleButton.centerY() - (fm2.ascent + fm2.descent) / 2f, textPaint,
        )
        textPaint.textAlign = Paint.Align.LEFT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val dx = (event.x - offX) / scale
        val dy = (event.y - offY) / scale
        val open = bubbleCarId
        if (open != null) {
            if (bubbleButton.contains(dx, dy)) {
                val m = marks.firstOrNull { it.carId == open }
                openBubble = null
                invalidate()
                if (m != null) onMuteToggle?.invoke(m.carId, !m.muted)
                return true
            }
            if (bubbleRect.contains(dx, dy)) return true
        }
        val hit = chipHits.firstOrNull { it.rect.contains(dx, dy) }
        openBubble = if (hit != null && hit.carId != open) hit.carId else null
        invalidate()
        performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        /** 열려 있는 말풍선 차(화면을 다시 그려도 유지) */
        private var openBubble: String? = null

        /** 핸들 각도(도)를 기울기 단계로 끊는다: [stepDeg]도마다 1단계, ±[maxSteps]단계까지 */
        fun tiltSteps(angleDeg: Float, stepDeg: Int, maxSteps: Int): Int {
            if (stepDeg <= 0) return 0
            return (angleDeg / stepDeg).roundToInt().coerceIn(-maxSteps, maxSteps)
        }

        private const val DESIGN_W = 672f
        private const val DESIGN_H = 756f
        private const val MAX_SCALE = 1.6f
        private const val MAX_STRETCH = 2.4f
        private const val PULSE_AMP = 0.07f
        private const val MAX_OTHERS = 7
        private const val RING_CX = 336f
        private const val RING_CY = 415f
        private const val RING_RX = 260f
        private const val RING_RY = 85f

        // 확정본 4대 배치(차 가운데): 현우(위) · 민수(왼쪽) · 지윤(오른쪽), 내 차는 가운데 앞
        private val FIXED_SLOTS = listOf(440f to 303f, 125f to 389f, 580f to 408f)
        private val ME_SLOT = 336f to 443f
        private val BULB_Y = floatArrayOf(14f, 30f, 42f, 50f, 54f, 54f, 50f, 42f, 30f, 14f)
    }
}
