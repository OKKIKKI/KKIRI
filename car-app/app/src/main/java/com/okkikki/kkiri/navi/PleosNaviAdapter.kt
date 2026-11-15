package com.okkikki.kkiri.navi

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.okkikki.kkiri.contract.NaviAdapter
import com.okkikki.kkiri.contract.Place
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pleos 순정 내비에 경유지를 넣는 [NaviAdapter] (시연용, 2026-11-15). D-08은 아직 OPEN.
 * 기본으로 쓰고, 빌드 옵션 -Pkkiri.realNavi=false면 MockNaviAdapter를 쓴다.
 *
 * - 경유지 목록이 바뀌면 내비에 목적지가 있는지 먼저 묻는다(getDestinationInfo).
 *   - 목적지가 있으면: 그 목적지 + 새 경유지(최대 2개, SDK가 FIRST/SECOND만 받음)로 경로를 다시 요청한다.
 *   - 목적지가 없으면: 마지막 경유지를 목적지로 경로를 요청한다(시연에서 화면이 바뀌어 보이게).
 *   - 목록이 비면: 이 앱이 경유지를 넣은 적이 있을 때만 전부 삭제(세션 참여 직후 빈 목록으로 내비 경로를 건드리지 않게).
 * - SDK 호출은 결과를 돌려주지 않아서, 오류 이벤트가 없으면 성공으로 본다.
 */
class PleosNaviAdapter(context: Context) : NaviAdapter {
    private val helper = NaviHelper(context.applicationContext)
    private val lock = Mutex()
    private var connected = CompletableDeferred<Boolean>()
    private var pendingDest: CompletableDeferred<DestinationInfo?>? = null
    private var lastError: NaviErrorCode? = null
    /** 이 앱이 내비에 경유지를 넣은 적이 있는지. 없으면 빈 목록 동기화 때 아무것도 안 한다 */
    private var sentAny = false

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) {
            Log.i(TAG, "목적지 정보: ${info.name} (${info.latitude}, ${info.longitude})")
            pendingDest?.complete(info)
        }
        override fun onRouteStateInfo(info: RouteStateInfo) {
            Log.i(TAG, "경로 상태: routing=${info.isRouting} mode=${info.naviMode} terms=${info.termsAgreement}")
        }
        override fun onWaypointChanged(info: WaypointChangedInfo) {
            Log.i(TAG, "내비 경유지 변경: ${info.cmdType} ${info.index}")
        }
        override fun onWayPointInfo(list: List<WaypointInfo>) {
            Log.i(TAG, "내비 경유지 ${list.size}건: " + list.joinToString { it.name })
        }
        override fun onRoutingCompleted() { Log.i(TAG, "경로 계산 완료") }
        override fun onError(code: NaviErrorCode) {
            Log.w(TAG, "내비 오류: $code")
            lastError = code
        }
    }

    init {
        helper.initialize(
            {
                Log.i(TAG, "NaviHelper 연결됨")
                helper.addListener(listener)
                connected.complete(true)
                helper.getRouteStateInfo()
            },
            { e ->
                Log.w(TAG, "NaviHelper 연결 실패", e)
                connected.complete(false)
            },
        )
    }

    private suspend fun ready(): Boolean = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } == true

    private suspend fun currentDestination(): DestinationInfo? {
        val d = CompletableDeferred<DestinationInfo?>()
        pendingDest = d
        helper.getDestinationInfo()
        val r = withTimeoutOrNull(QUERY_TIMEOUT_MS) { d.await() }
        pendingDest = null
        // 목적지가 없을 때 0,0이 올 수 있다
        return r?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }
    }

    override suspend fun setWaypoints(places: List<Place>): Result<Unit> = lock.withLock {
        Log.i(TAG, "setWaypoints ${places.size}건: " + places.joinToString { it.name })
        if (!ready()) return Result.failure(IllegalStateException("NaviHelper 미연결"))
        lastError = null
        runCatching {
            if (places.isEmpty()) {
                if (sentAny) helper.removeWaypoint(WaypointIndex.ALL) else Log.i(TAG, "빈 목록, 넣은 적 없음 → 내비 그대로")
                sentAny = false
                return@runCatching
            }
            sentAny = true
            val dest = currentDestination()
            if (dest == null) {
                Log.i(TAG, "내비 목적지 없음 → 마지막 경유지를 목적지로")
                helper.requestRoute(routeInfo(places.last().lat, places.last().lng, places.last().name, places.last().address))
                return@runCatching
            }
            val wps = places.takeLast(2).mapIndexed { i, p ->
                RequestWaypointInfo(p.lat, p.lng, p.name, if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                    p.placeId, p.address, "", "")
            }
            helper.requestRouteWithWaypoints(
                RouteWithWaypointsRequest(routeInfo(dest.latitude, dest.longitude, dest.name, dest.address), wps),
            )
            Log.i(TAG, "경로 재요청: 목적지 ${dest.name} + 경유지 " + wps.joinToString { it.poiName ?: "" })
        }.also { checkError()?.let { e -> return Result.failure(e) } }
    }

    override suspend fun setDestination(place: Place): Result<Unit> = lock.withLock {
        Log.i(TAG, "setDestination ${place.name}")
        if (!ready()) return Result.failure(IllegalStateException("NaviHelper 미연결"))
        lastError = null
        runCatching { helper.requestRoute(routeInfo(place.lat, place.lng, place.name, place.address)) }
            .also { checkError()?.let { e -> return Result.failure(e) } }
    }

    /** 호출 직후 잠깐 기다려 오류 이벤트가 오면 실패로 */
    private suspend fun checkError(): Exception? {
        kotlinx.coroutines.delay(ERROR_WAIT_MS)
        return lastError?.let { IllegalStateException("내비 오류 $it") }
    }

    private fun routeInfo(lat: Double, lng: Double, name: String, address: String) =
        RouteInfo(lat, lng, null, null, name, "", address, "", RouteOption.RECOMMENDED, "", false)

    private companion object {
        const val TAG = "KKIRI.PleosNavi"
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val QUERY_TIMEOUT_MS = 2_000L
        const val ERROR_WAIT_MS = 800L
    }
}
