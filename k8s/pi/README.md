# 라즈베리파이4 배포 (k3s + self-hosted runner)

`main` 에 푸시하면 `.github/workflows/cd.yml` 이 여기로 무중단 배포한다.
이 문서는 **그 워크플로가 전제하는 노드 상태**를 만드는 1회성 절차다.

대상: 라즈베리파이4 **4GB**, Ubuntu Server 26.04 (arm64), microSD 부팅.

> **먼저 알아둘 것 — 이 구성은 넉넉하지 않다.**
> 4GB 에 MySQL + Kafka + JVM 4개 + Chromium 을 전부 올린다. 아래 설정은 "잘 되면 좋고"가
> 아니라 **하나라도 빠지면 노드가 멈추는** 값들이다. 특히 §2 의 `system-reserved`.

---

## 빠른 길 — `scripts/setup-pi.sh`

§1~§4 를 전부 수행하는 멱등 스크립트가 있다. 러너를 돌릴 계정으로(root 아님) 실행한다.

```bash
git clone https://github.com/pentas1150/jaeipgo-alimi.git && cd jaeipgo-alimi
./scripts/setup-pi.sh
```

끝에서 `allocatable` 확인과 "빌드 → containerd 적재" 스모크까지 돌린다.
아래 §1~§4 는 그 스크립트가 무엇을 왜 하는지에 대한 설명이다. 값을 바꾸고 싶을 때 읽으면 된다.

---

## 0. 아키텍처는 문제가 아니다

쓰는 베이스 이미지가 전부 `linux/arm64` 매니페스트를 게시한다 (`docker manifest inspect` 확인):
`mcr.microsoft.com/playwright/java:v1.62.0-jammy`, `flyway/flyway:11-alpine`,
`confluentinc/cp-kafka:7.8.0`, `mysql:8.4`, `eclipse-temurin:21-jre`, `nginx:1.27-alpine`.

→ Dockerfile 의 베이스를 바꿀 필요가 없고, checker 의 arm64 Chromium 도 공식 이미지가 해결한다.
**제약은 아키텍처가 아니라 메모리다.**

---

## 1. 커널 / 부팅

```bash
# ⚠️ 가장 먼저 확인할 것. 이게 없으면 메모리 limit 이 조용히 무시된다 —
# OOMKill 이 안 나고 노드가 통째로 굳어버려서 원인 찾기가 지독하게 어렵다.
grep -o 'cgroup_enable=memory\|cgroup_memory=1' /boot/firmware/cmdline.txt
# 둘 다 안 나오면 cmdline.txt 끝에 한 줄로 덧붙이고 재부팅한다 (줄바꿈 금지):
#   cgroup_enable=memory cgroup_memory=1

# 헤드리스이므로 GPU 메모리를 회수한다 (~48MB)
echo 'gpu_mem=16' | sudo tee -a /boot/firmware/config.txt

# zram swap 1GiB. 4GB 에서 롤링 배포 순간의 서지를 흡수하는 안전판이다.
# request 를 낮게 적어 스케줄러를 속이는 것보다 이쪽이 정직하다.
sudo apt install -y systemd-zram-generator
printf '[zram0]\nzram-size = 1024\ncompression-algorithm = zstd\n' \
  | sudo tee /etc/systemd/zram-generator.conf
```

`journald` 가 microSD 를 갉아먹지 않게:
```bash
sudo mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]\nStorage=volatile\nRuntimeMaxUse=64M\n' \
  | sudo tee /etc/systemd/journald.conf.d/99-sd-card.conf
sudo systemctl restart systemd-journald
```

---

## 2. k3s 설치

먼저 kubeconfig 를 읽을 그룹을 만들고 자신을(=러너 계정을) 넣는다.
**설치 전에 해야 한다** — k3s 가 기동하면서 이 그룹으로 파일 소유권을 잡는다.

```bash
sudo groupadd -f k3s
sudo usermod -aG k3s "$(id -un)"
```

```bash
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="server \
  --disable metrics-server \
  --write-kubeconfig-mode 640 \
  --write-kubeconfig-group k3s \
  --kubelet-arg=system-reserved=cpu=300m,memory=1Gi \
  --kubelet-arg=eviction-hard=memory.available<150Mi" sh -
```

그룹 반영은 **새 로그인 세션부터**다. 지금 셸에 즉시 적용하려면 `newgrp k3s`,
러너 서비스에는 `sudo systemctl restart 'actions.runner.*'`.

