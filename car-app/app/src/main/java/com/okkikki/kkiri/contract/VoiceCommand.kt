package com.okkikki.kkiri.contract

/** 명세 6절. 음성 명령 6종. */
sealed interface VoiceCommand {
    /** text=null이면 녹음형 무전 */
    data class SendTo(val target: String, val text: String?) : VoiceCommand

    /** target: carId 또는 "ALL" */
    data class Mute(val target: String, val muted: Boolean) : VoiceCommand
    object AcceptProposal : VoiceCommand
    object DeclineProposal : VoiceCommand
    object UndoWaypoint : VoiceCommand
    data class MusicLock(val locked: Boolean) : VoiceCommand
}
