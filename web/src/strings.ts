import type { CarStatus, ProposalStatus } from './api';

// 화면 문구는 docs/03_SCREENS.md의 것만 쓴다. 문서에 없던 것은 담당자 승인 문구이며 출처를 적는다.
// 새 문구가 필요하면 여기 추가하기 전에 [승인 요청]을 올린다.

export const S = {
  join: {
    title: 'KKIRI 세션에 참여해요', // WEB-01 ①
    nameLabel: '이름', // WEB-01 ② 요소 이름
    submit: '입장하기', // WEB-01 ③
    notice: '앱 설치·가입이 필요 없어요. 차에 연결 기록이 남지 않아요.', // WEB-01 ④
  },
  error: {
    QR_EXPIRED: 'QR이 만료됐어요. 호스트에게 새 QR을 요청해 주세요', // WEB-01 오류
    SESSION_ENDED: '이미 끝난 세션이에요', // WEB-01 오류
    SESSION_NOT_FOUND: '세션을 찾을 수 없어요', // CAR-03 문구 재사용 (승인 2026-11-11, 팀원)
    retry: '잠시 후 다시 시도해 주세요', // 네트워크·INTERNAL (승인 2026-11-11, 팀원)
  },
  home: {
    bannerReserved: '차가 아직 연결되지 않았어요', // WEB-02 ①
    bannerPaused: '일시정지 중이에요', // WEB-02 ①
    people: (n: number) => `${n}명`, // WEB-02 ① 인원 (승인 2026-11-11, 팀원)
    route: '경로', // WEB-02 ② 요소 이름
    destination: (name: string) => `목적지 : ${name}`, // CAR-01 ① 형식 재사용
    noDestination: '목적지 없음', // CAR-01 ① 재사용 (승인 2026-11-11, 팀원)
    addWaypoint: '+ 경유지 추가', // WEB-02 ③
    cars: '일행 차량', // WEB-02 ④ 요소 이름
    carLine: (displayName: string, driverName: string, status: string) => `${displayName} · ${driverName} · ${status}`, // WEB-02 ④
    mutedUs: (carName: string) => `${carName}가 우리 차를 음소거했어요`, // WEB-02 ④
    remove: '삭제', // WEB-02 ② 내가 넣은 경유지의 [삭제]
    proposals: '제안 상태', // WEB-02 ⑥ 요소 이름
    proposalLine: (carName: string, status: string) => `${carName} ${status}`, // WEB-02 ⑥ "B차 대기 중"
  },
  // WEB-02 ⑥ "대기 중 / 수락 / 거절 / 시간 초과"
  proposalStatus: {
    PENDING: '대기 중',
    ACCEPTED: '수락',
    DECLINED: '거절',
    EXPIRED: '시간 초과',
  } satisfies Record<ProposalStatus, string>,
  me: {
    login: '카카오로 로그인', // WEB-07 ①
    hello: (name: string) => `${name}님`, // WEB-07 ①
    myCar: (displayName: string) => `내 차 : ${displayName} (등록됨)`, // WEB-07 ②
    sessionName: '세션 이름', // WEB-07 ③ "세션 이름 입력" 요소 이름
    reserve: '세션 미리 열기', // WEB-07 ③
    qrHint: '걸어가면서 보여 주세요', // WEB-07 ④
    preJoin: (n: number, m: number) => `미리 들어온 사람 ${n}명 · 경유지 ${m}곳`, // WEB-07 ⑤ (n은 리더 제외, 팀원 결정)
    carAttached: '차가 연결됐어요', // WEB-07 (session.state_changed → ACTIVE)
    friends: '친구 차량', // WEB-07 ⑥ 요소 이름
    friendLine: (displayName: string, ownerName: string) => `${displayName} · ${ownerName}`, // WEB-07 ⑥ 목록
    invite: '초대', // WEB-07 ⑥ [초대]
    preparing: '준비 중이에요', // WEB-07 ⑥ [초대] 결과
    goSession: '세션으로 가기', // WEB-07 [세션으로 가기]
  },
  places: {
    title: '장소 검색·경유지', // WEB-03 화면 이름
    search: '장소 검색', // WEB-03 ① 검색창 접근성 이름(화면 이름에서)
    distance: (km: number) => `${km}km`, // WEB-03 ② "{distanceKm}km"
    scopeMyCar: '우리 차만', // WEB-03 ③ (기본)
    scopePropose: '다른 차에도 제안', // WEB-03 ③
    add: '경유지로 추가', // WEB-03 ④
    added: '경유지에 추가했어요', // WEB-03 ④ 성공 토스트
  },
  message: {
    title: '메시지', // WEB-02 ⑤ 요소 이름(메시지 목록 + 입력)
    urgent: '긴급', // WEB-02 ⑤ [긴급] 토글, 목록의 긴급 표시
    send: '보내기', // WEB-02 ⑤ [보내기]
    urgentLimited: (sec: number) => `${sec}초 뒤에 다시 보낼 수 있어요`, // WEB-02 ⑤ URGENT_RATE_LIMITED
    voice: '음성 메시지', // CAR-05 ② 재사용: VOICE 메시지(text=null) 표시
    meta: (name: string, time: string) => `${name} · ${time}`, // 보낸 사람 · 시각(HH:mm)
  },
  // CAR-05 ① 상태 문구 재사용 (승인 2026-11-11, 팀원)
  carStatus: {
    CONNECTED: '연결됨',
    PAUSED: '일시정지',
    DISCONNECTED: '끊김',
  } satisfies Record<CarStatus, string>,
  // WEB-02 ⑦ 하단 탭
  tabs: {
    session: '세션',
    music: '음악',
  },
  // WEB-05 음악 탭 (03_SCREENS WEB-05. 보충 문구는 Q-WEB-019로 PM 승인·반영)
  music: {
    queuedByOther: (name: string, title: string) => `${name}님이 다음 곡으로 넣었어요: ${title}`, // music.queue_added(다른 사람)
    searchLabel: '곡 검색', // ③ 입력칸 접근성 이름
    errNotConnected: 'Spotify가 연결되지 않았어요', // MUSIC_NOT_CONNECTED
    errLocked: '운전자가 음악을 잠갔어요', // MUSIC_LOCKED
    nothing: '재생 중인 곡이 없어요', // ①
    prev: '이전', // ②
    pause: '일시정지', // ②
    play: '재생', // ②
    next: '다음 곡', // ②
    queue: '다음 곡으로', // ④ 버튼
    queued: '다음 곡으로 넣었어요', // ④ 토스트
    locked: '운전자가 음악을 잠갔어요', // ⑤
    notConnected: 'Spotify가 연결되지 않았어요', // 미연결
    goConnect: '연결하러 가기', // 미연결
    errNoDevice: '차에서 Spotify를 먼저 재생해 주세요', // MUSIC_NO_ACTIVE_DEVICE
    errPremium: 'Spotify Premium 계정이 필요해요', // MUSIC_PREMIUM_REQUIRED
    errRate: '잠시 후 다시 눌러 주세요', // CONTROL_RATE_LIMITED
  },
  // WEB-04 오늘의 주행 요약. 목록 잇기·빈 값·직접 접속·버튼 노출 기준은 Q-WEB-020(승인 대기)
  summary: {
    places: (list: string) => `들른 곳 ${list}`, // ①
    cars: (list: string) => `함께한 차 ${list}`, // ①
    tracks: (n: number) => `함께 들은 곡 ${n}곡`, // ①
    guestNotice: '이 화면을 닫으면 사라져요. 서버에 남지 않아요.', // ②
    startAgain: '이 일행과 다음에도 바로 시작', // ③
    keepSession: '세션 유지하기', // ④
    preparing: '준비 중이에요', // ③·④ 누르면
  },
  // WEB-06 Spotify 연결
  connect: {
    intro: '뒷자리에서 이 Spotify를 넘기고 신청할 수 있어요', // ①
    button: 'Spotify로 연결', // ②
    conditions: 'Spotify Premium 계정 필요 · 차에서 Spotify가 재생 중이어야 해요 · 세션이 끝나면 연결이 해제돼요', // ③
    connected: '연결됨', // ④
    notConnected: '연결 안 됨', // ④
  },
} as const;
