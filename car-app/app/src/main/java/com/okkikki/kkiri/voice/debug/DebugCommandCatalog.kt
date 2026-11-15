package com.okkikki.kkiri.voice.debug

import com.okkikki.kkiri.contract.VoiceCommand

/** 대상 값이 어디서 왔는지. fixture 값과 실제 세션 값을 섞지 않으려고 구분한다. */
enum class DebugTargetOrigin {
    /** 명세 6절의 "ALL" */
    SPEC_ALL,

    /** docs/fixtures 의 carId. 실제 세션의 carId가 아니다(실제 carId는 R-01에서 서버가 만든다) */
    FIXTURE,

    /** 사람이 직접 입력한 carId(실제 세션 값을 넣는 용도). 존재 여부는 확인하지 않는다 */
    MANUAL,
}

data class DebugTarget(val value: String, val origin: DebugTargetOrigin)

/** 디버그 패널 버튼 9개. 타입은 contract의 VoiceCommand 6종이다. */
enum class DebugButton(val label: String, val needsTarget: Boolean) {
    SEND_TEXT("말 전하기(글자) — SendTo(target, text)", true),
    SEND_VOICE("녹음형 무전 — SendTo(target, null)", true),
    MUTE_ON("음소거 켜기 — Mute(target, true)", true),
    MUTE_OFF("음소거 끄기 — Mute(target, false)", true),
    ACCEPT_PROPOSAL("제안 수락 — AcceptProposal", false),
    DECLINE_PROPOSAL("제안 거절 — DeclineProposal", false),
    UNDO_WAYPOINT("되돌리기 — UndoWaypoint", false),
    MUSIC_LOCK("음악 잠금 — MusicLock(true)", false),
    MUSIC_UNLOCK("음악 잠금 해제 — MusicLock(false)", false),
}

/** 버튼 → 명령 변환 결과 */
sealed interface BuildResult {
    data class Command(val command: VoiceCommand) : BuildResult

    /** SEND_TEXT인데 글자가 비었다(공백만 포함). 명령을 내지 않으며 null로 바꾸지 않는다 */
    data object BlankText : BuildResult

    /** 대상이 필요한 버튼인데 대상이 없다 */
    data object NoTarget : BuildResult
}

/**
 * 버튼 → [VoiceCommand] 변환과 입력 검사. android.* 에 의존하지 않는다(JVM 테스트 대상).
 * 되묻기(명세 5절-7)는 명령 처리(VoiceHub) 단계의 일이라 여기서 하지 않는다.
 */
object DebugCommandCatalog {
    const val TARGET_ALL = "ALL"

    /** docs/fixtures 기준: 내 차 car_A(R-01 예시·hostCarId), 다른 차 car_B(B차·현우) */
    const val FIXTURE_OTHER_CAR_ID = "car_B"

    /** docs/fixtures R-20 예시 문구. 글자 입력칸의 기본값 */
    const val FIXTURE_TEXT = "기름 넣고 갈게"

    /** 미리 고를 수 있는 대상. 실제 세션 대상 목록 조회는 하지 않는다(SessionRepository 구현 없음) */
    val presetTargets: List<DebugTarget> = listOf(
        DebugTarget(TARGET_ALL, DebugTargetOrigin.SPEC_ALL),
        DebugTarget(FIXTURE_OTHER_CAR_ID, DebugTargetOrigin.FIXTURE),
    )

    /** 직접 입력한 carId. 앞뒤 공백만 떼고, 비면 null */
    fun manualTarget(input: String): DebugTarget? =
        input.trim().takeIf { it.isNotEmpty() }?.let { DebugTarget(it, DebugTargetOrigin.MANUAL) }

    fun build(button: DebugButton, target: DebugTarget?, text: String): BuildResult {
        if (button.needsTarget && target == null) return BuildResult.NoTarget
        val t = target?.value
        val command: VoiceCommand = when (button) {
            DebugButton.SEND_TEXT -> {
                if (text.isBlank()) return BuildResult.BlankText
                VoiceCommand.SendTo(t!!, text)
            }
            DebugButton.SEND_VOICE -> VoiceCommand.SendTo(t!!, null)
            DebugButton.MUTE_ON -> VoiceCommand.Mute(t!!, true)
            DebugButton.MUTE_OFF -> VoiceCommand.Mute(t!!, false)
            DebugButton.ACCEPT_PROPOSAL -> VoiceCommand.AcceptProposal
            DebugButton.DECLINE_PROPOSAL -> VoiceCommand.DeclineProposal
            DebugButton.UNDO_WAYPOINT -> VoiceCommand.UndoWaypoint
            DebugButton.MUSIC_LOCK -> VoiceCommand.MusicLock(true)
            DebugButton.MUSIC_UNLOCK -> VoiceCommand.MusicLock(false)
        }
        return BuildResult.Command(command)
    }
}
