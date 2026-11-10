#!/usr/bin/env bash
# 다음 배포 버전을 Conventional Commits로 정한다(배포 워크플로 deploy.yml이 부른다).
#
# 마지막 vX.Y.Z 태그 뒤 커밋들의 제목·본문으로:
#   호환이 깨지는 변경(제목 "type!:" 또는 본문 "BREAKING CHANGE:") → 1.0.0 전에는 minor, 뒤에는 major
#   feat → minor
#   그 밖(fix·refactor·perf·chore·ci·docs 등) → patch
# 기준 커밋에 이미 버전 태그가 있으면(같은 커밋을 다시 배포) 그 버전을 그대로 쓴다.
#
# 사용: next-version.sh [기준 커밋, 기본 HEAD]
# 출력: "0.2.0 new"(새 버전) 또는 "0.2.0 existing"(이미 태그가 있는 버전)
set -euo pipefail

head="${1:-HEAD}"

# 버전 태그(vX.Y.Z)만, 큰 버전부터
version_tags() {
  git tag --list 'v*' --sort=-v:refname "$@" | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true
}

existing="$(version_tags --points-at "$head" | head -n 1)"
if [ -n "$existing" ]; then
  echo "${existing#v} existing"
  exit 0
fi

last="$(version_tags | head -n 1)"
if [ -n "$last" ]; then
  range="${last}..${head}"
  base="${last#v}"
else
  range="$head"
  base="0.0.0"
fi
IFS=. read -r major minor patch <<< "$base"

subjects="$(git log --format=%s "$range")"
# 본문은 메시지 전체(%B)에서 찾는다: 제목 바로 아래 빈 줄 없이 붙은 BREAKING CHANGE도 잡는다
messages="$(git log --format=%B "$range")"

if grep -qE '^[a-z]+(\([^)]*\))?!:' <<< "$subjects" || grep -qE '^BREAKING[ -]CHANGE:' <<< "$messages"; then
  if [ "$major" -eq 0 ]; then
    minor=$((minor + 1))
    patch=0
  else
    major=$((major + 1))
    minor=0
    patch=0
  fi
elif grep -qE '^feat(\([^)]*\))?:' <<< "$subjects"; then
  minor=$((minor + 1))
  patch=0
else
  patch=$((patch + 1))
fi

echo "${major}.${minor}.${patch} new"
