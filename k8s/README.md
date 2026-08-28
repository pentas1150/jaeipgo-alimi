# 로컬 k8s 배포 (kind + KEDA)

설계 배경은 [../docs/DESIGN.md §12](../docs/DESIGN.md) 참고.
여기는 **실행 절차**만 다룬다.

## 0. 선행 조건

```bash
brew install kind helm     # kubectl 은 이미 설치돼 있음
```

Docker Desktop 메모리를 **최소 8GB**로 올려둘 것 (Settings → Resources).
이 클러스터는 idle 상태에서 약 4GB, checker 를 3대로 늘리면 약 6GB를 쓴다.

## 1. 클러스터 생성

```bash
kind create cluster --config k8s/kind-cluster.yaml
kubectl config use-context kind-alimi
```

## 2. 이미지 빌드 + 클러스터에 적재

kind 노드는 호스트의 Docker 이미지를 **볼 수 없다.** 명시적으로 넣어줘야 한다.
(이걸 안 해서 `ErrImagePull` 나는 게 kind 입문 1번 함정)

이미지는 6개다. 코드가 같으므로 Gradle 빌드 스테이지는 캐시를 공유한다.

```bash
# 슬림 앱 3종 — MODULE build arg 로 어느 모듈의 jar 를 담을지 고른다
for m in app-api app-scheduler app-notifier; do
  docker build --target runtime --build-arg MODULE=$m -t alimi-${m#app-}:local .
done

# 브라우저 이미지 — checker 전용 (~6GB, 처음엔 오래 걸린다)
docker build --target runtime-playwright -t alimi-checker:local .

# 마이그레이션 Job (~800MB) + 정적 프론트 (~76MB)
docker build --target migration -t alimi-migration:local .
docker build --target frontend  -t alimi-frontend:local .

kind load docker-image \
  alimi-api:local alimi-scheduler:local alimi-notifier:local \
  alimi-migration:local alimi-frontend:local --name alimi
kind load docker-image alimi-checker:local --name alimi   # 크므로 따로
```

| 이미지 | 크기 | 내용 |
|--------|------|------|
| `alimi-frontend` | 76MB | nginx + 정적 파일 (**Node 런타임 없음**) |
| `alimi-api` / `-scheduler` / `-notifier` | 636MB | JRE + 앱 |
| `alimi-migration` | 826MB | Flyway CLI (alpine 이지만 JRE 포함) |
| `alimi-checker` | 6.4GB | Playwright + Chromium/Firefox/WebKit |

## 3. KEDA 설치

```bash
helm repo add kedacore https://kedacore.github.io/charts
helm repo update
helm install keda kedacore/keda --namespace keda --create-namespace

kubectl -n keda rollout status deploy/keda-operator --timeout=180s
```

## 4. 애플리케이션 배포

```bash
kubectl apply -k k8s/

# 인프라가 먼저 떠야 앱이 산다
kubectl -n alimi rollout status statefulset/mysql --timeout=300s
kubectl -n alimi rollout status statefulset/kafka --timeout=300s

# 스키마 마이그레이션 (앱은 Flyway 를 돌리지 않는다 — DESIGN.md §10.4)
kubectl -n alimi wait --for=condition=complete job/alimi-migration --timeout=300s

kubectl -n alimi rollout status deploy/alimi-scheduler --timeout=300s

# KEDA CRD 가 설치된 뒤에 적용 (그래서 kustomization.yaml 에 안 들어있다)
kubectl apply -f k8s/keda/
```

확인:

```bash
kubectl -n alimi get pods
kubectl -n alimi get scaledobject,hpa

# 프론트(nginx) → API 프록시까지 한 번에 확인
open http://localhost:8080
curl -s localhost:8080/healthz
curl -s -X POST localhost:8080/api/notifications \
  -H 'Content-Type: application/json' \
  -d '{"recipient":"user@example.com","channel":"EMAIL","title":"재입고","content":"https://example.com/1"}'
```

