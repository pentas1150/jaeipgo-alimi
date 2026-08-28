rootProject.name = "alimi"

// 백엔드는 배포 단위 = 모듈 이다. (docs/DESIGN.md §11)
//
//   contract  프로세스 간 계약. 의존성 0.
//   core      엔티티 / 리포지토리 / 도메인 / 마이그레이션
//   app-*     실행 가능한 bootJar. 서로를 절대 의존하지 않는다.
//
// frontend 는 Gradle 이 아니라 npm/Vite 로 빌드하므로 여기 없다.
include(
    ":backend:contract",
    ":backend:core",
    ":backend:app-api",
    ":backend:app-scheduler",
    ":backend:app-checker",
    ":backend:app-notifier",
)
