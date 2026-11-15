package com.okkikki.kkiri.net

import com.okkikki.kkiri.contract.ActorType
import com.okkikki.kkiri.contract.Car
import com.okkikki.kkiri.contract.CarJoined
import com.okkikki.kkiri.contract.CarLook
import com.okkikki.kkiri.contract.CarLookChanged
import com.okkikki.kkiri.contract.CarStatusChanged
import com.okkikki.kkiri.contract.Message
import com.okkikki.kkiri.contract.MessageCreated
import com.okkikki.kkiri.contract.Music
import com.okkikki.kkiri.contract.MusicLockChanged
import com.okkikki.kkiri.contract.MusicNowPlaying
import com.okkikki.kkiri.contract.MusicQueueAdded
import com.okkikki.kkiri.contract.Mute
import com.okkikki.kkiri.contract.MuteChanged
import com.okkikki.kkiri.contract.NameRef
import com.okkikki.kkiri.contract.NowPlaying
import com.okkikki.kkiri.contract.Participant
import com.okkikki.kkiri.contract.ParticipantJoined
import com.okkikki.kkiri.contract.ParticipantLeft
import com.okkikki.kkiri.contract.ParticipantRef
import com.okkikki.kkiri.contract.Place
import com.okkikki.kkiri.contract.Proposal
import com.okkikki.kkiri.contract.ProposalResponse
import com.okkikki.kkiri.contract.Qr
import com.okkikki.kkiri.contract.Sender
import com.okkikki.kkiri.contract.ServerEvent
import com.okkikki.kkiri.contract.Session
import com.okkikki.kkiri.contract.SessionEnded
import com.okkikki.kkiri.contract.SessionSnapshot
import com.okkikki.kkiri.contract.SessionStateChanged
import com.okkikki.kkiri.contract.Summary
import com.okkikki.kkiri.contract.Unknown
import com.okkikki.kkiri.contract.VoiceClip
import com.okkikki.kkiri.contract.Waypoint
import com.okkikki.kkiri.contract.WaypointAdded
import com.okkikki.kkiri.contract.WaypointProposalResolved
import com.okkikki.kkiri.contract.WaypointProposed
import com.okkikki.kkiri.contract.WaypointRemoved
import com.okkikki.kkiri.contract.You
import org.json.JSONArray
import org.json.JSONObject

/**
 * 서버 JSON → contract 모델 (명세 2절 모델, 4.2 봉투, 4.3 이벤트). Android 내장 org.json 사용(CAR-A 승인).
 *
 * 규칙 (명세 1.1, 6절):
 * - 필드 이름은 명세 그대로. 모르는 필드는 읽지 않는다(무시).
 * - 모르는 이벤트 type은 [Unknown]으로 돌려준다(에러 아님).
 * - null 가능 필드는 contract에서 `?`인 것뿐. 그 밖의 필드가 없거나 형식이 틀리면 예외를 던진다
 *   (org.json.JSONException 또는 enum 값이 틀리면 IllegalArgumentException). 잡아서 처리하는 것은 부르는 쪽 몫.
 */
object JsonMapper {

    // ── 봉투·이벤트 (4.2, 4.3) ─────────────────────────────

    fun serverEvent(text: String): ServerEvent = serverEvent(JSONObject(text))