| 옵션 | 이유 |
|---|---|
| `--disable metrics-server` | KEDA/HPA 를 안 쓰므로 `kubectl top` 전용이다. request 70Mi 회수. |
| `--kubelet-arg=system-reserved=…,memory=1Gi` | **이게 이 문서에서 제일 중요하다.** k3s 서버(apiserver/controller/scheduler/containerd/kubelet)는 **파드가 아니라서 스케줄러 눈에 안 보인다.** 예약하지 않으면 스케줄러는 3.7GiB 가 비었다고 믿고 배치한 뒤 노드째 OOM 난다. 러너와 buildkitd 몫도 여기 포함된다. |
| `--kubelet-arg=eviction-hard=…` | 마지막 방어선. |
| `--write-kubeconfig-mode 640` + `--write-kubeconfig-group k3s` | **둘을 같이 줘야 한다.** 모드만 640 으로 주면 그룹이 `root` 라 결국 root 전용이고, 러너가 `permission denied` 로 막힌다. (k3s 자신이 경고로 알려준다) |

Traefik 은 **끄지 않는다** — Ingress 로 쓴다 (`k8s/overlays/pi/ingress.yaml`).

확인:
```bash
kubectl describe node | sed -n '/Allocatable/,/^ *pods/p'
# memory 가 약 2,600Mi 근처여야 한다. 3.7Gi 로 나오면 system-reserved 가 안 먹은 것이다.
```

> `permission denied` 로 막히면 그룹이 아직 반영 안 된 것이다. `id` 로 `k3s` 가 보이는지
> 확인하고, 급하면 `sudo k3s kubectl describe node` 로 우회한다.

---

## 3. 러너 권한

self-hosted 러너는 이미 설치돼 있다고 가정한다. 필요한 것은 세 가지다.

**① 라벨** — 워크플로가 `runs-on: [self-hosted, linux, ARM64, alimi-pi]` 로 고른다.
러너 설정에서 `alimi-pi` 라벨을 추가한다.

**② kubeconfig** — §2 에서 그룹을 줬으므로 **심볼릭 링크**면 된다.

```bash
mkdir -p "$HOME/.kube"
ln -sf /etc/rancher/k3s/k3s.yaml "$HOME/.kube/config"
kubectl get nodes    # 확인
```

복사(`cp`)가 아니라 링크인 이유: k3s 는 재시작하며 클라이언트 인증서를 갱신할 수 있는데,
복사본은 그때 낡아서 어느 날 갑자기 인증 오류를 낸다. 링크는 원본을 계속 따라간다.

`cd.yml` 은 `KUBECONFIG` 를 따로 지정하지 않고 kubectl 의 기본 경로(`~/.kube/config`)를
쓴다. 그래서 이 링크가 러너 계정 홈에 있어야 한다.

**③ sudo** — 이미지 빌드/정리에 containerd 소켓(root 전용)이 필요하다.
```bash
printf '%s ALL=(root) NOPASSWD: /usr/local/bin/nerdctl, /usr/local/bin/k3s\n' "$(id -un)" \
  | sudo tee /etc/sudoers.d/alimi-runner
sudo chmod 440 /etc/sudoers.d/alimi-runner
```

---

## 4. buildkit — Docker 없이 containerd 에 직접 굽는다

`docker build` → `docker save`(checker 이미지 6.4GB tar) → `ctr import` 경로는 **매 배포마다
microSD 를 갈아먹는다.** buildkitd 를 k3s 의 containerd 에 워커로 물리면 결과 이미지가 바로
`k8s.io` 네임스페이스에 들어가고, 바뀐 레이어만 쓰인다. Docker 데몬을 안 띄우니 RAM 도 아낀다.

```bash
ARCH=arm64
BUILDKIT_VER=0.32.2 ; NERDCTL_VER=2.3.5
# ⚠️ kustomize 는 CI(.github/workflows/*.yml 의 KUSTOMIZE_VERSION)와 반드시 같은 버전으로.
# 렌더 결과가 갈리면 "CI 는 통과했는데 파이에서만 다른 매니페스트가 적용되는" 상황이 된다.
KUSTOMIZE_VER=5.5.0

curl -sSL "https://github.com/moby/buildkit/releases/download/v${BUILDKIT_VER}/buildkit-v${BUILDKIT_VER}.linux-${ARCH}.tar.gz" \
  | sudo tar -xz -C /usr/local
curl -sSL "https://github.com/containerd/nerdctl/releases/download/v${NERDCTL_VER}/nerdctl-${NERDCTL_VER}-linux-${ARCH}.tar.gz" \
  | sudo tar -xz -C /usr/local/bin nerdctl
curl -sSL "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2Fv${KUSTOMIZE_VER}/kustomize_v${KUSTOMIZE_VER}_linux_${ARCH}.tar.gz" \
  | sudo tar -xz -C /usr/local/bin kustomize
```

