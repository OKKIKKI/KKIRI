package com.okkikki.kkiri.voice.audio

/** 오디오 포커스 변화(Android AudioManager 값과 분리). duck 가능 일시 상실도 [LOSS_TRANSIENT]로 본다(음성은 낮춰 듣지 않고 멈춤) */
internal enum class FocusChange { GAIN, LOSS_TRANSIENT, LOSS }

/**
 * 소리 한 건의 오디오 포커스 요청 경계(CAR-B 내부, android.* 의존 없음). 실제 구현은
 * [com.okkikki.kkiri.ptt.ClipAudioFocus.asSpeechFocus](클립과 같은 용도·요청 방식). JVM 검증 때 가짜로 바꿔 끼운다.
 */
internal fun interface SpeechFocus {
    /** 포커스를 요청한다. 거절이면 null. 받았으면 끝날 때 [Held.release]를 꼭 부른다. [onChange]는 다른 스레드에서 올 수 있다 */
    fun acquire(onChange: (FocusChange) -> Unit): Held?

    fun interface Held {
        fun release()
    }
}