    fun serverEvent(o: JSONObject): ServerEvent {
        val type = o.getString("type")
        val sid = o.getString("sessionId")
        val seq = o.getLong("seq")
        val ts = o.getString("ts")
        val p = o.optJSONObject("payload") ?: JSONObject()
        return when (type) {
            "session.snapshot" -> SessionSnapshot(sid, seq, ts, session(p.getJSONObject("session")), you(p.getJSONObject("you")))
            "session.state_changed" -> SessionStateChanged(
                sid, seq, ts, enumOf(p, "state"), stringOrNull(p, "pausedAt"), stringOrNull(p, "expiresAt"),
            )
            "session.ended" -> SessionEnded(sid, seq, ts, enumOf(p, "reason"), summary(p.getJSONObject("summary")))
            "participant.joined" -> ParticipantJoined(sid, seq, ts, participant(p.getJSONObject("participant")))
            "participant.left" -> ParticipantLeft(sid, seq, ts, participant(p.getJSONObject("participant")))
            "car.joined" -> CarJoined(sid, seq, ts, car(p.getJSONObject("car")))
            "car.status_changed" -> CarStatusChanged(
                sid, seq, ts, p.getString("carId"), enumOf(p, "status"), enumOf(p, "driveState"),
            )
            "car.look_changed" -> CarLookChanged(sid, seq, ts, p.getString("carId"), look(p.getJSONObject("look")))
            "message.created" -> MessageCreated(
                sid, seq, ts, message(p.getJSONObject("message")), enumOf(p.getJSONObject("delivery"), "mode"),
            )
            "mute.changed" -> MuteChanged(sid, seq, ts, p.getJSONArray("mutes").mapObjects(::mute))
            "waypoint.added" -> WaypointAdded(sid, seq, ts, waypoint(p.getJSONObject("waypoint")), p.optBoolean("byUndo", false))
            "waypoint.removed" -> WaypointRemoved(
                sid, seq, ts, p.getString("waypointId"), p.getString("carId"), p.optBoolean("byUndo", false),
            )
            "waypoint.proposed" -> WaypointProposed(sid, seq, ts, proposal(p.getJSONObject("proposal")))
            "waypoint.proposal_resolved" -> WaypointProposalResolved(
                sid, seq, ts, p.getString("proposalId"), p.getString("carId"), enumOf(p, "status"),
            )
            "music.now_playing" -> MusicNowPlaying(sid, seq, ts, music(p.getJSONObject("music")))
            "music.queue_added" -> MusicQueueAdded(
                sid, seq, ts, p.getString("title"), p.getString("artist"), NameRef(p.getJSONObject("addedBy").getString("name")),
            )
            "music.lock_changed" -> MusicLockChanged(sid, seq, ts, p.getBoolean("locked"))
            else -> Unknown(sid, seq, ts, type)
        }
    }

    // ── REST 응답 감싸기 ─────────────────────────────────────

    /** `{ "session": Session }` (R-02, R-04, R-05, R-07) */
    fun sessionResponse(text: String): Session = session(JSONObject(text).getJSONObject("session"))

    /** `{ "session": Session 또는 null }` (R-03) */
    fun sessionOrNullResponse(text: String): Session? {
        val o = JSONObject(text)
        return if (o.isNull("session")) null else session(o.getJSONObject("session"))
    }

    // ── 모델 (2절) ───────────────────────────────────────────

    fun session(o: JSONObject) = Session(
        sessionId = o.getString("sessionId"),
        name = o.getString("name"),
        state = enumOf(o, "state"),
        isAccountSession = o.getBoolean("isAccountSession"),
        hostCarId = o.getString("hostCarId"),
        destination = objectOrNull(o, "destination")?.let(::place),
        joinCode = o.getString("joinCode"),
        qr = o.getJSONObject("qr").let { Qr(it.getString("url"), it.getString("expiresAt")) },
        cars = o.getJSONArray("cars").mapObjects(::car),
        participants = o.getJSONArray("participants").mapObjects(::participant),
        waypoints = o.getJSONArray("waypoints").mapObjects(::waypoint),
        proposals = o.getJSONArray("proposals").mapObjects(::proposal),
        mutes = o.getJSONArray("mutes").mapObjects(::mute),
        music = music(o.getJSONObject("music")),
        createdAt = o.getString("createdAt"),
        pausedAt = stringOrNull(o, "pausedAt"),
        expiresAt = stringOrNull(o, "expiresAt"),
    )

    fun place(o: JSONObject) = Place(
        placeId = o.getString("placeId"),
        name = o.getString("name"),
        address = o.getString("address"),
        lat = o.getDouble("lat"),
        lng = o.getDouble("lng"),
        distanceKm = o.getDouble("distanceKm"),
    )

