package com.okkikki.kkiri.contract

import kotlinx.coroutines.flow.StateFlow

/** 명세 6절. CAR-A가 구현하고 CAR-B가 쓴다. 실제 값은 D-06 확정 전까지 디버그 토글(목업). */
interface DriveStateProvider {
    /** DRIVING / PARKED / UNKNOWN */
    val driveState: StateFlow<DriveState>

    /** 앱 화면이 보이는지(스플릿뷰 포함) */
    val isAppVisible: StateFlow<Boolean>
}
