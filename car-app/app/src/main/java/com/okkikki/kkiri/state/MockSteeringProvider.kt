package com.okkikki.kkiri.state

import com.okkikki.kkiri.contract.SteeringProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 명세 6.1절 SteeringProvider의 목업 구현. 실제 Vehicle SDK(SteeringWheel) 대신 디버그 버튼으로 값을 넣는다.
 * SDK의 함수 시그니처와 각도 부호가 문서로 확인되지 않아 아직 연결하지 않았다(docs/pleos/PLEOS_SDK_NOTES.md 3절).
 */
class MockSteeringProvider : SteeringProvider {

    private val _angleDeg = MutableStateFlow(0f)
    override val angleDeg: StateFlow<Float> = _angleDeg.asStateFlow()

    fun setAngle(deg: Float) { _angleDeg.value = deg }
    /** 지금 값에서 [delta]도만큼 돌린다 */
    fun turn(delta: Float) { _angleDeg.value += delta }
}

/** 앱 전체에서 하나만 쓰는 핸들 각도 제공자. 실제 구현이 생기면 여기 한 줄만 바꾼다. */
object Steerings {
    val mock: MockSteeringProvider = MockSteeringProvider()
}
