package com.okkikki.kkiri.net

import android.content.Context
import android.util.Log
import com.okkikki.kkiri.BuildConfig
import com.okkikki.kkiri.contract.DriveState
import com.okkikki.kkiri.contract.Ignition
import com.okkikki.kkiri.contract.Place
import com.okkikki.kkiri.contract.Session
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 명세 1.4 오류 본문 `{ "error": { code, message, details } }`. 본문을 못 읽으면 code = "HTTP_<상태>" */
class ApiException(
    val httpStatus: Int,
    val code: String,
    message: String,
    val details: JSONObject?,
) : Exception(message)

/**
 * 차량 앱 REST 클라이언트 (명세 3.1, D-22 OkHttp 4.12.0).
 * - 주소: BuildConfig.KKIRI_API_BASE(-Pkkiri.apiBase, Q-PM-001). 코드에 주소를 적지 않는다.
 * - carToken·carId는 기기에 저장(SharedPreferences "kkiri_net"). R-01은 저장된 토큰이 없을 때 한 번.
 * - carToken으로 부른 요청이 401이면 같은 deviceId로 R-01을 다시 불러 토큰을 새로 저장하고 한 번만 다시 보낸다(명세 5절-1).
 * - 모든 함수는 Result로 돌려준다. 실패는 ApiException(서버 오류 코드) 또는 IOException(연결 실패).
 * - WebSocket과 세션 상태 관리는 RemoteSessionRepository가 한다. 거기서 쓰는 공통 요청은 [request]·[uploadVoiceClip]·[download].
 */
