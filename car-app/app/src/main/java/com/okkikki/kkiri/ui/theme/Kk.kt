package com.okkikki.kkiri.ui.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.okkikki.kkiri.R

/**
 * KKIRI 디자인 토큰을 화면 코드(코드로 그리는 View)에서 쓰기 위한 도우미.
 * 색·크기 값은 res/values(colors, dimens)에만 있고, 여기서는 이름으로 읽기만 한다.
 */
object Kk {

    @ColorInt
    fun color(context: Context, @ColorRes res: Int): Int = ContextCompat.getColor(context, res)

    fun px(context: Context, @DimenRes res: Int): Int = context.resources.getDimensionPixelSize(res)

    /** dimens의 sp 값을 글자 크기로 넣는다 */
    fun textSize(view: TextView, @DimenRes res: Int) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.resources.getDimension(res))
    }

    /** 둥근 사각형 배경. stroke를 주면 테두리를 그린다 */
    fun rounded(
        context: Context,
        @ColorRes fill: Int,
        @DimenRes radius: Int,
        @ColorRes stroke: Int? = null,
    ): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.resources.getDimension(radius)
        setColor(Kk.color(context, fill))
        if (stroke != null) setStroke(Kk.px(context, R.dimen.kk_stroke), Kk.color(context, stroke))
    }

    /** 주요 버튼 배경: 알약형, 브랜드 색. 눌림·비활성 상태 포함 */
    fun primaryButtonBackground(context: Context): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.resources.getDimension(R.dimen.kk_radius_pill)
        setColor(stateColors(Kk.color(context, R.color.kk_btn)))
    }

    /** 보조 버튼 배경: 알약형, 칩 색 + 테두리 */
    fun secondaryButtonBackground(context: Context): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.resources.getDimension(R.dimen.kk_radius_pill)
        setColor(stateColors(Kk.color(context, R.color.kk_chip)))
        setStroke(Kk.px(context, R.dimen.kk_stroke), Kk.color(context, R.color.kk_line))
    }

    /** 켜진 토글(음소거 중) 배경: 알약형, 브랜드 틴트 + 브랜드 테두리 */
    fun toggledButtonBackground(context: Context): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.resources.getDimension(R.dimen.kk_radius_pill)
        setColor(stateColors(Kk.color(context, R.color.kk_brand_tint)))
        setStroke(Kk.px(context, R.dimen.kk_stroke), Kk.color(context, R.color.kk_brand))
    }

    /** 버튼 글자색: 비활성일 때 흐리게 */
    fun textStates(@ColorInt normal: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(withAlpha(normal, DISABLED_ALPHA), normal),
    )

    private fun stateColors(@ColorInt normal: Int): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(),
        ),
        intArrayOf(
            withAlpha(normal, DISABLED_ALPHA),
            ColorUtils.blendARGB(normal, Color.BLACK, PRESSED_DARKEN),
            normal,
        ),
    )

    /** 원래 알파에 비율을 곱한다(반투명 토큰도 그대로 흐려지게) */
    @ColorInt
    private fun withAlpha(@ColorInt c: Int, ratio: Float): Int =
        ColorUtils.setAlphaComponent(c, (Color.alpha(c) * ratio).toInt())

    // ───────── V2-S5: Figma 확정본 글자 스타일·부품 ─────────

    /** KKIRI/v2 글자 스타일. 크기·행간은 dimens(kk_t_*), 굵기는 Asta Sans 가변 축(wght) 값 */
    enum class TextStyleKk(@DimenRes val size: Int, @DimenRes val lineHeight: Int, val weight: Int) {
        TITLE_L(R.dimen.kk_t_title_l, R.dimen.kk_t_title_l_lh, 500),
        TITLE_S(R.dimen.kk_t_title_s, R.dimen.kk_t_title_s_lh, 500),
        TITLE_S_BOLD(R.dimen.kk_t_title_s, R.dimen.kk_t_title_s_lh, 700),
        BODY(R.dimen.kk_t_body, R.dimen.kk_t_body_lh, 400),
        CAPTION(R.dimen.kk_t_caption, R.dimen.kk_t_caption_lh, 400),
        SMALL(R.dimen.kk_t_small, R.dimen.kk_t_small_lh, 400),
        CODE(R.dimen.kk_t_code, R.dimen.kk_t_code_lh, 500),
        CODE_BOLD(R.dimen.kk_t_code, R.dimen.kk_t_code_lh, 700),
    }

    private val typefaces = HashMap<Int, Typeface>()

    /** Asta Sans(assets/fonts, SIL OFL). 못 읽으면 시스템 글꼴 */
    @Synchronized
    fun typeface(context: Context, weight: Int): Typeface = typefaces.getOrPut(weight) {
        runCatching {
            Typeface.Builder(context.applicationContext.assets, "fonts/AstaSans.ttf")
                .setFontVariationSettings("'wght' $weight").build()
        }.getOrNull() ?: Typeface.DEFAULT
    }

    /** 글자 스타일을 TextView에 적용한다(크기·행간·굵기) */
    fun applyStyle(view: TextView, style: TextStyleKk, @ColorRes color: Int = R.color.kk_fg): TextView = view.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(style.size))
        val lh = resources.getDimension(style.lineHeight)
        val sz = resources.getDimension(style.size)
        setLineSpacing(lh - sz, 1f)
        typeface = Kk.typeface(context, style.weight)
        setTextColor(Kk.color(context, color))
        includeFontPadding = false
    }

    fun text(context: Context, text: CharSequence, style: TextStyleKk, @ColorRes color: Int = R.color.kk_fg): TextView =
        applyStyle(TextView(context), style, color).also { it.text = text }

    fun title(context: Context, text: CharSequence, @ColorRes color: Int = R.color.kk_fg) = text(context, text, TextStyleKk.TITLE_S, color)
    fun body(context: Context, text: CharSequence, @ColorRes color: Int = R.color.kk_fg) = text(context, text, TextStyleKk.BODY, color)
    fun caption(context: Context, text: CharSequence, @ColorRes color: Int = R.color.kk_fg) = text(context, text, TextStyleKk.CAPTION, color)
    fun small(context: Context, text: CharSequence, @ColorRes color: Int = R.color.kk_fg_muted) = text(context, text, TextStyleKk.SMALL, color)

    private fun fill(context: Context, @ColorRes fillRes: Int, @DimenRes radius: Int, @ColorRes stroke: Int? = null): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.resources.getDimension(radius)
            setColor(stateColors(Kk.color(context, fillRes)))
            if (stroke != null) setStroke(Kk.px(context, R.dimen.kk_stroke), Kk.color(context, stroke))
        }

    private fun button(context: Context, label: CharSequence, bg: Drawable, @ColorRes ink: Int, onClick: () -> Unit): TextView =
        applyStyle(TextView(context), TextStyleKk.BODY, ink).apply {
            text = label
            gravity = Gravity.CENTER
            minHeight = Kk.px(context, R.dimen.kk_h_btn)
            val h = Kk.px(context, R.dimen.kk_btn_pad_h)
            setPadding(h, 0, h, 0)
            background = bg
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 주요 버튼: 높이 104, r=32, 면 kk_btn, 글자 kk_btn_ink */
    fun primaryButton(context: Context, label: CharSequence, onClick: () -> Unit): TextView =
        button(context, label, fill(context, R.color.kk_btn, R.dimen.kk_r_btn), R.color.kk_btn_ink, onClick)

    /** 보조(ghost) 버튼: 면 kk_chip + 테두리 kk_line */
    fun ghostButton(context: Context, label: CharSequence, onClick: () -> Unit): TextView =
        button(context, label, fill(context, R.color.kk_chip, R.dimen.kk_r_btn, R.color.kk_line), R.color.kk_fg, onClick)

    /** 비활성 버튼(눌리지 않음) */
    fun disabledButton(context: Context, label: CharSequence): TextView =
        primaryButton(context, label) {}.apply { isEnabled = false }

    /** 카드 배경: 면 kk_card, r=32 */
    fun cardBackground(context: Context): Drawable = fill(context, R.color.kk_card, R.dimen.kk_r_card)

    /** 칩 배경: 면 kk_chip, r=12 */
    fun chipBackground(context: Context): Drawable = fill(context, R.color.kk_chip, R.dimen.kk_r_chip)

    private const val DISABLED_ALPHA = 0.4f
    private const val PRESSED_DARKEN = 0.14f
}