> `alimi-api` 는 이제 `ClusterIP` 다. 외부 입구는 프론트(nginx) 하나뿐이고,
> nginx 가 `/api/` 를 API 로 프록시한다 → 같은 오리진이라 CORS 설정이 필요 없다.

## 5. 오토스케일 관찰하기 — 이게 핵심

터미널 두 개를 띄운다.

**터미널 A** — 파드 수와 HPA 지표를 지켜본다:

```bash
watch -n 2 'kubectl -n alimi get pods -l role=checker --no-headers | wc -l; \
            kubectl -n alimi get hpa'
```

**터미널 B** — 토픽에 메시지를 왕창 밀어 넣어 랙을 만든다.
**반드시 키와 함께 보낼 것** (이유는 바로 아래):

```bash
kubectl -n alimi exec -it kafka-0 -- bash -c '
  for i in $(seq 1 500); do
    echo "$i:{\"productId\":$i,\"productUrl\":\"https://example.com/$i\"}"
  done | kafka-console-producer --bootstrap-server localhost:9092 \
         --topic stock.check.requested.v1 \
         --property parse.key=true --property key.separator=:'
```

`lagThreshold: 5`, 파티션 12개이므로 랙 500이면 KEDA가 checker를 상한인 **12대**까지 밀어 올린다.
그 뒤 랙이 빠지면 `stabilizationWindowSeconds: 300` 만큼 기다렸다가 천천히 줄인다.

### 실제로 돌려본 결과 (2026-08-29)

```
[20s] HPA=30/5   replicas=12   Running=2  Pending=0
[40s] HPA=30/5   replicas=12   Running=4  Pending=0
[80s] HPA=7500m/5 replicas=12  Running=5  Pending=3
[120s] HPA=5/5   replicas=12   Running=5  Pending=7   ← 여기서 멈춤
```

**KEDA는 12를 요구했지만 5개만 떴다.** 이유:

```
FailedScheduling: 0/1 nodes are available: 1 Insufficient memory
노드 메모리 요청 사용률 92%
```

checker 1개가 768Mi를 요청하니 12개면 9.2GB인데 Docker 할당은 8GB다.
**스케일 상한이 두 개**라는 걸 보여준다 — 파티션 수(12)와 노드 자원(약 5). 낮은 쪽이 걸린다.

그리고 scheduler/api 는 계속 `Running` 을 유지한다 — `priorityclass.yaml` 덕분이다.
이걸 빼고 실험했을 때는 checker 가 노드를 다 먹어 **scheduler 가 `Pending` 에 빠졌다.**
스케줄러가 멈추면 체크 요청 자체가 안 나가므로 시스템 전체가 정지한다.

### ⚠️ 키를 빼면 이 모든 게 무의미해진다

위 명령에서 `--property parse.key=true` 를 빼고 보내면
`kafka-console-producer` 는 키 없이 발행하고, 최신 Kafka 의 sticky 파티셔너가
**500건을 전부 한 파티션에 몰아넣는다:**

```
$ kubectl -n alimi exec kafka-0 -- kafka-get-offsets \
    --bootstrap-server localhost:9092 --topic stock.check.requested.v1

stock.check.requested.v1:6:500      ← 여기만 500건
stock.check.requested.v1:0:0        ← 나머지 11개는 전부 0
...
```

**이 상태면 파드를 12개 띄워도 실제로 일할 수 있는 건 1개다.**

키를 넣고 같은 500건을 보내면 이렇게 된다:

```
p0:56  p1:31  p2:43  p3:51  p4:39  p5:44
p6:44  p7:48  p8:38  p9:44  p10:30 p11:32     → 12개 파티션 전부 사용
```

즉 `productId` 를 파티션 키로 쓰는 건 성능 최적화가 아니라 **스케일 아웃의 전제 조건**이다.
프로듀서 구현 시 키를 빠뜨리면 이 모든 오토스케일 설계가 통째로 무력화된다.
(설계 배경: [../docs/DESIGN.md §3.4](../docs/DESIGN.md))

