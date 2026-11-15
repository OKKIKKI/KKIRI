package com.okkikki.kkiri.navi

import android.util.Log
import com.okkikki.kkiri.contract.NaviAdapter
import com.okkikki.kkiri.contract.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [NaviAdapter] 목업(D-08 OPEN). 실제 내비를 부르지 않고 로그를 남기며 마지막으로 받은 값만 보관한다.
 * Pleos 내비 SDK 이름은 문서를 받기 전까지 쓰지 않는다(AGENTS.md 4절 3번).
 * 항상 success를 돌려주지만 이는 "목업이 받았다"는 뜻이며 실제 내비 반영이 아니다.
 */
class MockNaviAdapter : NaviAdapter {

    private val _waypoints = MutableStateFlow<List<Place>>(emptyList())
    /** 마지막으로 받은 경유지 전체 목록(디버그·확인용) */
    val waypoints: StateFlow<List<Place>> = _waypoints.asStateFlow()

    private val _destination = MutableStateFlow<Place?>(null)
    /** 마지막으로 받은 목적지(디버그·확인용) */
    val destination: StateFlow<Place?> = _destination.asStateFlow()

    override suspend fun setWaypoints(places: List<Place>): Result<Unit> {
        _waypoints.value = places.toList()
        Log.i(TAG, "setWaypoints(목업, 내비 미반영) ${places.size}건: " + places.joinToString { "${it.placeId}:${it.name}" })
        return Result.success(Unit)
    }

    override suspend fun setDestination(place: Place): Result<Unit> {
        _destination.value = place
        Log.i(TAG, "setDestination(목업, 내비 미반영): ${place.placeId}:${place.name}")
        return Result.success(Unit)
    }

    private companion object {
        const val TAG = "KKIRI.MockNavi"
    }
}
