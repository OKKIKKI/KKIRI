package com.okkikki.kkiri.state

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import android.content.Context
import android.util.Log
import com.okkikki.kkiri.BuildConfig
import com.okkikki.kkiri.R
import com.okkikki.kkiri.contract.AmbientLightAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.resume

/**
 * V2-S3: Pleos Vehicle SDK Light의 앰비언트 색(32비트 ARGB, 좌석 영역별)으로 알린다. 선언: docs/pleos/VEHICLE_SDK_SIGNATURES.md.
 * 흐름: 지원 영역 조회 → 영역마다 현재 색 읽기 → 브랜드 색 설정 → AMBIENT_FLASH_MS 대기 → 읽어 둔 색으로 복원.
 * - 지원 영역이 없거나 현재 색을 못 읽은 영역은 건드리지 않는다(되돌릴 수 없으므로).
 * - 이미 진행 중이면 무시한다.
 */
class PleosAmbientLight(context: Context) : AmbientLightAdapter {
    private val brand: Int = (context.applicationContext.getColor(R.color.kk_ambient))
    private val mutex = Mutex()
    @Volatile private var areas: List<SeatArea>? = null // null = 아직 조회 전, 빈 목록 = 미지원

    override suspend fun flash() {
        val vehicle = PleosVehicle.get()
        if (vehicle == null) { Log.i(TAG, "Vehicle 연결 없음, 건너뜀"); return }
        if (!mutex.tryLock()) return
        val saved = mutableListOf<Pair<SeatArea, Int>>()
        try {
            val list = areas ?: queryAreas(vehicle).also { areas = it }
            if (list.isEmpty()) { Log.i(TAG, "앰비언트 색 미지원, 건너뜀"); return }
            val light = vehicle.getLight()
            for (a in list) {
                val prev = suspendCancellableCoroutine<Int?> { c ->
                    light.getAmbientLightBrightnessColor(a, { c.resume(it) }, { c.resume(null) })
                } ?: continue
                saved += a to prev
                light.setAmbientLightBrightnessColor(a, brand, { _, _ -> }, { Log.w(TAG, "색 설정 실패 $a: ${it.message}") })
            }
            Log.i(TAG, "알림 시작: 영역 ${saved.size}곳")
            delay(BuildConfig.AMBIENT_FLASH_MS.toLong())
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) Log.i(TAG, "취소, 복원만 함") else Log.w(TAG, "알림 실패: ${e.message}")
        } finally {
            val light = vehicle.getLight()
            for ((a, c) in saved) light.setAmbientLightBrightnessColor(a, c, { _, _ -> }, { Log.w(TAG, "복원 실패 $a: ${it.message}") })
            if (saved.isNotEmpty()) Log.i(TAG, "복원 완료")
            mutex.unlock()
        }
    }

    private suspend fun queryAreas(vehicle: Vehicle): List<SeatArea> = suspendCancellableCoroutine { c ->
        vehicle.getLight().checkAmbientLightBrightnessColorCapabilities(
            onComplete = { _, list -> Log.i(TAG, "지원 영역 ${list.size}곳"); c.resume(list) },
            onFailed = { Log.w(TAG, "지원 조회 실패: ${it.message}"); c.resume(emptyList()) },
        )
    }

    private companion object { const val TAG = "KkiriAmbient" }
}

/** SDK를 못 쓰는 기기용: 로그만 남긴다 */
class LogAmbientLight : AmbientLightAdapter {
    override suspend fun flash() { Log.i("KkiriAmbient", "flash(로그만)") }
}

object AmbientLights {
    @Volatile private var inst: AmbientLightAdapter? = null
    fun get(context: Context): AmbientLightAdapter = inst ?: synchronized(this) {
        inst ?: (try { PleosAmbientLight(context) } catch (e: Throwable) { LogAmbientLight() }).also { inst = it }
    }
}

/**
 * 낭독(SPEAK)되는 메시지가 라우팅될 때 voice/MessageRouter가 부르는 한 줄짜리 연결점(V2-S3).
 * 서버 이벤트와 디버그 입력이 모두 MessageRouter를 지나므로 여기서 한 번만 알린다.
 */
object AmbientTrigger {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    @Volatile var adapter: AmbientLightAdapter? = null

    fun fire() {
        val a = adapter ?: return
        scope.launch { runCatching { a.flash() } }
    }
}
