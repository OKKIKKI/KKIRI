package com.okkikki.kkiri.ui

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.annotation.StringRes
import com.okkikki.kkiri.BuildConfig
import com.okkikki.kkiri.R
import com.okkikki.kkiri.ui.theme.Kk
import com.okkikki.kkiri.contract.CarLook
import com.okkikki.kkiri.contract.DriveState
import com.okkikki.kkiri.contract.Message
import com.okkikki.kkiri.contract.MessageCreated
import com.okkikki.kkiri.contract.SenderType
import com.okkikki.kkiri.contract.SessionState
import com.okkikki.kkiri.state.DriveStates
import com.okkikki.kkiri.state.PleosVehicle
import com.okkikki.kkiri.net.ApiException
import com.okkikki.kkiri.state.SessionControllers
import com.okkikki.kkiri.state.SessionOp
import com.okkikki.kkiri.state.DecoStore
import com.okkikki.kkiri.state.Steerings
import com.okkikki.kkiri.state.VoiceHubs
import com.okkikki.kkiri.voice.debug.VoiceCommandDebugPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 앱 진입점. 화면 5개(CAR-01~05)를 한 화면 틀(container)에 바꿔 끼운다.
 * 창 크기가 바뀌면(전체화면 ↔ 반쪽 창) 안드로이드가 화면을 다시 만들 수 있으므로
 * 지금 화면을 저장해 두었다가 그대로 복원한다.
 *
 * 주행 중 잠금 (HB-F-COM-01, 명세 5절-2, 03_SCREENS):
 * - DRIVING이 되면 어느 화면에 있든 CAR-04로 바꾼다. CAR-04에는 터치 요소가 없다.
 * - PARKED가 되면 CAR-04에서 나온다. 세션 중(CAR-02·CAR-05)이었으면 CAR-05,
 *   세션 전(CAR-01·CAR-03)이었으면 원래 화면으로 돌아간다.
 * - UNKNOWN이면 화면을 바꾸지 않는다.
 *
 * QR 다시 보기 (CAR-05, CAR-A 결정 2026-11-11): 정차 중(CAR-05)에만 버튼이 있다.
 * - 앱 창이 가로(전체화면)면 CAR-02 페이지로 넘어간다.
 * - 앱 창이 세로(Pleos 화면 분할의 반쪽 창)면 QR·접속코드를 모달로 띄운다. 바깥을 누르면 닫힌다.
 * - 주행이 시작되면 모달을 닫는다.
 */
class MainActivity : AppCompatActivity() {

