package com.okkikki.kkiri.ui

import android.content.Context
import android.graphics.PorterDuff
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.okkikki.kkiri.R
import com.okkikki.kkiri.ui.theme.Kk

/** 왼쪽 레일의 탭(Figma 확정본, V2-S6). 위쪽 5개 + 아래쪽 2개 */
enum class Tab(@DrawableRes val icon: Int, @StringRes val label: Int, val bottom: Boolean = false) {
    SPACE(R.drawable.kk_ic_space, R.string.tab_space),
    GROUP(R.drawable.kk_ic_group, R.string.tab_group),
    MESSAGES(R.drawable.kk_ic_messages, R.string.tab_messages),
    MAP(R.drawable.kk_ic_map, R.string.tab_map),
    DECO(R.drawable.kk_ic_deco, R.string.tab_deco),
    SETTINGS(R.drawable.kk_ic_settings, R.string.tab_settings, bottom = true),
    HELP(R.drawable.kk_ic_help, R.string.tab_help, bottom = true),
}

/**
 * 레일. 폭 120dp, 면 kk_rail, 위아래 여백 24dp. 위에서 로고(80dp) → 탭 5개, 맨 아래 설정·도움말.
 * 탭 96×104dp, 모서리 28dp, 간격 16dp, 가운데 48dp 아이콘. 상태: 선택(kk_brand_tint 면) / 기본 / 꺼짐(아이콘 65%, 눌러도 반응 없음).
 */
object Rail {
    private const val OFF_ALPHA = 0.65f

    /**
     * @param enabled 켜진 탭 집합(선택된 탭 포함). 여기에 없으면 꺼짐.
     */
    fun build(context: Context, selected: Tab?, enabled: Set<Tab>, onTab: (Tab) -> Unit): View {
        val rail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Kk.color(context, R.color.kk_rail))
            val v = Kk.px(context, R.dimen.kk_margin)
            setPadding(Kk.px(context, R.dimen.kk_rail_side), v, Kk.px(context, R.dimen.kk_rail_side), v)
        }
        val gap = Kk.px(context, R.dimen.kk_space_s)
        rail.addView(logo(context))
        Tab.entries.filter { !it.bottom }.forEach { rail.addView(tab(context, it, selected, it in enabled, onTab), tabParams(context, gap)) }
        rail.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) // 위·아래 묶음 사이 빈칸
        Tab.entries.filter { it.bottom }.forEachIndexed { i, t ->
            rail.addView(tab(context, t, selected, t in enabled, onTab), tabParams(context, if (i == 0) 0 else gap))
        }
        return rail.also { it.layoutParams = LinearLayout.LayoutParams(Kk.px(context, R.dimen.kk_rail_width), LinearLayout.LayoutParams.MATCH_PARENT) }
    }

    private fun logo(context: Context): View = FrameLayout(context).apply {
        val s = Kk.px(context, R.dimen.kk_rail_logo_box)
        layoutParams = LinearLayout.LayoutParams(s, s).apply { leftMargin = Kk.px(context, R.dimen.kk_rail_logo_side) }
        addView(
            ImageView(context).apply { setImageResource(R.drawable.kk_logo) },
            FrameLayout.LayoutParams(s, Kk.px(context, R.dimen.kk_logo_height), Gravity.CENTER),
        )
    }

    private fun tabParams(context: Context, topMargin: Int) =
        LinearLayout.LayoutParams(Kk.px(context, R.dimen.kk_tab_w), Kk.px(context, R.dimen.kk_tab_h)).apply { this.topMargin = topMargin }

    private fun tab(context: Context, tab: Tab, selected: Tab?, on: Boolean, onTab: (Tab) -> Unit): View {
        val isSel = on && tab == selected
        return FrameLayout(context).apply {
            contentDescription = context.getString(tab.label) // PR-06: 아이콘에는 글자 라벨(접근성 이름)
            if (isSel) background = Kk.rounded(context, R.color.kk_brand_tint, R.dimen.kk_r_tab)
            addView(
                ImageView(context).apply {
                    setImageResource(tab.icon)
                    setColorFilter(Kk.color(context, if (isSel) R.color.kk_brand_text else R.color.kk_fg), PorterDuff.Mode.SRC_IN)
                    alpha = if (on) 1f else OFF_ALPHA
                },
                FrameLayout.LayoutParams(Kk.px(context, R.dimen.kk_tab_icon), Kk.px(context, R.dimen.kk_tab_icon), Gravity.CENTER),
            )
            if (on) {
                isClickable = true
                setOnClickListener { onTab(tab) }
            }
        }
    }
}
