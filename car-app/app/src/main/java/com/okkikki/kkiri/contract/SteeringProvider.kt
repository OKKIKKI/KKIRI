package com.okkikki.kkiri.contract

import kotlinx.coroutines.flow.StateFlow

/** 명세 6.1절(v1.13). CAR-A가 구현하고 화면(ui/)이 쓴다. 실제 값은 Vehicle SDK 확인 전까지 디버그 버튼(목업). */
interface SteeringProvider {
    /** 핸들 각도(도). 0 = 가운데, 양수 = 오른쪽. 값을 모르면 0 */
    val angleDeg: StateFlow<Float>
}
