#!/usr/bin/env bash
# 라즈베리파이4(4GB, Ubuntu 26.04 arm64) 를 alimi CD 대상 노드로 만든다.
#
#   ./scripts/setup-pi.sh
#
# 러너를 돌릴 계정으로(root 아님) 실행한다. sudo 는 스크립트가 알아서 부른다.
# 여러 번 실행해도 안전하다(멱등).
#
# 배경과 각 값의 이유는 k8s/pi/README.md 에 있다. 이 스크립트는 그 문서의 실행본이다.
set -euo pipefail

BUILDKIT_VER=0.32.2
NERDCTL_VER=2.3.5
# ⚠️ CI(.github/workflows/*.yml 의 KUSTOMIZE_VERSION)와 반드시 같아야 한다.
# 다르면 "CI 는 통과했는데 파이에만 다른 매니페스트가 적용되는" 상황이 생긴다.
KUSTOMIZE_VER=5.5.0

ARCH=arm64
K3S_GROUP=k3s
CONTAINERD_SOCK=/run/k3s/containerd/containerd.sock
REBOOT_REQUIRED=0

say()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()   { printf '   \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '   \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -ne 0 ] || die "root 로 실행하지 말 것. 러너를 돌릴 일반 계정으로 실행한다."
sudo -v || die "sudo 권한이 필요하다."
USER_NAME="$(id -un)"

# ─────────────────────────────────────────────────────────────
say "1/9  커널 cgroup 설정"
# 이게 없으면 메모리 limit 이 조용히 무시된다. OOMKill 이 안 나고 노드가 통째로 굳는다.
CMDLINE=/boot/firmware/cmdline.txt
[ -f "$CMDLINE" ] || CMDLINE=/boot/cmdline.txt
[ -f "$CMDLINE" ] || die "cmdline.txt 를 못 찾았다 ($CMDLINE)"

if grep -q 'cgroup_enable=memory' "$CMDLINE" && grep -q 'cgroup_memory=1' "$CMDLINE"; then
  ok "이미 설정돼 있다"
else
  sudo cp "$CMDLINE" "${CMDLINE}.bak.$(date +%s)"
  # ⚠️ cmdline.txt 는 반드시 한 줄이어야 한다. 줄바꿈이 들어가면 부팅이 안 된다.
  sudo sed -i '1 s/$/ cgroup_enable=memory cgroup_memory=1/' "$CMDLINE"
  ok "추가함 (백업: ${CMDLINE}.bak.*)"
  REBOOT_REQUIRED=1
fi

# ─────────────────────────────────────────────────────────────
say "2/9  헤드리스 GPU 메모리 회수"
CONFIG=/boot/firmware/config.txt
[ -f "$CONFIG" ] || CONFIG=/boot/config.txt
if [ -f "$CONFIG" ] && ! grep -q '^gpu_mem=' "$CONFIG"; then
  echo 'gpu_mem=16' | sudo tee -a "$CONFIG" >/dev/null
  ok "gpu_mem=16 추가 (~48MB 회수)"
  REBOOT_REQUIRED=1
else
  ok "이미 설정돼 있거나 config.txt 없음"
fi

# ─────────────────────────────────────────────────────────────
say "3/9  zram swap"
# 4GB 에서 롤링 배포 순간의 서지를 흡수하는 안전판.
# request 를 낮게 적어 스케줄러를 속이는 것보다 이쪽이 정직하다.
sudo apt-get install -y -qq systemd-zram-generator >/dev/null
printf '[zram0]\nzram-size = 1024\ncompression-algorithm = zstd\n' \
  | sudo tee /etc/systemd/zram-generator.conf >/dev/null
sudo systemctl daemon-reload
sudo systemctl restart systemd-zram-setup@zram0.service 2>/dev/null || REBOOT_REQUIRED=1
ok "1GiB zstd zram 설정"

# ─────────────────────────────────────────────────────────────
say "4/9  journald 를 microSD 에서 떼기"
sudo mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]\nStorage=volatile\nRuntimeMaxUse=64M\n' \
  | sudo tee /etc/systemd/journald.conf.d/99-sd-card.conf >/dev/null
sudo systemctl restart systemd-journald
ok "로그를 RAM 으로 (최대 64M)"