**nerdctl 이 k3s 의 containerd 를 보게 한다.** 기본 소켓은 `/run/containerd/containerd.sock`
인데 k3s 는 `/run/k3s/containerd/containerd.sock` 을 쓴다. `--namespace` 는 네임스페이스만
바꾸고 주소는 안 바꾸므로, 이걸 안 잡으면 `cannot access containerd socket` 으로 죽는다.

```bash
sudo mkdir -p /etc/nerdctl
printf 'address = "unix:///run/k3s/containerd/containerd.sock"\nnamespace = "k8s.io"\n' \
  | sudo tee /etc/nerdctl/nerdctl.toml
```

```bash
sudo mkdir -p /etc/buildkit
sudo tee /etc/buildkit/buildkitd.toml >/dev/null <<'EOF'
# k3s 의 containerd 를 그대로 워커로 쓴다. 빌드 결과가 kubelet 이 보는 곳에 바로 생긴다.
[worker.oci]
  enabled = false
[worker.containerd]
  enabled   = true
  address   = "/run/k3s/containerd/containerd.sock"
  namespace = "k8s.io"
EOF

sudo tee /etc/systemd/system/buildkit.service >/dev/null <<'EOF'
[Unit]
Description=BuildKit
After=k3s.service
Requires=k3s.service
[Service]
ExecStart=/usr/local/bin/buildkitd --config /etc/buildkit/buildkitd.toml
# 빌드가 system-reserved(1Gi)를 넘겨 파드를 밀어내지 못하게 못 박는다.
MemoryMax=512M
Restart=always
[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload && sudo systemctl enable --now buildkit
```

러너 서비스에도 상한을 건다:
```bash
sudo systemctl edit actions.runner.*.service    # 아래를 넣는다
# [Service]
# MemoryMax=384M
```

---

## 5. GitHub 설정

리포지토리 Secret 에 **`ALIMI_SECRET_ENV`** 를 만든다. 값은 `k8s/base/secret.env.example`
과 같은 형식의 **여러 줄** 텍스트다:

```
MYSQL_ROOT_PASSWORD=...
MYSQL_DATABASE=alimi
MYSQL_USER=alimi
MYSQL_PASSWORD=...
```

워크플로가 배포 때만 파일로 풀었다가 `shred` 한다. 자격증명은 매니페스트에도, 리포지토리에도
들어가지 않는다.

---

## 6. 첫 배포

`main` 에 푸시하면 자동으로 돈다. 수동으로 확인하려면 Actions 에서 **CD** 를 실행한다.

흐름:

```
[ubuntu-latest]  build      gradlew build(테스트 포함) → layertools 분해 → npm build → artifact
[ubuntu-latest]  manifests  kustomize build (base + pi) → kubeconform
        ↓ 둘 다 통과해야만
[raspberrypi]    deploy     nerdctl build(COPY 뿐) → kustomize edit set image
                            → 1단계 apply(-l alimi.stage=infra) → 마이그레이션 Job 완료 대기
                            → 2단계 apply → rollout status → 스모크 → 이미지 정리
```

**왜 2단계인가:** 스키마가 없으면 `ddl-auto=validate` 가 앱 기동을 실패시킨다. 순서를 강제하지
않으면 새 파드가 CrashLoop 하다 `progressDeadlineSeconds`(600s)에 걸려 배포가 실패로 뒤집힌다.
`maxUnavailable: 0` 덕에 서비스는 안 끊기지만, 파이프라인은 빨갛게 된다.

**여기서 따라오는 규칙:** 마이그레이션이 구버전 파드가 살아있는 상태에서 끝나므로
**모든 마이그레이션은 직전 버전 앱과 호환되어야 한다.** 컬럼 추가는 되고, DROP/RENAME 은
두 번의 배포로 나눠야 한다.

확인:
```bash
kubectl -n alimi get pods -o wide          # Pending 이 하나도 없어야 한다
kubectl -n alimi get ingress
curl -s http://localhost/healthz
# ⚠️ actuator 는 Ingress 로 안 나온다. nginx 의 location /api/ 는 원본 URI 를 그대로
# 넘기는데 actuator 는 /actuator/** 이고 컨트롤러만 /api/** 다. 클러스터 안에서 친다:
kubectl -n alimi exec deploy/alimi-frontend -- wget -qO- http://alimi-api:8080/actuator/health
```

