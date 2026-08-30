# jaeipgo-alimi

네이버 스마트스토어 품절 상품 재입고 알림 서비스.

## 먼저 읽을 것

**[docs/DESIGN.md](docs/DESIGN.md)** — 아키텍처 합의문. 도메인 모델, Kafka 토픽, 상태 전이 규칙,
모듈 구조, 배포까지 전부 여기 있다. 코드를 쓰기 전에 반드시 읽는다.

## 핵심 규칙 (어기면 안 되는 것)

1. **알림은 `OUT_OF_STOCK → IN_STOCK` 전이에서만 발행한다.** `IN_STOCK`을 관측했다는 이유로 알리면 안 된다.
2. **판정 실패는 `last_status` 를 건드리지 않는다** (fail-closed). 절대 재입고로 취급하지 않는다.
   ⚠️ 실패를 `UNKNOWN` 으로 **덮으면 안 된다** — 실패 한 번이 곧바로 재입고 누락이 된다 (§4.1).
   `UNKNOWN` 의 뜻은 "모른다"가 아니라 **"아직 한 번도 관측하지 못했다"** 다.
3. **재고 체크는 상품 단위, 알림은 구독 단위.** 같은 상품을 N명이 구독해도 브라우저는 1번만 띄운다.
4. **Kafka 발행에는 반드시 키를 넣는다** (`productId` / `watchId`). 키가 없으면 sticky 파티셔너가
   한 파티션에 다 몰아넣어 **오토스케일이 통째로 무력화된다.** (실측: §12.6 ③⑥)
5. **모든 컨슈머는 멱등해야 한다.** Kafka는 at-least-once다. 발송은 `notification_log.idempotency_key` UNIQUE로 물리 차단한다.
6. **`BrowserContext`는 반드시 close한다.** 안 닫으면 메모리가 샌다.
7. **도메인은 Kafka를 모른다.** 발행은 `@TransactionalEventListener(AFTER_COMMIT)`를 거친다 (dual-write 방지).
8. **알림 발송은 `NotificationSender` 포트로만 한다.** 호출하는 쪽이 채널을 알면 안 된다.
   채널 추가 = 구현체 1개 추가. `Registry`/`Listener` 는 수정하지 않는다. (§11)
9. **발송 실패는 재시도 가능 여부를 구분해 던진다** — `TransientSendException` / `PermanentSendException`.
   영구 실패를 재시도하면 뒤에 쌓인 정상 메시지가 밀린다.
10. **마이그레이션은 직전 버전 앱과 호환되어야 한다.** CD 는 마이그레이션 Job 을 끝낸 뒤 앱을
   롤링한다 — 즉 **구버전 파드가 새 스키마 위에서 잠시 돈다.** 컬럼 추가는 되지만
   DROP/RENAME 은 두 번의 배포로 나눠야 한다. Flyway 에 down 은 없다. (§12.9)

## 모듈 구조

```
backend/contract/     의존성 0. 이벤트 DTO + 토픽 이름
backend/core/         엔티티 / 리포지토리 / 도메인 / Flyway / 공통 설정(application.yml)
backend/app-api/      → alimi-api
backend/app-scheduler/→ alimi-scheduler   (반드시 replicas=1)
backend/app-checker/  → alimi-checker     (Playwright는 여기만)
backend/app-notifier/ → alimi-notifier
frontend/             Vite → 정적 빌드 → nginx (배포에 Node 런타임 없음)
```

**지켜야 할 의존성 규칙:**

- `app-*` 는 **서로를 절대 의존하지 않는다.** 통신은 Kafka로만.
- `contract` 에는 **의존성을 추가하지 않는다.** 넣고 싶어지면 그건 `core`에 갈 것이다.
- 무거운 의존성은 **쓰는 모듈에만.** 메일 클라이언트는 `app-notifier`, 브라우저는 `app-checker`.
- **`common` 모듈을 만들지 않는다.** 이유는 DESIGN.md §10.1.

**프로파일은 환경만 의미한다** (`local` / `docker` / `k8s`). 역할은 모듈이 나눈다 —
`@Profile("checker")` 같은 역할 프로파일은 쓰지 않는다.

## 스키마 변경

`backend/core/src/main/resources/db/migration/V{n}__*.sql` 에 추가한다.
JPA `ddl-auto=validate` 이므로 엔티티와 스키마가 어긋나면 기동에 실패한다.

**k8s에서는 앱이 Flyway를 돌리지 않는다** — `k8s/base/migration-job.yaml` 이 소유한다 (§10.4).
로컬(`local`/`docker`)에서는 앱이 직접 돌린다.
배포 시에는 CD 가 이 Job 을 **앱보다 먼저** 완료시킨다. 그래서 위 규칙 10이 따라온다.

