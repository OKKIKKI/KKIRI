package com.okkikki.kkiri.ptt

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.okkikki.kkiri.voice.ClipPlayer
import com.okkikki.kkiri.voice.StartGate
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 받은 음성 클립(R-25로 받은 파일, D-12 m4a)을 android.media.MediaPlayer로 재생하는 [ClipPlayer].
 *
 * 정지·취소 보장([ClipPlayer] 계약, 팀원 SpeechQueue T10과 같은 방식):
 * - play는 재생이 끝나면 success, 오류면 failure를 돌려준다.
 * - [stop]: 진행 중인 play가 failure("stopped")로 **반환**하고 소리를 멈춘다(MediaPlayer.stop·release).
 *   play에 들어온 뒤 재생 시작 전에 stop이 와도 시작하지 않고 failure. play가 없을 때 부른 stop은 이후 play에 영향 없음.
 * - play를 부른 코루틴이 취소되면 소리를 멈추고(stop·release) `CancellationException`을 그대로 던진다.
 *   prepare 중 취소되면 prepare가 끝난 뒤 시작하지 않고 던진다.
 * - 한 번에 하나만 재생한다. 재생 중에 다시 play하면 이전 재생은 stop된 것으로 끝난다.
 * - 시작·정지 경합: play는 들어올 때 [StartGate] 표를 받고, 재생 시작(MediaPlayer.start) 직전에 같은 lock 안에서
 *   표가 유효한지 본다. stop은 그 lock 안에서 정지 세대를 올린다. 대기 해제(deferred complete)는 lock 밖에서 하지만
 *   시작 여부는 세대 번호로만 판단하므로, 완료 신호가 늦게 전달돼도 stop 뒤에 재생을 시작하지 않는다.
 * - 실기기(MediaPlayer) 동작은 확인 안 됨.
 * - Pleos 선행 묵음 가이드: 원본 대신 200ms 무음 PCM을 붙인 임시 WAV를 재생하고, 재생 후 삭제한다.
 * - 녹음 메시지 알림: NOTIFICATION / SPEECH, 재생 전에 일시적 포커스를 요청한다.
 *   거절·영구 상실은 failure, 일시 상실(duck 포함)은 멈추고 gain 뒤 같은 위치에서 재개한다.
 *   일시 상실이 [focusSuspendLimitMs] 넘게 이어지면 failure로 끝낸다(낭독 대기열이 이 클립에 묶여 멈추지 않게).
 *   완료·실패·취소 때 포커스를 반납한다. 실제 차량 매트릭스 동작은 실행 미확인.
 */
