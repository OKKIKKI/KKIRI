package com.okkikki.kkiri.voice

import com.okkikki.kkiri.contract.VoiceCommandSource
import com.okkikki.kkiri.voice.debug.DebugVoiceCommandSource

/**
 * CAR-B 내부 구성: 음성 명령 소스 인스턴스를 앱 프로세스에서 하나로 유지한다.
 * 다른 역할이 쓰는 공통 계약이 아니다(CAR-A는 VoiceHub와 디버그 패널 진입점만 쓴다).
 *
 * - [debug]: 디버그 패널이 명령을 내는 목업 소스.
 * - [active]: 명령 처리 쪽이 구독할 소스. D-05(OPEN) 확정 전에는 [debug]와 같은 인스턴스.
 *   후속 작업에서 VoiceHubImpl(context)가 생성자를 바꾸지 않고 이것을 구독해 패널과 같은 인스턴스를 공유한다.
 *   이번 작업에서는 VoiceHub에 연결하지 않으며, 이 구성은 VoiceHub 전체 수명 관리 정책을 정하지 않는다.
 */
internal object VoiceCommandSources {
    val debug: DebugVoiceCommandSource by lazy { DebugVoiceCommandSource() }

    val active: VoiceCommandSource
        get() = debug
}
