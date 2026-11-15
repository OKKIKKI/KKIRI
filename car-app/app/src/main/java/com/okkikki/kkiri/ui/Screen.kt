package com.okkikki.kkiri.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.okkikki.kkiri.R
import com.okkikki.kkiri.contract.Car
import com.okkikki.kkiri.contract.CarRole
import com.okkikki.kkiri.contract.CarStatus
import com.okkikki.kkiri.contract.Message
import com.okkikki.kkiri.contract.Place
import com.okkikki.kkiri.contract.ParticipantRole
import com.okkikki.kkiri.contract.Session
import com.okkikki.kkiri.contract.SpeakerInfo
import com.okkikki.kkiri.ui.theme.Kk
import java.time.Instant

/** 차량 화면 5개 (docs/03_SCREENS.md HB-S-CAR-01~05). */
enum class Screen(val screenId: String, @StringRes val titleRes: Int) {
    CAR_01("HB-S-CAR-01", R.string.screen_car_01_title),
    CAR_02("HB-S-CAR-02", R.string.screen_car_02_title),
    CAR_03("HB-S-CAR-03", R.string.screen_car_03_title),
    CAR_04("HB-S-CAR-04", R.string.screen_car_04_title),
    CAR_05("HB-S-CAR-05", R.string.screen_car_05_title),
}

/** 화면에서 일어나는 일. 실제 처리(세션 불러오기·종료, 화면 이동)는 MainActivity가 한다. */
sealed interface UiAction {
    data class Go(val screen: Screen) : UiAction
    /** CAR-01 ③ [세션 만들기] — R-02. name은 ② 입력칸 값(비면 기본값) */
    data class CreateSession(val name: String) : UiAction
    /** CAR-03 ②③ [합류] — R-05. joinCode는 ① 입력칸의 숫자 6자리 */
    data class Join(val joinCode: String, val applyDestination: Boolean) : UiAction
    /** CAR-05 ⑥ [세션 종료] 확인 후 — R-09 */
    object EndSession : UiAction
    /** CAR-05 [QR 다시 보기] — 전체화면이면 페이지, 작은 창이면 모달 */
    object ShowQr : UiAction
    /** 일행 탭 [꾸미기]: 꾸미기 탭으로 이동 */
    object OpenDeco : UiAction
    /** 세션 대기 [차량 목록](세로 창): 메시지 탭으로 */
    object OpenMessages : UiAction
    /** CAR-05 ① 일행 차량 [음소거]/[음소거 중] — R-22. mute=true면 음소거, false면 해제 */
    data class ToggleMute(val targetCarId: String, val mute: Boolean) : UiAction
}

/**
 * 입력칸에 쓰던 값. 세션 값이 바뀌어 화면을 다시 그려도 입력한 글자가 지워지지 않게 MainActivity가 들고 있는다.
 * sessionName이 null이면 아직 손대지 않은 것 → 기본값 "{운전자 이름}의 드라이브"를 보여 준다.
 */
class ScreenDrafts {
    var sessionName: String? = null
    var joinCode: String = ""
}

/**
 * 차량 화면(코드로 그림). 값은 SessionRepository의 session에서 읽는다.
 * - 모양(색·크기·모서리)은 res/values의 kk_ 토큰과 ui/theme/Kk.kt만 쓴다(V2 디자인 적용, 프로토타입 v4 기준).
 * - 맨 위에는 로고와 화면 이름을 둔다(개발용 화면 ID 표시는 뺐다).
 * - 문구는 03_SCREENS.md에 있는 것만 쓴다(strings.xml).
 * - 오류 문구는 토스트 대신 화면 아래 빨간 글자 한 줄(Pleos 에뮬레이터에서 토스트가 보이지 않음, 2026-11-12).
 * - 아직 없는 것:
 *   CAR-05 ④ [참가자 관리](2차).
 */
object ScreenViews {