---

## 6.1 도메인 + TLS (구글 OAuth 전제 조건)

구글 OAuth 는 승인된 리다이렉트 URI 에 **https** 를 요구한다. 예외는 `localhost` / `127.0.0.1`
뿐이고 **사설 IP(`192.168.*`)와 `.local` 은 콘솔에 등록조차 되지 않는다.** 그래서 로그인을
붙이려면 공인 도메인과 신뢰받는 인증서가 먼저 있어야 한다.

### 왜 공유기 DDNS(`*.iptime.org`)가 아니라 DuckDNS 인가

Let's Encrypt 의 발급 한도(**등록 도메인당 주 50장**)는 Public Suffix List 로 등록 도메인을
판정한다. 확인해 보면:

```bash
curl -s https://publicsuffix.org/list/public_suffix_list.dat | grep -x 'iptime.org\|duckdns.org'
# duckdns.org   ← 등재
# (iptime.org 는 나오지 않는다)
```

`iptime.org` 는 PSL 에 없으므로 **전국 ipTIME DDNS 사용자가 주 50장을 공유**하게 되어
`too many certificates already issued for: iptime.org` 로 막힌다. 구글의 승인된 도메인
소유권 검증도 같은 이유로 `iptime.org` 단위가 되어 증명이 불가능할 수 있다.
`duckdns.org` 는 PSL 에 등재돼 서브도메인마다 한도가 독립이다.

> **인증서는 살 필요가 없다.** Let's Encrypt 는 무료이고 모든 브라우저가 신뢰한다.
> 돈이 드는 건 도메인이지 인증서가 아니다. 자체서명은 OAuth 리다이렉트 **중간에**
> 브라우저 경고를 끼워넣어서 데모가 끊긴다.

### 포트는 443만 연다

DNS-01 챌린지는 TXT 레코드만 세우면 되므로 **Let's Encrypt 가 우리 서버로 들어올 필요가 없다.**
국내 통신사는 가정 회선의 80번을 막는 경우가 많은데, 그 문제를 통째로 우회한다.

구글 OAuth 도 마찬가지다 — **구글 서버는 우리 서버에 접속하지 않는다.** 리다이렉트는 사용자
브라우저가 하고, 토큰 교환은 파이가 바깥으로 나가는 요청이다.

### 절차

**① DuckDNS 서브도메인 확보**

<https://www.duckdns.org> 에서 로그인 → 서브도메인 생성 → 토큰 확인.
`current ip` 에 집 공인 IP 를 넣는다.

**② IP 자동 갱신** — 공유기 DDNS 목록에 DuckDNS 가 있으면 그걸 쓰고, 없으면 파이에서:

```bash
# crontab -e
*/5 * * * * curl -fsS "https://www.duckdns.org/update?domains=<sub>&token=<token>&ip=" >/dev/null
```

**③ 공유기 포트포워딩** — 외부 443 → 파이 443. **80은 열지 않는다.**

**④ 매니페스트에 도메인 반영** — `k8s/overlays/pi/ingress.yaml` 의 `host` **두 곳**
(`spec.tls[0].hosts[0]` 와 `spec.rules[0].host`)을 본인 도메인으로 바꾼다.

**⑤ Secret 채우기** — `k8s/base/secret.env` 에 세 줄을 추가한다
(`secret.env.example` 참고). CD 는 GitHub Secret `ALIMI_SECRET_ENV` 를 풀어 쓰므로
**거기에도 같이 넣어야 한다.**

```
DUCKDNS_SUBDOMAIN=<sub>      # .duckdns.org 를 뺀 앞부분만
DUCKDNS_TOKEN=<token>
ACME_EMAIL=you@example.com
```

**⑥ 인증서 최초 발급** — CronJob 은 매주 월요일 04:00 KST 에 돌지만, 처음 한 번은
기다리지 말고 직접 돌린다:

```bash
kubectl -n alimi create job --from=cronjob/cert-renew cert-renew-bootstrap
kubectl -n alimi wait --for=condition=complete job/cert-renew-bootstrap --timeout=600s
kubectl -n alimi logs job/cert-renew-bootstrap --all-containers

kubectl -n alimi get secret alimi-tls    # 이게 생기면 성공
```

