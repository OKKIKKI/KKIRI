package com.okkikki.kkiri.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import com.okkikki.kkiri.R
import com.okkikki.kkiri.state.DecoStore
import com.okkikki.kkiri.ui.theme.Kk
import kotlin.math.min

/**
 * 앱을 열 때 보이는 로딩 애니메이션 5종(prototype_v4.html LD의 A~E를 그대로 옮김).
 * 240×180 기준 장면 + 문구 + 로고·"끼리". 시간(초)을 [setTime]으로 받아 그때그때 위치·크기·불투명도를 계산한다(자체 타이머 없음).
 * [driving]이면 애니메이션 없이 로고 + "끼리" + "주행 중에는 바로 시작해요"만 그린다.
 */
class SplashView(context: Context, private val variant: Int, private val driving: Boolean) : View(context) {

    private var t = 0f
    fun setTime(sec: Float) { t = sec; invalidate() }

    // ───────── 키프레임 ─────────
    private class Kf(
        val p: Float, val tx: Float = 0f, val ty: Float = 0f, val sx: Float = 1f, val sy: Float = 1f,
        val rot: Float = 0f, val a: Float = 1f,
    )

    private class Anim(val dur: Float, val frames: List<Kf>, val ease: Interpolator, val infinite: Boolean = false)

    private val popEase = PathInterpolator(0.3f, 1.7f, 0.5f, 1f)
    private val decel = DecelerateInterpolator()
    private val accel = AccelerateInterpolator()
    private val inOut = AccelerateDecelerateInterpolator()
    private val lin = LinearInterpolator()

    private val POP = Anim(0.45f, listOf(Kf(0f, sx = 0f, sy = 0f), Kf(1f)), popEase)
    private val HOP = Anim(1f, listOf(
        Kf(0f, tx = -150f, a = 0f), Kf(.1f, tx = -141f, a = 1f), Kf(.3f, tx = -100f, ty = -22f), Kf(.5f, tx = -55f),
        Kf(.7f, tx = -22f, ty = -12f), Kf(.85f, sx = 1.06f, sy = .9f), Kf(1f),
    ), decel)
    private val DROP = Anim(.7f, listOf(
        Kf(0f, ty = -90f, a = 0f), Kf(.5f, sx = 1.1f, sy = .85f), Kf(.65f, ty = -10f, sx = .96f, sy = 1.05f), Kf(.8f), Kf(1f),
    ), decel)
    private val FALL = Anim(.55f, listOf(
        Kf(0f, ty = -170f, a = 0f), Kf(.1f, ty = -153f), Kf(.6f, sx = 1.12f, sy = .86f), Kf(.78f, ty = -6f, sx = .97f, sy = 1.04f), Kf(1f),
    ), accel)
    private val SWAY = Anim(1.4f, listOf(Kf(0f), Kf(.25f, rot = -2f), Kf(.75f, rot = 2f), Kf(1f)), inOut, infinite = true)
    private val TW2 = Anim(1f, listOf(Kf(0f, sx = .4f, sy = .4f, a = 0f), Kf(.5f), Kf(1f, sx = .4f, sy = .4f, a = 0f)), inOut, infinite = true)
    private val WAKE = Anim(1.6f, listOf(
        Kf(0f), Kf(.58f), Kf(.63f, ty = -8f, rot = -5f), Kf(.72f, rot = 5f), Kf(.82f, rot = -3f), Kf(.92f), Kf(1f),
    ), decel)
    private val ZZ = Anim(.95f, listOf(Kf(0f, a = 0f), Kf(.2f), Kf(.8f, tx = 4f, ty = -6f), Kf(1f, tx = 6f, ty = -10f, a = 0f)), decel)
    private val PUFF = Anim(.9f, listOf(Kf(0f, sx = .3f, sy = .3f, a = 0f), Kf(.3f, sx = .65f, sy = .65f, a = .45f), Kf(1f, tx = -30f, ty = -8f, sx = 1.5f, sy = 1.5f, a = 0f)), decel)
    private val FIN = { d: Float -> Anim(d, listOf(Kf(0f, a = 0f), Kf(1f)), lin) }
    private val FLASH = { d: Float -> Anim(d, listOf(Kf(0f, a = 0f), Kf(.15f), Kf(.85f), Kf(1f, a = 0f)), lin) }

