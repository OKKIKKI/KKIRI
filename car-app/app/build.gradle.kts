plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** Gradle 속성(-P 또는 gradle.properties)이 있으면 그 값, 없으면 기본값 */
fun prop(name: String, default: String): String = (project.findProperty(name) as String?) ?: default

android {
    namespace = "com.okkikki.kkiri"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.okkikki.kkiri"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 명세 1.2절 서버 주소. 기본값은 에뮬레이터용(PC의 localhost = 10.0.2.2).
        // 실기기 통합 때는 -Pkkiri.apiBase=http://<PC LAN IP>:8080 (Q-PM-001, docs/06_INTEGRATION_TEST.md)
        // WebSocket 주소는 따로 주지 않으면 API 주소의 http→ws(https→wss)로 만든다. 따로 줄 때는 -Pkkiri.wsBase=...
        val apiBase = prop("kkiri.apiBase", "http://10.0.2.2:8080")
        val wsBase = prop("kkiri.wsBase", apiBase.replaceFirst("http", "ws"))
        buildConfigField("String", "KKIRI_API_BASE", "\"$apiBase\"")
        buildConfigField("String", "KKIRI_WS_BASE", "\"$wsBase\"")
        buildConfigField("int", "TTS_MIN_GAP_SEC", "10")
        // V2-S3: 앰비언트 알림 유지 시간(ms, 명세 1.2)
        buildConfigField("int", "AMBIENT_FLASH_MS", prop("kkiri.ambientFlashMs", "2000"))
        // Q-PM-003: 음소거·되돌리기 확인 질문 답을 기다리는 시간(초, D-24). CAR-B가 쓴다
        buildConfigField("int", "CONFIRM_TIMEOUT_SEC", prop("kkiri.confirmTimeoutSec", "10"))

        // R-01 차량 등록 값. D-19: 입력 화면 없이 빌드 설정값으로 넣는다.
        // 시연 차마다 다르게 빌드하려면 -Pkkiri.deviceId=... 처럼 Gradle 속성으로 바꾼다.
        // deviceId 기본값 dev-0001은 BE 시드(backend/seed/users.json, Q-018)의 개발용 값
        // 서버 없이 fixtures 목업으로 돌리려면 -Pkkiri.useMock=true (기본은 실제 서버, 05_DEV_RULES 4절)
        buildConfigField("boolean", "KKIRI_USE_MOCK", prop("kkiri.useMock", "false"))
        // 시연(2026-11-15): 디버그 패널(주행 토글·음성 명령) 기본 숨김. 다시 보려면 -Pkkiri.debugPanels=true (디버그 빌드에서만)
        buildConfigField("boolean", "KKIRI_DEBUG_PANELS", prop("kkiri.debugPanels", "false"))
        // 시연(2026-11-15): 경유지를 Pleos 순정 내비에 반영(기본 켬). 끄면 MockNaviAdapter
        buildConfigField("boolean", "KKIRI_REAL_NAVI", prop("kkiri.realNavi", "true"))
        buildConfigField("String", "KKIRI_DEVICE_ID", "\"${prop("kkiri.deviceId", "dev-0001")}\"")
        buildConfigField("String", "KKIRI_CAR_DISPLAY_NAME", "\"${prop("kkiri.carDisplayName", "A차")}\"")
        buildConfigField("String", "KKIRI_DRIVER_NAME", "\"${prop("kkiri.driverName", "드라이버A")}\"")
    }

    buildFeatures { buildConfig = true }

    // V2-S0: AAOS Car API(android.car.*)를 컴파일 시 참조한다(기어·시동 읽기). 플랫폼 선택 라이브러리.
    useLibrary("android.car")

    // SessionRepository 목업이 읽는 예시 JSON. docs/fixtures(PM 소유)를 복사하지 않고 가리키기만 한다.
    sourceSets {
        getByName("main") {
            assets.srcDir("../../docs/fixtures")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Q-007: contract/의 StateFlow·SharedFlow·suspend용 (팀장 승인 2026-11-11)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 화면(Activity)에서 Flow를 안전하게 구독하는 lifecycleScope용
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // D-22: net/ 실제 구현의 REST·WebSocket 통신 (PM 승인 2026-11-12)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // D-25: CAR-02 QR 그리기(인코딩만, 카메라·스캔 미사용) (PM 승인 2026-11-12, Q-CAR-A-001)
    implementation("com.google.zxing:core:3.5.3")
    // D-26: CAR-B 낭독 엔진(Pleos TextToSpeech SDK, Mode.ON_DEVICE) (PM 승인 2026-11-12, Q-CAR-A-002)
    // 3.3.1이 에뮬레이터에서 안 되면 2.1.5.1까지만 시험(그 버전 문서로 권한 이름 재확인)
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // V2-S0: Pleos Vehicle SDK(핸들 각도·엠비언트 라이트). 선언부는 docs/pleos/VEHICLE_SDK_SIGNATURES.md (V2 계획 승인)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // 시연(2026-11-15): Pleos 순정 내비 경유지 반영(PleosNaviAdapter). -Pkkiri.realNavi=false면 목업
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