## 배포

```
k8s/base/         공통 매니페스트
k8s/overlays/pi/  라즈베리파이4(4GB, k3s) — CD 가 배포하는 실제 환경
k8s/keda/         KEDA ScaledObject (kind 전용. 파이에는 안 올린다)
```

- **kind (학습/실험)**: `kubectl apply -k k8s/base/` — 절차는 [k8s/README.md](k8s/README.md), 배경은 §12
- **라즈베리파이 (자동)**: `main` 푸시 → `.github/workflows/cd.yml` — 런북은 [k8s/pi/README.md](k8s/pi/README.md), 배경은 §12.9~12.10

**Secret 은 리포지토리에 없다.** `k8s/base/secret.env`(git 제외)를 만들어야 배포된다 —
`cp k8s/base/secret.env.example k8s/base/secret.env`. 자격증명을 매니페스트에 직접 쓰지 않는다.
CD 는 GitHub Secret `ALIMI_SECRET_ENV` 를 배포 시점에만 이 파일로 풀었다 지운다.

**Kafka 컨슈머 파드는 파티션 수를 넘겨 늘려봐야 논다.** `maxReplicaCount ≤ 파티션 수`를 지킨다.
단 실제 상한은 `min(파티션 수, 노드 자원)` 이다 — 파이4 4GB 에서는 노드 자원이 이긴다 (§12.10).

## 현재 상태

`notification` 패키지(core/app-api/app-notifier에 흩어져 있음)는 **배선 확인용 샘플**이다.
실제 도메인(`Product` / `Watch` / `NotificationLog`)이 정해지면 교체 대상이다.

구현된 것:
- **알림 발송 추상화** (`app-notifier/send/`) — 포트 + 이메일/로그 어댑터 + 재시도 정책. 테스트 10개
- `notification_log` 멱등성 (V2 마이그레이션)
- **회원 + 구글 OAuth 로그인** (V3) — `core/user/`, `app-api/auth/`. 세션은 Redis (§8.1)
- **`Product` 엔티티 + 상태 전이** (V4) — `core/product/`. 재고/감시 2축 분리 (§4.1)

아직 없는 것:
- `Topics.kt` 의 실제 토픽들은 **생성만 되고 프로듀서/컨슈머가 없다**
- `app-checker` 에 Playwright 의존성은 있으나 **판정 코드가 없다** (셀렉터 미확정)
- `notification.dispatch.v1` 은 **컨슈머만 있고 프로듀서가 없다** (팬아웃 미구현)
- 이메일은 기본이 `log` 어댑터다. 실제 발송하려면 `alimi.notification.email.transport=smtp`
- Spring Batch (`spring-boot-starter-batch`) 미추가 — 메타데이터 테이블 마이그레이션과 함께 도입

## 인증

**신원은 이메일이 아니라 `(provider, provider_user_id)` 다.** 구글 계정 이메일은 바뀔 수 있고
불변 식별자는 `sub` 다. `email` 은 신원이 아니라 알림 수신 주소이므로 로그인마다 갱신한다.

**컨트롤러는 공급자를 모른다.** `@AuthenticationPrincipal me: AuthUser` 만 받는다.
공급자 추가 = `AlimiOAuth2UserService` 분기 1개. 컨트롤러는 수정하지 않는다. (§11 과 같은 모양)

지켜야 할 것:
- **OAuth 경로는 `/api/` 아래에 둔다.** nginx 가 `location /api/` 만 프록시하므로 Security 기본
  경로를 쓰면 로그인이 시작조차 안 된다. 구글 콘솔 등록값도 `/api/login/oauth2/code/google`.
- **`/actuator/health/**` 는 반드시 `permitAll`.** 막히면 프로브가 401 을 받아 파드가 계속 죽는다.
- **미인증 응답은 302 가 아니라 401.** 프론트가 HTML 을 JSON 으로 파싱하려다 실패한다.
- **세션에 담는 객체는 `Serializable`.** Redis 에 JDK 직렬화로 저장된다.
- **`k8s` 프로파일은 `GOOGLE_CLIENT_ID`/`SECRET` 이 없으면 기동에 실패한다** — 일부러 그렇게 뒀다.
  로컬(`local`/`docker`)에서는 `not-configured` 기본값으로 앱이 뜬다.

## 명령

```bash
./gradlew build                        # 전 모듈 컴파일 + 테스트
./gradlew test                         # 테스트 (Docker 필요 — Testcontainers)
./gradlew :backend:app-api:bootRun     # 특정 앱만 실행
docker compose up -d                   # 인프라만 (mysql + kafka)
docker compose --profile app up --build # 전체 스택
```