    private val drive = DriveStates.mock
    /** 핸들 각도(명세 6.1). 지금은 목업(디버그 버튼) */
    private val steering = Steerings.mock
    /** 세션 동작. 기본은 실제 서버, -Pkkiri.useMock=true면 fixtures 목업 (state/SessionController.kt) */
    private val controller by lazy { SessionControllers.get(this) }
    private val repo get() = controller.repo
    /** 서버 요청 중에는 버튼을 다시 눌러도 무시한다(두 번 만들기 방지) */
    private var busy = false
    /** 지금 진행 중인 요청이 "세션 만들기"인지(CAR-01 카드의 만드는 중 표시용) */
    private var creating = false
    /** 입력칸 값(CAR-01 세션 이름, CAR-03 접속코드). 화면을 다시 그려도 유지 */
    private val drafts = ScreenDrafts()
    /** 지금 화면 아래에 보여 줄 오류 문구. 다른 화면으로 가면 지운다 */
    private var errorMessage: String? = null
    /** CAR-05 ② 이번에 정차 화면에서 보여 주는 보관 메시지(R-23). CAR-05를 떠나면 비운다 */
    private var heldShown: List<Message> = emptyList()
    /** 이 세션에서 받은 메시지(최신이 앞). 메시지 탭이 보여 준다. 기기 메모리에만 두고 세션이 끝나면 비운다 */
    private var receivedLog: List<Message> = emptyList()
    private var receivedSession: String? = null
    private var heldLoading = false
    private lateinit var container: FrameLayout
    private var current: Screen = Screen.CAR_01
    /** V2-S6: 레일에서 고른 탭. 일행·공간 말고는 내용이 아직 자리표시(S9~S11에서 채움) */
    private var tab: Tab = Tab.GROUP
    /** 주행이 시작되기 직전에 보던 탭(정차하면 돌아간다) */
    private var tabBeforeDriving: Tab? = null
    /** [출발]을 누른 뒤인가(CAR-05에 들어온 적 있음). 일행 탭이 "출발 후" 화면을 보이는 기준. 세션 화면을 벗어나면 false */
    private var departed = false
    /** 첫 주행 안내 말풍선(떠 있는 동안만 값이 있다) */
    private var tipView: View? = null
    /** 주행이 시작되기 직전에 보던 화면 */
    private var beforeDriving: Screen? = null
    /** CAR-B 음성 명령 디버그 패널(Q-CAR-B-001). 디버그 빌드에서만 있고 CAR-04에서만 보인다 */
    private var voicePanel: View? = null
    /** 작은 창에서 띄운 QR 모달 */
    private var qrDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.okkikki.kkiri.state.DecoStore.init(this) // V2-S10: 내 차 꾸미기 값
        PleosVehicle.start(this) // V2-S1·S2: 실제 기어·핸들 값 연결(여러 번 불려도 한 번만)
        val root = FrameLayout(this)
        container = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(MATCH, MATCH))
        if (BuildConfig.DEBUG && BuildConfig.KKIRI_DEBUG_PANELS) {
            root.addView(
                DebugDrivePanel.build(this, this, drive, steering),
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END),
            )
        }
        // Q-CAR-B-001: 왼쪽 아래(주행 토글은 오른쪽 아래). 크기는 고정하지 않고, 화면 높이 안에서 패널(ScrollView)이 스크롤된다
        VoiceCommandDebugPanel.createOrNull(this)?.takeIf { BuildConfig.KKIRI_DEBUG_PANELS }?.let { panel ->
            voicePanel = FrameLayout(this).apply {
                setBackgroundColor(Color.argb(230, 255, 255, 255))
                addView(panel)
                visibility = View.GONE
            }.also {
                // V2-S6: 레일(왼쪽 120dp)을 가리지 않게 레일 폭만큼 띄운다
                root.addView(it, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = resources.getDimensionPixelSize(R.dimen.kk_rail_width)
                })
            }
        }
        setContentView(root)
        // V2-S9: 앱을 새로 열 때만 로딩 애니메이션(화면 회전 등으로 다시 만들어질 때는 없음). 시작 흐름은 뒤에서 그대로 진행한다
        if (savedInstanceState == null) playSplash(root)

        beforeDriving = screenOf(savedInstanceState?.getString(KEY_BEFORE_DRIVING))
        tabBeforeDriving = Tab.entries.firstOrNull { it.name == savedInstanceState?.getString(KEY_TAB_BEFORE_DRIVING) }
        show(
            screenOf(savedInstanceState?.getString(KEY_SCREEN)) ?: Screen.CAR_01,
            Tab.entries.firstOrNull { it.name == savedInstanceState?.getString(KEY_TAB) },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                drive.driveState.collect { onDriveState(it) }
            }
        }
        // 세션 값이 바뀌면 지금 화면을 다시 그린다
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.session.collect { session ->
                    // session.ended를 받으면 연결을 끊고 세션을 비운다 → 아래 null 처리로 CAR-01
                    if (session?.state == SessionState.ENDED) {
                        controller.clearLocal()
                        return@collect
                    }
                    VoiceHubs.sync(this@MainActivity, session, repo, drive)
                    if (session == null) leaveSessionScreens() else show(current)
                }
            }
        }
        // 메시지 탭: 받은 메시지를 쌓는다(보관·낭독 구분 없이). 세션이 바뀌거나 끝나면 비운다
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.events.collect { e ->
                    val sid = repo.session.value?.sessionId
                    if (sid != receivedSession) { receivedSession = sid; receivedLog = emptyList() }
                    if (e is MessageCreated && sid != null && !(e.message.sender.type == SenderType.CAR && e.message.sender.id == repo.myCarId)) {
                        receivedLog = (listOf(e.message) + receivedLog).distinctBy { it.messageId }.take(50)
                        if (current == Screen.CAR_05 && tab == Tab.MESSAGES) show(current)
                    }
                }
            }
        }
        // CAR-04 ②③: CAR-B VoiceHub의 발화자·보관 건수가 바뀌면 CAR-04를 다시 그린다.
        // CAR-05에 있을 때 보관 건수가 바뀌면(새로 쌓임) R-23으로 다시 받아 온다
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val hub = VoiceHubs.get(this@MainActivity)
                combine(hub.nowSpeaking, hub.heldCount) { _, _ -> Unit }.collect {
                    // 발화자·보관 건수가 바뀌면 공간 광장을 다시 그린다(주행 CAR-04, 정차 CAR-05 공간 탭)
                    if (current == Screen.CAR_04 || (current == Screen.CAR_05 && tab == Tab.SPACE)) show(current)
                    if (current == Screen.CAR_05) loadHeld()
                }
            }
        }
        // 명세 6.1: 핸들 각도나 주행 상태가 바뀌면 공간 그림의 내 차 기울기를 맞춘다(정차 중에만 기운다)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(steering.angleDeg, drive.driveState) { _, _ -> Unit }.collect { applySteering() }
            }
        }
        // CAR-01 ①: 내비 목적지(CAR-B VoiceHub.currentDestination)가 바뀌면 CAR-01을 다시 그린다
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VoiceHubs.get(this@MainActivity).currentDestination.collect {
                    if (current == Screen.CAR_01) show(current)
                }
            }
        }
        // R-08: 주행 상태·시동·세션이 바뀔 때 보낸다(같은 값은 controller가 거른다)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(drive.driveState, drive.ignition, repo.session.map { it?.sessionId }) { d, i, id -> Triple(d, i, id) }
                    .distinctUntilChanged()
                    .collect { (d, i, _) ->
                        controller.reportCarState(d, i).onFailure { Log.w(TAG, "R-08 실패", it) }
                    }
            }
        }
        // R-47(v1.15): 내 차 모양(핸들 단계·꾸밈)이 바뀔 때 보낸다. 최소 간격을 지키되 마지막 값은 반드시 보낸다
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val minGap = resources.getInteger(R.integer.kk_look_min_interval_ms).toLong()
                combine(
                    combine(DecoStore.color, DecoStore.hat, DecoStore.balloon, DecoStore.character, DecoStore.items2) { c, h, b, ch, it2 -> listOf<Any?>(c, h, b, ch, it2) },
                    combine(steering.angleDeg, drive.driveState) { a, d -> currentSteerStep(a, d) },
                    repo.session.map { it?.sessionId },
                ) { v, step, id ->
                    @Suppress("UNCHECKED_CAST")
                    id to CarLook(step, String.format("#%06X", (v[0] as Int) and 0xFFFFFF), v[1] as Boolean, v[2] as Boolean, v[3] as String?, v[4] as List<String>)
                }
                    .distinctUntilChanged()
                    .conflate()
                    .collect { (id, look) ->
                        if (id != null) {
                            controller.reportCarLook(look).onFailure { Log.w(TAG, "R-47 실패(무시)", it) }
                            delay(minGap)
                        }
                    }
            }
        }
        // 앱 시작 흐름(R-01 → R-03 → R-04). 화면 회전 등으로 다시 만들어질 때는 하지 않는다
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                controller.start()
                    .onSuccess { if (repo.session.value != null && current == Screen.CAR_01) show(Screen.CAR_05) }
                    .onFailure { Log.w(TAG, "앱 시작 흐름 실패(서버 연결 확인)", it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        drive.setAppVisible(true)
    }

    override fun onStop() {
        drive.setAppVisible(false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, current.name)
        outState.putString(KEY_BEFORE_DRIVING, beforeDriving?.name)
        outState.putString(KEY_TAB, tab.name)
        outState.putString(KEY_TAB_BEFORE_DRIVING, tabBeforeDriving?.name)
    }

    private fun onDriveState(state: DriveState) {
        when (state) {
            DriveState.DRIVING -> {
                closeQrDialog()
                if (current != Screen.CAR_04) {
                    beforeDriving = current
                    tabBeforeDriving = tab
                    show(Screen.CAR_04)
                }
            }
            DriveState.PARKED -> if (current == Screen.CAR_04) {
                show(parkedScreenAfter(beforeDriving), tabBeforeDriving)
                beforeDriving = null
                tabBeforeDriving = null
            }
            DriveState.UNKNOWN -> Unit
        }
    }

    /** 세션이 없어졌을 때 세션 화면(CAR-02·05)에 있으면 CAR-01로. 주행 중이면 정차 후 CAR-01로 가게 한다 */
    private fun leaveSessionScreens() {
        when {
            current == Screen.CAR_02 || current == Screen.CAR_05 -> show(Screen.CAR_01)
            current == Screen.CAR_04 && (beforeDriving == Screen.CAR_02 || beforeDriving == Screen.CAR_05) -> beforeDriving = Screen.CAR_01
            else -> show(current)
        }
    }

    private fun parkedScreenAfter(before: Screen?): Screen = when (before) {
        Screen.CAR_02, Screen.CAR_05 -> Screen.CAR_05
        Screen.CAR_01, Screen.CAR_03 -> before
        else -> Screen.CAR_01
    }

    /** 화면 이름으로 정한 기본 탭. 주행 중(CAR-04)은 탭을 바꾸지 않는다 */
    private fun defaultTab(screen: Screen): Tab = when (screen) {
        Screen.CAR_05 -> Tab.SPACE
        Screen.CAR_04 -> tab
        else -> Tab.GROUP
    }

    /** 켜진 탭: 주행 중=없음, 세션 없음=일행·지도·꾸미기·설정·도움말, 세션 중 정차=전부 (V2_PLAN S6) */
    private fun enabledTabs(screen: Screen): Set<Tab> = when {
        screen == Screen.CAR_04 || drive.driveState.value == DriveState.DRIVING -> emptySet()
        repo.session.value == null -> setOf(Tab.GROUP, Tab.MAP, Tab.DECO, Tab.SETTINGS, Tab.HELP)
        else -> Tab.entries.toSet() // 설정은 아직 "준비 중" 표시 상자만 있다
    }

    /** 레일 탭을 눌렀을 때 */
    private fun onTab(t: Tab) {
        val hasSession = repo.session.value != null
        when (t) {
            Tab.GROUP -> show(if (hasSession) (if (departed) Screen.CAR_05 else Screen.CAR_02) else if (current == Screen.CAR_03) Screen.CAR_03 else Screen.CAR_01, Tab.GROUP)
            Tab.SPACE -> if (hasSession) show(Screen.CAR_05, Tab.SPACE)
            else -> show(current, t)
        }
    }

    /** 아직 기능이 없는 탭(설정 등): 탭 이름 + "준비 중" 표시 상자 */
    private fun placeholder(t: Tab): View = TabScreens.comingSoonPage(this, t.label)

    private fun show(screen: Screen, tabOverride: Tab? = null) {
        if (screen != current) errorMessage = null
        val enteringDriving = screen == Screen.CAR_04 && current != Screen.CAR_04
        val enteringParked = screen == Screen.CAR_05 && ((current != Screen.CAR_05 && (tabOverride ?: Tab.SPACE) == Tab.SPACE) || tabOverride == Tab.MESSAGES)
        if (screen != Screen.CAR_05) heldShown = emptyList()
        val sameScreen = screen == current
        current = screen
        if (screen == Screen.CAR_05) departed = true else if (screen != Screen.CAR_04 && screen != Screen.CAR_02) departed = false
        // 같은 화면을 다시 그릴 때(이벤트 등)는 보던 탭을 그대로 둔다
        tab = tabOverride ?: if (sameScreen && screen == Screen.CAR_05) tab else defaultTab(screen)
        val content: View = if (screen != Screen.CAR_04 && tab == Tab.DECO) {
            DecoScreens.build(this)
        } else if (screen != Screen.CAR_04 && tab == Tab.MESSAGES) {
            TabScreens.messages(this, ((if (receivedSession == repo.session.value?.sessionId) receivedLog else emptyList()) + heldShown).distinctBy { it.messageId }.sortedByDescending { it.createdAt })
        } else if (screen != Screen.CAR_04 && tab == Tab.MAP) {
            TabScreens.map(this, repo.session.value, repo.myCarId)
        } else if (screen != Screen.CAR_04 && tab == Tab.HELP) {
            TabScreens.help(this)
        } else if (screen != Screen.CAR_04 && tab !in setOf(Tab.GROUP, Tab.SPACE)) {
            placeholder(tab)
        } else {
            ScreenViews.build(
                this, screen, repo.session.value, repo.myCarId, ::onAction,
                drafts = drafts, driverName = BuildConfig.KKIRI_DRIVER_NAME, error = errorMessage,
                speaking = VoiceHubs.get(this).nowSpeaking.value,
                heldCount = VoiceHubs.get(this).heldCount.value,
                held = heldShown,
                destination = VoiceHubs.get(this).currentDestination.value,
                busy = creating,
                tab = tab,
            )
        }
        container.removeAllViews()
        container.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Rail.build(this@MainActivity, if (screen == Screen.CAR_04) null else tab, enabledTabs(screen), ::onTab))
                addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))
            },
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        if (enteringDriving) maybeShowFirstDriveTip()
        if (screen != Screen.CAR_04) dismissTip()
        tipView?.let { container.addView(it, tipParams()) }
        applySteering()
        if (enteringParked) loadHeld()
        voicePanel?.visibility = if (screen == Screen.CAR_04) View.VISIBLE else View.GONE
    }

    // ───────── V2-S9: 로딩 애니메이션 ─────────

    /** 열 때마다 A→B→C→D→E 순서로 하나씩. 주행 중이면 애니메이션 없이 로고만 짧게 */
    private fun playSplash(root: FrameLayout) {
        val prefs = getSharedPreferences("kkiri_ui", MODE_PRIVATE)
        val variant = (prefs.getInt(KEY_SPLASH_LAST, -1) + 1).mod(5)
        prefs.edit().putInt(KEY_SPLASH_LAST, variant).apply()
        val driving = drive.driveState.value == DriveState.DRIVING
        val ms = resources.getInteger(if (driving) R.integer.kk_splash_driving_ms else R.integer.kk_splash_ms).toLong()
        val view = SplashView(this, variant, driving).apply { isClickable = true }
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        ValueAnimator.ofFloat(0f, ms / 1000f).apply {
            duration = ms
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { view.setTime(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { root.removeView(view) }
            })
            start()
        }
    }

    // ───────── V2-S8: 첫 주행 안내 말풍선(처음 한 번만, 버튼 없이 자동으로 닫힘) ─────────

    private fun tipParams() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.TOP or Gravity.START
        leftMargin = Kk.px(this@MainActivity, R.dimen.kk_rail_width) + dp(6)
        topMargin = dp(172)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun maybeShowFirstDriveTip() {
        val prefs = getSharedPreferences("kkiri_ui", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FIRST_DRIVE_TIP_SEEN, false) || tipView != null) return
        prefs.edit().putBoolean(KEY_FIRST_DRIVE_TIP_SEEN, true).apply()
        val ink = R.color.kk_btn_ink
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                setColor(Kk.color(this@MainActivity, R.color.kk_btn))
                alpha = 204
                cornerRadius = dp(24).toFloat()
            }
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.kk_ic_mic)
                    setColorFilter(Kk.color(this@MainActivity, ink), PorterDuff.Mode.SRC_IN)
                },
                LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(20) },
            )
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(Kk.text(this@MainActivity, getString(R.string.space_tip_title), Kk.TextStyleKk.TITLE_S_BOLD, ink))
                addView(Kk.text(this@MainActivity, getString(R.string.space_tip_body), Kk.TextStyleKk.CAPTION, ink))
            })
        }
        val tail = View(this).apply {
            val p = Path().apply { moveTo(20f, 0f); lineTo(0f, 17f); lineTo(20f, 34f); close() }
            background = ShapeDrawable(PathShape(p, 20f, 34f)).apply {
                paint.color = Kk.color(this@MainActivity, R.color.kk_btn)
                paint.alpha = 204
            }
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(tail, LinearLayout.LayoutParams(dp(20), dp(34)).apply { topMargin = dp(56) })
            addView(body)
            isClickable = false
        }
        tipView = view
        Handler(Looper.getMainLooper()).postDelayed({
            if (tipView === view) dismissTip()
        }, resources.getInteger(R.integer.kk_first_drive_tip_ms).toLong())
    }

    private fun dismissTip() {
        val v = tipView ?: return
        tipView = null
        (v.parent as? FrameLayout)?.removeView(v)
    }

    /**
     * 공간 그림의 내 차 기울기를 지금 핸들 각도에 맞춘다(명세 6.1).
     * 정차(PARKED) 중에만 기울이고, 주행 중이거나 상태를 모르면 0(정지 그림)으로 둔다.
     */
    /** 서버에 보낼 핸들 단계: 정차 중에만 실제 값, 아니면 0 (명세 6.3) */
    private fun currentSteerStep(angleDeg: Float, state: DriveState): Int =
        if (state == DriveState.PARKED) {
            SpaceView.tiltSteps(angleDeg, resources.getInteger(R.integer.kk_steer_step_deg), resources.getInteger(R.integer.kk_steer_max_steps))
        } else {
            0
        }

    private fun applySteering() {
        val space = container.findViewById<SpaceView>(R.id.kk_space) ?: return
        val steps = if (drive.driveState.value == DriveState.PARKED) {
            SpaceView.tiltSteps(
                steering.angleDeg.value,
                resources.getInteger(R.integer.kk_steer_step_deg),
                resources.getInteger(R.integer.kk_steer_max_steps),
            )
        } else {
            0
        }
        // 실제 확인(Virtual Console: -540=왼쪽 끝, +540=오른쪽 끝): 그림 회전 방향이 반대라 부호를 뒤집는다
        space.setTilt(-(steps * resources.getInteger(R.integer.kk_steer_tilt_per_step_deg)).toFloat())
        space.setOthersTilt(drive.driveState.value == DriveState.PARKED) // 다른 차의 기울임(R-47)은 내 차가 정차 중일 때만
    }

    /**
     * 화면 버튼 처리 (SessionController가 실제 서버 또는 목업을 부른다).
     * - [세션 만들기]: R-02. 이름은 CAR-01 ② 입력칸 값, 목적지는 VoiceHub.currentDestination.
     * - [합류]: R-05. 접속코드는 CAR-03 ① 입력칸(6자리). 성공하면 입력칸을 비운다.
     *   [목적지로 설정하고 합류]면 목적지가 있을 때만 VoiceHub.applyDestination (03_SCREENS CAR-03 ②).
     * - [세션 종료]: R-09 → CAR-01.
     * - 실패하면 03_SCREENS 문구를 화면 아래에 띄우고 화면은 그대로 둔다(토스트는 Pleos에서 안 보임).
     */
    private fun onAction(action: UiAction) {
        when (action) {
            is UiAction.Go -> show(action.screen)
            is UiAction.CreateSession -> request(SessionOp.CREATE) {
                controller.create(action.name, VoiceHubs.get(this).currentDestination.value)
                    .onSuccess {
                        drafts.sessionName = null
                        show(Screen.CAR_02)
                    }
            }
            is UiAction.Join -> request(SessionOp.JOIN) {
                controller.join(action.joinCode, action.applyDestination)
                    .onSuccess {
                        drafts.joinCode = ""
                        if (action.applyDestination) {
                            repo.session.value?.destination?.let { VoiceHubs.get(this).applyDestination(it) }
                        }
                        show(Screen.CAR_05)
                    }
            }
            UiAction.EndSession -> request(SessionOp.END) {
                controller.end().onSuccess { show(Screen.CAR_01) }
            }
            UiAction.ShowQr -> showQr()
            UiAction.OpenDeco -> show(current, Tab.DECO)
            UiAction.OpenMessages -> show(current, Tab.MESSAGES)
            // R-22: 성공하면 RemoteSessionRepository가 응답의 mutes로 session을 바꾸고, 화면은 session 구독으로 다시 그려진다
            is UiAction.ToggleMute -> request({ R.string.error_retry_later }) {
                repo.setMute(action.targetCarId, action.mute)
            }
        }
    }

    /**
     * CAR-05 ② 보관 메시지: R-23으로 받아 화면에 더하고, 보여 준 것은 R-24로 확인 처리한다
     * (CAR-A가 부름 — CAR-B 팀원 확인 2026-11-12). 확인한 메시지도 이번 정차 화면에서는 계속 보인다.
     */
    private fun loadHeld() {
        if (heldLoading || repo.session.value == null) return
        heldLoading = true
        lifecycleScope.launch {
            try {
                val list = repo.heldMessages().getOrElse {
                    Log.w(TAG, "R-23 실패", it)
                    return@launch
                }
                if (list.isEmpty()) return@launch
                heldShown = (heldShown + list).distinctBy { it.messageId }
                if (current == Screen.CAR_05) show(current)
                repo.ackHeld(list.map { it.messageId }).onFailure { Log.w(TAG, "R-24 실패", it) }
            } finally {
                heldLoading = false
            }
        }
    }

    /** 세션 동작(만들기·합류·종료) 요청. 실패 문구는 03_SCREENS 기준 [errorRes] */
    private fun request(op: SessionOp, block: suspend () -> Result<Unit>) =
        request({ errorRes(op, it) }, op == SessionOp.CREATE, block)

    /**
     * 서버 요청 하나를 실행한다. 실패하면 지금 화면 아래에 오류 문구를 띄운다.
     * [showProgress]가 true(세션 만들기)일 때만 CAR-01 카드를 "만드는 중"으로 그린다.
     * 종료·합류 요청 중에는 그리지 않는다(종료 직후 CAR-01이 "만드는 중"으로 남던 문제, V2-S7).
     */
    private fun request(errorTextRes: (Throwable) -> Int, showProgress: Boolean = false, block: suspend () -> Result<Unit>) {
        if (busy) return
        busy = true
        creating = showProgress
        errorMessage = null
        if (showProgress && current == Screen.CAR_01) show(current)
        lifecycleScope.launch {
            try {
                block().onFailure {
                    Log.w(TAG, "요청 실패", it)
                    errorMessage = getString(errorTextRes(it))
                }
            } finally {
                busy = false
                val wasCreating = creating
                creating = false
                // 끝난 뒤 한 번 다시 그린다: 실패면 오류 줄, 성공이면 진행 표시를 지운다
                if (wasCreating || errorMessage != null) show(current)
            }
        }
    }

    /** 03_SCREENS 오류 문구. 명세에 없는 경우는 WEB과 같은 "잠시 후 다시 시도해 주세요" */
    @StringRes
    private fun errorRes(op: SessionOp, e: Throwable): Int = when (op) {
        SessionOp.CREATE -> R.string.car01_error_create
        SessionOp.END -> R.string.car05_error_end
        SessionOp.JOIN -> when ((e as? ApiException)?.code) {
            "JOIN_CODE_INVALID", "SESSION_NOT_FOUND" -> R.string.car03_error_not_found
            "SESSION_ENDED" -> R.string.car03_error_ended
            else -> R.string.error_retry_later
        }
    }

    private fun showQr() {
        val smallWindow = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (!smallWindow) {
            show(Screen.CAR_02)
            return
        }
        closeQrDialog()
        qrDialog = AlertDialog.Builder(this)
            .setView(ScreenViews.build(this, Screen.CAR_02, repo.session.value, repo.myCarId, act = {}, inModal = true))
            .setCancelable(true)
            .create()
            .also {
                it.setCanceledOnTouchOutside(true)
                it.show()
            }
    }

    private fun closeQrDialog() {
        qrDialog?.dismiss()
        qrDialog = null
    }

    override fun onDestroy() {
        closeQrDialog()
        super.onDestroy()
    }

    private fun screenOf(name: String?): Screen? = Screen.entries.firstOrNull { it.name == name }

    private companion object {
        const val TAG = "KKIRI-Main"
        const val KEY_SCREEN = "screen"
        const val KEY_BEFORE_DRIVING = "beforeDriving"
        const val KEY_TAB = "tab"
        const val KEY_FIRST_DRIVE_TIP_SEEN = "first_drive_tip_seen"
        const val KEY_SPLASH_LAST = "splash_last"
        const val KEY_TAB_BEFORE_DRIVING = "tabBeforeDriving"
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
