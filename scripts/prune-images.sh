#!/usr/bin/env bash
# 라즈베리파이의 containerd 에서 오래된 alimi 이미지를 정리한다. CD 가 매 배포 끝에 호출한다.
#
#   scripts/prune-images.sh <배포한-태그>
#
# ⚠️ `crictl rmi --prune` 을 그냥 쓰면 안 된다. 그건 "지금 컨테이너가 쓰지 않는" 이미지를
# 전부 지우는데 거기엔 **직전 배포 이미지도 포함된다.** 그러면 `kubectl rollout undo` 가
# ErrImagePull 로 죽는다 — 레지스트리가 없어서 다시 받아올 곳이 없기 때문이다.
#
# 이미지의 생성 시각으로 정렬하는 방법도 못 쓴다(ctr 은 시간순 정렬을 안 해주고,
# 레이어 캐시가 재사용되면 시각이 뒤섞인다). 그래서 **배포 순서를 원장에 직접 적는다.**
set -euo pipefail

TAG="${1:?사용법: prune-images.sh <tag>}"
KEEP="${KEEP:-5}"
LEDGER="${LEDGER:-${XDG_STATE_HOME:-$HOME/.local/state}/alimi/deployed-tags}"
REPOS=(alimi-api alimi-scheduler alimi-notifier alimi-checker alimi-frontend alimi-migration)
# nerdctl 의 기본 소켓은 /run/containerd/containerd.sock 이다. k3s 는 다른 곳을 쓴다.
CONTAINERD_SOCK="${CONTAINERD_SOCK:-/run/k3s/containerd/containerd.sock}"

# 원장은 러너 계정 홈에 둔다. /var/lib 에 두면 install/touch/tee 까지 sudo 가 필요해지는데,
# sudoers 는 nerdctl 과 k3s 만 열어 뒀다(그 이상 열 이유가 없다).
mkdir -p "$(dirname "$LEDGER")"
touch "$LEDGER"

# 이번 태그를 맨 뒤에 붙이고, 중복은 마지막 것만 남긴다(재배포/롤백 대비).
keep_list="$(printf '%s\n' "$(cat "$LEDGER")" "$TAG" | grep -v '^$' | awk '!seen[$0]++ {o[++n]=$0} END{for(i=1;i<=n;i++) print o[i]}' | tail -n "$KEEP")"
printf '%s\n' "$keep_list" > "$LEDGER"

# 클러스터가 지금 참조 중인 이미지는 무슨 일이 있어도 남긴다(원장이 어긋났을 때의 안전망).
in_use="$(kubectl get pods -A -o jsonpath='{range .items[*]}{range .spec.containers[*]}{.image}{"\n"}{end}{end}' 2>/dev/null || true)"

ctr() { sudo k3s ctr --namespace k8s.io "$@"; }

removed=0
for repo in "${REPOS[@]}"; do
  while read -r ref; do
    [ -z "$ref" ] && continue
    tag="${ref##*:}"
    [ "$tag" = "local" ] && continue                       # kind/로컬 수동 빌드는 건드리지 않는다
    grep -qxF "$tag" <<<"$keep_list" && continue           # 최근 KEEP 개
    grep -qF "$ref"  <<<"$in_use"    && { echo "skip (in use): $ref"; continue; }
    echo "rm: $ref"
    ctr images remove "$ref" >/dev/null && removed=$((removed + 1))
  done < <(ctr images list -q | grep -E "(^|/)${repo}:" || true)
done

# buildkit 캐시. microSD 에서는 이게 조용히 수 GB 로 자란다.
sudo nerdctl --address "$CONTAINERD_SOCK" builder prune --force --keep-storage 4GB >/dev/null 2>&1 || true

echo "이미지 ${removed}개 정리, 보존 태그: $(tr '\n' ' ' <<<"$keep_list")"
df -h / | tail -1