# ─────────────────────────────────────────────────────────────
say "5/9  k3s 설치 / 재설정"
# 그룹을 먼저 만든다 — k3s 가 기동하면서 이 그룹으로 kubeconfig 소유권을 잡는다.
sudo groupadd -f "$K3S_GROUP"
sudo usermod -aG "$K3S_GROUP" "$USER_NAME"

# ⚠️ system-reserved 가 이 설정의 생명줄이다.
# k3s 서버(apiserver/controller/scheduler/containerd/kubelet)는 파드가 아니라서
# 스케줄러 눈에 안 보인다. 예약하지 않으면 3.7GiB 가 비었다고 믿고 배치한 뒤 노드째 OOM 난다.
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="server \
  --disable metrics-server \
  --write-kubeconfig-mode 640 \
  --write-kubeconfig-group ${K3S_GROUP} \
  --kubelet-arg=system-reserved=cpu=300m,memory=1Gi \
  --kubelet-arg=eviction-hard=memory.available<150Mi" sh - >/dev/null
ok "k3s 설치/재설정 완료"

# kubeconfig 는 복사가 아니라 링크다. k3s 가 인증서를 갱신해도 따라간다.
mkdir -p "$HOME/.kube"
ln -sf /etc/rancher/k3s/k3s.yaml "$HOME/.kube/config"
ok "~/.kube/config → /etc/rancher/k3s/k3s.yaml"

for i in $(seq 1 60); do
  sudo k3s kubectl get node >/dev/null 2>&1 && break
  [ "$i" = 60 ] && die "k3s 가 60초 안에 준비되지 않았다. journalctl -u k3s -n 50"
  sleep 1
done
ok "노드 Ready"

# ─────────────────────────────────────────────────────────────
say "6/9  러너 sudo 권한"
# 이미지 빌드/정리에 containerd 소켓(root 전용)이 필요하다.
# 원장 파일은 홈에 두므로 이 둘 외에 더 열 필요가 없다.
printf '%s ALL=(root) NOPASSWD: /usr/local/bin/nerdctl, /usr/local/bin/k3s\n' "$USER_NAME" \
  | sudo tee /etc/sudoers.d/alimi-runner >/dev/null
sudo chmod 440 /etc/sudoers.d/alimi-runner
sudo visudo -cf /etc/sudoers.d/alimi-runner >/dev/null || die "sudoers 문법 오류"
ok "nerdctl / k3s NOPASSWD"

# ─────────────────────────────────────────────────────────────
say "7/9  buildkit + nerdctl + kustomize"
dl() { curl -fsSL --retry 3 "$1"; }
dl "https://github.com/moby/buildkit/releases/download/v${BUILDKIT_VER}/buildkit-v${BUILDKIT_VER}.linux-${ARCH}.tar.gz" \
  | sudo tar -xz -C /usr/local
dl "https://github.com/containerd/nerdctl/releases/download/v${NERDCTL_VER}/nerdctl-${NERDCTL_VER}-linux-${ARCH}.tar.gz" \
  | sudo tar -xz -C /usr/local/bin nerdctl
dl "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2Fv${KUSTOMIZE_VER}/kustomize_v${KUSTOMIZE_VER}_linux_${ARCH}.tar.gz" \
  | sudo tar -xz -C /usr/local/bin kustomize
ok "buildkit ${BUILDKIT_VER} / nerdctl ${NERDCTL_VER} / kustomize ${KUSTOMIZE_VER}"

# nerdctl 의 기본 소켓은 /run/containerd/containerd.sock 인데 k3s 는 다른 곳을 쓴다.
# 이걸 안 잡으면 "cannot access containerd socket" 으로 죽는다.
sudo mkdir -p /etc/nerdctl
printf 'address = "unix://%s"\nnamespace = "k8s.io"\n' "$CONTAINERD_SOCK" \
  | sudo tee /etc/nerdctl/nerdctl.toml >/dev/null
ok "nerdctl 기본 소켓/네임스페이스를 k3s 쪽으로"