> **직접 확인해볼 것:** `maxReplicaCount` 를 20으로 올려도 파티션을 할당받아 일하는 건 12개가 상한이다.

랙 직접 확인:

```bash
kubectl -n alimi exec -it kafka-0 -- \
  kafka-consumer-groups --bootstrap-server localhost:9092 \
                        --describe --group alimi-checker
```

## 6. 정리

```bash
kind delete cluster --name alimi
```

---

## 자주 겪는 문제

| 증상 | 원인 | 해결 |
|------|------|------|
| `ErrImagePull` / `ImagePullBackOff` | kind 노드에 이미지가 없음 | 2번의 `kind load` 재실행 |
| checker 파드가 `OOMKilled` | 브라우저가 메모리 한도 초과 | `checker.yaml` 의 `limits.memory` 상향 |
| Chromium 이 랜덤 크래시 | `/dev/shm` 부족 | `dshm` 볼륨이 마운트됐는지 확인 |
| KEDA 가 파드를 안 늘림 | KEDA→Kafka 접속 실패 | `kubectl -n keda logs deploy/keda-operator` 확인. 브로커 advertised listener 가 FQDN 인지 볼 것 |
| HPA 가 `<unknown>` | ScaledObject 가 트리거를 못 읽음 | `kubectl -n alimi describe scaledobject alimi-checker` |
| 앱이 DB 연결 실패로 CrashLoop | MySQL 이 아직 준비 안 됨 | 정상. 재시작하며 회복된다 |
| `No resolvable bootstrap urls` 로 CrashLoop | Kafka 가 Ready 되기 전에 앱이 붙으려 함. 헤드리스 서비스는 **Ready 인 파드만** DNS 에 올린다 | 정상. Kafka Ready 후 자동 회복 (실측 재시작 4회) |
| **토픽이 안 생김** | 기동 시점에 브로커가 없으면 `KafkaAdmin` 이 ERROR 만 남기고 앱은 정상 기동한다 | `spring.kafka.admin.fail-fast: true` 로 방지 중. 그래도 안 생기면 `kubectl -n alimi rollout restart deploy/alimi-scheduler` |
| 파드 다수가 `Pending` | 노드 메모리 부족 (파티션 상한과 별개의 상한) | `kubectl describe node` 로 요청 사용률 확인. checker `requests.memory` 하향 또는 `maxReplicaCount` 하향 |
| **scheduler/api 가 `Pending`** | 오토스케일된 워커가 노드 자원을 다 먹음 | `priorityclass.yaml` 이 적용됐는지 확인: `kubectl -n alimi get pods -o custom-columns=NAME:.metadata.name,PRIO:.spec.priority` — checker/notifier 가 **-10** 이어야 한다 |
| 파드는 떴는데 일을 안 함 | 파티션 수 < 파드 수, **또는 메시지에 키가 없어 한 파티션에 몰림** | 위 5번 참고 |

## 아직 안 한 것 (다음 단계)

- **Ingress** — 지금은 NodePort. 실전은 ingress-nginx + Ingress 리소스
- **Strimzi** — 지금 Kafka 는 단일 노드 StatefulSet. 운영급은 Strimzi 오퍼레이터로 (`KafkaTopic` CR 로 토픽도 선언적 관리)
- **Secret 관리** — `config.yaml` 의 Secret 이 평문으로 git 에 있다. Sealed Secrets / External Secrets 로 이전
- **kustomize overlay** — 지금은 base 뿐. `overlays/local`, `overlays/prod` 로 분리
- **PodDisruptionBudget** — 노드 드레인 시 checker 가 한꺼번에 죽지 않도록
- **ShedLock** — scheduler 단일 실행을 `Recreate` 전략이 아니라 애플리케이션 레벨에서 보장
- **관측** — Prometheus + Grafana. 앱은 이미 `/actuator/prometheus` 를 노출 중
