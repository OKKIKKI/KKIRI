package com.okkikki.kkiri.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.okkikki.kkiri.contract.DriveState
import com.okkikki.kkiri.contract.Ignition
import com.okkikki.kkiri.state.MockDriveStateProvider
import com.okkikki.kkiri.state.MockSteeringProvider
import com.okkikki.kkiri.state.PleosVehicle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 디버그 빌드에서만 보이는 주행 상태 토글 (D-06 목업 = 디버그 토글)과 핸들 각도 버튼(명세 6.1 목업).
 * 화면 문구가 아니라 개발용 버튼이라 strings.xml에 넣지 않는다. 출시 빌드에는 나오지 않는다.
 */
object DebugDrivePanel {

    fun build(
        context: Context,
        owner: LifecycleOwner,
        drive: MockDriveStateProvider,
        steering: MockSteeringProvider,
    ): View {
        val status = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(16, 16, 16, 16)
            addView(status)
            // 실차값(Virtual Console)이 디버그 입력을 덮어쓰지 않게 끄는 스위치. 기본은 실차값 사용
            addView(row(context, Button(context).apply {
                isAllCaps = false
                textSize = 14f
                fun label() { text = if (PleosVehicle.realEnabled) "실차값 켬 (누르면 끔)" else "실차값 끔 (누르면 켬)" }
                label()
                setOnClickListener { PleosVehicle.setRealEnabled(!PleosVehicle.realEnabled); label() }
            }))
            addView(row(context,
                button(context, "주행") { drive.setDriveState(DriveState.DRIVING) },
                button(context, "정차(P)") { drive.setDriveState(DriveState.PARKED) },
            ))
            addView(row(context,
                button(context, "시동 켬") { drive.setIgnition(Ignition.ON) },
                button(context, "시동 끔") { drive.setIgnition(Ignition.OFF) },
            ))
            addView(row(context,
                button(context, "핸들 ◀") { steering.turn(-STEER_DEBUG_STEP_DEG) },
                button(context, "가운데") { steering.setAngle(0f) },
                button(context, "핸들 ▶") { steering.turn(STEER_DEBUG_STEP_DEG) },
            ))
        }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(drive.driveState, drive.ignition, steering.angleDeg) { d, i, a -> "[디버그] $d · 시동 $i · 핸들 ${a.toInt()}°" }
                    .collect { status.text = it }
            }
        }
        return panel
    }

    /** 디버그 버튼을 한 번 누를 때 돌리는 각도(도). 기울기 1단계(kk_steer_step_deg)와 같게 둔다 */
    private const val STEER_DEBUG_STEP_DEG = 150f

    private fun row(context: Context, vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun button(context: Context, text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setOnClickListener { onClick() }
    }
}
