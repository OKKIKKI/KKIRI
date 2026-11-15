package com.okkikki.kkiri.state

import android.content.Context
import android.util.Log
import com.okkikki.kkiri.contract.DriveStateProvider
import com.okkikki.kkiri.contract.Session
import com.okkikki.kkiri.contract.SessionRepository
import com.okkikki.kkiri.contract.SessionState
import com.okkikki.kkiri.contract.VoiceHub
import com.okkikki.kkiri.voice.VoiceHubImpl

/**
 * 앱 전체에서 하나만 쓰는 VoiceHub(CAR-B 구현)와 그 시작·종료 (docs/scope/CAR-A.md 3절).
 * - 세션이 ACTIVE가 되면 start(repo, drive), 세션이 없어지거나 ENDED면 stop().
 * - RESERVED·PAUSED에서는 상태를 바꾸지 않는다(일시정지 중에도 보관·낭독 대기는 CAR-B가 판단).
 * - 화면(Activity)이 다시 만들어져도 두 번 시작하지 않도록 시작 여부를 여기 둔다.
 */
object VoiceHubs {
    private const val TAG = "KKIRI-VoiceHubs"

    @Volatile private var instance: VoiceHub? = null
    private var started = false

    fun get(context: Context): VoiceHub =
        instance ?: synchronized(this) {
            instance ?: VoiceHubImpl(context.applicationContext).also { instance = it }
        }

    /** 세션 값이 바뀔 때마다 부른다 */
    @Synchronized
    fun sync(context: Context, session: Session?, repo: SessionRepository, drive: DriveStateProvider) {
        val hub = get(context)
        val state = session?.state
        when {
            state == SessionState.ACTIVE && !started -> {
                hub.start(repo, drive)
                started = true
                Log.i(TAG, "VoiceHub 시작 (session=${session.sessionId})")
            }
            (session == null || state == SessionState.ENDED) && started -> {
                hub.stop()
                started = false
                Log.i(TAG, "VoiceHub 정지")
            }
        }
    }
}