class MediaPlayerClipPlayer(
    context: Context,
    /** 포커스 일시 상실을 기다리는 최대 시간(ms). 값은 [com.okkikki.kkiri.voice.VoiceComponents] 한곳에서 주입 */
    private val focusSuspendLimitMs: Long,
) : ClipPlayer {

    init {
        require(focusSuspendLimitMs > 0) { "focusSuspendLimitMs must be > 0: $focusSuspendLimitMs" }
    }

    private val audioFocus = ClipAudioFocus(context)

    /** 시작·정지 판정. 한 번에 하나만 재생하므로 새 play가 이전 표를 무효로 만든다(exclusive) */
    private val gate = StartGate(exclusive = true)

    /** currentDone 보호용. gate의 lock 안에서 잡을 수 있지만 그 반대는 하지 않는다 */
    private val lock = Any()
    /** 지금 재생 요청의 대기 해제 신호(시작 판정에 쓰지 않음) */
    private var currentDone: CompletableDeferred<Result<Unit>>? = null

    override suspend fun play(file: File): Result<Unit> {
        val ticket = gate.open()
        return withContext(Dispatchers.IO) {
            val done = CompletableDeferred<Result<Unit>>()
            // 표가 유효할 때만 지금 요청으로 등록한다(늦게 온 옛 요청이 새 요청의 신호를 덮지 않게)
            var previous: CompletableDeferred<Result<Unit>>? = null
            val registered = gate.startIfValid(ticket) {
                synchronized(lock) {
                    previous = currentDone
                    currentDone = done
                }
                true
            } ?: false
            if (!registered) return@withContext Result.failure(IllegalStateException("stopped: 시작 전"))
            previous?.complete(Result.failure(IllegalStateException("stopped: 새 재생")))
            var mp: MediaPlayer? = null
            var prepared: File? = null
            var focusGate: ClipFocusGate? = null
            var focusSession: ClipAudioFocus.Session? = null
            val result: Result<Unit> = try {
                val padded = PrerollClipDecoder.decode(file) { gate.isValid(ticket) }
                prepared = padded
                ensureActive()
                if (!gate.isValid(ticket)) return@withContext Result.failure(IllegalStateException("stopped: 준비 뒤"))
                val player = MediaPlayer()
                mp = player
                player.setAudioAttributes(audioFocus.attributes)
                player.setDataSource(padded.absolutePath)
                player.setOnCompletionListener { focusGate?.close(); done.complete(Result.success(Unit)) }
                player.setOnErrorListener { _, what, extra ->
                    focusGate?.close()
                    done.complete(Result.failure(IOException("MediaPlayer 오류 what=$what extra=$extra")))
                    true
                }
                player.prepare()
                ensureActive() // prepare 중 취소됐으면 시작하지 않는다
                val focus = ClipFocusGate(
                    play = { player.start() },
                    pause = { player.pause() },
                    fail = { done.complete(Result.failure(IOException("오디오 포커스 거절·상실"))) },
                )
                focusGate = focus
                val session = audioFocus.session { change ->
                    try {
                        gate.startIfValid(ticket) {
                            when (change) {
                                AudioManager.AUDIOFOCUS_GAIN -> focus.gain()
                                AudioManager.AUDIOFOCUS_LOSS -> focus.loss(temporary = false)
                                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> focus.loss(temporary = true)
                            }
                            true
                        }
                    } catch (e: IllegalStateException) {
                        focus.close()
                        done.complete(Result.failure(e))
                    }
                }
                focusSession = session
                if (!gate.isValid(ticket)) return@withContext Result.failure(IllegalStateException("stopped: 포커스 요청 전"))
                val granted = session.acquire()
                Log.i(TAG, "클립 focus: NOTIFICATION/SPEECH, GAIN_TRANSIENT_MAY_DUCK, granted=$granted")
                focus.requestResult(granted)
                ensureActive()
                // stop과 같은 lock 안에서 표를 확인하고 시작: stop(또는 새 play)이 먼저 확정됐으면 시작하지 않고
                // (완료 신호 전달 여부와 무관), 시작이 먼저면 이후 stop의 신호로 아래 finally가 멈춘다
                val started = gate.startIfValid(ticket) { focus.start(); true } ?: false
                if (started) {
                    Log.i(TAG, "재생 요청(포커스 상태 적용): ${file.name}")
                } else {
                    done.complete(Result.failure(IllegalStateException("stopped: 시작 전")))
                }
                withSuspendLimit(focus.suspended, focusSuspendLimitMs, onLimit = {
                    focus.close() // 늦은 gain이 다시 시작하지 않게
                    done.complete(Result.failure(IOException("오디오 포커스 일시 상실이 ${focusSuspendLimitMs}ms 넘게 이어짐")))
                }) { done.await() }
            } catch (e: CancellationException) {
                throw e // 코루틴 취소는 삼키지 않는다(아래 finally에서 소리를 멈춘다)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: IllegalStateException) {
                Result.failure(e)
            } catch (e: IllegalArgumentException) {
                Result.failure(e)
            } finally {
                synchronized(lock) { if (currentDone === done) currentDone = null }
                focusGate?.close() // 늦은 gain이 release된 플레이어를 다시 시작하지 않게 한다
                try {
                    if (mp?.isPlaying == true) mp.stop()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "stop 중 상태 오류", e)
                }
                try {
                    mp?.release()
                } finally {
                    try {
                        focusSession?.release()
                    } finally {
                        prepared?.let { if (!it.delete()) Log.w(TAG, "선행 묵음 임시 파일 삭제 실패: ${it.name}") }
                    }
                }
            }
            result.onFailure { Log.w(TAG, "재생 실패·중단: ${file.name}: ${it.message}") }
            result
        }
    }

    override fun stop() {
        var d: CompletableDeferred<Result<Unit>>? = null
        // 정지 확정(세대 번호)을 시작 판정과 같은 lock 안에서 한다
        gate.stop { d = synchronized(lock) { currentDone.also { currentDone = null } } }
        // 대기 해제 신호는 lock 밖에서 보낸다(시작은 이미 세대 번호로 막혀 있음)
        d?.complete(Result.failure(IllegalStateException("stopped")))
    }

    private companion object {
        const val TAG = "KKIRI.ClipPlayer"
    }
}
