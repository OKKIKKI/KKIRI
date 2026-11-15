package com.okkikki.kkiri.contract

/** 명세 6.2(v1.14). 메시지가 낭독될 때 실내 앰비언트 라이트로 한 번 알린다. 지원하지 않는 차종·기기는 아무것도 하지 않는다. */
interface AmbientLightAdapter {
    suspend fun flash()
}
