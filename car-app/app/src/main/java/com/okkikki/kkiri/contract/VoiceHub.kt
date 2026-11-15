package com.okkikki.kkiri.contract

import kotlinx.coroutines.flow.StateFlow

data class SpeakerInfo(val carId: String, val carName: String, val name: String, val kind: MessageKind)

/** 명세 6절. CAR-B가 구현하고 CAR-A가 쓴다. 음성 쪽 전체의 입구이며 CAR-A는 이것만 안다. */
interface VoiceHub {
    /** 지금 낭독·재생 중인 발화자 (화면 CAR-04 ② 표시용) */
    val nowSpeaking: StateFlow<SpeakerInfo?>

    /** carId별 보관 메시지 수 */
    val heldCount: StateFlow<Map<String, Int>>

    /** 지금 내비 목적지 (CAR-01 ① 표시용). 모르면 null */
    val currentDestination: StateFlow<Place?>

    fun start(repo: SessionRepository, drive: DriveStateProvider)
    fun stop()

    /** 화면 버튼(정차 중)에서 무전 시작 */
    fun startPtt(target: String)

    /** 합류 시 "목적지로 설정하고 합류"를 눌렀을 때 (CAR-03 ②) */
    fun applyDestination(place: Place)
}
