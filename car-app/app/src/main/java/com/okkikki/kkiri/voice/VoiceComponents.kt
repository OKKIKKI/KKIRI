package com.okkikki.kkiri.voice

import android.content.Context
import com.okkikki.kkiri.BuildConfig
import com.okkikki.kkiri.contract.NaviAdapter
import com.okkikki.kkiri.contract.TtsAdapter
import com.okkikki.kkiri.contract.VoiceRecorder
import com.okkikki.kkiri.navi.MockNaviAdapter
import com.okkikki.kkiri.navi.PleosNaviAdapter
import com.okkikki.kkiri.ptt.ClipAudioFocus
import com.okkikki.kkiri.ptt.MediaPlayerClipPlayer
import com.okkikki.kkiri.ptt.MockVoiceRecorder
import com.okkikki.kkiri.voice.queue.SpeechQueue
import com.okkikki.kkiri.voice.tts.AndroidTts
import com.okkikki.kkiri.voice.tts.LogTts
import com.okkikki.kkiri.voice.tts.PleosTts

/**
 * VoiceHubImpl이 쓰는 구현과 설정을 고르는 **유일한 생성 지점**(CAR-B 내부).
 * 목업 ↔ 실제 교체는 여기 한 줄만 바꾼다(05_DEV_RULES 4절).
 *
 * | 대상 | 지금 | 근거·바꾸는 조건 |
 * | 낭독 출력 | SpeechQueue(팀원, voice/queue/) | D-21. 확인 질문은 ConfirmationSpeechOutput.announceAndAwait |
 * | TTS | **PleosTts**(Pleos TextToSpeech SDK 3.3.1, ON_DEVICE·ko-KR). 초기화 실패·5초 무응답·치명 오류(명세 5절-9) 뒤로는 LogTts로 대신 | D-26(CONFIRMED, 명세 v1.12 5절-9). 빌드 설정은 CAR-A. 되돌리기: 아래 `tts =`를 `androidTts(appContext)`로 |
 * | 클립 재생 | MediaPlayerClipPlayer(NOTIFICATION/SPEECH, 일시 포커스) | — |
 * | 포커스 일시 상실 상한 | 임시 10초(아래, 클립·TTS 공통, PM 승인 대기) | 승인·빌드 필드가 생기면 교체 |
 * | 내비 | **PleosNaviAdapter**(Pleos NaviHelper 3.0.5, 시연 2026-11-15). 빌드 옵션 -Pkkiri.realNavi=false면 MockNaviAdapter | D-08(OPEN) 시연용 적용 |
 * | 녹음 | MockVoiceRecorder(사인파, 마이크 미사용) | D-07 CONFIRMED 시 교체 |
 * | 확인 제한 시간 | BuildConfig.CONFIRM_TIMEOUT_SEC(기본 10초) | 명세 1.2, Q-PM-003(CAR-A #43) |
 * | TTS 초기화 제한 시간 | 5초(아래, Q-CAR-B-002 PM 승인 2026-11-12: 이름 `TTS_INIT_TIMEOUT_SEC`, 기본 5) | CAR-A 빌드 필드가 생기면 `BuildConfig.TTS_INIT_TIMEOUT_SEC`로 교체 |
 */
internal class VoiceComponents(
    val speechOutput: ConfirmationSpeechOutput,
    val tts: TtsAdapter,
    val clipPlayer: ClipPlayer,
    val navi: NaviAdapter,
    val recorder: VoiceRecorder,
    /**
     * 확인 제한 시간(명세 1.2 `CONFIRM_TIMEOUT_SEC`, D-24 기본 10초, 질문 낭독 종료 시점부터).
     * 빌드 필드(Q-PM-003, CAR-A #43): Gradle `-Pkkiri.confirmTimeoutSec=<초>`로 바꾼다(명세 v1.11 1.2).
     */
    val confirmConfig: ConfirmConfig,
) {
    /**
     * 앱에서 쓰는 구성. **구현을 고르는 곳은 여기 하나다**(위 표).
     * 주 생성자는 고른 구현을 담기만 한다(레포 밖 JVM 검증에서 contract 구현을 바꿔 끼우는 경계).
     * TTS 초기화 신호가 [MOCK_TTS_INIT_TIMEOUT_SEC] 안에 오지 않으면 그 인스턴스는 LogTts로 읽는다(PleosTts·AndroidTts 같은 정책).
     */
    constructor(appContext: Context) : this(
        speechOutput = SpeechQueue(),
        tts = pleosTts(appContext),
        clipPlayer = MediaPlayerClipPlayer(appContext, focusSuspendLimitMs = MOCK_FOCUS_SUSPEND_LIMIT_SEC * 1000L),
        navi = if (BuildConfig.KKIRI_REAL_NAVI) PleosNaviAdapter(appContext) else MockNaviAdapter(),
        recorder = MockVoiceRecorder(appContext),
        confirmConfig = ConfirmConfig(timeoutMs = BuildConfig.CONFIRM_TIMEOUT_SEC * 1000L),
    )

    private companion object {
        /**
         * TTS 초기화 제한 시간(초, PleosTts·AndroidTts 공통). 이름 `TTS_INIT_TIMEOUT_SEC`·기본 5초는
         * **Q-CAR-B-002로 PM 승인(PM, 2026-11-12 17:36)**. 빌드 필드는 아직 없어서 승인된 기본값을 여기 둔다.
         * CAR-A가 `buildConfigField("int", "TTS_INIT_TIMEOUT_SEC", …)`를 추가하면 이 값 대신 그 필드를 쓴다(D-17: 시간 값은 설정으로).
         */
        const val MOCK_TTS_INIT_TIMEOUT_SEC = 5L

        /**
         * 클립·TTS가 오디오 포커스 일시 상실 뒤 gain을 기다리는 최대 시간(초). **임시 대체**(2026-11-12 CAR-B 지시 #1·#3,
         * 팀원 확인: 클립이 실패로 끝나도 대기열은 다음으로 넘어감). 넘으면 그 클립·문장은 실패로 끝나 대기열이 진행된다.
         * 이름·값은 PM 승인 대기(D-17), 빌드 필드가 생기면 교체한다.
         */
        const val MOCK_FOCUS_SUSPEND_LIMIT_SEC = 10L

        /**
         * 지금 쓰는 TTS. 오디오 속성은 SDK 기본값(팀원이 소리를 확인한 설정)이고 앱이 포커스를 따로 요청하지 않는다
         * (재생은 Pleos 서비스(caas)가 하며, 서비스가 포커스를 어떻게 다루는지 미확인 — 기기 확인 항목).
         */
        fun pleosTts(appContext: Context): TtsAdapter =
            PleosTts(appContext, fallback = LogTts(), initTimeoutMs = MOCK_TTS_INIT_TIMEOUT_SEC * 1000L)

        /**
         * D-24 AndroidTts(되돌릴 때 위 생성자의 `tts =`를 이것으로). 클립과 같은 용도(NOTIFICATION/SPEECH)·포커스 요청 방식,
         * 엔진으로 실제 읽을 때만 포커스를 요청한다.
         */
        @Suppress("unused")
        fun androidTts(appContext: Context): AndroidTts {
            val audio = ClipAudioFocus(appContext)
            return AndroidTts(
                appContext,
                fallback = LogTts(),
                initTimeoutMs = MOCK_TTS_INIT_TIMEOUT_SEC * 1000L,
                audioAttributes = audio.attributes,
                focus = audio.asSpeechFocus(),
                focusSuspendLimitMs = MOCK_FOCUS_SUSPEND_LIMIT_SEC * 1000L,
            )
        }
    }
}
