package com.okkikki.kkiri.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import com.okkikki.kkiri.R
import com.okkikki.kkiri.contract.CarStatus
import com.okkikki.kkiri.contract.Session
import com.okkikki.kkiri.contract.SpeakerInfo
import com.okkikki.kkiri.ui.theme.Kk

/**
 * 공간 탭 화면(Figma 확정본 CAR-05 정차 중 / CAR-04 주행 중): 헤더 "공간" + 상태 칩 + 광장.
 * 정차 중에만 이름 칩을 눌러 음소거 말풍선을 연다. 주행 중에는 터치를 받지 않는다.
 */
object SpaceScreens {

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun build(
        context: Context,
        driving: Boolean,
        session: Session,
        myCarId: String?,
        speaking: SpeakerInfo?,
        heldCount: Map<String, Int>,
        act: (UiAction) -> Unit,
    ): View {
        val marks = session.cars.map { c ->
            SpaceView.Mark(
                carId = c.carId,
                carName = c.displayName,
                driverName = c.driverName,
                isMe = c.carId == myCarId,
                status = c.status,
                muted = session.mutes.any { it.byCarId == myCarId && it.targetCarId == c.carId },
                heldCount = heldCount[c.carId] ?: 0,
                look = c.look,
            )
        }
        val statusText = if (driving) {
            context.getString(R.string.space_status_driving)
        } else {
            context.getString(
                R.string.space_status_parked,
                session.cars.count { it.status == CarStatus.CONNECTED },
                session.cars.size,
            )
        }
        val chip = Kk.small(context, statusText, R.color.kk_fg).apply {
            background = GradientDrawable().apply {
                setColor(Kk.color(context, R.color.kk_card))
                cornerRadius = 999f * context.resources.displayMetrics.density
            }
            setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 12))
        }
        val plaza = SpaceView(context).apply {
            id = R.id.kk_space
            setState(marks, speaking?.carId, interactive = !driving)
            onMuteToggle = { carId, mute -> act(UiAction.ToggleMute(carId, mute)) }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Kk.color(context, R.color.kk_bg))
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 48))
            addView(Kk.text(context, context.getString(R.string.tab_space), Kk.TextStyleKk.TITLE_S_BOLD))
            addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 24)
            })
            val now = session.music.nowPlaying
            if (session.music.connected && now != null) {
                addView(Kk.small(context, context.getString(R.string.car05_now_playing, now.title, now.artist), R.color.kk_fg_muted),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, 12) })
            }
            addView(plaza, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(context, 24)
            })
        }
    }
}
