#!/usr/bin/env bash
# ./gradlew를 돌리되, 라이브러리나 Gradle 본체 내려받기가 막혔을 때만 잠깐 쉬고 다시 한다(모두 세 번까지).
# 컴파일 오류와 테스트 실패는 다시 하지 않고 바로 실패로 끝낸다. 진짜 실패를 가리지 않으려는 것이다.
# 다시 하는 경우는 Gradle이 내려받기 실패 때 쓰는 문구가 출력에 있을 때뿐이다(아래 pattern).
#   - Maven Central이 429(요청 너무 많음)·5xx를 주거나 응답이 없을 때
#   - Gradle 본체(services.gradle.org) 내려받기가 시간 초과일 때
# 라이브러리 이름을 잘못 적은 경우도 같은 문구가 나와 세 번 해 본 뒤 실패한다(1분 30초쯤 늦게 알게 된다).
# 쓰는 법: .github/scripts/gradlew-retry.sh test
set -uo pipefail

pattern="Could not resolve all (files|dependencies|artifacts|task dependencies) for configuration|Could not (GET|HEAD) '|Received status code [0-9]+ from server|Downloading from .* failed"
tries=3
log="$(mktemp)"
trap 'rm -f "$log"' EXIT

for try in $(seq 1 "$tries"); do
  ./gradlew "$@" 2>&1 | tee "$log"
  code="${PIPESTATUS[0]}"
  if [ "$code" -eq 0 ]; then
    exit 0
  fi
  if [ "$try" -eq "$tries" ] || ! grep -qE "$pattern" "$log"; then
    exit "$code"
  fi
  wait_seconds=$((try * 30))
  echo "::warning::내려받기 실패로 보여 ${wait_seconds}초 뒤 다시 합니다(${try}/${tries}번째 실패)"
  sleep "$wait_seconds"
done