    fun car(o: JSONObject) = Car(
        carId = o.getString("carId"),
        displayName = o.getString("displayName"),
        driverName = o.getString("driverName"),
        role = enumOf(o, "role"),
        status = enumOf(o, "status"),
        driveState = enumOf(o, "driveState"),
        look = o.optJSONObject("look")?.let(::look),
    )

    fun look(o: JSONObject) = CarLook(
        steerStep = o.getInt("steerStep"),
        color = stringOrNull(o, "color"),
        hat = o.optBoolean("hat", false),
        balloon = o.optBoolean("balloon", false),
        character = stringOrNull(o, "character"),
        items = o.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { v -> v.isNotEmpty() } } } ?: emptyList(),
    )

    fun participant(o: JSONObject) = Participant(
        participantId = o.getString("participantId"),
        name = o.getString("name"),
        role = enumOf(o, "role"),
        carId = o.getString("carId"),
        isMember = o.getBoolean("isMember"),
        online = o.getBoolean("online"),
    )

    fun waypoint(o: JSONObject) = Waypoint(
        waypointId = o.getString("waypointId"),
        carId = o.getString("carId"),
        place = place(o.getJSONObject("place")),
        addedBy = participantRef(o.getJSONObject("addedBy")),
        createdAt = o.getString("createdAt"),
    )

    fun proposal(o: JSONObject) = Proposal(
        proposalId = o.getString("proposalId"),
        place = place(o.getJSONObject("place")),
        fromCarId = o.getString("fromCarId"),
        proposedBy = participantRef(o.getJSONObject("proposedBy")),
        responses = o.getJSONArray("responses").mapObjects { ProposalResponse(it.getString("carId"), enumOf(it, "status")) },
        expiresAt = o.getString("expiresAt"),
    )

    fun message(o: JSONObject) = Message(
        messageId = o.getString("messageId"),
        kind = enumOf(o, "kind"),
        sender = o.getJSONObject("sender").let {
            Sender(enumOf(it, "type"), it.getString("id"), it.getString("name"), it.getString("carId"))
        },
        target = o.getString("target"),
        text = stringOrNull(o, "text"),
        clip = objectOrNull(o, "clip")?.let { VoiceClip(it.getString("url"), it.getLong("durationMs"), it.getString("mime")) },
        urgent = o.getBoolean("urgent"),
        createdAt = o.getString("createdAt"),
    )

    fun mute(o: JSONObject) = Mute(o.getString("byCarId"), o.getString("targetCarId"))

    fun music(o: JSONObject) = Music(
        connected = o.getBoolean("connected"),
        locked = o.getBoolean("locked"),
        nowPlaying = objectOrNull(o, "nowPlaying")?.let {
            NowPlaying(it.getString("title"), it.getString("artist"), stringOrNull(it, "albumImageUrl"), it.getBoolean("isPlaying"))
        },
    )

    private fun you(o: JSONObject) = You(
        actorType = enumOf<ActorType>(o, "actorType"),
        carId = stringOrNull(o, "carId"),
        participantId = stringOrNull(o, "participantId"),
        role = stringOrNull(o, "role"),
    )

    private fun summary(o: JSONObject) = Summary(
        places = o.getJSONArray("places").mapStrings(),
        cars = o.getJSONArray("cars").mapStrings(),
        trackCount = o.getInt("trackCount"),
    )

    private fun participantRef(o: JSONObject) = ParticipantRef(o.getString("participantId"), o.getString("name"))

    // ── 도우미 ───────────────────────────────────────────────

    /** enum은 명세 1.5의 대문자 값 그대로. 모르는 값이면 IllegalArgumentException */
    private inline fun <reified T : Enum<T>> enumOf(o: JSONObject, key: String): T = enumValueOf(o.getString(key))

    /** JSON null 또는 없음 → null. (org.json의 getString은 null을 "null" 글자로 돌려주므로 따로 확인) */
    private fun stringOrNull(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.getString(key)

    private fun objectOrNull(o: JSONObject, key: String): JSONObject? = if (o.isNull(key)) null else o.getJSONObject(key)

    private fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }

    private fun JSONArray.mapStrings(): List<String> = (0 until length()).map { getString(it) }
}
