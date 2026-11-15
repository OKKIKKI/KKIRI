package com.okkikki.kkiri.voice.queue

/** SpeechQueue가 읽는 문구. 명세 5절·5.1(v1.9)에 있는 것만 쓴다. 없는 문구가 필요하면 [승인 요청]. */
internal object SpeechTexts {
    /** 명세 5절-4: 앱이 보이지 않을 때 메시지 앞에 붙인다(끝 공백 포함 원문 그대로) */
    fun intro(senderName: String): String = "${senderName}에게서 온 메시지입니다. "

    /** 명세 5절-5: PARKED 전환 시 보관 1건 이상인 차마다 한 번 */
    fun heldNotice(carName: String, count: Int): String = "$carName 음소거 중, 메시지 ${count}건"

    /** 명세 v1.9 5.1(D-24): 음악 추가(`music.queue_added`, 호스트 차만) */
    fun musicQueued(addedByName: String, title: String): String =
        "이번 DJ는 ${addedByName}님! 다음곡은 ${title}!"
}
