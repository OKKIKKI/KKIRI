package com.okkikki.kkiri.state

import com.okkikki.kkiri.contract.DriveState
import com.okkikki.kkiri.contract.DriveStateProvider
import com.okkikki.kkiri.contract.Ignition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 명세 6절 DriveStateProvider의 목업 구현 (D-06 OPEN → 실제 Vehicle SDK 대신 디버그 토글).
 * - 시작 값: 정차(PARKED), 시동 켬(ON).
 * - 시동(ignition)은 contract에 없다. R-08에 보낼 값이라 CAR-A 내부 표시용으로만 둔다
 *   (CAR-A 승인 2026-11-11. 실제 API가 확인되면 PM에게 contract 추가 요청).
 */
class MockDriveStateProvider : DriveStateProvider {

    private val _driveState = MutableStateFlow(DriveState.PARKED)
    override val driveState: StateFlow<DriveState> = _driveState.asStateFlow()

    private val _isAppVisible = MutableStateFlow(false)
    override val isAppVisible: StateFlow<Boolean> = _isAppVisible.asStateFlow()

    private val _ignition = MutableStateFlow(Ignition.ON)
    /** CAR-A 내부 표시용 (contract 밖) */
    val ignition: StateFlow<Ignition> = _ignition.asStateFlow()

    fun setDriveState(state: DriveState) { _driveState.value = state }
    fun setIgnition(value: Ignition) { _ignition.value = value }
    /** 앱 화면이 보이는지: MainActivity의 onStart/onStop에서 넣는다 */
    fun setAppVisible(visible: Boolean) { _isAppVisible.value = visible }
}

/**
 * 앱 전체에서 하나만 쓰는 주행 상태 제공자.
 * 창 크기 변경으로 화면(Activity)이 다시 만들어져도 값이 유지되도록 화면 밖에 둔다.
 * 실제 구현이 생기면(D-06) 여기 한 줄만 바꾼다.
 */
object DriveStates {
    val mock: MockDriveStateProvider = MockDriveStateProvider()
}
