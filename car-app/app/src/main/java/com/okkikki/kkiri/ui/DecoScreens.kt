package com.okkikki.kkiri.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.okkikki.kkiri.R
import com.okkikki.kkiri.state.DecoStore
import com.okkikki.kkiri.ui.theme.Kk

/**
 * 꾸미기 탭(Figma 09 A안 "탭 + 그리드" + 광장 탭, V2): 내 차 미리 보기 + 카테고리 탭(자동차·캐릭터·소품·광장) + 아이템 격자 + [저장].
 * - 자동차(색)·캐릭터·소품은 [저장] 후 기기에 저장되고 R-47(v1.16)로 일행 화면의 내 차에도 보인다.
 * - 광장 배경은 내 화면에만 적용된다(서버로 보내지 않는다).
 * - 소품은 최대 4개, 머리 위(고깔모자·왕관·리본)는 하나만. 가격·구매 표시는 없다.
 */
object DecoScreens {

    private enum class Cat(val label: Int) {
        CAR(R.string.deco_tab_car), CHAR(R.string.deco_tab_char), PROP(R.string.deco_tab_prop), PLAZA(R.string.deco_tab_plaza)
    }

    // 화면이 다시 그려져도(세션 이벤트 등) 고르던 값이 남도록 object에 둔다. 저장하면 비운다
    private var draftColor: Int? = null
    private var draftChar: String? = null
    private var draftCharSet = false
    private var draftItems: List<String>? = null
    private var draftPlaza: String? = null
    private var tab = Cat.CAR

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun dpf(context: Context, v: Int) = v * context.resources.displayMetrics.density
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun build(context: Context): View {
        var color = draftColor ?: DecoStore.color.value
        var character = if (draftCharSet) draftChar else DecoStore.character.value
        var items = draftItems ?: DecoStore.items2.value
        var plaza = draftPlaza ?: DecoStore.plaza.value
        val host = FrameLayout(context).apply { setBackgroundColor(Kk.color(context, R.color.kk_bg)) }

        fun toy(col: Int, ch: String?, its: List<String>) = ToyCarDrawable(context, true).also { t -> t.bodyColor = col; t.character = ch; t.items = its; t.hat = "hat" in its; t.balloon = "balloon" in its }

        fun render() {
            draftColor = color; draftChar = character; draftCharSet = true; draftItems = items; draftPlaza = plaza
            val wide = context.resources.configuration.screenWidthDp >= 1000
            val changed = color != DecoStore.color.value || character != DecoStore.character.value || items != DecoStore.items2.value || plaza != DecoStore.plaza.value

            val preview = previewCard(context, toy(color, character, items), plaza)
            val tabs = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    Cat.values().forEachIndexed { i, c ->
                        val on = c == tab
                        addView(Kk.caption(context, context.getString(c.label), if (on) R.color.kk_brand_text else R.color.kk_fg).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(context, 36), 0, dp(context, 36), 0)
                            background = GradientDrawable().apply {
                                cornerRadius = dpf(context, 48)
                                setColor(Kk.color(context, if (on) R.color.kk_brand_tint else R.color.kk_chip))
                            }
                            isClickable = true
                            setOnClickListener { tab = c; render() }
                        }, LinearLayout.LayoutParams(WRAP, dp(context, 96)).apply { if (i > 0) marginStart = dp(context, 12) })
                    }
                })
            }

            // 카드 목록: (이름, 그림, 고른 상태, 누르면)
            class Card(val name: String, val art: View, val on: Boolean, val click: () -> Unit)
            val cards: List<Card> = when (tab) {
                Cat.CAR -> DecoStore.items.map { it ->
                    Card(it.name, art(context, toy(it.color, character, items)), it.color == color) { color = it.color; render() }
                }
                Cat.CHAR -> DecoStore.characters.map { (id, name) ->
                    Card(name, art(context, toy(color, id, emptyList())), (character ?: "bear") == id) { character = if (id == "bear") null else id; render() }
                }
                Cat.PROP -> DecoStore.props.map { (id, name) ->
                    Card(name, art(context, toy(color, character, listOf(id))), id in items) {
                        items = when {
                            id in items -> items - id
                            else -> {
                                val base = if (id in DecoStore.headProps) items.filter { it !in DecoStore.headProps } else items
                                if (base.size >= 4) base else base + id
                            }
                        }
                        render()
                    }
                }
                Cat.PLAZA -> DecoStore.plazas.map { (id, name) ->
                    Card(name, swatch(context, id), plaza == id) { plaza = id; render() }
                }
            }
            val grid = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                cards.chunked(3).forEachIndexed { r, row ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        row.forEachIndexed { c, card ->
                            addView(itemCard(context, card.name, card.art, card.on, card.click),
                                LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (c > 0) marginStart = dp(context, 16) })
                        }
                        repeat(3 - row.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(context, 16) }) }
                    }, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (r > 0) topMargin = dp(context, 16) })
                }
            }
            val note: View? = when (tab) {
                Cat.PLAZA -> Kk.small(context, context.getString(R.string.deco_plaza_note), R.color.kk_fg).apply { alpha = 0.75f }
                Cat.PROP -> Kk.small(context, context.getString(R.string.deco_prop_note), R.color.kk_fg).apply { alpha = 0.75f }
                else -> null
            }
            val save: View = if (changed) {
                Kk.primaryButton(context, context.getString(R.string.deco_save)) {
                    DecoStore.saveAll(color, character, items, plaza)
                    draftColor = null; draftChar = null; draftCharSet = false; draftItems = null; draftPlaza = null
                    render()
                }
            } else {
                Kk.disabledButton(context, context.getString(R.string.deco_saved))
            }
            val title = Kk.text(context, context.getString(R.string.tab_deco), Kk.TextStyleKk.TITLE_S_BOLD)

            val controls = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(grid, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(context, 24) })
                if (note != null) addView(note, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(context, 16) })
            }
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 48))
                addView(title)
                if (wide) {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(preview, LinearLayout.LayoutParams(0, MATCH, 1f))
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(ScrollView(context).apply { addView(controls) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
                            addView(save, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(context, 16) })
                        }, LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(context, 24) })
                    }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(context, 24) })
                } else {
                    addView(preview, LinearLayout.LayoutParams(MATCH, dp(context, 340)).apply { topMargin = dp(context, 24) })
                    addView(controls, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(context, 24) })
                    addView(save, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(context, 24) })
                }
            }
            host.removeAllViews()
            host.addView(
                if (wide) body else ScrollView(context).apply { isFillViewport = true; addView(body) },
                FrameLayout.LayoutParams(MATCH, MATCH),
            )
        }
        render()
        return host
    }

    /** 미리 보기: 카드 면(광장 배경 포함) + 바닥(도로색) 띠 + 내 차 크게 */
    private fun previewCard(context: Context, toy: ToyCarDrawable, plaza: String): View {
        val radius = dpf(context, 24)
        val cardColor = Kk.color(context, R.color.kk_card)
        return FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = radius }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, radius) }
            }
            addView(object : View(context) {
                override fun onDraw(canvas: Canvas) { PlazaTheme.draw(canvas, plaza, 0f, 0f, width.toFloat(), height.toFloat()) }
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(View(context).apply { setBackgroundColor(Kk.color(context, R.color.kk_road)) },
                FrameLayout.LayoutParams(MATCH, dp(context, 54), Gravity.BOTTOM))
            addView(
                ImageView(context).apply { setImageDrawable(toy) },
                FrameLayout.LayoutParams(dp(context, 280), dp(context, 280), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(context, 30) },
            )
        }
    }

    private fun art(context: Context, toy: ToyCarDrawable): View = ImageView(context).apply { setImageDrawable(toy) }

    /** 광장 견본 썸네일 */
    private fun swatch(context: Context, id: String): View {
        val base = Kk.color(context, R.color.kk_bg)
        return object : View(context) {
            override fun onDraw(canvas: Canvas) { PlazaTheme.swatch(canvas, id, width.toFloat(), height.toFloat(), base) }
        }.apply {
            val r = dpf(context, 12)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, r) }
            }
        }
    }

    /** 아이템 한 칸: 그림 + 이름. 고른 칸은 보라 테두리 */
    private fun itemCard(context: Context, name: String, art: View, selected: Boolean, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(context, 8), dp(context, 12), dp(context, 8), dp(context, 12))
            background = cardFrame(context, selected)
            isClickable = true
            setOnClickListener { onClick() }
            addView(art, LinearLayout.LayoutParams(dp(context, 120), dp(context, 120)))
            addView(Kk.small(context, name, if (selected) R.color.kk_brand_text else R.color.kk_fg).apply { gravity = Gravity.CENTER })
        }

    private fun cardFrame(context: Context, selected: Boolean) = GradientDrawable().apply {
        setColor(Kk.color(context, R.color.kk_card))
        cornerRadius = dpf(context, 20)
        if (selected) setStroke(dp(context, 4), Kk.color(context, R.color.kk_brand))
    }
}
