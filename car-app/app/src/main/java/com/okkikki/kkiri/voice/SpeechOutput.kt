package com.okkikki.kkiri.voice

import com.okkikki.kkiri.contract.DeliveryMode
import com.okkikki.kkiri.contract.DriveStateProvider
import com.okkikki.kkiri.contract.Message
import com.okkikki.kkiri.contract.ServerEvent
import com.okkikki.kkiri.contract.SpeakerInfo
import com.okkikki.kkiri.contract.TtsAdapter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * CAR-B 내부 계약: VoiceHubImpl(CAR-B) → 낭독 출력(팀원, voice/queue/).
 * CAR-B 소유. 바꿀 때는 두 사람이 합의한다.
 * 소리를 내는 곳은 이 출력 하나뿐이다(TtsAdapter·ClipPlayer를 다른 곳에서 부르지 않는다).
 */
interface SpeechOutput {
    /** 지금 낭독·재생 중인 메시지의 발화자. 시스템 안내 중이거나 조용하면 null */
    val nowSpeaking: StateFlow<SpeakerInfo?>
    /** 보낸 차(sender.carId)별 HOLD 건수 */
    val heldCount: StateFlow<Map<String, Int>>

    /** VoiceHub.start마다 호출. 그 전에 reset이 불린다. scope가 취소되면 모든 작업이 끝나야 한다 */
    fun start(scope: CoroutineScope, ctx: SpeechContext)
    /** VoiceHub.stop·세션 종료. 대기열·보관·재생을 비우고 nowSpeaking=null, heldCount=빈 맵 */
    fun reset()

    /** mode는 SPEAK 또는 HOLD만 온다(DISPLAY는 CAR-B이 거름). SPEAK+VOICE는 clipFile이 받아진 경우에만 온다 */
    fun onMessage(item: IncomingMessage)
    /** 시스템 안내(경유지 안내·되묻기·실패 안내). 메시지 간격을 적용하지 않고 일반 메시지보다 먼저 읽는다 */
    fun announce(text: String, urgent: Boolean = false)

    /**
     * [announce]와 같고, 이 안내의 **낭독이 실제로 시작될 때마다** [onStart]를 부른다(2026-11-12 CAR-B·팀원 합의).
     * - pause 등으로 끊겼다가 다시 읽으면 다시 부른다. reset·폐기로 끝내 읽히지 않으면 부르지 않는다.
     * - 구현은 출력 내부 lock 밖에서 부른다(받는 쪽이 자기 lock을 잡는다).
     * - 기본 구현: 시작 시점을 모르는 출력용. 대기열에 넣은 직후 한 번 부른다(이 오버로드가 생기기 전 동작과 같다).
     *   SpeechQueue(팀원)가 실제 낭독 시작 시점으로 override하면 그 값이 쓰인다.
     */
    fun announce(text: String, urgent: Boolean, onStart: (() -> Unit)?) {
        announce(text, urgent)
        onStart?.invoke()
    }
    /** 팀원 담당 이벤트 전달(MusicQueueAdded, MuteChanged, SessionSnapshot 등). 필요 없는 것은 무시 */
    fun onServerEvent(event: ServerEvent)

    /** 녹음 시작 전. 진행 중인 소리를 멈추고 새 재생을 막는다. 끊긴 항목은 버리지 않고 resume 뒤 다시 읽는다 */
    fun pause()
    /** 녹음이 끝난 뒤(성공·실패·취소 모두) */
    fun resume()
}

data class IncomingMessage(
    val message: Message,
    val mode: DeliveryMode,
    /** session.cars에서 찾은 보낸 차 displayName. 못 찾으면 sender.name */
    val carName: String,
    /** SPEAK+VOICE이고 다운로드에 성공한 경우만 not null */
    val clipFile: File?,
)

data class SpeechContext(
    val tts: TtsAdapter,
    val clipPlayer: ClipPlayer,
    /** driveState(PARKED 전환 감지), isAppVisible(발화자 소개 문장) */
    val drive: DriveStateProvider,
    val myCarId: () -> String?,
    val isHostCar: () -> Boolean,
    /** carId → 차 표시 이름 */
    val carName: (String) -> String?,
    val config: SpeechConfig,
)

/** 시간 값은 여기로만 주입한다(D-17). minGapMs = BuildConfig.TTS_MIN_GAP_SEC * 1000 */
data class SpeechConfig(val minGapMs: Long)

/** 클립 재생 어댑터(CAR-B 구현). play는 재생이 끝나야 반환한다. stop되면 failure를 돌려준다 */
interface ClipPlayer {
    suspend fun play(file: File): Result<Unit>
    fun stop()
}
