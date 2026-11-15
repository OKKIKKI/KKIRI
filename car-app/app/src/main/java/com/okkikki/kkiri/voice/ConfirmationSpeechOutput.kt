package com.okkikki.kkiri.voice

interface ConfirmationSpeechOutput : SpeechOutput {
    /**
     * 시스템 안내 우선순위로 질문을 넣고 낭독 종료까지 기다린다.
     * true: 낭독 작업 정상 종료
     * false: reset·폐기·확인된 재생 실패로 종료
     * 호출 코루틴 취소: 해당 질문을 취소하고 CancellationException 전파
     */
    suspend fun announceAndAwait(text: String): Boolean
}