    /** 시간 t(초)에서의 값. 시작 전에는 첫 프레임, 끝난 뒤에는 마지막 프레임(both 채움) */
    private fun sample(a: Anim, delay: Float): Kf {
        val lt = t - delay
        val frames = a.frames
        if (lt <= 0f) return frames.first()
        val p = if (a.infinite) (lt % a.dur) / a.dur else min(lt / a.dur, 1f)
        if (p >= 1f) return frames.last()
        for (i in 0 until frames.size - 1) {
            val f0 = frames[i]; val f1 = frames[i + 1]
            if (p >= f0.p && p <= f1.p) {
                val span = f1.p - f0.p
                val e = if (span <= 0f) 1f else a.ease.getInterpolation((p - f0.p) / span)
                fun m(x: Float, y: Float) = x + (y - x) * e
                return Kf(p, m(f0.tx, f1.tx), m(f0.ty, f1.ty), m(f0.sx, f1.sx), m(f0.sy, f1.sy), m(f0.rot, f1.rot), m(f0.a, f1.a))
            }
        }
        return frames.last()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val logo = ContextCompat.getDrawable(context, R.drawable.kk_logo)

    private val fg = Kk.color(context, R.color.kk_fg)
    private val me = Kk.color(context, R.color.kk_me)
    private val accent = Kk.color(context, R.color.kk_toy_accent)
    private val cream = Kk.color(context, R.color.kk_toy_cream)
    private val myColor = DecoStore.color.value

    // ───────── 장면 요소 ─────────
    private fun toyDraw(canvas: Canvas, color: Int, hat: Boolean = false, balloon: Boolean = false, sleeping: Boolean = false, isMe: Boolean = false) {
        val toy = ToyCarDrawable(context, isMe, hat, balloon, color, sleeping)
        // 프로토타입 장난감(폭 60, 바닥 y=30) → 150칸 장난감: 한 변 110, 바퀴 바닥이 y=30에 닿게
        toy.setBounds(-55, (30f - 117f / 150f * 110f).toInt(), 55, (30f - 117f / 150f * 110f).toInt() + 110)
        toy.draw(canvas)
    }

    private fun shadow(canvas: Canvas, x: Float, y: Float, rx: Float) {
        paint.style = Paint.Style.FILL; paint.color = fg; paint.alpha = 26
        rect.set(x - rx, y - 4f, x + rx, y + 4f)
        canvas.drawOval(rect, paint)
    }

    private fun star(canvas: Canvas) {
        path.reset()
        path.moveTo(0f, -8f); path.lineTo(2f, -2f); path.lineTo(8f, 0f); path.lineTo(2f, 2f)
        path.lineTo(0f, 8f); path.lineTo(-2f, 2f); path.lineTo(-8f, 0f); path.lineTo(-2f, -2f); path.close()
        paint.style = Paint.Style.FILL; paint.color = accent; paint.alpha = 255
        canvas.drawPath(path, paint)
    }

    private fun heart(canvas: Canvas) {
        canvas.save()
        canvas.scale(1.5f, 1.5f)
        path.reset()
        path.moveTo(0f, -3f)
        path.cubicTo(-3f, -7f, -9f, -5f, -7f, 0f)
        path.lineTo(0f, 6f)
        path.lineTo(7f, 0f)
        path.cubicTo(9f, -5f, 3f, -7f, 0f, -3f)
        path.close()
        paint.style = Paint.Style.FILL; paint.color = accent; paint.alpha = 255
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    /** ㄲ 차 한 대(로고 원본 좌표 54×49) */
    private fun lcar(canvas: Canvas, color: Int) {
        strokePaint.color = color; strokePaint.strokeWidth = 9f; strokePaint.alpha = 255
        path.reset()
        path.moveTo(0f, 0f); path.lineTo(28f, 0f); path.lineTo(40f, 14f); path.lineTo(54f, 14f); path.lineTo(54f, 30f)
        canvas.drawPath(path, strokePaint)
        paint.style = Paint.Style.FILL; paint.color = color; paint.alpha = 255
        canvas.drawCircle(10f, 42f, 7f, paint)
        canvas.drawCircle(44f, 42f, 7f, paint)
    }

    private fun label(canvas: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, alpha: Int = 255) {
        textPaint.typeface = Kk.typeface(context, 400)
        textPaint.textSize = size
        textPaint.color = color
        textPaint.alpha = alpha
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(s, x, y, textPaint)
    }

    /** 요소 하나를 (x,y,s) 자리에 놓고 애니메이션을 입혀 그린다. (ox,oy)는 변형 기준점(요소 아래 가운데) */
    private fun place(
        canvas: Canvas, x: Float, y: Float, s: Float, anim: Anim?, delay: Float,
        ox: Float = 0f, oy: Float = 0f, draw: (Canvas) -> Unit,
    ) {
        val k = if (anim != null) sample(anim, delay) else Kf(0f)
        if (k.a <= 0f) return
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(s, s)
        canvas.translate(ox + k.tx, oy + k.ty)
        if (k.rot != 0f) canvas.rotate(k.rot)
        canvas.scale(k.sx, k.sy)
        canvas.translate(-ox, -oy)
        if (k.a < 1f) canvas.saveLayerAlpha(-300f, -300f, 300f, 300f, (k.a * 255).toInt())
        draw(canvas)
        if (k.a < 1f) canvas.restore()
        canvas.restore()
    }

    private fun toyAt(canvas: Canvas, x: Float, y: Float, s: Float, anim: Anim?, delay: Float, color: Int, hatAt: Float? = null, balloonAt: Float? = null, sleeping: Boolean = false) {
        place(canvas, x, y, s, anim, delay, 0f, 30f) {
            toyDraw(it, color, hat = hatAt != null && t >= hatAt, balloon = balloonAt != null && t >= balloonAt, sleeping = sleeping)
        }
    }

    private fun scene(canvas: Canvas) {
        when (variant) {
            0 -> { // A 모여라 일행
                shadow(canvas, 60f, 140f, 22f); shadow(canvas, 120f, 140f, 22f); shadow(canvas, 180f, 140f, 22f)
                toyAt(canvas, 60f, 122f, .62f, POP, 0f, myColor)
                toyAt(canvas, 120f, 122f, .62f, POP, .35f, Color.parseColor("#3D8BFD"))
                toyAt(canvas, 180f, 122f, .62f, POP, .7f, Color.parseColor("#E8453C"))
                place(canvas, 120f, 50f, 1.2f, POP, 1.15f, 0f, 8f) { star(it) }
            }
            1 -> { // B 통통 ㄲ
                shadow(canvas, 120f, 112f, 80f)
                place(canvas, 52f, 62f, 1f, HOP, 0f, 27f, 49f) { lcar(it, accent) }
                place(canvas, 132f, 62f, 1f, HOP, .25f, 27f, 49f) { lcar(it, me) }
                place(canvas, 119f, 42f, 1f, POP, 1.2f, 0f, 7f) { heart(it) }
            }
            2 -> { // C 꾸미기 입는 중
                shadow(canvas, 120f, 160f, 34f)
                toyAt(canvas, 120f, 128f, 1.05f, DROP, 0f, myColor, hatAt = .7f, balloonAt = 1.1f)
                place(canvas, 60f, 62f, 1f, TW2, 1.3f, 0f, 8f) { star(it) }
                place(canvas, 186f, 92f, .7f, TW2, 1.5f, 0f, 8f) { star(it) }
            }
            3 -> { // D 잠 깨우기
                shadow(canvas, 120f, 160f, 34f)
                toyAt(canvas, 120f, 128f, 1.05f, WAKE, 0f, myColor, sleeping = t < .95f)
                place(canvas, 0f, 0f, 1f, ZZ, 0f) {
                    label(it, "z", 146f, 80f, 14f, Color.parseColor("#C7B2F5"))
                    label(it, "Z", 158f, 64f, 18f, Color.parseColor("#C7B2F5"))
                }
                place(canvas, 82f, 140f, 1f, PUFF, 1.0f) { c -> paint.style = Paint.Style.FILL; paint.color = fg; paint.alpha = 255; c.drawCircle(0f, 0f, 7f, paint) }
                place(canvas, 74f, 132f, 1f, PUFF, 1.15f) { c -> paint.style = Paint.Style.FILL; paint.color = fg; paint.alpha = 255; c.drawCircle(0f, 0f, 9f, paint) }
                place(canvas, 172f, 92f, 1f, POP, 1.0f, 0f, 0f) { label(it, "부릉!", 0f, 0f, 16f, accent) }
            }
            else -> { // E 탑 쌓기
                shadow(canvas, 120f, 162f, 34f)
                val sway = sample(SWAY, 1.5f)
                canvas.save()
                canvas.translate(120f, 162f)
                canvas.rotate(sway.rot)
                canvas.translate(-120f, -162f)
                toyAt(canvas, 120f, 143.5f, .55f, FALL, 0f, Color.parseColor("#3D8BFD"))
                toyAt(canvas, 116f, 115.5f, .55f, FALL, .3f, Color.parseColor("#E8453C"))
                toyAt(canvas, 124f, 87.5f, .55f, FALL, .6f, Color.parseColor("#7BC86C"))
                toyAt(canvas, 120f, 59.5f, .55f, FALL, .9f, myColor, hatAt = 1.35f)
                canvas.restore()
                place(canvas, 160f, 46f, 1f, POP, 1.5f, 0f, 8f) { star(it) }
            }
        }
    }

    /** 장면 아래 문구: (글, 애니메이션, 지연) */
    private fun captions(): List<Triple<String, Anim, Float>> = when (variant) {
        0 -> listOf(Triple("일행 모으는 중…", FIN(.3f), 0f))
        1 -> listOf(Triple("빵빵! 출발 준비", FIN(.3f), .9f))
        2 -> listOf(Triple("모자 쓰는 중…", FLASH(.8f), 0f), Triple("풍선 다는 중…", FLASH(.7f), .8f), Triple("준비 완료!", FIN(.3f), 1.5f))
        3 -> listOf(Triple("쿨쿨…", FLASH(.95f), 0f), Triple("시동 완료!", FIN(.3f), 1f))
        else -> listOf(Triple("차곡차곡 모이는 중…", FIN(.3f), 0f))
    }

    private fun drawLogo(canvas: Canvas, cx: Float, top: Float, width: Float, alpha: Int) {
        val d = logo ?: return
        val ratio = d.intrinsicHeight.toFloat() / d.intrinsicWidth.toFloat()
        d.setBounds((cx - width / 2f).toInt(), top.toInt(), (cx + width / 2f).toInt(), (top + width * ratio).toInt())
        d.alpha = alpha
        d.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawColor(Kk.color(context, R.color.kk_bg))
        if (driving) {
            // 주행 중: 애니메이션 없이 로고 + "끼리" + 안내
            val k = min(w / 300f, h / 260f).coerceIn(1f, 2.6f)
            val logoW = 96f * k
            val ratio = (logo?.intrinsicHeight ?: 1).toFloat() / (logo?.intrinsicWidth ?: 1).toFloat()
            val total = logoW * ratio + 14f * k + 24f * k + 24f * k
            var y = (h - total) / 2f
            drawLogo(canvas, w / 2f, y, logoW, 255)
            y += logoW * ratio + 28f * k
            textPaint.typeface = Kk.typeface(context, 500)
            textPaint.textSize = 20f * k; textPaint.color = fg; textPaint.alpha = 255; textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(context.getString(R.string.splash_name), w / 2f, y, textPaint)
            label(canvas, context.getString(R.string.splash_driving), w / 2f, y + 26f * k, 13f * k, fg, 153)
            return
        }
        // 장면 240×180 + 문구 22 + 로고줄 26 (간격 포함 약 258)
        val k = min(w * 0.6f / 240f, h * 0.55f / 258f)
        val sceneW = 240f * k
        val left = (w - sceneW) / 2f
        val totalH = 258f * k
        val top = (h - totalH) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(k, k)
        scene(canvas)
        // 문구
        captions().forEach { (text, anim, delay) ->
            val a = sample(anim, delay).a
            if (a > 0f) label(canvas, text, 120f, 180f + 8f + 15f, 15f, fg, (a * 255).toInt())
        }
        // 로고 + "끼리" (1.3초에 나타남)
        val ba = FIN(.4f).let { sample(it, 1.3f).a }
        if (ba > 0f) {
            val lw = 26f
            val ratio = (logo?.intrinsicHeight ?: 1).toFloat() / (logo?.intrinsicWidth ?: 1).toFloat()
            val ly = 180f + 8f + 22f + 14f
            val name = context.getString(R.string.splash_name)
            textPaint.typeface = Kk.typeface(context, 500); textPaint.textSize = 16f
            val tw = textPaint.measureText(name)
            val rowW = lw + 8f + tw
            val x0 = 120f - rowW / 2f
            drawLogo(canvas, x0 + lw / 2f, ly, lw, (ba * 255).toInt())
            textPaint.color = fg; textPaint.alpha = (ba * 255).toInt(); textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(name, x0 + lw + 8f, ly + lw * ratio / 2f + 6f, textPaint)
        }
        canvas.restore()
    }
}
