package com.okkikki.kkiri.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import com.okkikki.kkiri.R
import com.okkikki.kkiri.contract.Car
import com.okkikki.kkiri.contract.CarStatus
import com.okkikki.kkiri.contract.Place
import com.okkikki.kkiri.contract.Session
import java.time.Instant
import com.okkikki.kkiri.state.DecoStore
import com.okkikki.kkiri.ui.theme.Kk

/** 일행 탭 화면들(Figma 확정본, V2-S7). 이 파일은 CAR-01 시작부터 채운다. */
object GroupScreens {
    private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    /**
     * CAR-01 시작: 헤더 "일행" + 카드 2개([세션 만들기] 누르면 바로 시작, [세션 참여] → CAR-03).
     * @param busy true면 만들기 카드를 "만드는 중" 모양으로(보라 테두리 + 진행 표시)
     * @param error 카드 위 안내 줄 문구(없으면 null)
     */
    fun start(
        context: Context,
        destination: Place?,
        driverName: String,
        busy: Boolean,
        error: String?,
        act: (UiAction) -> Unit,
    ): View {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val destText = if (destination != null) context.getString(R.string.car01_destination, destination.name)
        else context.getString(R.string.car01_destination_none)
        val createDesc = if (busy) context.getString(R.string.start_create_loading)
        else context.getString(R.string.start_create_desc, destText)
        val defaultName = context.getString(R.string.car01_session_name_default, driverName)

        val create = startCard(
            context, landscape, busy,
            icon = if (busy) loaderBox(context) else logoPlusBox(context),
            title = context.getString(R.string.start_create_title),
            desc = createDesc,
        ).apply { setOnClickListener { act(UiAction.CreateSession(defaultName)) } }
        val join = startCard(
            context, landscape, false,
            icon = joinIconBox(context),
            title = context.getString(R.string.start_join_title),
            desc = context.getString(R.string.start_join_desc),
        ).apply { setOnClickListener { act(UiAction.Go(Screen.CAR_03)) } }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val m = Kk.px(context, R.dimen.kk_margin)
            setPadding(m, m, m, m)
            addView(Kk.text(context, context.getString(R.string.start_header), Kk.TextStyleKk.TITLE_S_BOLD))
            if (error != null) addView(banner(context, error), lpTop(context, R.dimen.kk_space_l))
            if (landscape) {
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        addView(create, LinearLayout.LayoutParams(Kk.px(context, R.dimen.kk_start_card_sq), Kk.px(context, R.dimen.kk_start_card_sq)))
                        addView(join, LinearLayout.LayoutParams(Kk.px(context, R.dimen.kk_start_card_sq), Kk.px(context, R.dimen.kk_start_card_sq)).apply {
                            marginStart = Kk.px(context, R.dimen.kk_gap_card_l)
                        })
                    },
                    LinearLayout.LayoutParams(MATCH, 0, 1f),
                )
            } else {
                addView(create, LinearLayout.LayoutParams(MATCH, Kk.px(context, R.dimen.kk_start_card_h)).apply { topMargin = Kk.px(context, R.dimen.kk_space_l) })
                addView(join, LinearLayout.LayoutParams(MATCH, Kk.px(context, R.dimen.kk_start_card_h)).apply { topMargin = Kk.px(context, R.dimen.kk_space_l) })
            }
        }
        return ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(Kk.color(context, R.color.kk_bg))
            addView(column, ViewGroup.LayoutParams(MATCH, MATCH))
        }
    }

    private fun lpTop(context: Context, @DimenRes top: Int) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Kk.px(context, top) }

    // ───────── CAR-03 합류 ─────────
    private const val CODE_LEN = 6

    /**
     * CAR-03 합류: [‹ 취소] + 제목 + 6칸 코드 + 자체 숫자 키패드 + [내 경로 유지]/[일행 따라가기].
     * 시스템 키보드는 쓰지 않는다. 6자리 전에는 두 버튼 모두 비활성 모양이고 눌러도 반응이 없다.
     * - [일행 따라가기] = 기존 "목적지로 설정하고 합류"(applyDestination=true), [내 경로 유지] = false
     * - 마이크 키는 음성 입력 API가 없어 빈 키로 둔다(Q2 기본값)
     */
    fun join(context: Context, drafts: ScreenDrafts, error: String?, act: (UiAction) -> Unit): View {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val boxes = (0 until CODE_LEN).map { codeBox(context, landscape) }
        val keep = Kk.text(context, context.getString(R.string.join_keep), Kk.TextStyleKk.BODY).apply { gravity = Gravity.CENTER }
        val follow = Kk.text(context, context.getString(R.string.join_follow), Kk.TextStyleKk.BODY).apply { gravity = Gravity.CENTER }
        val errorRow = if (error != null) errorRow(context, error) else null

        fun refresh() {
            val code = drafts.joinCode
            boxes.forEachIndexed { i, b ->
                b.text = code.getOrNull(i)?.toString().orEmpty()
                val current = code.length < CODE_LEN && i == code.length
                b.background = shape(
                    context, R.color.kk_chip, R.dimen.kk_radius_field,
                    if (current) R.color.kk_brand else R.color.kk_line,
                    if (current || landscape) R.dimen.kk_stroke_icon else R.dimen.kk_stroke_card,
                )
            }
            val ok = code.length == CODE_LEN
            styleJoinButton(context, keep, ok, primary = false)
            styleJoinButton(context, follow, ok, primary = true)
            keep.isClickable = ok
            follow.isClickable = ok
        }
        keep.setOnClickListener { if (drafts.joinCode.length == CODE_LEN) act(UiAction.Join(drafts.joinCode, applyDestination = false)) }
        follow.setOnClickListener { if (drafts.joinCode.length == CODE_LEN) act(UiAction.Join(drafts.joinCode, applyDestination = true)) }

        fun press(d: String?) { // d == null → 지우기
            errorRow?.visibility = View.GONE
            drafts.joinCode = if (d == null) drafts.joinCode.dropLast(1)
            else if (drafts.joinCode.length < CODE_LEN) drafts.joinCode + d else drafts.joinCode
            refresh()
        }

        val back = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = context.getString(R.string.car03_cancel)
            val end = Kk.px(context, R.dimen.kk_space_s)
            setPadding(0, 0, end, 0)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.kk_ic_back)
                setColorFilter(Kk.color(context, R.color.kk_fg), PorterDuff.Mode.SRC_IN)
            }, LinearLayout.LayoutParams(Kk.px(context, R.dimen.kk_back_icon), Kk.px(context, R.dimen.kk_back_icon)))
            addView(Kk.body(context, context.getString(R.string.car03_cancel)), LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = Kk.px(context, R.dimen.kk_gap_text)
            })
            setOnClickListener { act(UiAction.Go(Screen.CAR_01)) }
        }
        val titleBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(Kk.text(context, context.getString(R.string.join_title), Kk.TextStyleKk.TITLE_S_BOLD))
            addView(
                Kk.text(context, context.getString(R.string.join_desc), Kk.TextStyleKk.CAPTION).apply { alpha = 0.75f },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Kk.px(context, R.dimen.kk_gap_text) },
            )
        }
        val boxRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            boxes.forEachIndexed { i, b ->
                addView(b, LinearLayout.LayoutParams(0, Kk.px(context, if (landscape) R.dimen.kk_code_h_l else R.dimen.kk_code_h_p), 1f).apply {
                    if (i > 0) marginStart = Kk.px(context, R.dimen.kk_gap_key)
                })
            }
        }
        val keypad = keypad(context, landscape, ::press)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val gap = Kk.px(context, if (landscape) R.dimen.kk_gap_btn_l else R.dimen.kk_space_l)
            val h = Kk.px(context, R.dimen.kk_h_btn)
            addView(keep, LinearLayout.LayoutParams(0, h, 1f))
            addView(follow, LinearLayout.LayoutParams(0, h, 1f).apply { marginStart = gap })
        }
        refresh()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val m = Kk.px(context, R.dimen.kk_margin)
            setPadding(m, m, m, if (landscape) Kk.px(context, R.dimen.kk_pad_join_bottom) else Kk.px(context, R.dimen.kk_pad_join_bottom))
            addView(back, LinearLayout.LayoutParams(WRAP, Kk.px(context, R.dimen.kk_back_touch)))
            if (landscape) {
                val left = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(titleBlock)
                    addView(boxRow, lpTop(context, R.dimen.kk_space_l))
                    if (errorRow != null) addView(errorRow, lpTop(context, R.dimen.kk_space_s))
                    addView(buttons, lpTop(context, R.dimen.kk_space_l))
                }
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        addView(left, LinearLayout.LayoutParams(Kk.px(context, R.dimen.kk_join_left_l), WRAP))
                        addView(keypad, LinearLayout.LayoutParams(Kk.px(context, R.dimen.kk_join_pad_l), WRAP).apply {
                            marginStart = Kk.px(context, R.dimen.kk_join_col_gap_l)
                        })
                    },
                    LinearLayout.LayoutParams(MATCH, 0, 1f),
                )
            } else {
                addView(titleBlock, lpTop(context, R.dimen.kk_space_l))
                addView(boxRow, lpTop(context, R.dimen.kk_space_l))
                if (errorRow != null) addView(errorRow, lpTop(context, R.dimen.kk_space_l))
                addView(keypad, lpTop(context, R.dimen.kk_space_l))
                addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f)) // 키패드와 버튼 사이 빈칸
                addView(buttons, lpTop(context, R.dimen.kk_space_l))
            }
        }
        return ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(Kk.color(context, R.color.kk_bg))
            addView(column, ViewGroup.LayoutParams(MATCH, MATCH))
        }
    }

    private fun codeBox(context: Context, landscape: Boolean): TextView =
        Kk.text(context, "", if (landscape) Kk.TextStyleKk.CODE_BOLD else Kk.TextStyleKk.TITLE_S_BOLD).apply { gravity = Gravity.CENTER }

    /** 6자리 전: 칩 면 + 흐린 글자(눌러도 반응 없음). 완료: 보조=테두리, 주=btn 면 */
    private fun styleJoinButton(context: Context, v: TextView, enabled: Boolean, primary: Boolean) {
        v.background = when {
            !enabled -> shape(context, R.color.kk_chip, R.dimen.kk_r_btn, null, R.dimen.kk_stroke)
            primary -> shape(context, R.color.kk_btn, R.dimen.kk_r_btn, null, R.dimen.kk_stroke)
            else -> shape(context, R.color.kk_chip, R.dimen.kk_r_btn, R.color.kk_line, R.dimen.kk_stroke_card)
        }
        v.setTextColor(Kk.color(context, if (enabled && primary) R.color.kk_btn_ink else R.color.kk_fg))
        v.alpha = if (enabled) 1f else 0.4f
    }

    private fun errorRow(context: Context, text: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.kk_ic_alert)
                setColorFilter(Kk.color(context, R.color.kk_fg), PorterDuff.Mode.SRC_IN)
            },
            LinearLayout.LayoutParams(Kk.px(context, R.dimen.kk_error_icon), Kk.px(context, R.dimen.kk_error_icon)),
        )
        addView(Kk.text(context, text, Kk.TextStyleKk.CAPTION), LinearLayout.LayoutParams(WRAP, WRAP).apply {
            marginStart = Kk.px(context, R.dimen.kk_gap_key)
        })
    }

    /** 숫자 키패드 4줄: 1-2-3 / 4-5-6 / 7-8-9 / [빈칸] 0 ⌫ */
    private fun keypad(context: Context, landscape: Boolean, onKey: (String?) -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val h = Kk.px(context, if (landscape) R.dimen.kk_key_h_l else R.dimen.kk_key_h_p)
        val gap = Kk.px(context, R.dimen.kk_gap_key)
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫")).forEachIndexed { r, row ->
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEachIndexed { c, k ->
                        addView(key(context, k, onKey), LinearLayout.LayoutParams(0, h, 1f).apply { if (c > 0) marginStart = gap })
                    }
                },
                LinearLayout.LayoutParams(MATCH, h).apply { if (r > 0) topMargin = gap },
            )
        }
    }

    private fun key(context: Context, k: String, onKey: (String?) -> Unit): View {
        if (k.isEmpty()) return View(context) // 마이크 자리: 빈 키(면 없음, 반응 없음)
        val bg = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(context, R.color.kk_chip, R.dimen.kk_r_key, null, R.dimen.kk_stroke))
            addState(intArrayOf(), shape(context, R.color.kk_card, R.dimen.kk_r_key, null, R.dimen.kk_stroke))
        }
        return FrameLayout(context).apply {
            background = bg
            isClickable = true
            if (k == "⌫") {
                contentDescription = context.getString(R.string.join_backspace)
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.kk_ic_backspace)
                        setColorFilter(Kk.color(context, R.color.kk_fg), PorterDuff.Mode.SRC_IN)
                    },
                    FrameLayout.LayoutParams(Kk.px(context, R.dimen.kk_key_icon), Kk.px(context, R.dimen.kk_key_icon), Gravity.CENTER),
                )
                setOnClickListener { onKey(null) }
            } else {
                addView(Kk.text(context, k, Kk.TextStyleKk.TITLE_S_BOLD), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                setOnClickListener { onKey(k) }
            }
        }
    }

    // ───────── CAR-02 세션 대기 · 일행 출발 후 ─────────

    private fun isExpired(expiresAt: String): Boolean =
        runCatching { Instant.parse(expiresAt).isBefore(Instant.now()) }.getOrDefault(false)

    private fun carName(c: Car) = c.displayName

    private fun scroll(context: Context, column: View): View = ScrollView(context).apply {
        isFillViewport = true
        setBackgroundColor(Kk.color(context, R.color.kk_bg))
        addView(column, ViewGroup.LayoutParams(MATCH, MATCH))
    }

    /** QR 카드: 흰 둥근 상자 안 QR(D-25) + "접속코드" + 6자리. 만료면 QR 대신 문구만 */
    private fun qrCard(context: Context, s: Session): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER // QR+접속코드를 카드 안에서 세로·가로 가운데로
        background = shape(context, R.color.kk_card, R.dimen.kk_r_card, null, R.dimen.kk_stroke)
        val v = Kk.px(context, R.dimen.kk_pad_card_p)
        setPadding(v, v, v, v)
        if (isExpired(s.qr.expiresAt)) {
            addView(Kk.text(context, context.getString(R.string.car02_qr_expired), Kk.TextStyleKk.BODY))
        } else {
            val side = Kk.px(context, R.dimen.kk_qr_size)
            val bmp = QrView.bitmap(s.qr.url, side)
            addView(
                FrameLayout(context).apply {
                    // 둥근 흰 배경 안쪽에 여백을 두고 QR을 올려 모서리가 둥글게 보이게 한다
                    background = shape(context, R.color.kk_qr_bg, R.dimen.kk_r_card, null, R.dimen.kk_stroke)
                    val pad = side / 32
                    setPadding(pad, pad, pad, pad)
                    if (bmp != null) addView(ImageView(context).apply {
                        setImageBitmap(bmp); contentDescription = s.qr.url
                    }, FrameLayout.LayoutParams(side - pad * 2, side - pad * 2))
                },
                LinearLayout.LayoutParams(side, side),
            )
        }
        addView(Kk.small(context, context.getString(R.string.wait_code_label), R.color.kk_fg).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Kk.px(context, R.dimen.kk_space_s) })
        addView(Kk.text(context, s.joinCode, Kk.TextStyleKk.CODE_BOLD).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /** 아직 만들지 않은 기능(제목 편집·차량 목록 등)을 눌렀을 때: "준비 중" 안내 상자 */
    private fun soon(context: Context) = ScreenViews.comingSoon(context)

    /** 연필 아이콘 버튼(터치 96dp). 제목 편집 API가 아직 없어 눌러도 "준비 중" 상자만 뜬다 */
    private fun pencil(context: Context, sizeRes: Int = R.dimen.kk_touch): View = FrameLayout(context).apply {
        isClickable = true
        contentDescription = context.getString(R.string.edit_pencil)
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.kk_ic_pencil)
                setColorFilter(Kk.color(context, R.color.kk_fg), PorterDuff.Mode.SRC_IN)
                alpha = 0.6f
            },
            FrameLayout.LayoutParams(Kk.px(context, R.dimen.kk_pencil_icon), Kk.px(context, R.dimen.kk_pencil_icon), Gravity.CENTER),
        )
        setOnClickListener { soon(context) }
        layoutParams = LinearLayout.LayoutParams(Kk.px(context, sizeRes), Kk.px(context, sizeRes))
    }

    /** 세션 제목 줄: 이름 + 연필 + (호스트만) [세션 종료] */
    private fun titleRow(context: Context, s: Session, myCarId: String?, act: (UiAction) -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Kk.text(context, s.name, Kk.TextStyleKk.TITLE_S_BOLD))
        if (s.hostCarId == myCarId) addView(pencil(context))
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        if (s.hostCarId == myCarId) {
            addView(
                Kk.small(context, context.getString(R.string.wait_end), R.color.kk_brand_text).apply {
                    gravity = Gravity.CENTER
                    minHeight = Kk.px(context, R.dimen.kk_touch)
                    setPadding(Kk.px(context, R.dimen.kk_space_s), 0, 0, 0)
                    isClickable = true
                    setOnClickListener { ScreenViews.confirmEndSession(context) { act(UiAction.EndSession) } }
                },
            )
        }
    }

    /**
     * CAR-02 세션 대기.
     * - 세로 창: QR·접속코드 카드 + 세션 요약 카드(장난감 차 줄) + 아래 [차량 목록][출발]
     * - 가로 창: 제목 줄 + 호스트 줄, 왼쪽 QR 카드 / 오른쪽 차량 모임(2줄·도로·탑승자 풍선), 아래 [출발]
     */
    fun waiting(context: Context, s: Session, myCarId: String?, act: (UiAction) -> Unit): View {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val qr = qrCard(context, s)
        val depart = Kk.primaryButton(context, context.getString(R.string.car02_depart)) { act(UiAction.Go(Screen.CAR_05)) }
        val m = Kk.px(context, R.dimen.kk_margin)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(m, m, m, Kk.px(context, R.dimen.kk_pad_join_bottom))
            addView(Kk.text(context, context.getString(R.string.wait_title), Kk.TextStyleKk.TITLE_S_BOLD))
            if (landscape) {
                addView(titleRow(context, s, myCarId, act), lpTop(context, R.dimen.kk_space_s))
                addView(hostRow(context, s), lpTop(context, R.dimen.kk_gap_text))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(qr, LinearLayout.LayoutParams(Kk.px(context, R.dimen.kk_wait_card_l), MATCH))
                        addView(carGrid(context, s, myCarId), LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                            marginStart = Kk.px(context, R.dimen.kk_gap_card_l)
                        })
                    },
                    LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = Kk.px(context, R.dimen.kk_space_l) },
                )
                addView(depart, LinearLayout.LayoutParams(MATCH, Kk.px(context, R.dimen.kk_h_btn)).apply { topMargin = Kk.px(context, R.dimen.kk_space_l) })
            } else {
                addView(qr, lpTop(context, R.dimen.kk_space_s))
                addView(sessionCard(context, s, myCarId, act, withCars = true), lpTop(context, R.dimen.kk_space_s))
                addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
                val list = Kk.ghostButton(context, context.getString(R.string.wait_cars)) { act(UiAction.OpenMessages) }
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val h = Kk.px(context, R.dimen.kk_h_btn)
                        addView(list, LinearLayout.LayoutParams(0, h, 1f))
                        addView(depart, LinearLayout.LayoutParams(0, h, 1f).apply { marginStart = Kk.px(context, R.dimen.kk_space_l) })
                    },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Kk.px(context, R.dimen.kk_space_l) },
                )
            }
        }
        return scroll(context, column)
    }

    /** 가로 창 호스트 줄: 아바타 + 이름 + [호스트] 태그 + 오른쪽 "4대 중 3대 준비 완료" */
    private fun hostRow(context: Context, s: Session): View {
        val host = s.cars.firstOrNull { it.carId == s.hostCarId }
        val name = host?.driverName.orEmpty()
        val ready = s.cars.count { it.status == CarStatus.CONNECTED }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val a = Kk.px(context, R.dimen.kk_avatar_host)
            addView(Riders.circle(context, name, a), LinearLayout.LayoutParams(a, a))
            addView(Kk.caption(context, name), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = Kk.px(context, R.dimen.kk_gap_key) })
            addView(
                Kk.small(context, context.getString(R.string.wait_host_tag), R.color.kk_brand_text).apply {
                    background = shape(context, R.color.kk_brand_tint, R.dimen.kk_r_chip, null, R.dimen.kk_stroke)
                    val ph = Kk.px(context, R.dimen.kk_chip_pad_h)
                    setPadding(ph, 0, ph, 0)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(WRAP, Kk.px(context, R.dimen.kk_chip_h)).apply { marginStart = Kk.px(context, R.dimen.kk_gap_key) },
            )
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(Kk.small(context, context.getString(R.string.wait_ready_l, ready, s.cars.size), R.color.kk_fg).apply { alpha = 0.75f })
        }
    }

    /** 가로 창 차량 모임: 2열 격자. 칸마다 탑승자 풍선 + 장난감 차 + 도로 + 이름. 연결 안 된 차는 그림만 50% */
    private fun carGrid(context: Context, s: Session, myCarId: String?): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        s.cars.chunked(2).forEachIndexed { r, pair ->
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    pair.forEach { c -> addView(carCell(context, s, c, c.carId == myCarId), LinearLayout.LayoutParams(0, WRAP, 1f)) }
                    if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { if (r > 0) topMargin = Kk.px(context, R.dimen.kk_space_l) },
            )
        }
    }

    private fun carCell(context: Context, s: Session, c: Car, isMe: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val graphicAlpha = if (c.status == CarStatus.CONNECTED) 1f else 0.5f
        addView(
            Riders.CarWithBalloons(context, toyOf(context, c, isMe), Riders.names(s, c)).apply { alpha = graphicAlpha },
            LinearLayout.LayoutParams(MATCH, Kk.px(context, R.dimen.kk_cell_toy) + Kk.px(context, R.dimen.kk_cell_balloon)),
        )
        // 차 바퀴가 도로 띠 위에 얹히도록 도로를 차 그림 아래쪽에 겹친다
        addView(Riders.RoadView(context), LinearLayout.LayoutParams(MATCH, Kk.px(context, R.dimen.kk_road_h)).apply { topMargin = -Kk.px(context, R.dimen.kk_road_h) * 2 / 5 })
        addView(
            Kk.caption(context, if (isMe) context.getString(R.string.car_me_name, carName(c), c.driverName) else carName(c)),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Kk.px(context, R.dimen.kk_gap_key) },
        )
    }

    /**
     * 세션 요약 카드(세로 창): 제목 줄(이름·연필·[세션 종료]) + 요약 한 줄 + (선택) 장난감 차 줄.
     */
    private fun sessionCard(context: Context, s: Session, myCarId: String?, act: (UiAction) -> Unit, withCars: Boolean): LinearLayout {
        val host = s.cars.firstOrNull { it.carId == s.hostCarId }
        val hostName = host?.driverName.orEmpty()
        val ready = s.cars.count { it.status == CarStatus.CONNECTED }
        val summaryText = if (withCars) {
            val waiting = s.cars.filter { it.status != CarStatus.CONNECTED }.joinToString("") {
                context.getString(R.string.wait_summary_pending, carName(it))
            }
            context.getString(R.string.wait_summary, hostName, ready, s.cars.size) + waiting
        } else context.getString(R.string.group_host_only, hostName)

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(context, R.color.kk_card, R.dimen.kk_r_card, null, R.dimen.kk_stroke)
            val h = Kk.px(context, R.dimen.kk_pad_card_p)
            val v = Kk.px(context, R.dimen.kk_pad_session_v)
            setPadding(h, v, h, v)
            addView(titleRow(context, s, myCarId, act), LinearLayout.LayoutParams(MATCH, WRAP))
            addView(Kk.small(context, summaryText, R.color.kk_fg), lpTop(context, R.dimen.kk_gap_text))
            if (withCars) {
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        s.cars.forEach { c -> addView(carChip(context, c, c.carId == myCarId), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = Kk.px(context, R.dimen.kk_gap_key) }) }
                    },
                    lpTop(context, R.dimen.kk_space_s),
                )
            }
        }
    }

    /** CAR-02 장난감 차 한 대(96dp) + 이름. 연결 안 된 차는 그림만 50% */
    private fun carChip(context: Context, c: Car, isMe: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val t = Kk.px(context, R.dimen.kk_toy_wait)
        addView(
            ImageView(context).apply { setImageDrawable(toyOf(context, c, isMe)); alpha = if (c.status == CarStatus.CONNECTED) 1f else 0.5f },
            LinearLayout.LayoutParams(t, t),
        )
    }

    /**
     * "일행 · 출발 후". 오른쪽 아래 [+] 초대 버튼은 스크롤과 상관없이 화면 위에 떠 있다.
     * - 세로 창: 세션 카드 + 차량 목록(차 · 이름·상태 · 탑승자 아바타)
     * - 가로 창: 왼쪽 "내 차" 큰 카드([꾸미기]) / 오른쪽 일행 차 카드 2열(탑승자 풍선)
     */
    fun group(context: Context, s: Session, myCarId: String?, act: (UiAction) -> Unit): View {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val m = Kk.px(context, R.dimen.kk_margin)
        val fab = Kk.px(context, R.dimen.kk_fab)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 아래쪽은 떠 있는 [+] 버튼 높이만큼 비워 둔다(마지막 줄이 가려지지 않게)
            setPadding(m, m, m, fab + Kk.px(context, R.dimen.kk_pad_join_bottom) + m)
            addView(Kk.text(context, context.getString(R.string.start_header), Kk.TextStyleKk.TITLE_S_BOLD))
            addView(sessionCard(context, s, myCarId, act, withCars = false), lpTop(context, R.dimen.kk_space_l))
            if (landscape) {
                // 호스트 카드 아래 남는 높이를 본문이 채우고 가운데에 놓는다(빈 공간이 아래로 몰리지 않게)
                addView(groupWide(context, s, myCarId, act), LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = Kk.px(context, R.dimen.kk_space_l) })
            } else {
                val list = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = shape(context, R.color.kk_card, R.dimen.kk_r_card, null, R.dimen.kk_stroke)
                    val h = Kk.px(context, R.dimen.kk_pad_session_v)
                    setPadding(h, h, h, h)
                    s.cars.forEachIndexed { i, c ->
                        addView(carRow(context, s, c, myCarId), LinearLayout.LayoutParams(MATCH, WRAP).apply { if (i > 0) topMargin = Kk.px(context, R.dimen.kk_space_l) })
                    }
                }
                addView(list, lpTop(context, R.dimen.kk_space_l))
            }
        }
        val plus = FrameLayout(context).apply {
            background = shape(context, R.color.kk_btn, R.dimen.kk_r_btn, R.color.kk_bg, R.dimen.kk_stroke_icon)
            elevation = Kk.px(context, R.dimen.kk_fab_elevation).toFloat()
            isClickable = true
            contentDescription = context.getString(R.string.group_invite)
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.kk_ic_plus)
                    setColorFilter(Kk.color(context, R.color.kk_btn_ink), PorterDuff.Mode.SRC_IN)
                },
                FrameLayout.LayoutParams(Kk.px(context, R.dimen.kk_tab_icon), Kk.px(context, R.dimen.kk_tab_icon), Gravity.CENTER),
            )
            setOnClickListener { act(UiAction.ShowQr) }
        }
        return FrameLayout(context).apply {
            setBackgroundColor(Kk.color(context, R.color.kk_bg))
            addView(ScrollView(context).apply {
                isFillViewport = true
                addView(column, ViewGroup.LayoutParams(MATCH, MATCH))
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(plus, FrameLayout.LayoutParams(fab, fab, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, m, Kk.px(context, R.dimen.kk_pad_join_bottom))
            })
        }
    }

    /** 가로 창 본문: 내 차 카드 + 일행 차 카드 2열 */
    private fun groupWide(context: Context, s: Session, myCarId: String?, act: (UiAction) -> Unit): View {
        val mine = s.cars.firstOrNull { it.carId == myCarId }
        val others = s.cars.filter { it.carId != myCarId }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (mine != null) {
                addView(myCarCard(context, s, mine, act), LinearLayout.LayoutParams(Kk.px(context, R.dimen.kk_wait_card_l), WRAP))
            }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    others.chunked(2).forEachIndexed { r, pair ->
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                pair.forEachIndexed { i, c ->
                                    addView(otherCard(context, s, c, myCarId), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                                        if (i > 0) marginStart = Kk.px(context, R.dimen.kk_space_l)
                                    })
                                }
                                if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = Kk.px(context, R.dimen.kk_space_l) })
                            },
                            LinearLayout.LayoutParams(MATCH, WRAP).apply { if (r > 0) topMargin = Kk.px(context, R.dimen.kk_space_l) },
                        )
                    }
                },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = Kk.px(context, R.dimen.kk_space_l) },
            )
        }
    }

    private fun myCarCard(context: Context, s: Session, c: Car, act: (UiAction) -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = shape(context, R.color.kk_card, R.dimen.kk_r_icon, null, R.dimen.kk_stroke)
        val p = Kk.px(context, R.dimen.kk_pad_card_p)
        setPadding(p, p, p, p)
        addView(Kk.caption(context, context.getString(R.string.group_label_me)).apply { alpha = 0.75f }, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(
            Riders.CarWithBalloons(context, toyOf(context, c, true), Riders.names(s, c)),
            LinearLayout.LayoutParams(MATCH, Kk.px(context, R.dimen.kk_my_toy) + Kk.px(context, R.dimen.kk_my_balloon)),
        )
        addView(
            Kk.body(context, context.getString(R.string.car_me_name, carName(c), c.driverName)),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Kk.px(context, R.dimen.kk_gap_text) },
        )
        addView(
            Kk.primaryButton(context, context.getString(R.string.group_deco)) { act(UiAction.OpenDeco) },
            LinearLayout.LayoutParams(MATCH, Kk.px(context, R.dimen.kk_h_btn)).apply { topMargin = Kk.px(context, R.dimen.kk_space_l) },
        )
    }

    private fun otherCard(context: Context, s: Session, c: Car, myCarId: String?): View {
        val muted = s.mutes.any { it.byCarId == myCarId && it.targetCarId == c.carId }
        val base = statusText(context, c) + (if (muted) context.getString(R.string.group_muted_suffix) else "")
        val graphicAlpha = when (c.status) { CarStatus.DISCONNECTED -> 0.35f; CarStatus.PAUSED -> 0.5f; else -> 1f }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = shape(context, R.color.kk_card, R.dimen.kk_r_icon, null, R.dimen.kk_stroke)
            val p = Kk.px(context, R.dimen.kk_pad_session_v)
            setPadding(p, p, p, p)
            addView(
                Riders.CarWithBalloons(context, toyOf(context, c, false), Riders.names(s, c)).apply { alpha = graphicAlpha },
                LinearLayout.LayoutParams(MATCH, Kk.px(context, R.dimen.kk_card_toy) + Kk.px(context, R.dimen.kk_card_balloon)),
            )
            addView(Kk.body(context, carName(c)))
            addView(Kk.small(context, base, R.color.kk_fg).apply { alpha = 0.75f })
        }
    }

    private fun statusText(context: Context, c: Car): String = when (c.status) {
        CarStatus.CONNECTED -> context.getString(R.string.car_status_connected)
        CarStatus.PAUSED -> context.getString(R.string.car_status_paused)
        CarStatus.DISCONNECTED -> context.getString(R.string.car_status_disconnected)
    }

    /** 세로 창 차량 목록 한 줄: 장난감 차 + 이름(+연필) + 상태 + 오른쪽 탑승자 아바타 */
    private fun carRow(context: Context, s: Session, c: Car, myCarId: String?): View {
        val isMe = c.carId == myCarId
        val muted = s.mutes.any { it.byCarId == myCarId && it.targetCarId == c.carId }
        val riders = Riders.names(s, c)
        val base = statusText(context, c)
        val status = (if (isMe) context.getString(R.string.group_me, base) else base) +
            (if (muted) context.getString(R.string.group_muted_suffix) else "") +
            (if (riders.isNotEmpty()) context.getString(R.string.group_riders, riders.size) else "")
        val graphicAlpha = when (c.status) { CarStatus.DISCONNECTED -> 0.35f; CarStatus.PAUSED -> 0.5f; else -> 1f }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(context).apply { setImageDrawable(toyOf(context, c, isMe)); alpha = graphicAlpha },
                LinearLayout.LayoutParams(Kk.px(context, R.dimen.kk_toy_list), Kk.px(context, R.dimen.kk_toy_list)),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(Kk.body(context, carName(c)))
                            if (isMe) addView(pencil(context, R.dimen.kk_pencil_row))
                        },
                    )
                    addView(Kk.small(context, status, R.color.kk_fg).apply { alpha = 0.75f })
                },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = Kk.px(context, R.dimen.kk_gap_row) },
            )
            if (riders.isNotEmpty()) {
                val a = Kk.px(context, R.dimen.kk_avatar_row)
                addView(
                    Riders.stack(context, riders, a, Kk.px(context, R.dimen.kk_avatar_overlap)).apply { alpha = graphicAlpha },
                    LinearLayout.LayoutParams(WRAP, a),
                )
            }
        }
    }

    /** 차 한 대의 장난감: 내 차는 DecoStore, 일행 차는 서버로 받은 모양(Car.look, v1.15). 없으면 기본 */
    private fun toyOf(context: Context, c: Car, isMe: Boolean): ToyCarDrawable {
        if (isMe) return ToyCarDrawable(context, true).applyMine()
        return ToyCarDrawable(context, false).applyLook(c.look)
    }

    /** 내 차는 꾸민 모습(DecoStore), 일행 차는 기본 */
    private fun toyFor(context: Context, isMe: Boolean) =
        if (isMe) ToyCarDrawable(context, true).applyMine() else ToyCarDrawable(context, false)

    /** 카드 하나: 세로(가로 창) 또는 가로(세로 창) 배치. 눌리는 면 */
    private fun startCard(context: Context, vertical: Boolean, accentStroke: Boolean, icon: View, title: String, desc: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER else Gravity.CENTER_VERTICAL
            val pad = Kk.px(context, if (vertical) R.dimen.kk_pad_card_l else R.dimen.kk_pad_card_p)
            setPadding(pad, pad, pad, pad)
            background = shape(
                context, R.color.kk_card, R.dimen.kk_r_card,
                if (accentStroke) R.color.kk_brand else if (vertical) R.color.kk_line else null,
                if (accentStroke) R.dimen.kk_stroke_icon else R.dimen.kk_stroke_card,
            )
            isClickable = true
            addView(icon)
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                addView(Kk.text(context, title, Kk.TextStyleKk.TITLE_S_BOLD).apply { if (vertical) gravity = Gravity.CENTER })
                addView(Kk.text(context, desc, Kk.TextStyleKk.CAPTION).apply {
                    if (vertical) gravity = Gravity.CENTER
                    setLineSpacing(0f, 0.88f) // 설명 줄끼리는 촘촘하게
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Kk.px(context, R.dimen.kk_space_s) }) // 제목과는 넓게
            }
            val tp = if (vertical) LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Kk.px(context, R.dimen.kk_space_l) }
            else LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = Kk.px(context, R.dimen.kk_pad_card_p) }
            addView(texts, tp)
        }

    private fun shape(context: Context, @ColorRes fill: Int, @DimenRes radius: Int, @ColorRes stroke: Int?, @DimenRes strokeW: Int): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.resources.getDimension(radius)
            setColor(Kk.color(context, fill))
            if (stroke != null) setStroke(Kk.px(context, strokeW), Kk.color(context, stroke))
        }

    /** 144dp 아이콘 상자: 면 kk_rail, 테두리 brand 3dp, r=24 */
    private fun iconBox(context: Context): FrameLayout = FrameLayout(context).apply {
        val s = Kk.px(context, R.dimen.kk_start_icon_box)
        layoutParams = LinearLayout.LayoutParams(s, s)
        background = shape(context, R.color.kk_rail, R.dimen.kk_r_icon, R.color.kk_brand, R.dimen.kk_stroke_icon)
    }

    private fun logoPlusBox(context: Context): View = iconBox(context).apply {
        val logo = Kk.px(context, R.dimen.kk_start_logo)
        addView(ImageView(context).apply { setImageResource(R.drawable.kk_logo) }, FrameLayout.LayoutParams(logo, logo, Gravity.CENTER))
        val b = Kk.px(context, R.dimen.kk_start_badge)
        addView(
            FrameLayout(context).apply {
                background = shape(context, R.color.kk_btn, R.dimen.kk_r_icon, null, R.dimen.kk_stroke)
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.kk_ic_plus)
                        setColorFilter(Kk.color(context, R.color.kk_btn_ink), PorterDuff.Mode.SRC_IN)
                    },
                    FrameLayout.LayoutParams(Kk.px(context, R.dimen.kk_start_badge_icon), Kk.px(context, R.dimen.kk_start_badge_icon), Gravity.CENTER),
                )
            },
            FrameLayout.LayoutParams(b, b, Gravity.BOTTOM or Gravity.END),
        )
    }

    private fun joinIconBox(context: Context): View = iconBox(context).apply {
        addView(
            Kk.text(context, context.getString(R.string.start_join_icon), Kk.TextStyleKk.TITLE_S_BOLD, R.color.kk_brand_text),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER),
        )
    }

    /** 요청 중: 로고 대신 돌아가는 진행 표시 */
    private fun loaderBox(context: Context): View = iconBox(context).apply {
        val s = Kk.px(context, R.dimen.kk_start_loader)
        val loader = LoaderView(context)
        addView(loader, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        ObjectAnimator.ofFloat(loader, View.ROTATION, 0f, 360f).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** 오류 안내 줄: 면 kk_brand_tint, 경고 아이콘 + 문구 */
    private fun banner(context: Context, text: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(context, R.color.kk_brand_tint, R.dimen.kk_r_banner, null, R.dimen.kk_stroke)
        val v = Kk.px(context, R.dimen.kk_margin)
        val h = Kk.px(context, R.dimen.kk_pad_card_p)
        setPadding(h, v, h, v)
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.kk_ic_alert)
                setColorFilter(Kk.color(context, R.color.kk_fg), PorterDuff.Mode.SRC_IN)
            },
            LinearLayout.LayoutParams(Kk.px(context, R.dimen.kk_alert_icon), Kk.px(context, R.dimen.kk_alert_icon)),
        )
        addView(Kk.text(context, text, Kk.TextStyleKk.CAPTION), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = Kk.px(context, R.dimen.kk_space_s)
        })
    }

    /** Figma "Loader": 바깥 고리(fg 10%) + 보라 호 */
    private class LoaderView(context: Context) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            color = Kk.color(context, R.color.kk_fg); alpha = 26
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            color = Kk.color(context, R.color.kk_brand)
        }
        override fun onDraw(canvas: Canvas) {
            val w = width * 8f / 72f
            ring.strokeWidth = w; arc.strokeWidth = w
            val r = RectF(w, w, width - w, height - w)
            canvas.drawArc(r, 0f, 360f, false, ring)
            canvas.drawArc(r, -90f, 90f, false, arc)
        }
    }
}
