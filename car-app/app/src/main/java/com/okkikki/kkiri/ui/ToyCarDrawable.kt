package com.okkikki.kkiri.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.okkikki.kkiri.R
import com.okkikki.kkiri.ui.theme.Kk

/**
 * 장난감 차(Figma KKIRI/Toy car, 150×150 기준 좌표를 칸 크기에 맞춰 늘려 그린다).
 * 머리(38) + 귀 2개(14) + 눈 2개(5) + 몸(82×27 알약) + 바퀴 2개(16). 일행=보라 몸, 나=무채색(kk_me) 몸.
 * 장식 슬롯: 모자(hat), 풍선(balloon) — 꾸미기 탭(S10)에서 쓴다.
 * 정지 그림이다(애니메이션 없음). 기울이기는 부르는 쪽이 캔버스를 돌려서 한다.
 */
class ToyCarDrawable(
    context: Context,
    private val isMe: Boolean = false,
    var hat: Boolean = false,
    var balloon: Boolean = false,
    /** 몸 색을 따로 정할 때(로딩 애니메이션·꾸미기). null이면 나=kk_me, 일행=보라 */
    var bodyColor: Int? = null,
    /** 눈을 감은 모습(로딩 애니메이션 D) */
    var sleeping: Boolean = false,
) : Drawable() {

    private val cream = Kk.color(context, R.color.kk_toy_cream)
    private val ink = Kk.color(context, R.color.kk_toy_ink)
    private val baseAccent = if (isMe) Kk.color(context, R.color.kk_me) else Kk.color(context, R.color.kk_toy_accent)
    private val toyAccent = Kk.color(context, R.color.kk_toy_accent)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var alphaValue = 255

    /** 캐릭터 id(null/모르는 id면 곰). 꾸미기 탭·다른 차 모양(CarLook.character)이 쓴다 */
    var character: String? = null
    /** 소품 id 목록(hat·crown·ribbon·balloon·glasses·flag). hat/balloon 켜기와 합쳐 그린다. 모르는 id는 무시 */
    var items: List<String> = emptyList()

    private val path = android.graphics.Path()
    private fun has(id: String) = items.contains(id) || (id == "hat" && hat) || (id == "balloon" && balloon)
    private fun stroke(color: Int, w: Float) { paint.style = Paint.Style.STROKE; paint.strokeWidth = w; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.color = withAlpha(color) }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val s = minOf(b.width(), b.height()) / REF
        canvas.save()
        canvas.translate(b.left + (b.width() - REF * s) / 2f, b.top + (b.height() - REF * s) / 2f)
        canvas.scale(s, s)
        // 뒤쪽 소품: 깃발(몸 뒤)
        if (has("flag")) {
            stroke(cream, 1.6f); canvas.drawLine(112f, 50f, 112f, 82f, paint)
            path.reset(); path.moveTo(112f, 50f); path.lineTo(130f, 56f); path.lineTo(112f, 63f); path.close()
            fill(0xFFE8453C.toInt()); canvas.drawPath(path, paint)
        }
        drawHead(canvas)
        fill(bodyColor ?: baseAccent); rect.set(34f, 79f, 116f, 106f); canvas.drawRoundRect(rect, 13.5f, 13.5f, paint) // 몸 82×27
        fill(ink); canvas.drawCircle(50f, 109f, 8f, paint)             // 바퀴 16
        canvas.drawCircle(99f, 109f, 8f, paint)
        if (has("hat")) {
            fill(toyAccent); rect.set(64f, 16f, 86f, 43f); canvas.drawRoundRect(rect, 6f, 6f, paint) // 모자 22×27
            fill(cream); canvas.drawCircle(75.5f, 15.5f, 3.5f, paint)                                // 방울 7
        }
        if (has("crown")) {
            path.reset(); path.moveTo(62f, 46f); path.lineTo(62f, 32f); path.lineTo(68.5f, 39f); path.lineTo(75f, 29f)
            path.lineTo(81.5f, 39f); path.lineTo(88f, 32f); path.lineTo(88f, 46f); path.close()
            fill(0xFFF6C343.toInt()); canvas.drawPath(path, paint)
            fill(0xFFE8453C.toInt()); canvas.drawCircle(75f, 41f, 2.4f, paint)
        }
        if (has("ribbon")) {
            fill(0xFFE86A9A.toInt())
            path.reset(); path.moveTo(75f, 44f); path.lineTo(62f, 36f); path.lineTo(62f, 52f); path.close()
            path.moveTo(75f, 44f); path.lineTo(88f, 36f); path.lineTo(88f, 52f); path.close()
            canvas.drawPath(path, paint); canvas.drawCircle(75f, 44f, 4f, paint)
        }
        if (has("glasses")) {
            stroke(ink, 1.8f)
            canvas.drawCircle(67.5f, 62.5f, 6.5f, paint); canvas.drawCircle(81.5f, 62.5f, 6.5f, paint)
            canvas.drawLine(74f, 62.5f, 75f, 62.5f, paint)
        }
        if (has("balloon")) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.4f; paint.color = withAlpha(cream)
            canvas.drawLine(125f, 26f, 120f, 79f, paint)                                            // 끈
            fill(toyAccent); canvas.drawCircle(135.5f, 13.5f, 12.5f, paint)                         // 풍선 25
        }
        canvas.restore()
    }

    private fun eyes(canvas: Canvas, color: Int = ink) {
        if (sleeping) {
            stroke(color, 1.8f)
            rect.set(63.5f, 58.5f, 71.5f, 64.5f); canvas.drawArc(rect, 20f, 140f, false, paint)
            rect.set(77.5f, 58.5f, 85.5f, 64.5f); canvas.drawArc(rect, 20f, 140f, false, paint)
        } else {
            fill(color); canvas.drawCircle(67.5f, 62.5f, 2.5f, paint); canvas.drawCircle(81.5f, 62.5f, 2.5f, paint)
        }
    }

    /** 머리 영역(150 격자의 x 56~94, y 46~84)에 캐릭터별 얼굴을 그린다 */
    private fun drawHead(canvas: Canvas) {
        when (character) {
            "elephant" -> {
                fill(0xFFB9BFCC.toInt()); canvas.drawCircle(58f, 63f, 10f, paint); canvas.drawCircle(92f, 63f, 10f, paint)
                fill(0xFFD3D8E2.toInt()); canvas.drawCircle(75f, 65f, 19f, paint)
                fill(0xFFB9BFCC.toInt()); rect.set(71.5f, 68f, 78.5f, 86f); canvas.drawRoundRect(rect, 3.5f, 3.5f, paint)
                eyes(canvas)
            }
            "monkey" -> {
                fill(0xFF8A5A3C.toInt()); canvas.drawCircle(75f, 65f, 19f, paint); canvas.drawCircle(57f, 64f, 7f, paint); canvas.drawCircle(93f, 64f, 7f, paint)
                fill(0xFFE9C9A0.toInt()); canvas.drawCircle(57f, 64f, 3.6f, paint); canvas.drawCircle(93f, 64f, 3.6f, paint)
                rect.set(62f, 58f, 88f, 80f); canvas.drawOval(rect, paint)
                eyes(canvas)
            }
            "robot" -> {
                stroke(0xFFAAB2C0.toInt(), 1.8f); canvas.drawLine(75f, 47f, 75f, 40f, paint)
                fill(0xFFE8453C.toInt()); canvas.drawCircle(75f, 38.5f, 3f, paint)
                fill(0xFFC9CED6.toInt()); rect.set(56f, 47f, 94f, 84f); canvas.drawRoundRect(rect, 8f, 8f, paint)
                fill(0xFF9AA3B2.toInt()); canvas.drawCircle(56f, 66f, 3.5f, paint); canvas.drawCircle(94f, 66f, 3.5f, paint)
                fill(0xFF3DDCFF.toInt()); rect.set(63f, 59f, 72f, 67f); canvas.drawRoundRect(rect, 2f, 2f, paint); rect.set(78f, 59f, 87f, 67f); canvas.drawRoundRect(rect, 2f, 2f, paint)
                fill(ink); rect.set(66f, 74f, 84f, 77f); canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
            }
            "santa" -> {
                fill(0xFFF5D5B8.toInt()); canvas.drawCircle(75f, 65f, 19f, paint)
                fill(0xFFFFFFFF.toInt()); rect.set(58f, 68f, 92f, 90f); canvas.drawArc(rect, 0f, 180f, true, paint)
                fill(0xFFD93A3A.toInt()); path.reset(); path.moveTo(56f, 58f); path.quadTo(75f, 52f, 94f, 58f); path.lineTo(84f, 34f); path.close(); canvas.drawPath(path, paint)
                fill(0xFFFFFFFF.toInt()); rect.set(55f, 55f, 95f, 61f); canvas.drawRoundRect(rect, 3f, 3f, paint); canvas.drawCircle(85f, 33f, 4.5f, paint)
                eyes(canvas)
            }
            "smore" -> {
                fill(0xFFE4B879.toInt()); rect.set(57f, 71f, 93f, 84f); canvas.drawRoundRect(rect, 4f, 4f, paint)
                fill(0xFF5A3A2A.toInt()); rect.set(59f, 66f, 91f, 72f); canvas.drawRect(rect, paint)
                fill(0xFFFFF4E3.toInt()); rect.set(58f, 47f, 92f, 68f); canvas.drawRoundRect(rect, 9f, 9f, paint)
                fill(0xFFF2D6A8.toInt()); rect.set(58f, 47f, 92f, 52f); canvas.drawRoundRect(rect, 3f, 3f, paint)
                eyes(canvas)
            }
            "campfire" -> {
                fill(0xFF8A5A3C.toInt()); canvas.save(); canvas.rotate(20f, 75f, 84f); rect.set(55f, 80f, 95f, 86f); canvas.drawRoundRect(rect, 3f, 3f, paint); canvas.restore()
                canvas.save(); canvas.rotate(-20f, 75f, 84f); rect.set(55f, 80f, 95f, 86f); canvas.drawRoundRect(rect, 3f, 3f, paint); canvas.restore()
                fill(0xFFFF8A3D.toInt()); path.reset(); path.moveTo(75f, 42f); path.cubicTo(92f, 58f, 95f, 70f, 75f, 84f); path.cubicTo(55f, 70f, 58f, 58f, 75f, 42f); path.close(); canvas.drawPath(path, paint)
                fill(0xFFFFD24A.toInt()); path.reset(); path.moveTo(75f, 56f); path.cubicTo(84f, 66f, 85f, 72f, 75f, 82f); path.cubicTo(65f, 72f, 66f, 66f, 75f, 56f); path.close(); canvas.drawPath(path, paint)
                eyes(canvas)
            }
            "bigfoot" -> {
                fill(0xFF5E4232.toInt()); canvas.drawCircle(75f, 65f, 21f, paint); canvas.drawCircle(57f, 54f, 6f, paint); canvas.drawCircle(93f, 54f, 6f, paint)
                fill(0xFFC9A27E.toInt()); rect.set(62f, 56f, 88f, 80f); canvas.drawOval(rect, paint)
                eyes(canvas)
                stroke(ink, 1.4f); canvas.drawLine(71f, 74f, 79f, 74f, paint)
            }
            "owl" -> {
                fill(0xFF8B6B4A.toInt()); canvas.drawCircle(75f, 65f, 19f, paint)
                path.reset(); path.moveTo(58f, 52f); path.lineTo(61f, 42f); path.lineTo(68f, 49f); path.close(); canvas.drawPath(path, paint)
                path.reset(); path.moveTo(92f, 52f); path.lineTo(89f, 42f); path.lineTo(82f, 49f); path.close(); canvas.drawPath(path, paint)
                if (sleeping) eyes(canvas, 0xFFFFF4E3.toInt()) else {
                    fill(0xFFFFFFFF.toInt()); canvas.drawCircle(67f, 62f, 6.5f, paint); canvas.drawCircle(83f, 62f, 6.5f, paint)
                    fill(ink); canvas.drawCircle(67f, 62f, 2.8f, paint); canvas.drawCircle(83f, 62f, 2.8f, paint)
                }
                fill(0xFFF0A93C.toInt()); path.reset(); path.moveTo(71.5f, 68f); path.lineTo(78.5f, 68f); path.lineTo(75f, 75f); path.close(); canvas.drawPath(path, paint)
            }
            else -> { // 곰(기본)
                fill(cream); canvas.drawCircle(75f, 65f, 19f, paint)          // 머리 38
                canvas.drawCircle(60f, 51f, 7f, paint)                        // 귀 14
                canvas.drawCircle(90f, 51f, 7f, paint)
                eyes(canvas)
            }
        }
    }

    private fun fill(color: Int) { paint.style = Paint.Style.FILL; paint.color = withAlpha(color) }
    private fun withAlpha(c: Int): Int = (c and 0x00FFFFFF) or (((c ushr 24) * alphaValue / 255) shl 24)

    override fun setAlpha(alpha: Int) { alphaValue = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object { const val REF = 150f }
}

/** 내 차: DecoStore의 색·캐릭터·소품을 입힌다 */
fun ToyCarDrawable.applyMine(): ToyCarDrawable = apply {
    bodyColor = com.okkikki.kkiri.state.DecoStore.color.value
    character = com.okkikki.kkiri.state.DecoStore.character.value
    items = com.okkikki.kkiri.state.DecoStore.items2.value
    hat = "hat" in items; balloon = "balloon" in items
}

/** 일행 차: R-47로 받은 CarLook을 입힌다(items가 비면 이전 앱 값 hat·balloon). look이 없으면 기본 */
fun ToyCarDrawable.applyLook(look: com.okkikki.kkiri.contract.CarLook?): ToyCarDrawable = apply {
    if (look == null) return@apply
    bodyColor = look.color?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
    character = look.character
    items = look.items
    hat = look.hat; balloon = look.balloon
}