    /**
     * @param session 지금 세션(없으면 null)
     * @param myCarId 내 차(호스트 판별·일행 목록에서 내 차 빼기에 씀)
     * @param inModal true면 CAR-02를 모달 안에 그린다([출발] 버튼을 빼고 QR·접속코드·목록만)
     * @param drafts 입력칸 값(CAR-01 세션 이름, CAR-03 접속코드)
     * @param driverName 세션 이름 기본값에 넣을 운전자 이름
     * @param error 화면 아래에 보여 줄 오류 문구(없으면 null)
     * @param speaking CAR-04 ② 지금 말하는 차(VoiceHub.nowSpeaking)
     * @param heldCount CAR-04 ③ carId별 보관 메시지 수(VoiceHub.heldCount)
     * @param held CAR-05 ② 보관 메시지(R-23으로 받은 것)
     * @param destination CAR-01 ① 지금 내비 목적지(VoiceHub.currentDestination, 모르면 null)
     */
    fun build(
        context: Context,
        screen: Screen,
        session: Session?,
        myCarId: String?,
        act: (UiAction) -> Unit,
        inModal: Boolean = false,
        drafts: ScreenDrafts = ScreenDrafts(),
        driverName: String = "",
        error: String? = null,
        speaking: SpeakerInfo? = null,
        heldCount: Map<String, Int> = emptyMap(),
        held: List<Message> = emptyList(),
        destination: Place? = null,
        busy: Boolean = false,
        tab: Tab = Tab.GROUP,
    ): View {
        // V2-S7: CAR-01은 Figma 확정본 화면(GroupScreens)
        if (screen == Screen.CAR_01 && !inModal) return GroupScreens.start(context, destination, driverName, busy, error, act)
        if (screen == Screen.CAR_02 && !inModal && session != null) return GroupScreens.waiting(context, session, myCarId, act)
        if (screen == Screen.CAR_05 && tab == Tab.GROUP && session != null) return GroupScreens.group(context, session, myCarId, act)
        if (screen == Screen.CAR_03 && !inModal) return GroupScreens.join(context, drafts, error, act)
        // V2-S8: 공간 탭(정차 CAR-05 · 주행 CAR-04)은 광장 화면
        if (session != null && !inModal && (screen == Screen.CAR_04 || (screen == Screen.CAR_05 && tab == Tab.SPACE))) {
            return SpaceScreens.build(context, screen == Screen.CAR_04, session, myCarId, speaking, heldCount, act)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val side = if (inModal) Kk.px(context, R.dimen.kk_pad_modal) else sidePadding(context)
            val vertical = Kk.px(context, if (inModal) R.dimen.kk_pad_modal else R.dimen.kk_pad_screen)
            setPadding(side, vertical, side, vertical)
        }
        if (!inModal) root.addView(header(context, screen))

        when (screen) {
            Screen.CAR_01 -> {
                // ① 목적지: "목적지 : {현재 내비 목적지 이름}", 없으면 "목적지 없음" (CAR-A는 내비를 직접 부르지 않는다)
                val destText = destination?.let { context.getString(R.string.car01_destination, it.name) }
                    ?: context.getString(R.string.car01_destination_none)
                val form = card(context)
                form.addView(label(context, destText, R.dimen.kk_text_m, muted = true))
                // ② 세션 이름 입력. 기본값 "{운전자 이름}의 드라이브" (03_SCREENS CAR-01 ②)
                val defaultName = context.getString(R.string.car01_session_name_default, driverName)
                form.addView(input(context, drafts.sessionName ?: defaultName).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    doAfterTextChanged { drafts.sessionName = it?.toString() }
                })
                form.addView(button(context, R.string.car01_create_session) {
                    val name = drafts.sessionName?.trim().orEmpty().ifEmpty { defaultName }
                    act(UiAction.CreateSession(name))
                })
                root.addView(form)
                root.addView(button(context, R.string.car01_join_card, ButtonStyle.SECONDARY) { act(UiAction.Go(Screen.CAR_03)) })
            }
            Screen.CAR_02 -> {
                if (session != null) addSessionWaiting(context, root, session)
                if (!inModal) {
                    root.addView(button(context, R.string.car02_depart) { act(UiAction.Go(Screen.CAR_05)) })
                }
            }
            Screen.CAR_03 -> {
                // ① 접속코드: 숫자 6자리 + 숫자 키패드. 6자리가 되어야 합류 버튼이 눌린다
                val withDest = button(context, R.string.car03_join_with_destination) {
                    act(UiAction.Join(drafts.joinCode, applyDestination = true))
                }
                val keepDest = button(context, R.string.car03_join_keep_destination, ButtonStyle.SECONDARY) {
                    act(UiAction.Join(drafts.joinCode, applyDestination = false))
                }
                fun updateEnabled() {
                    val ok = drafts.joinCode.length == JOIN_CODE_LENGTH
                    withDest.isEnabled = ok
                    keepDest.isEnabled = ok
                }
                root.addView(input(context, drafts.joinCode).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    filters = arrayOf(InputFilter.LengthFilter(JOIN_CODE_LENGTH))
                    Kk.textSize(this, R.dimen.kk_text_xl)
                    letterSpacing = CODE_LETTER_SPACING
                    doAfterTextChanged {
                        drafts.joinCode = it?.toString().orEmpty()
                        updateEnabled()
                    }
                })
                root.addView(withDest)
                root.addView(keepDest)
                updateEnabled()
                // ④ [취소]: 서버 호출 없이 CAR-01로 (03_SCREENS CAR-03, 명세 v1.5)
                root.addView(button(context, R.string.car03_cancel, ButtonStyle.SECONDARY) { act(UiAction.Go(Screen.CAR_01)) })
            }
            Screen.CAR_04 -> {
                // 주행 중: 터치 요소를 두지 않는다 (03_SCREENS CAR-04, COM-01). 메시지 본문·경유지 이름은 띄우지 않는다
                root.addView(band(context, context.getString(R.string.car04_lock_band)))
                addDriving(context, root, session, myCarId, speaking, heldCount)
            }
            Screen.CAR_05 -> {
                if (session != null) addParked(context, root, session, myCarId, act, held)
                root.addView(button(context, R.string.car05_show_qr, ButtonStyle.SECONDARY) { act(UiAction.ShowQr) })
                // ⑥ 호스트 차에만 보임 (03_SCREENS CAR-05, 명세 v1.5 R-09)
                if (session != null && session.hostCarId == myCarId) {
                    root.addView(button(context, R.string.car05_end_session, ButtonStyle.SECONDARY) {
                        confirmEndSession(context) { act(UiAction.EndSession) }
                    })
                }
            }
        }
        if (error != null && !inModal) {
            // 오류 문구: 빨강은 계기판 경고등과 겹쳐 쓰지 않는다(컬러 규칙). 브랜드 글자색 + 굵게
            root.addView(label(context, error, R.dimen.kk_text_m).apply {
                setTextColor(Kk.color(context, R.color.kk_brand_text))
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        // 내용이 창보다 길면 스크롤된다(반쪽 창). 짧으면 가운데 정렬
        return ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(Kk.color(context, R.color.kk_bg))
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** CAR-02 ①~④ */
    private fun addSessionWaiting(context: Context, root: LinearLayout, s: Session) {
        // ① QR (D-25): qr.url 그대로 흑백으로 그린다. 만료면 QR 없이 만료 문구만(다시 만들기는 2차).
        //    주소 글자는 QR 아래에 작게(폰 카메라가 안 될 때 직접 입력용). 인코딩 실패면 글자만
        if (isExpired(s.qr.expiresAt)) {
            root.addView(label(context, context.getString(R.string.car02_qr_expired), R.dimen.kk_text_m))
        } else {
            // QR 그림 자체(흰 바탕·검은 모듈·여백 4칸, D-25)는 QrView 그대로. 둘레에 흰 둥근 카드만 두른다
            QrView.create(context, s.qr.url)?.let { qr ->
                root.addView(LinearLayout(context).apply {
                    background = Kk.rounded(context, R.color.kk_qr_bg, R.dimen.kk_radius_card)
                    val pad = Kk.px(context, R.dimen.kk_gap_small)
                    setPadding(pad, pad, pad, pad)
                    addView(qr)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Kk.px(context, R.dimen.kk_gap) }
                })
            }
            root.addView(label(context, s.qr.url, R.dimen.kk_text_xs, muted = true))
        }
        // ② 접속코드 6자리 크게
        root.addView(label(context, s.joinCode, R.dimen.kk_text_display).apply {
            letterSpacing = CODE_LETTER_SPACING
            setTypeface(typeface, Typeface.BOLD)
        })
        // ③ 참가자 목록: 호스트 차 운전자 + 참가자(리더/크루). 참가자 0명이면 빈 상태 문구
        val list = card(context)
        val host = s.cars.firstOrNull { it.carId == s.hostCarId }
        if (host != null) {
            list.addView(label(context, "${host.driverName} · ${context.getString(R.string.role_host_driver)}", R.dimen.kk_text_s))
        }
        if (s.participants.isEmpty()) {
            list.addView(label(context, context.getString(R.string.car02_empty_participants), R.dimen.kk_text_s, muted = true))
        } else {
            s.participants.forEach { p ->
                val role = when (p.role) {
                    ParticipantRole.LEADER -> R.string.role_leader
                    ParticipantRole.CREW -> R.string.role_crew
                }
                list.addView(label(context, "${p.name} · ${context.getString(role)}", R.dimen.kk_text_s))
            }
        }
        // ④ 합류 차량 목록 "{displayName} · {driverName} · 상태"
        s.cars.filter { it.role == CarRole.COHOST }.forEach {
            list.addView(label(context, carLine(context, it), R.dimen.kk_text_s))
        }
        root.addView(list)
    }

    /** CAR-04 ②~⑦ (글자만, 터치 없음) */
    private fun addDriving(
        context: Context,
        root: LinearLayout,
        s: Session?,
        myCarId: String?,
        speaking: SpeakerInfo?,
        heldCount: Map<String, Int>,
    ) {
        // ② 발화 차량: "{carName} · {name}" + "말하는 중" 크게. 없으면 비움
        if (speaking != null) {
            root.addView(label(context, context.getString(R.string.car04_speaker, speaking.carName, speaking.name), R.dimen.kk_text_l))
            root.addView(label(context, context.getString(R.string.car04_speaking), R.dimen.kk_text_xl).apply {
                setTextColor(Kk.color(context, R.color.kk_brand_text))
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        if (s != null) {
            val nameOf = { carId: String -> s.cars.firstOrNull { it.carId == carId }?.displayName ?: carId }
            // ③ 음소거 상태: 내가 음소거한 차(보관 건수 함께), 나를 음소거한 차
            s.mutes.filter { it.byCarId == myCarId }.forEach { m ->
                val n = heldCount[m.targetCarId] ?: 0
                root.addView(label(context, context.getString(R.string.car04_mute_by_me, nameOf(m.targetCarId), n), R.dimen.kk_text_m))
            }
            s.mutes.filter { it.targetCarId == myCarId && it.byCarId != myCarId }.forEach { m ->
                root.addView(label(context, context.getString(R.string.car04_muted_me, nameOf(m.byCarId)), R.dimen.kk_text_m))
            }
            // ④ 음악 잠금: Spotify 미연결이면 숨김
            if (s.music.connected) {
                val res = if (s.music.locked) R.string.car04_music_lock_on else R.string.car04_music_lock_off
                root.addView(label(context, context.getString(res), R.dimen.kk_text_m, muted = true))
            }
        }
        // ⑤⑥⑦ 음성 안내 카드 3개 (고정 문구)
        listOf(R.string.car04_hint_message, R.string.car04_hint_mute, R.string.car04_hint_proposal).forEach {
            root.addView(card(context).apply { addView(label(context, context.getString(it), R.dimen.kk_text_s)) })
        }
    }

    /** CAR-05 ①②③⑤ */
    private fun addParked(
        context: Context,
        root: LinearLayout,
        s: Session,
        myCarId: String?,
        act: (UiAction) -> Unit,
        held: List<Message>,
    ) {
        // ① 일행 차량 목록 (내 차 제외) + [음소거]/[음소거 중] 토글.
        //    내가 그 차를 음소거했는지는 session.mutes에서 byCarId=내 차, targetCarId=그 차인 항목으로 본다(R-22, mute.changed)
        s.cars.filter { it.carId != myCarId }.forEach { car ->
            val muted = s.mutes.any { it.byCarId == myCarId && it.targetCarId == car.carId }
            val row = card(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // 이름은 남는 너비를 차지하고, 토글은 오른쪽 끝에 둔다
            row.addView(label(context, carLine(context, car), R.dimen.kk_text_m).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            val toggle = button(
                context,
                if (muted) R.string.car05_muted else R.string.car05_mute,
                if (muted) ButtonStyle.TOGGLED else ButtonStyle.SECONDARY,
            ) {
                act(UiAction.ToggleMute(car.carId, mute = !muted))
            }
            toggle.minHeight = Kk.px(context, R.dimen.kk_btn_small_height)
            Kk.textSize(toggle, R.dimen.kk_text_s)
            toggle.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = Kk.px(context, R.dimen.kk_gap)
            }
            row.addView(toggle)
            root.addView(row)
        }
        // ② 보관 메시지: 음소거 중 쌓인 메시지 "{보낸 사람} · {본문 또는 "음성 메시지"}". 없으면 그리지 않는다
        if (held.isNotEmpty()) {
            val box = card(context)
            box.addView(label(context, context.getString(R.string.car05_held_title), R.dimen.kk_text_s, muted = true))
            held.forEach { m ->
                val body = m.text ?: context.getString(R.string.car05_held_voice)
                box.addView(label(context, context.getString(R.string.car05_held_line, m.sender.name, body), R.dimen.kk_text_s))
            }
            root.addView(box)
        }
        // ③ 경유지 변경 내역: 내 차 경유지만, "{장소 이름} · {추가한 사람}". 없으면 그리지 않는다
        val myWaypoints = s.waypoints.filter { it.carId == myCarId }
        if (myWaypoints.isNotEmpty()) {
            val box = card(context)
            box.addView(label(context, context.getString(R.string.car05_waypoints_title), R.dimen.kk_text_s, muted = true))
            myWaypoints.forEach { w ->
                box.addView(label(context, context.getString(R.string.car05_waypoint_line, w.place.name, w.addedBy.name), R.dimen.kk_text_s))
            }
            root.addView(box)
        }
        // ⑤ 지금 재생: Spotify 연결 시에만
        val now = s.music.nowPlaying
        if (s.music.connected && now != null) {
            root.addView(label(context, context.getString(R.string.car05_now_playing, now.title, now.artist), R.dimen.kk_text_s, muted = true))
        }
    }

    private fun carLine(context: Context, c: Car): String {
        val status = when (c.status) {
            CarStatus.CONNECTED -> R.string.car_status_connected
            CarStatus.PAUSED -> R.string.car_status_paused
            CarStatus.DISCONNECTED -> R.string.car_status_disconnected
        }
        return "${c.displayName} · ${c.driverName} · ${context.getString(status)}"
    }

    /** 시간 형식이 틀리면 만료가 아닌 것으로 본다(QR 주소는 그대로 보여 줌) */
    private fun isExpired(expiresAt: String): Boolean =
        runCatching { Instant.parse(expiresAt).isBefore(Instant.now()) }.getOrDefault(false)

    /** CAR-05 ⑥ 확인 창: [종료]면 onEnd, [취소]면 그대로 닫는다. */
    /** 아직 만들지 않은 기능을 눌렀을 때 보여 주는 "준비 중" 안내 상자 */
    internal fun comingSoon(context: Context) {
        AlertDialog.Builder(context)
            .setMessage(R.string.coming_soon)
            .setPositiveButton(R.string.coming_soon_ok, null)
            .show()
    }

    internal fun confirmEndSession(context: Context, onEnd: () -> Unit) {
        AlertDialog.Builder(context)
            .setMessage(R.string.car05_end_confirm_message)
            .setPositiveButton(R.string.car05_end_confirm_end) { _, _ -> onEnd() }
            .setNegativeButton(R.string.car05_end_confirm_cancel, null)
            .show()
    }

    /** 맨 위 줄: 로고 + 화면 이름 */
    private fun header(context: Context, screen: Screen) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(ImageView(context).apply {
            setImageResource(R.drawable.kk_logo)
            layoutParams = LinearLayout.LayoutParams(
                Kk.px(context, R.dimen.kk_logo_width),
                Kk.px(context, R.dimen.kk_logo_height),
            ).apply { marginEnd = Kk.px(context, R.dimen.kk_gap) }
        })
        addView(label(context, context.getString(screen.titleRes), R.dimen.kk_text_l).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun label(context: Context, text: String, @DimenRes sizeRes: Int, muted: Boolean = false) =
        TextView(context).apply {
            this.text = text
            Kk.textSize(this, sizeRes)
            setTextColor(Kk.color(context, if (muted) R.color.kk_fg_muted else R.color.kk_fg))
            gravity = Gravity.CENTER
            val pad = Kk.px(context, R.dimen.kk_pad_text)
            setPadding(0, pad, 0, pad)
        }

    /** 카드: 둥근 모서리 + 옅은 면 + 테두리. 창 너비를 채운다 */
    private fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = Kk.rounded(context, R.color.kk_card, R.dimen.kk_radius_card, R.color.kk_line)
        val pad = Kk.px(context, R.dimen.kk_pad_card)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Kk.px(context, R.dimen.kk_gap) }
    }

    /** CAR-04 ① 잠금 안내 띠: 브랜드 틴트 면에 굵은 글자. 터치 요소 아님 */
    private fun band(context: Context, text: String) = label(context, text, R.dimen.kk_text_m).apply {
        background = Kk.rounded(context, R.color.kk_brand_tint, R.dimen.kk_radius_card)
        setTypeface(typeface, Typeface.BOLD)
        val pad = Kk.px(context, R.dimen.kk_pad_card)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Kk.px(context, R.dimen.kk_gap) }
    }

    /**
     * 입력칸. 가로 화면에서 키보드가 화면 전체를 덮고 입력값을 작게 보여 주는 모드(extract UI)를 끈다.
     * 키보드는 아래에만 뜨고 입력한 글자는 이 칸에 그대로 보인다.
     */
    private fun input(context: Context, initial: String) = EditText(context).apply {
        setText(initial)
        setSelection(text.length)
        Kk.textSize(this, R.dimen.kk_text_m)
        setTextColor(Kk.color(context, R.color.kk_fg))
        background = Kk.rounded(context, R.color.kk_chip, R.dimen.kk_radius_field, R.color.kk_line)
        val pad = Kk.px(context, R.dimen.kk_gap)
        setPadding(pad, pad, pad, pad)
        gravity = Gravity.CENTER
        isSingleLine = true
        minWidth = Kk.px(context, R.dimen.kk_field_min_width)
        imeOptions = EditorInfo.IME_ACTION_DONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Kk.px(context, R.dimen.kk_gap_small) }
    }

    private enum class ButtonStyle { PRIMARY, SECONDARY, TOGGLED }

    private fun button(
        context: Context,
        @StringRes textRes: Int,
        style: ButtonStyle = ButtonStyle.PRIMARY,
        onClick: () -> Unit,
    ) = Button(context).apply {
        setText(textRes)
        Kk.textSize(this, R.dimen.kk_text_m)
        isAllCaps = false
        minHeight = Kk.px(context, R.dimen.kk_btn_height)
        val padH = Kk.px(context, R.dimen.kk_btn_pad_h)
        setPadding(padH, 0, padH, 0)
        // 기본 버튼의 그림자·눌림 띄우기는 쓰지 않는다(평평한 알약형)
        stateListAnimator = null
        when (style) {
            ButtonStyle.PRIMARY -> {
                background = Kk.primaryButtonBackground(context)
                setTextColor(Kk.textStates(Kk.color(context, R.color.kk_btn_ink)))
                setTypeface(typeface, Typeface.BOLD)
            }
            ButtonStyle.SECONDARY -> {
                background = Kk.secondaryButtonBackground(context)
                setTextColor(Kk.textStates(Kk.color(context, R.color.kk_fg)))
            }
            ButtonStyle.TOGGLED -> {
                background = Kk.toggledButtonBackground(context)
                setTextColor(Kk.textStates(Kk.color(context, R.color.kk_brand_text)))
                setTypeface(typeface, Typeface.BOLD)
            }
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Kk.px(context, R.dimen.kk_gap) }
    }

    /** 좌우 여백: 넓은 창에서는 본문 열이 kk_content_max_dp를 넘지 않게 여백을 늘린다 */
    private fun sidePadding(context: Context): Int {
        val res = context.resources
        val extraDp = ((res.configuration.screenWidthDp - res.getInteger(R.integer.kk_content_max_dp)) / 2).coerceAtLeast(0)
        return Kk.px(context, R.dimen.kk_pad_screen) + (extraDp * res.displayMetrics.density).toInt()
    }

    private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    /** 접속코드 글자 간격(em) */
    private const val CODE_LETTER_SPACING = 0.3f
    private const val JOIN_CODE_LENGTH = 6
}
