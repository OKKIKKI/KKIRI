pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // D-26: Pleos TextToSpeech SDK 저장소(로그인 불필요 확인됨) (PM 승인 2026-11-12, Q-CAR-A-002)
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}
rootProject.name = "KKIRI"
include(":app")
