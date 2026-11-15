package com.okkikki.kkiri.navi

import android.util.Log
import com.okkikki.kkiri.contract.NaviAdapter
import com.okkikki.kkiri.contract.Place
import com.okkikki.kkiri.contract.Session
import com.okkikki.kkiri.contract.Waypoint
import com.okkikki.kkiri.contract.WaypointAdded
import com.okkikki.kkiri.contract.WaypointRemoved
import com.okkikki.kkiri.voice.VoiceTexts
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 내 차 경유지 → 내비 반영(명세 5절-6, NAV-02·NAV-03·ACC-04).
 * - 스냅샷(세션 시작·새 스냅샷)으로 내 차(myCarId) 목록을 다시 만들고 전체 목록을 setWaypoints 한다(안내 없음).
 *   예약 중 담긴 경유지(ACC-04)도 이 첫 동기화로 한 번에 반영된다.
 * - waypoint.added/removed는 waypointId 기준으로 반영한다(이미 있으면 다시 추가하지 않음). 반영할 때마다 전체 목록을 넘긴다.
 * - 다른 차의 경유지는 반영하지 않는다. byUndo도 같은 처리.
 * - 안내: 추가는 명세 5절-6 문구, 삭제는 명세 5.1 문구(장소 이름은 보관 목록에서). 내비 반영 실패는 5.1 "내비에 반영되지 않았어요".
 *   추가·삭제 안내를 **먼저** 읽고 실패 안내를 뒤에 읽는다(서버에서는 바뀐 사실 → 내 내비에는 안 들어감 순서).
 * - 마지막으로 **성공한** 목록과 같은 목록은 내비에 다시 보내지 않는다(스냅샷 재동기화 때 같은 경로 재탐색 방지).
 *   실패했거나 처음이면 보낸다. 이 클래스는 실행 단위(start~stop)마다 새로 만들어지므로 새 실행의 첫 동기화는 항상 보낸다.
 */
internal class WaypointSync(
    private val navi: NaviAdapter,
    private val announce: (String) -> Unit,
    private val myCarId: () -> String?,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val warn: (String, Throwable?) -> Unit = { m, e -> Log.w(TAG, m, e) },
) {
    private val mutex = Mutex()
    private val mine = LinkedHashMap<String, Waypoint>()
    /** 마지막으로 내비가 성공을 돌려준 목록. 실패하면 null(다음에 다시 보낸다) */
    private var lastPushed: List<Place>? = null

    suspend fun resync(session: Session) = mutex.withLock {
        val me = myCarId()
        if (me == null) {
            warn("resync: myCarId 없음", null)
            return@withLock
        }
        mine.clear()
        session.waypoints.filter { it.carId == me }.forEach { mine[it.waypointId] = it }
        log("resync: 내 차($me) 경유지 ${mine.size}건")
        if (!push()) announce(VoiceTexts.NAVI_FAILED)
    }

    suspend fun onAdded(event: WaypointAdded) = mutex.withLock {
        val wp = event.waypoint
        if (wp.carId != myCarId()) return@withLock
        if (mine.containsKey(wp.waypointId)) {
            log("이미 있는 경유지, 다시 추가 안 함: ${wp.waypointId}")
            return@withLock
        }
        mine[wp.waypointId] = wp
        val ok = push()
        announce(VoiceTexts.waypointAdded(wp.addedBy.name, wp.place.name))
        if (!ok) announce(VoiceTexts.NAVI_FAILED)
    }

    suspend fun onRemoved(event: WaypointRemoved) = mutex.withLock {
        if (event.carId != myCarId()) return@withLock
        val wp = mine.remove(event.waypointId)
        if (wp == null) {
            warn("보관 목록에 없는 경유지 삭제 이벤트: ${event.waypointId}", null)
            return@withLock
        }
        val ok = push()
        announce(VoiceTexts.waypointRemoved(wp.place.name))
        if (!ok) announce(VoiceTexts.NAVI_FAILED)
    }

    suspend fun clear() = mutex.withLock {
        mine.clear()
        lastPushed = null
    }

    /** 전체 목록을 만든 시각 순서로 넘긴다. 성공(또는 마지막 성공과 같아 생략)이면 true. 안내는 부르는 쪽이 한다 */
    private suspend fun push(): Boolean {
        val places = mine.values.sortedBy { it.createdAt }.map { it.place }
        if (places == lastPushed) {
            log("마지막으로 반영한 목록과 같음, 내비에 다시 보내지 않음(${places.size}건)")
            return true
        }
        // 호출이 실패·예외로 끝나면 내비 상태를 모르므로, 성공이 확인될 때까지 비워 둔다
        lastPushed = null
        return navi.setWaypoints(places).fold(
            onSuccess = {
                lastPushed = places
                true
            },
            onFailure = {
                warn("setWaypoints 실패", it)
                false
            },
        )
    }

    private companion object {
        const val TAG = "KKIRI.WaypointSync"
    }
}
