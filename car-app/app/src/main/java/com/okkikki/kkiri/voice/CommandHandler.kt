package com.okkikki.kkiri.voice

import android.util.Log
import com.okkikki.kkiri.contract.SessionRepository
import com.okkikki.kkiri.contract.VoiceCommand
import com.okkikki.kkiri.contract.VoiceRecorder
import kotlinx.coroutines.sync.Mutex

/** 제안 응답(단위 6에서 연결). 응답할 제안이 없으면 false */
internal fun interface ProposalResponder {
    suspend fun respond(accept: Boolean): Boolean
}

/**
 * 음성 명령 6종 처리(명세 5절-7, 6절 VoiceCommand). 서버 호출은 모두 [SessionRepository]로 한다.
 * - SendTo(text) → R-20 sendBroadcast
 * - SendTo(null)·startPtt → 출력 pause → 녹음(목업) → R-21 sendVoiceClip → resume(실패해도 resume)
 * - Mute → 되묻기 → R-22 setMute(target 그대로, "ALL" 포함)
 * - UndoWaypoint → 되묻기 → R-33 undoWaypoint
 * - MusicLock → R-46 setMusicLock
 * - AcceptProposal·DeclineProposal → 되묻는 중이면 응/아니, 아니면 제안 응답(R-34)
 * 실패는 [SpeechOutput.announce]로 안내한다(명세 5.1 문구, [VoiceTexts]).
 * 대상이 비었거나 글자가 공백뿐인 명령은 서버를 부르지 않고(되묻기·녹음도 하지 않고) 같은 실패 문구로 안내한다.
 * 글자가 공백뿐인 SendTo를 녹음형 무전(text=null)으로 바꾸지 않는다.
 * 확인 질문은 [ConfirmationSpeechOutput.announceAndAwait]로 읽고, 낭독이 끝난 뒤부터 제한 시간을 센다([ConfirmGate]).
 */
internal class CommandHandler(
    private val repo: SessionRepository,
    private val output: ConfirmationSpeechOutput,
    private val recorder: VoiceRecorder,
    private val gate: ConfirmGate,
    private val proposals: ProposalResponder,
    /** "ALL"·carId → 읽을 이름 */
    private val targetLabel: (String) -> String,
    private val maxClipMs: Long,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    /** 실패 로그(수준·예외 보존) */
    private val warn: (String, Throwable?) -> Unit = { m, e -> Log.w(TAG, m, e) },
) {
    private val pttLock = Mutex()

    suspend fun handle(command: VoiceCommand) {
        log("명령: $command")
        when (command) {
            is VoiceCommand.SendTo -> {
                val text = command.text
                if (text != null) sendText(command.target, text) else ptt(command.target)
            }
            is VoiceCommand.Mute -> {
                if (command.target.isBlank()) {
                    reject(VoiceTexts.MUTE_FAILED, "대상 없음")
                    return
                }
                confirmThen(VoiceTexts.confirmMute(targetLabel(command.target), command.muted)) {
                    repo.setMute(command.target, command.muted).onFailure { fail(VoiceTexts.MUTE_FAILED, it) }
                }
            }
            VoiceCommand.UndoWaypoint -> confirmThen(VoiceTexts.CONFIRM_UNDO) {
                repo.undoWaypoint().onFailure { fail(VoiceTexts.UNDO_FAILED, it) }
            }
            is VoiceCommand.MusicLock ->
                repo.setMusicLock(command.locked).onFailure { fail(VoiceTexts.MUSIC_LOCK_FAILED, it) }
            VoiceCommand.AcceptProposal -> answer(true)
            VoiceCommand.DeclineProposal -> answer(false)
        }
    }

    /** 녹음형 무전(VOI-01). 한 번에 하나만 녹음한다 */
    suspend fun ptt(target: String) {
        if (target.isBlank()) {
            reject(VoiceTexts.VOICE_SEND_FAILED, "대상 없음")
            return
        }
        if (!pttLock.tryLock()) {
            output.announce(VoiceTexts.PTT_BUSY)
            return
        }
        try {
            var failText: String? = null
            output.pause()
            try {
                val clip = recorder.record(maxClipMs).getOrElse {
                    warn("녹음 실패", it)
                    failText = VoiceTexts.RECORD_FAILED
                    null
                }
                if (clip != null) {
                    try {
                        repo.sendVoiceClip(clip.file, clip.durationMs, target).onFailure {
                            warn("R-21 실패", it)
                            failText = VoiceTexts.VOICE_SEND_FAILED
                        }
                    } finally {
                        // 전송 성공·실패·취소 모두에서 녹음 복사본을 지운다
                        if (!clip.file.delete()) warn("녹음 복사본 삭제 실패: ${clip.file.name}", null)
                    }
                }
            } finally {
                output.resume()
            }
            failText?.let { output.announce(it) }
        } finally {
            pttLock.unlock()
        }
    }

    private suspend fun sendText(target: String, text: String) {
        if (target.isBlank() || text.isBlank()) {
            reject(VoiceTexts.SEND_FAILED, if (target.isBlank()) "대상 없음" else "글자 없음")
            return
        }
        repo.sendBroadcast(text, target).onFailure { fail(VoiceTexts.SEND_FAILED, it) }
    }

    private suspend fun confirmThen(prompt: String, action: suspend () -> Unit) {
        when (gate.ask(prompt) { output.announceAndAwait(it) }) {
            ConfirmGate.Answer.YES -> action()
            ConfirmGate.Answer.NO -> output.announce(VoiceTexts.CONFIRM_DECLINED)
            ConfirmGate.Answer.TIMEOUT -> output.announce(VoiceTexts.CONFIRM_TIMEOUT)
            // 새 확인으로 대체·세션 종료·질문 낭독 실패(reset 등): 명령을 실행하지 않고 안내도 하지 않는다
            ConfirmGate.Answer.REPLACED -> log("확인 대체됨: $prompt")
            ConfirmGate.Answer.CANCELLED -> log("확인 취소됨(세션 종료·stop): $prompt")
            ConfirmGate.Answer.ABORTED -> log("질문 낭독이 끝나지 못해 확인 종료: $prompt")
        }
    }

    private suspend fun answer(yes: Boolean) {
        if (gate.answer(yes)) return
        if (!proposals.respond(yes)) output.announce(VoiceTexts.NO_PROPOSAL)
    }

    private fun fail(text: String, e: Throwable) {
        warn(text, e)
        output.announce(text)
    }

    /** 서버를 부르기 전에 걸러진 잘못된 명령(대상·글자 없음). 해당 기능의 실패 문구로 안내한다 */
    private fun reject(text: String, reason: String) {
        warn("$text: 명령 거부($reason)", null)
        output.announce(text)
    }

    private companion object {
        const val TAG = "KKIRI.Command"
    }
}
