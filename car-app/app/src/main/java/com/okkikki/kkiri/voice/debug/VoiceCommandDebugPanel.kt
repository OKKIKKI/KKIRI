package com.okkikki.kkiri.voice.debug

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.okkikki.kkiri.BuildConfig
import com.okkikki.kkiri.voice.VoiceCommandSources

/**
 * 음성 명령 6종(버튼 9개) 디버그 패널 (HB-F-COM-05 목업, docs/03_SCREENS.md CAR-04 "디버그 빌드에서만").
 * Kotlin 코드만으로 만든 View이며 res·매니페스트·Activity를 쓰지 않는다.
 *
 * - 명령을 내기만 하고 구독하지 않는다. 코루틴도 쓰지 않으므로 화면이 사라질 때 해제할 구독이 없다.
 * - 되묻기 UI는 없다(명세 5절-7의 되묻기는 VoiceHub 단계).
 * - 상태줄의 결과는 "발생 결과"다. 수집·처리 성공을 뜻하지 않는다.
 */
object VoiceCommandDebugPanel {
    private const val TAG = "KKIRI.VoiceDebug"

    /** 디버그 빌드에서만 패널을 만든다. release에서는 null. CAR-A가 화면에 붙이는 진입점 */
    fun createOrNull(context: Context): View? {
        if (!BuildConfig.DEBUG) return null
        return build(context, VoiceCommandSources.debug)
    }

    private fun build(context: Context, source: DebugVoiceCommandSource): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(context).apply { text = "[디버그] 음성 명령 목업 (COM-05, D-05 확정 전)" })

        // 대상: "ALL"(명세), car_B(fixture — 실제 세션 carId 아님), 직접 입력
        val targetGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        val presetIds = DebugCommandCatalog.presetTargets.associateBy { View.generateViewId() }
        presetIds.forEach { (id, target) ->
            targetGroup.addView(RadioButton(context).apply {
                this.id = id
                text = when (target.origin) {
                    DebugTargetOrigin.SPEC_ALL -> "${target.value} (전체)"
                    DebugTargetOrigin.FIXTURE -> "${target.value} (fixture — 실제 세션 carId 아님)"
                    DebugTargetOrigin.MANUAL -> target.value
                }
            })
        }
        val manualId = View.generateViewId()
        targetGroup.addView(RadioButton(context).apply { id = manualId; text = "직접 입력한 carId" })
        targetGroup.check(presetIds.keys.first())
        root.addView(targetGroup)

        val manualInput = EditText(context).apply {
            hint = "carId 직접 입력 (실제 세션 값)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(manualInput)

        val textInput = EditText(context).apply {
            hint = "보낼 글자 (SendTo text)"
            setText(DebugCommandCatalog.FIXTURE_TEXT)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(textInput)

        val status = TextView(context).apply { text = "상태: 아직 누르지 않음" }

        DebugButton.entries.forEach { button ->
            root.addView(Button(context).apply {
                text = button.label
                setOnClickListener {
                    val checked = targetGroup.checkedRadioButtonId
                    val target = if (checked == manualId) {
                        DebugCommandCatalog.manualTarget(manualInput.text.toString())
                    } else {
                        presetIds[checked]
                    }
                    val message = when (val built = DebugCommandCatalog.build(button, target, textInput.text.toString())) {
                        BuildResult.BlankText -> "명령 안 냄: 글자가 비었음"
                        BuildResult.NoTarget -> "명령 안 냄: 대상 없음"
                        is BuildResult.Command -> describe(source.emit(built.command), target)
                    }
                    Log.i(TAG, message)
                    status.text = "상태: $message"
                }
            })
        }
        // 동작 확인용 입력(fixtures 아님). VoiceHub가 start된 뒤에만 전달된다
        root.addView(TextView(context).apply { text = "[디버그] 테스트 입력 — fixtures 아님" })
        listOf(
            "긴급 메시지 받기(SPEAK, 다른 차)" to { DebugTestInputs.emitUrgentMessage() },
            "녹음형 무전 받기(SPEAK, 목업 클립)" to { DebugTestInputs.emitSpeakVoice() },
            "내 차 대상 경유지 제안 받기" to { DebugTestInputs.emitProposalToMyCar() },
        ).forEach { (label, action) ->
            root.addView(Button(context).apply {
                text = label
                setOnClickListener {
                    val sent = action()
                    val message = if (sent) "테스트 입력 보냄: $label" else "테스트 입력 버려짐(VoiceHub 구독 없음): $label"
                    Log.i(TAG, message)
                    status.text = "상태: $message"
                }
            })
        }

        root.addView(status)

        return ScrollView(context).apply { addView(root) }
    }

    private fun describe(result: EmitResult, target: DebugTarget?): String {
        val origin = target?.let { " [대상 출처: ${it.origin}]" } ?: ""
        return when (result) {
            is EmitResult.NoSubscriber ->
                "버려짐(구독자 없음, 다시 보내지 않음): ${result.command}$origin"
            is EmitResult.BufferFull ->
                "버려짐(버퍼 가득, 구독자 ${result.subscribers}): ${result.command}$origin"
            is EmitResult.Accepted -> {
                val warn = if (result.duplicateRisk) " ⚠ 구독자 ${result.subscribers}개 — 중복 처리 위험" else ""
                "버퍼 수용(구독자 ${result.subscribers}, 수집·처리 확인 아님): ${result.command}$origin$warn"
            }
        }
    }
}
