package com.okkikki.kkiri.voice.audio

/** Pleos 선행 묵음 가이드 권장 구간(150~200ms)의 상한. 오디오 데이터/엔진 큐에 적용하며 단순 delay가 아니다. */
internal object VoicePreroll {
    const val DURATION_MS = 200L

    /** 묵음 완료를 본문 완료로 오인하지 않도록 다른 utteranceId를 쓴다. */
    fun enqueueTts(
        text: String,
        utteranceId: String,
        silence: (Long, String) -> Boolean,
        speech: (String, String) -> Boolean,
    ): Boolean = silence(DURATION_MS, "$utteranceId:preroll") && speech(text, utteranceId)
}
