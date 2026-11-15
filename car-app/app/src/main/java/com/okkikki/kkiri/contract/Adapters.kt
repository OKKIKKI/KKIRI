package com.okkikki.kkiri.contract

import java.io.File
import kotlinx.coroutines.flow.SharedFlow

// 명세 6절. CAR-B 내부 어댑터. 실제/목업 구현을 바꿔 끼운다(docs/05_DEV_RULES.md 4절). 구현은 CAR-B가 만든다.

/** Gleo / STT / 디버그 버튼 (D-05) */
interface VoiceCommandSource {
    val commands: SharedFlow<VoiceCommand>
}

interface TtsAdapter {
    suspend fun speak(text: String)
    fun stop()
}

interface NaviAdapter {
    suspend fun setWaypoints(places: List<Place>): Result<Unit>
    suspend fun setDestination(place: Place): Result<Unit>
}

interface VoiceRecorder {
    suspend fun record(maxMs: Long): Result<RecordedClip>
}

/** mime: "audio/mp4" (D-12) */
data class RecordedClip(val file: File, val durationMs: Long, val mime: String)