Traefik 은 Secret 이 생기는 순간 알아서 바꿔 문다. 재시작이 필요 없다.

**⑦ 확인**

```bash
ing="$(kubectl -n alimi get ingress alimi -o jsonpath='{.status.loadBalancer.ingress[0].ip}')"
host="$(kubectl -n alimi get ingress alimi-domain -o jsonpath='{.spec.rules[0].host}')"

# --resolve 로 DNS 를 우회한다 — 공유기가 NAT 헤어핀을 지원하지 않으면
# 집 안에서 공인 IP 로 못 붙어서 멀쩡한 설정이 실패로 보인다.
curl -fsS --resolve "${host}:443:${ing}" "https://${host}/healthz"

# http 는 302 로 https 에 올라가야 한다
curl -sI --resolve "${host}:80:${ing}" "http://${host}/" | head -2
```

**⑧ 구글 클라우드 콘솔** — OAuth 2.0 클라이언트의 승인된 리다이렉트 URI 에 등록한다:

```
https://<sub>.duckdns.org/api/login/oauth2/code/google
```

`/api/` 가 붙는 이유는 nginx 가 `/api/` 만 백엔드로 프록시하기 때문이다. Spring Security 의
기본 경로(`/login/oauth2/code/google`)를 그대로 쓰면 nginx 가 `index.html` 을 돌려줘
로그인이 시작조차 안 된다. 자세한 건 1단계 구현 노트를 참고.

### 갱신이 매주인 이유

certbot 은 `--keep-until-expiring` 이라 만료 30일 전이 아니면 아무것도 하지 않고 끝난다.
즉 매주 돌려도 실제 발급은 90일에 한 번이고, 대신 **갱신이 실패했을 때 만료까지 네 번 이상
재시도할 기회**가 생긴다. 60일 단발 스케줄은 그 한 번이 실패하면 그대로 만료된다.

### 자원

cert-manager(controller/webhook/cainjector 3파드, ~150Mi 상시)를 쓰지 않았다.
§12.10 대로 노드 자원이 이미 상한이고, CronJob 은 **상시 메모리 0** 으로 같은 일을 한다.
파드는 주 1회 몇 분만 뜨는데, 스케줄을 04:00 로 잡아 롤링 배포와 겹치지 않게 했다.

---

## 7. 무중단 확인

배포를 한 번 더 트리거하고, 파이에서 동시에:

```bash
while true; do curl -s -o /dev/null -w '%{http_code} ' http://localhost/ ; sleep 0.2; done
```

**200 만 찍혀야 한다.** 502 나 000 이 한 번이라도 보이면 `k8s/overlays/pi/patches/api.yaml`
의 `preStop` 대기(5초)와 `terminationGracePeriodSeconds` 를 올린다.

무중단을 실제로 만드는 것은 replicas 수가 아니라 이 셋이다:
1. `maxUnavailable: 0` + `maxSurge: 1` — 새 파드가 Ready 가 된 뒤에야 옛 파드가 내려간다
2. `preStop: sleep 5` — 파드 종료와 kube-proxy 의 엔드포인트 제거는 **비동기**다. 이 대기가
   없으면 이미 죽은 파드로 가는 요청이 502 로 떨어진다
3. `server.shutdown: graceful` (application.yml) — 처리 중인 요청을 끝내고 죽는다

사용자 트래픽을 받지 않는 checker/notifier/scheduler 는 `Recreate` 다. 4GB 에서 서지 파드를
만들 여유가 없고, Kafka 컨슈머의 몇 초 공백은 사용자에게 안 보이며 랙으로만 남는다.

---

## 8. 롤백

Actions → **CD** → *Run workflow* → `rollback_to` 에 되돌아갈 **이미지 태그(커밋 SHA 12자리)**.
빌드를 건너뛰고 그 태그로 다시 렌더해 apply 한다.

```bash
kubectl -n alimi get deploy -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.template.spec.containers[0].image}{"\n"}{end}'
```

- 이미지는 `scripts/prune-images.sh` 가 최근 **5개**를 보존한다 (`/var/lib/alimi/deployed-tags`).
  그보다 오래된 태그로는 롤백할 수 없다 — 레지스트리가 없어 다시 받아올 곳이 없다.
- **스키마는 되돌아가지 않는다.** Flyway 에 down 마이그레이션이 없다. 그래서 §6 의 하위 호환
  규칙이 선택이 아니다.
- 급할 때는 워크플로 없이도 된다: `kubectl -n alimi rollout undo deploy/alimi-api`

