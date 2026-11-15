package com.okkikki.kkiri.contract

import java.io.File
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 명세 6절. CAR-A가 구현하고 CAR-B가 쓴다. 서버 통신은 전부 여기로 한다(CAR-B는 HTTP/WS를 직접 쓰지 않는다). */
interface SessionRepository {
    /** 최신 스냅샷(이벤트 반영됨) */
    val session: StateFlow<Session?>

    /** 4.3의 이벤트 */
    val events: SharedFlow<ServerEvent>
    val myCarId: String?

    /** R-20. target은 "ALL" 또는 carId */
    suspend fun sendBroadcast(text: String, target: String): Result<Unit>

    /** R-21 */
    suspend fun sendVoiceClip(file: File, durationMs: Long, target: String): Result<Unit>

    /** R-25 (clip.url 그대로 넘김) */
    suspend fun fetchVoiceClip(clipUrl: String): Result<File>

    /** R-22. targetCarId는 carId 또는 "ALL" */
    suspend fun setMute(targetCarId: String, muted: Boolean): Result<Unit>

    /** R-23 */
    suspend fun heldMessages(): Result<List<Message>>

    /** R-24 */
    suspend fun ackHeld(ids: List<String>): Result<Unit>

    /** R-33 */
    suspend fun undoWaypoint(): Result<Unit>

    /** R-34 */
    suspend fun respondProposal(proposalId: String, accept: Boolean): Result<Unit>

    /** R-46 */
    suspend fun setMusicLock(locked: Boolean): Result<Unit>
}