# ─────────────────────────────────────────────────────────────
say "8/9  buildkitd 를 k3s containerd 에 물리기"
# 결과 이미지가 kubelet 이 보는 자리(k8s.io)에 바로 생긴다.
# docker save(checker 는 6.4GB tar) → import 왕복이 사라진다 —
# microSD 에서는 속도가 아니라 카드 수명 문제다.
sudo mkdir -p /etc/buildkit
sudo tee /etc/buildkit/buildkitd.toml >/dev/null <<EOF
[worker.oci]
  enabled = false
[worker.containerd]
  enabled   = true
  address   = "${CONTAINERD_SOCK}"
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

sudo systemctl daemon-reload
sudo systemctl enable --now buildkit >/dev/null
sudo systemctl restart buildkit
for i in $(seq 1 30); do
  sudo buildctl debug workers >/dev/null 2>&1 && break
  [ "$i" = 30 ] && die "buildkitd 가 안 뜬다. journalctl -u buildkit -n 50"
  sleep 1
done
ok "buildkitd 기동 (containerd 워커)"

# 러너 서비스에도 메모리 상한을 건다.
RUNNER_UNIT="$(systemctl list-units --all --plain --no-legend 'actions.runner.*.service' 2>/dev/null | awk '{print $1}' | head -1)"
if [ -n "$RUNNER_UNIT" ]; then
  sudo mkdir -p "/etc/systemd/system/${RUNNER_UNIT}.d"
  printf '[Service]\nMemoryMax=384M\n' \
    | sudo tee "/etc/systemd/system/${RUNNER_UNIT}.d/10-memory.conf" >/dev/null
  sudo systemctl daemon-reload
  sudo systemctl restart "$RUNNER_UNIT"       # 그룹 변경도 여기서 반영된다
  ok "러너 $RUNNER_UNIT (MemoryMax=384M, 재시작 완료)"
else
  warn "actions.runner 서비스를 못 찾았다. 러너 설치 후 이 스크립트를 다시 실행할 것."
fi

# ─────────────────────────────────────────────────────────────
say "9/9  검증"
sudo k3s kubectl describe node | sed -n '/Allocatable/,/^ *pods/p' | sed 's/^/   /'

MEM_KI="$(sudo k3s kubectl get node -o jsonpath='{.items[0].status.allocatable.memory}' | tr -d 'Ki')"
MEM_MI=$(( MEM_KI / 1024 ))
if [ "$MEM_MI" -gt 3000 ]; then
  die "allocatable memory 가 ${MEM_MI}Mi 다. system-reserved 가 안 먹었다 — 이대로 배포하면 노드가 굳는다."
fi
ok "allocatable ${MEM_MI}Mi (목표 ~2600Mi)"

# 이미지가 정말 k8s.io 네임스페이스에 생기는지 끝까지 확인한다.
TMP="$(mktemp -d)"; printf 'FROM busybox\nRUN echo hi > /x\n' > "$TMP/Dockerfile"
sudo nerdctl --address "$CONTAINERD_SOCK" --namespace k8s.io build -q -t alimi-smoke:t "$TMP" >/dev/null
if sudo k3s ctr -n k8s.io images ls -q | grep -q 'alimi-smoke:t'; then
  ok "빌드 → k3s containerd 적재 확인"
  sudo k3s ctr -n k8s.io images rm docker.io/library/alimi-smoke:t >/dev/null 2>&1 || true
else
  die "이미지가 k8s.io 네임스페이스에 안 생겼다. buildkitd.toml 의 worker.containerd 확인."
fi
rm -rf "$TMP"

printf '\n\033[1;32m준비 완료.\033[0m\n'
echo "  남은 것: GitHub Secret ALIMI_SECRET_ENV 등록 + 러너 라벨에 'alimi-pi' 추가"
if [ "$REBOOT_REQUIRED" = 1 ]; then
  printf '\n\033[1;33m재부팅이 필요하다 (cgroup / gpu_mem / zram). sudo reboot\033[0m\n'
fi
if ! id -nG "$USER_NAME" | tr ' ' '\n' | grep -qx "$K3S_GROUP"; then
  printf '\033[1;33m현재 셸에는 %s 그룹이 아직 없다. 재로그인하거나 newgrp %s\033[0m\n' "$K3S_GROUP" "$K3S_GROUP"
fi