---

## 9. 트러블슈팅

| 증상 | 원인 / 조치 |
|---|---|
| 파드가 `Pending`, `FailedScheduling: Insufficient memory` | 예산 초과. `kubectl describe node` 의 *Allocated resources* 를 보고 `k8s/overlays/pi/kustomization.yaml` 의 표와 대조한다. |
| `cannot access containerd socket "/run/containerd/containerd.sock"` | nerdctl 이 k3s 가 아닌 기본 소켓을 봤다. `/etc/nerdctl/nerdctl.toml` 을 만들거나 `--address /run/k3s/containerd/containerd.sock` 을 준다 (§4). |
| `k3s.yaml: permission denied` | kubeconfig 그룹 미설정 또는 그룹 반영 전. `id` 에 `k3s` 가 보이는지 확인 → 없으면 §2, 있는데도 막히면 재로그인(`newgrp k3s`). 우회는 `sudo k3s kubectl …` |
| `Allocatable` 이 3.7Gi 로 나온다 | `system-reserved` 가 안 먹었다. `sudo cat /etc/systemd/system/k3s.service` 에서 `--kubelet-arg` 확인. |
| 메모리 limit 을 넘겨도 OOMKill 이 안 나고 노드가 굳는다 | `cgroup_enable=memory` 누락 (§1). |
| `ErrImagePull` / `ImagePullBackOff` | 레지스트리가 없으므로 노드에 이미지가 없다는 뜻이다. 프루닝이 과했거나(§8) `imagePullPolicy` 가 `Always` 로 바뀌었는지 확인. |
| checker 가 `OOMKilled` | `JAVA_OPTS` 의 `MaxRAMPercentage=35` 와 `/dev/shm` 256Mi 를 확인. `medium: Memory` 인 emptyDir 은 **컨테이너 메모리 limit 에 포함된다.** |
| Chromium 탭이 가끔 죽는다 | `/dev/shm` 이 기본 64MB 로 돌아갔는지 확인 (volumeMount 누락). |
| 디스크 부족 | `./scripts/prune-images.sh <현재태그>` 를 손으로 실행. checker 이미지만 6.4GB 다. |
| 배포가 마이그레이션에서 멈춘다 | `kubectl -n alimi logs job/alimi-migration` |
| StatefulSet 파드가 옛 설정으로 계속 돈다 | **StatefulSet 은 파드가 Ready 가 아니면 업데이트하지 않는다.** Ready 를 막는 버그는 자기 수정을 스스로 차단한다. CD 가 자동으로 풀지만 손으로 하려면 `kubectl -n alimi delete pod kafka-0` (DESIGN §12.11 ④) |
| exec 프로브가 계속 `command timed out` | `timeoutSeconds` 기본값이 **1초**다. JVM 도구는 절대 못 넘긴다 (§12.11 ③) |
| 마이그레이션이 `RSA public key is not available` | Flyway 는 MariaDB 드라이버 + MySQL 8.4 조합. URL 에 `allowPublicKeyRetrieval=true` 확인 (§12.11 ⑤) |

---

## 10. 알려진 한계

- **microSD 가 가장 약한 고리다.** Kafka 로그와 MySQL redo 가 카드 수명을 직접 깎는다.
  `k8s/overlays/pi/patches/{kafka,mysql}.yaml` 의 보존 기간·flush 설정은 완화책이지 해결책이
  아니다. **USB SSD 로 부팅하는 것을 강하게 권한다.**
- **KEDA 를 올리지 않는다.** 오퍼레이터+메트릭서버+웹훅이 약 300Mi 인데, 4GB 에서는 checker 를
  2대도 못 띄우므로 오토스케일할 대상이 없다. KEDA 실측은 kind 환경(`k8s/README.md`)에 남아 있다.
- **파티션을 3/2/2 로 줄였다** (base 는 12/6/6). 동시성 상한 = min(파티션, 노드 자원) 인데
  파이에서는 노드 자원이 1이다. ⚠️ 파티션은 늘릴 수만 있고 줄일 수 없다.
- **checker 이미지가 6.4GB 다.** 공식 Playwright 이미지가 Chromium/Firefox/WebKit 을 다 담고
  있는데 우리는 Chromium 하나만 쓴다. `Dockerfile` 의 주석에 슬림화 방법이 적혀 있다 (미검증).
- 단일 노드다. 노드가 죽으면 서비스도 죽는다. 무중단은 **배포 중**에만 해당한다.
