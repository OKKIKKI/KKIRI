package com.okkikki.kkiri.state

import android.content.Context
import android.util.Log
import com.okkikki.kkiri.BuildConfig
import com.okkikki.kkiri.contract.CarLook
import com.okkikki.kkiri.contract.DriveState
import com.okkikki.kkiri.contract.Ignition
import com.okkikki.kkiri.contract.Place
import com.okkikki.kkiri.contract.SessionRepository
import com.okkikki.kkiri.net.ApiClient
import com.okkikki.kkiri.net.RemoteSessionRepository

/**
 * CAR-A 화면이 부르는 세션 동작 모음 (HB-F-SES-01·03·04, ACC-03, COM-01).
 * 목업과 실제 서버 구현을 같은 모양으로 두고 빌드 설정 하나로 바꾼다(05_DEV_RULES 4절).
 * - 실제: BuildConfig.KKIRI_USE_MOCK = false (기본). 목업: -Pkkiri.useMock=true
 * - 모든 함수는 Result. 실패 문구(03_SCREENS)는 화면(MainActivity.errorRes + strings.xml)이 고른다.
 */
interface SessionController {
    /** 화면·CAR-B가 구독하는 세션 저장소 */
    val repo: SessionRepository

    /** 앱 시작(명세 5절-1): R-01(토큰 없을 때) → R-03 → 세션이 있으면 R-04 후 WebSocket */
    suspend fun start(): Result<Unit>

    /** CAR-01 [세션 만들기]: R-02 */
    suspend fun create(name: String, destination: Place?): Result<Unit>

    /** CAR-03 합류: R-05 */
    suspend fun join(joinCode: String, applyDestination: Boolean): Result<Unit>

    /** CAR-05 [세션 종료]: R-09 후 연결을 끊고 세션을 비운다 */
    suspend fun end(): Result<Unit>

    /** 주행 상태·시동이 바뀌었을 때 R-08. 세션이 없거나 직전에 보낸 값과 같으면 보내지 않는다 */
    suspend fun reportCarState(driveState: DriveState, ignition: Ignition): Result<Unit>

    /** R-47(v1.15): 내 차 모양. 세션이 없거나 직전에 보낸 값과 같으면 보내지 않는다. 실패해도 화면 장식이라 안내하지 않는다 */
    suspend fun reportCarLook(look: CarLook): Result<Unit>

    /** 서버에서 session.ended를 받았을 때: 서버 호출 없이 연결을 끊고 세션을 비운다 */
    fun clearLocal()
}

/** 어떤 동작이 실패했는지 (화면이 실패 문구를 고를 때 씀) */
enum class SessionOp { CREATE, JOIN, END }

/** 실제 서버 구현: ApiClient(REST) + RemoteSessionRepository(WebSocket) */
class RemoteSessionController(context: Context) : SessionController {
    private val api = ApiClient(context.applicationContext)
    private val remote = RemoteSessionRepository(context.applicationContext, api)
    override val repo: SessionRepository get() = remote

    /** 마지막으로 R-08에 보낸 값 (세션 id, 주행, 시동) */
    private var lastReported: Triple<String, DriveState, Ignition>? = null

    /** 마지막으로 R-47에 보낸 값 (세션 id, 모양) */
    private var lastLook: Pair<String, CarLook>? = null

    override suspend fun start(): Result<Unit> = runCatching {
        api.ensureRegistered().getOrThrow()
        val pending = api.pendingSession().getOrThrow()
        if (pending == null) {
            Log.i(TAG, "R-03: 이어 갈 세션 없음 → CAR-01")
        } else {
            remote.open(api.attach(pending.sessionId).getOrThrow())
            Log.i(TAG, "R-04: ${pending.sessionId} 다시 연결")
        }
    }

    override suspend fun create(name: String, destination: Place?): Result<Unit> = runCatching {
        remote.open(api.createSession(name, destination).getOrThrow())
    }

    override suspend fun join(joinCode: String, applyDestination: Boolean): Result<Unit> = runCatching {
        remote.open(api.joinByCode(joinCode, applyDestination).getOrThrow())
    }

    override suspend fun end(): Result<Unit> = runCatching {
        val id = remote.session.value?.sessionId ?: return@runCatching
        api.endSession(id).getOrThrow()
        clearLocal()
    }

    override suspend fun reportCarState(driveState: DriveState, ignition: Ignition): Result<Unit> = runCatching {
        val id = remote.session.value?.sessionId ?: return@runCatching
        val now = Triple(id, driveState, ignition)
        if (now == lastReported) return@runCatching
        api.postCarState(id, driveState, ignition).getOrThrow()
        lastReported = now
        Log.i(TAG, "R-08: $driveState / $ignition")
    }

    override suspend fun reportCarLook(look: CarLook): Result<Unit> = runCatching {
        val id = remote.session.value?.sessionId ?: return@runCatching
        if (lastLook == id to look) return@runCatching
        val body = org.json.JSONObject().put("steerStep", look.steerStep)
            .put("color", look.color ?: org.json.JSONObject.NULL).put("hat", look.hat).put("balloon", look.balloon)
            .put("character", look.character ?: org.json.JSONObject.NULL).put("items", org.json.JSONArray(look.items))
        api.request("POST", "/v1/sessions/$id/cars/me/look", body).getOrThrow()
        lastLook = id to look
    }

    override fun clearLocal() {
        lastReported = null
        lastLook = null
        remote.close()
    }

    private companion object { const val TAG = "KKIRI-Controller" }
}

/** 목업 구현: fixtures 세션을 불러오고 서버 호출은 로그만 남긴다 */
class MockSessionController(context: Context) : SessionController {
    private val mock = SessionRepositories.mock(context)
    override val repo: SessionRepository get() = mock

    override suspend fun start(): Result<Unit> = Result.success(Unit)

    override suspend fun create(name: String, destination: Place?): Result<Unit> {
        Log.i(TAG, "목업 R-02 create(name=$name)")
        mock.loadFixtureSession()
        return Result.success(Unit)
    }

    override suspend fun join(joinCode: String, applyDestination: Boolean): Result<Unit> {
        Log.i(TAG, "목업 R-05 join(code=$joinCode, applyDestination=$applyDestination)")
        mock.loadFixtureSession()
        return Result.success(Unit)
    }

    override suspend fun end(): Result<Unit> {
        clearLocal()
        return Result.success(Unit)
    }

    override suspend fun reportCarState(driveState: DriveState, ignition: Ignition): Result<Unit> {
        if (mock.session.value != null) Log.i(TAG, "목업 R-08: $driveState / $ignition")
        return Result.success(Unit)
    }

    override suspend fun reportCarLook(look: CarLook): Result<Unit> {
        if (mock.session.value != null) Log.i(TAG, "목업 R-47: $look")
        return Result.success(Unit)
    }

    override fun clearLocal() = mock.clearSession()

    private companion object { const val TAG = "KKIRI-MockController" }
}

/** 앱 전체에서 하나만 쓰는 SessionController. 화면이 다시 만들어져도 유지된다 */
object SessionControllers {
    @Volatile private var instance: SessionController? = null

    fun get(context: Context): SessionController =
        instance ?: synchronized(this) {
            instance ?: (if (BuildConfig.KKIRI_USE_MOCK) MockSessionController(context) else RemoteSessionController(context))
                .also { instance = it }
        }
}
