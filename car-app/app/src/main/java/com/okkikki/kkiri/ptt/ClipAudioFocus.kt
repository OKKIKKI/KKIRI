package com.okkikki.kkiri.ptt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.okkikki.kkiri.voice.audio.FocusChange
import com.okkikki.kkiri.voice.audio.SpeechFocus

/**
 * 받은 녹음 메시지 알림용. 실시간 통화(CALL)·내비 안내(NAVIGATION)로 분류하지 않는다.
 * TTS(메시지 낭독·시스템 안내)도 같은 용도·요청 방식으로 쓴다([asSpeechFocus], 2026-11-12 CAR-B 지시 #3).
 */
internal class ClipAudioFocus(context: Context) {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun session(listener: (Int) -> Unit): Session {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true) // 말소리를 낮춰 알아듣기 어렵게 만드는 대신 일시정지
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
            .build()
        return Session(manager, request)
    }

    class Session(private val manager: AudioManager?, private val request: AudioFocusRequest) {
        fun acquire(): Boolean = manager?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        fun release() { manager?.abandonAudioFocusRequest(request) }
    }

    /** 같은 속성·요청 방식을 [SpeechFocus] 경계로 내보낸다(TTS용). 거절이면 요청을 반납하고 null */
    fun asSpeechFocus(): SpeechFocus = SpeechFocus { onChange ->
        val s = session { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> onChange(FocusChange.GAIN)
                AudioManager.AUDIOFOCUS_LOSS -> onChange(FocusChange.LOSS)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onChange(FocusChange.LOSS_TRANSIENT)
            }
        }
        if (s.acquire()) {
            SpeechFocus.Held { s.release() }
        } else {
            s.release()
            null
        }
    }
}
