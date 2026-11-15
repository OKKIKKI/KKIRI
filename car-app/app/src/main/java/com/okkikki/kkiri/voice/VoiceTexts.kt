package com.okkikki.kkiri.voice

/**
 * CAR-B(CAR-B 담당)가 읽는 문구를 모은 곳(이곳에만 둔다).
 * 기준: docs/04_INTERFACE_SPEC.md 5절-6 문구와 **5.1 표(v1.9, D-24)** — 글자 그대로. 표에 없는 문구가 필요하면 [승인 요청].
 * 낭독 대기열·발화 소개·음소거 보관 안내·음악 추가 문구(팀원, voice/queue/SpeechTexts)는 여기 두지 않는다.
 */
internal object VoiceTexts {

    // ── 명세 5절-6 ─────────────────────────────────────────
    /** 5절-6: "{이름}님이 {장소}를 추가했어요" */
    fun waypointAdded(name: String, place: String) = "${name}님이 ${place}를 추가했어요"

    /** 5절-6: "{이름}님이 {장소}를 제안했어요. 추가할까요?" */
    fun proposalAsk(name: String, place: String) = "${name}님이 ${place}를 제안했어요. 추가할까요?"

    // ── 명세 5.1 표 ─────────────────────────────────────────
    /** 5.1 경유지 삭제(내 차 waypoint.removed): "{장소}, 경유지에서 뺐어요" */
    fun waypointRemoved(place: String) = "$place, 경유지에서 뺐어요"

    /** 5.1 음소거 확인 "{대상}, 음소거할까요?" / 음소거 해제 확인 "{대상}, 음소거 풀까요?" */
    fun confirmMute(target: String, muted: Boolean) =
        if (muted) "$target, 음소거할까요?" else "$target, 음소거 풀까요?"

    /** 5.1: `{대상}`이 ALL이면 "모두"로 읽는다 */
    const val ALL_LABEL = "모두"

    /** 5.1 되돌리기 확인 */
    const val CONFIRM_UNDO = "경유지 변경을 취소할까요?"

    /** 5.1 취소 */
    const val CONFIRM_DECLINED = "취소했어요"

    /** 5.1 확인 시간 초과 */
    const val CONFIRM_TIMEOUT = "답이 없어 취소했어요"

    /** 5.1 응답할 제안 없음 */
    const val NO_PROPOSAL = "지금은 답할 제안이 없어요"

    /** 5.1 녹음 중복 */
    const val PTT_BUSY = "이미 무전을 녹음 중이에요"

    // 5.1 실패 안내
    const val SEND_FAILED = "메시지가 전송되지 않았어요"
    const val RECORD_FAILED = "녹음이 되지 않았어요"
    const val VOICE_SEND_FAILED = "무전이 전송되지 않았어요"
    const val MUTE_FAILED = "음소거가 바뀌지 않았어요"
    const val UNDO_FAILED = "경유지 변경이 취소되지 않았어요"
    const val MUSIC_LOCK_FAILED = "음악 잠금이 바뀌지 않았어요"
    const val PROPOSAL_FAILED = "답변이 전달되지 않았어요"
    const val NAVI_FAILED = "내비에 반영되지 않았어요"
}