class ApiClient(context: Context, private val baseUrl: String = BuildConfig.KKIRI_API_BASE) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val carToken: String? get() = prefs.getString(KEY_TOKEN, null)
    val carId: String? get() = prefs.getString(KEY_CAR_ID, null)

    // ── R-01 ───────────────────────────────────────────────

    /** R-01 차량 등록. 같은 deviceId면 서버가 같은 carId를 돌려준다. 결과(carId, carToken)를 저장한다 */
    suspend fun registerCar(): Result<String> = runCatching {
        val body = JSONObject()
            .put("deviceId", BuildConfig.KKIRI_DEVICE_ID)
            .put("displayName", BuildConfig.KKIRI_CAR_DISPLAY_NAME)
            .put("driverName", BuildConfig.KKIRI_DRIVER_NAME)
        val o = JSONObject(call("POST", "/v1/cars/register", body, token = null)!!)
        val id = o.getString("carId")
        prefs.edit()
            .putString(KEY_CAR_ID, id)
            .putString(KEY_TOKEN, o.getString("carToken"))
            .putString(KEY_DEVICE_ID, BuildConfig.KKIRI_DEVICE_ID)
            .apply()
        Log.i(TAG, "R-01 등록됨 carId=$id")
        id
    }

    /**
     * 저장된 토큰이 없으면 R-01 (명세 5절-1 "최초 1회").
     * 토큰을 받을 때의 deviceId가 지금 빌드의 KKIRI_DEVICE_ID와 다르면(-Pkkiri.deviceId만 바꿔 다시 설치한 경우)
     * 이전 차의 토큰이므로 R-01을 다시 부른다(PM SES-01-net 리뷰 요청 2026-11-12). 예전 설치처럼 저장된 deviceId가 없어도 다시 부른다.
     */
    suspend fun ensureRegistered(): Result<Unit> {
        val savedDeviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (carToken != null && savedDeviceId == BuildConfig.KKIRI_DEVICE_ID) return Result.success(Unit)
        if (carToken != null) Log.i(TAG, "deviceId 바뀜($savedDeviceId → ${BuildConfig.KKIRI_DEVICE_ID}) → R-01 다시")
        return registerCar().map { }
    }

    // ── 세션 (R-02~R-09) ───────────────────────────────────

    /** R-02 빠른 세션 만들기 */
    suspend fun createSession(name: String, destination: Place?): Result<Session> = authed {
        val body = JSONObject().put("name", name).put("destination", destination?.let(::placeJson) ?: JSONObject.NULL)
        JsonMapper.sessionResponse(call("POST", "/v1/sessions", body, it)!!)
    }

    /** R-03 시동 때 붙을 세션(예약·일시정지·내 차가 끊긴 진행 중 세션). 없으면 null */
    suspend fun pendingSession(): Result<Session?> = authed {
        JsonMapper.sessionOrNullResponse(call("GET", "/v1/cars/me/pending-session", null, it)!!)
    }

    /** R-04 세션에 다시 붙기 */
    suspend fun attach(sessionId: String): Result<Session> = authed {
        JsonMapper.sessionResponse(call("POST", "/v1/sessions/$sessionId/attach", null, it)!!)
    }

    /** R-05 접속코드로 합류 */
    suspend fun joinByCode(joinCode: String, applyDestination: Boolean): Result<Session> = authed {
        val body = JSONObject().put("joinCode", joinCode).put("applyDestination", applyDestination)
        JsonMapper.sessionResponse(call("POST", "/v1/sessions/join-by-code", body, it)!!)
    }

    /** R-07 세션 조회 */
    suspend fun getSession(sessionId: String): Result<Session> = authed {
        JsonMapper.sessionResponse(call("GET", "/v1/sessions/$sessionId", null, it)!!)
    }

    /** R-08 주행 상태·시동 (값이 바뀔 때만 부른다) */
    suspend fun postCarState(sessionId: String, driveState: DriveState, ignition: Ignition): Result<Unit> = authed {
        val body = JSONObject().put("driveState", driveState.name).put("ignition", ignition.name)
        call("POST", "/v1/sessions/$sessionId/car-state", body, it)
        Unit
    }

    /** R-09 세션 종료(호스트 차) */
    suspend fun endSession(sessionId: String): Result<Unit> = authed {
        call("POST", "/v1/sessions/$sessionId/end", null, it)
        Unit
    }

    // ── 세션 안의 다른 요청 (RemoteSessionRepository가 씀) ──

    /** carToken을 붙인 JSON 요청 한 번(401이면 재등록 후 1회 재시도). 응답 본문(204면 null) */
    suspend fun request(method: String, path: String, body: JSONObject? = null): Result<String?> = authed {
        call(method, path, body, it)
    }

    /** R-21 음성 클립 업로드(multipart: audio, target, durationMs). 응답 본문 */
    suspend fun uploadVoiceClip(
        sessionId: String, file: File, durationMs: Long, target: String, mime: String = "audio/mp4",
    ): Result<String?> = authed { token ->
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", file.name, file.asRequestBody(mime.toMediaType()))
            .addFormDataPart("target", target)
            .addFormDataPart("durationMs", durationMs.toString())
            .build()
        send("POST", "/v1/sessions/$sessionId/voice-clips", form, token)
    }

    /** R-25 등 파일 받기. path는 "/v1/..."로 시작하는 상대 경로(clip.url 그대로). dest에 저장 */
    suspend fun download(path: String, dest: File): Result<File> = authed { token ->
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(baseUrl.trimEnd('/') + path).get()
                .header("Authorization", "Bearer $token").build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw toApiException(res.code, res.body?.string())
                dest.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
                dest
            }
        }
    }

    // ── 공통 ───────────────────────────────────────────────

    /**
     * carToken이 필요한 요청. 토큰이 없으면 먼저 R-01.
     * 401이면 R-01로 토큰을 새로 받고 한 번만 다시 보낸다(명세 5절-1).
     */
    private suspend fun <T> authed(block: suspend (token: String) -> T): Result<T> = runCatching {
        ensureRegistered().getOrThrow()
        try {
            block(carToken!!)
        } catch (e: ApiException) {
            if (e.httpStatus != 401) throw e
            Log.w(TAG, "401 → R-01 다시 등록 후 한 번 재시도")
            registerCar().getOrThrow()
            block(carToken!!)
        }
    }

    /** JSON HTTP 한 번. 2xx면 본문(204면 null), 아니면 ApiException. 연결 실패는 IOException */
    private suspend fun call(method: String, path: String, body: JSONObject?, token: String?): String? {
        val reqBody = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        return send(method, path, reqBody, token)
    }

    private suspend fun send(method: String, path: String, reqBody: RequestBody?, token: String?): String? =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .method(method, reqBody)
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string()
                if (res.isSuccessful) return@withContext if (res.code == 204) null else text
                throw toApiException(res.code, text)
            }
        }

    private fun toApiException(status: Int, text: String?): ApiException = try {
        val e = JSONObject(text ?: "").getJSONObject("error")
        ApiException(status, e.getString("code"), e.optString("message"), e.optJSONObject("details"))
    } catch (_: Exception) {
        ApiException(status, "HTTP_$status", text ?: "", null)
    }

    private fun placeJson(p: Place): JSONObject = JSONObject()
        .put("placeId", p.placeId).put("name", p.name).put("address", p.address)
        .put("lat", p.lat).put("lng", p.lng).put("distanceKm", p.distanceKm)

    private companion object {
        const val TAG = "KKIRI-Api"
        const val PREFS = "kkiri_net"
        const val KEY_TOKEN = "carToken"
        const val KEY_CAR_ID = "carId"
        const val KEY_DEVICE_ID = "deviceId"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** 연결 실패인지(서버에 닿지 못함) */
fun Throwable.isNetworkError(): Boolean = this is IOException
