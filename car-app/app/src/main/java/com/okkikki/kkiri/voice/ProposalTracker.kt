package com.okkikki.kkiri.voice

import android.util.Log
import com.okkikki.kkiri.contract.Proposal
import com.okkikki.kkiri.contract.ProposalStatus
import com.okkikki.kkiri.contract.SessionRepository
import com.okkikki.kkiri.contract.Session
import com.okkikki.kkiri.contract.WaypointProposalResolved
import com.okkikki.kkiri.contract.WaypointProposed

/**
 * 다른 차가 낸 경유지 제안 묻기·응답(명세 5절-6, NAV-08, R-34).
 * - 내 차 responses가 PENDING인 제안만 "…제안했어요. 추가할까요?"(명세 문구)로 묻는다. 내가 낸 제안은 묻지 않는다.
 * - 스냅샷으로 대기 목록을 다시 만든다(다시 묻지 않음). 스냅샷에만 있고 **묻지 않은 제안은 응답 대상이 아니다.**
 *   그 제안의 waypoint.proposed가 뒤늦게 오면 그때 한 번 묻는다.
 * - "물었다" = **질문 낭독이 실제로 시작됐다**([ask]의 시작 신호, SpeechOutput.announce(onStart), 2026-11-12 합의).
 *   질문을 대기열에 넣기만 하고 아직 읽기 전이면 응답 대상이 아니다(앞 낭독이 길어도 그 제안에 잘못 답하지 않음).
 * - Accept/Decline은 **낭독이 가장 최근에 시작된** 대기 제안에만 R-34로 답한다. 끊겼다 다시 읽히면(시작 신호가
 *   다시 오면) 그 제안이 가장 최근이 된다. 물은 제안이 없으면 false → "지금은 답할 제안이 없어요".
 *   성공하면 대기에서 뺀다. 응답은 한 번에 하나만 보낸다.
 * - waypoint.proposal_resolved는 carId가 내 차인 것만 대기에서 뺀다(만료 EXPIRED 포함). 이미 끝난 제안의 늦은
 *   시작 신호는 무시한다.
 * - 출력이 실제 시작 시점을 모르면(SpeechOutput 기본 구현) 시작 신호는 대기열에 넣은 직후 온다(이전 동작).
 * 수락 후 내비 반영은 서버가 보내는 waypoint.added로 [com.okkikki.kkiri.navi.WaypointSync]가 한다.
 */
internal class ProposalTracker(
    private val repo: SessionRepository,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val warn: (String, Throwable?) -> Unit = { m, e -> Log.w(TAG, m, e) },
    /**
     * 제안 질문. 두 번째 인자는 낭독이 실제로 시작될 때마다 부를 함수(출력이 자기 lock 밖에서 부른다).
     * null이면 [announce]로 넣고 바로 시작으로 본다(시작 신호가 없는 출력, 이전 동작).
     */
    private val ask: ((String, () -> Unit) -> Unit)? = null,
    /** 실패 안내(R-34 실패). 마지막 인자(호출 관례 유지) */
    private val announce: (String) -> Unit,
) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, Proposal>()
    /** 질문을 출력에 넘긴 제안 id. 같은 제안을 두 번 묻지 않기 위한 것(lock으로 보호) */
    private val questioned = HashSet<String>()
    /** 질문 낭독이 시작된 제안 id(시작된 순서, 다시 시작되면 맨 뒤로). 응답은 이 안에서만 고른다(lock으로 보호) */
    private val asked = LinkedHashSet<String>()
    /** R-34 응답이 진행 중인 제안 id(lock으로 보호). 한 번에 하나만 보낸다 */
    private var inFlight: String? = null

    fun resync(session: Session) {
        val me = repo.myCarId
        synchronized(lock) {
            pending.clear()
            session.proposals.filter { isMinePending(it, me) }.forEach { pending[it.proposalId] = it }
            // 이미 물은 것만 응답 대상으로 남긴다. 스냅샷에만 있는 제안은 묻지 않았으므로 응답 대상이 아니다
            questioned.retainAll(pending.keys)
            asked.retainAll(pending.keys)
        }
        log("resync: 대기 제안 ${pending.size}건(물은 것 ${asked.size}건)")
    }

    fun onProposed(event: WaypointProposed) {
        val p = event.proposal
        if (!isMinePending(p, repo.myCarId)) return
        // 스냅샷으로 먼저 들어온 제안이어도 아직 묻지 않았으면 여기서 한 번 묻는다(같은 이벤트가 또 오면 다시 묻지 않음)
        val firstAsk = synchronized(lock) {
            pending[p.proposalId] = p
            questioned.add(p.proposalId)
        }
        if (!firstAsk) return
        val text = VoiceTexts.proposalAsk(p.proposedBy.name, p.place.name)
        val started = { onQuestionStarted(p.proposalId) }
        val a = ask
        if (a != null) {
            a(text, started)
        } else {
            announce(text)
            started()
        }
    }

    /** 질문 낭독 시작(끊겼다 다시 읽히면 다시 온다). 아직 대기 중인 제안만 응답 대상으로 올리고 가장 최근으로 옮긴다 */
    private fun onQuestionStarted(proposalId: String) {
        synchronized(lock) {
            if (!pending.containsKey(proposalId)) return
            asked.remove(proposalId)
            asked.add(proposalId)
        }
    }

    fun onResolved(event: WaypointProposalResolved) {
        if (event.carId != repo.myCarId) return
        synchronized(lock) {
            pending.remove(event.proposalId)
            questioned.remove(event.proposalId)
            asked.remove(event.proposalId)
        }
        log("제안 종료: ${event.proposalId} ${event.status}")
    }

    /**
     * 응답할(= 실제로 물은) 제안이 없으면 false. 묻지 않은 제안만 대기 중일 때도 false다.
     * 응답(R-34)이 진행 중이면 새 수락·거절은 **보내지 않고 소비만** 한다(true): 같은 제안에 중복 전송하지 않고,
     * 다른 대기 제안에 잘못 적용하지도 않는다. 진행 중 상태는 성공·실패·취소 모두에서 정리된다.
     * 실패하면 제안은 대기에 남아 다시 답할 수 있다.
     */
    suspend fun respond(accept: Boolean): Boolean {
        val p = synchronized(lock) {
            if (inFlight != null) return true // 진행 중: 중복·오적용 방지(표에 맞는 안내 문구가 없어 조용히 소비)
            val id = asked.lastOrNull { pending.containsKey(it) } ?: return false
            val p = pending.getValue(id)
            inFlight = p.proposalId
            p
        }
        try {
            repo.respondProposal(p.proposalId, accept)
                .onSuccess {
                    synchronized(lock) {
                        pending.remove(p.proposalId)
                        questioned.remove(p.proposalId)
                        asked.remove(p.proposalId)
                    }
                }
                .onFailure {
                    warn("R-34 실패: ${p.proposalId}", it)
                    announce(VoiceTexts.PROPOSAL_FAILED)
                }
        } finally {
            synchronized(lock) { if (inFlight == p.proposalId) inFlight = null }
        }
        return true
    }

    fun clear() {
        synchronized(lock) {
            pending.clear()
            questioned.clear()
            asked.clear()
        }
    }

    private fun isMinePending(p: Proposal, me: String?): Boolean =
        me != null && p.fromCarId != me &&
            p.responses.any { it.carId == me && it.status == ProposalStatus.PENDING }

    private companion object {
        const val TAG = "KKIRI.Proposal"
    }
}
