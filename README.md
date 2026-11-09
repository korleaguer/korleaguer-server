# korleaguer-server — KOR Leaguer 야구 MVP 백엔드

한국 MLB 선수 6명의 최근 경기 한 줄 기록과 2026 시즌 성적을 JSON으로 준다. 화면([korleaguer-web](https://github.com/korleaguer/korleaguer-web))이 이 API를 부른다.

- Java 21, Spring Boot 4.1.2, H2 파일 DB(`./data/korleaguer`, PostgreSQL 호환 모드), Flyway
- 데이터 출처: balldontlie MLB API

## 실행

```bash
cd korleaguer-server
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # Java 21 경로
./gradlew build     # 빌드 + 테스트
./gradlew bootRun   # http://localhost:8080
```

API 키가 없으면 **샘플 모드**로 뜬다. 시작할 때 `src/main/resources/sample/*.json`(지어낸 숫자)을 자동으로 수집한다.

> **스키마가 바뀌었다(2026-11-01, V1 직접 수정: 경기 점수 컬럼 `home_score`/`away_score`, 현재 소속 중복 방지).**
> 이전에 띄운 적이 있으면 `data/` 폴더를 지우고 다시 띄운다. 지우지 않으면 Flyway 체크섬 오류로 뜨지 않는다.
> 그 뒤에 추가한 `V2__player_profile.sql`(선수 프로필 칸)은 띄울 때 자동으로 적용되므로 `data/`를 또 지울 필요는 없다.

## 모드

| 모드 | 조건 | 동작 |
|---|---|---|
| `sample` | 키 없음(기본) | 샘플 클라이언트. 앱 시작 때 자동 수집 |
| `bdl` | `BALLDONTLIE_API_KEY`가 있음 | 실제 API. 수집은 관리용 엔드포인트나 스케줄로 돌린다 |

`korleaguer.data.mode`(환경변수 `KORLEAGUER_DATA_MODE`)로 직접 정할 수도 있다.

> 샘플 모드로 돌린 뒤 실제 모드로 바꿀 때는 `data/` 폴더를 지운다. 같은 DB 파일을 쓰므로 샘플 경기가 남는다.

## 환경변수

비밀값은 파일에 쓰지 않고 환경변수로만 넣는다.

| 이름 | 뜻 |
|---|---|
| `BALLDONTLIE_API_KEY` | balldontlie API 키. 있으면 실제 모드 |
| `KORLEAGUER_ADMIN_TOKEN` | 관리용 엔드포인트 토큰. 없으면 관리용 엔드포인트는 전부 막힌다 |
| `KORLEAGUER_DATA_MODE` | `sample` / `bdl` (선택) |
| `KORLEAGUER_INGEST_SCHEDULE_ENABLED` | `true`면 하루치 수집을 매시 10분(UTC)에 돌린다. 기본 `false` |
| `KORLEAGUER_BDL_REQUESTS_PER_MINUTE` | 분당 요청 수. 기본 5(무료 요금제) |
| `KORLEAGUER_OFFICIAL_PHOTOS` | `false`면 응답의 `photoUrl`을 모두 null로 둔다. 기본 `true`(MLB 공식 사진 주소) |

> **공개 전 점검:** MLB 공식 사진·로고는 권리가 MLB에 있다. 공개하기 전에 허락을 받거나 `KORLEAGUER_OFFICIAL_PHOTOS=false`로 끈다.

## 엔드포인트

| 경로 | 설명 |
|---|---|
| `GET /api/home` | 최근 경기 한 줄 기록 + 시즌 성적(타자 타율순, 투수 평균자책점순) |
| `GET /api/players` | 선수 목록 |
| `GET /api/players/{id}` | 선수 정보 + 시즌 성적 + 경기 기록(최신순). 없으면 404 |
| `GET /api/my?ids=1,2,5&days=7` | 내 선수: 고른 선수마다 기간 합계 + 시즌 타율·평균자책점 + 연속 기록 + 경기 기록(최신순) |
| `GET /actuator/health` | 상태 확인 |
| `POST /admin/resolve-players` | 명단(`roster/baseball.yml`)의 선수를 balldontlie 선수와 짝짓기 |
| `POST /admin/ingest/season?season=2026` | 시즌 전체 수집 |
| `POST /admin/ingest/daily?date=2026-09-26` | 하루치(UTC 기준 전날·당일) 수집 |

관리용은 `X-Admin-Token` 헤더가 필요하다. 실제 모드 첫 수집 순서는 `resolve-players` → `ingest/season`이다. 분당 5회 제한 때문에 응답까지 몇 분 걸릴 수 있다.
수집은 한 번에 하나만 돈다. 도는 중에 또 부르면 409 `{"error":"ingest already running"}`이다.

```bash
curl -X POST -H "X-Admin-Token: $KORLEAGUER_ADMIN_TOKEN" localhost:8080/admin/resolve-players
```

응답 모양은 `plan/2026-11-01-야구-MVP-스펙.md` 2절(v2.1)을 따른다.

### 선수 짝짓기
- 별칭으로 검색하고, 출생지에 "Korea"가 들어가고 이름이 별칭과 같은 선수를 고른다.
- 한국 출생 후보로 못 골랐으면, 출생지와 상관없이 검색 결과 전체에서 이름이 별칭과 정확히 같은 선수가 딱 한 명일 때 그 선수를 고른다(이정후는 일본 나고야 출생). 한국 출생이 아니면 WARN을 남기고, 둘 이상이면 고르지 않는다.
- 그래도 못 골랐을 때만 "한국 출생 후보가 한 명이고 성이 같으면" 그 선수를 고른다(WARN).
- 자동으로 못 고르면 로그(WARN)에 후보가 찍힌다. `roster/baseball.yml`에 `bdl-player-id`를 적으면 검색하지 않고 그 선수를 쓴다.

### 선수 프로필(`GET /api/players`, `GET /api/players/{id}`)
- 명단(`roster/baseball.yml`)의 `mlb-id`(MLB 공식 선수 번호)로 `photoUrl`(공식 사진)과 `mlbUrl`(mlb.com/player/번호)을 만든다. `career-ko`(한국 시절 소속)는 사람이 적은 문장이라 공개 전에 다시 확인한다.
  이 값들은 balldontlie와 매칭 전인 선수도 나온다. `mlbUrl`은 사진 설정과 무관하다.
- 생년월일·키·몸무게·투타·등번호·데뷔 연도는 선수 매칭 때 balldontlie 값을 DB(`player`)에 저장한다. 없으면 null이다.
  - 생년월일: `yyyy-MM-dd`, `M/d/yyyy`를 읽는다. 못 읽으면 WARN 한 줄을 남기고 비운다.
  - `heightCm`·`weightKg`: 피트·인치(`6' 2"`), 파운드(`194 lbs`)를 정수로 반올림해 바꾼다.
  - `batsThrowsKo`: `Left/Right`(타격/투구) → `우투좌타`(투구 먼저).
  - `age`: 생년월일이 있으면 한국 시간 오늘 기준 만 나이, 없으면 balldontlie가 준 나이.

### 내 선수(`GET /api/my`)
- `ids`: 쉼표로 이은 선수 id. 겹치면 처음 것만 남기고 1~20개여야 한다. 응답 `players`는 요청한 순서이고, 없는 id는 조용히 뺀다.
- `days`: 7·14·30 중 하나(기본 7).
- 규칙에 안 맞으면(ids 없음·숫자 아님·21개 이상, days가 다른 값) 400 `{"error":"..."}`이다.
- 기간: 한국 날짜로 오늘까지 `days`일. 고른 선수들 팀의 마지막 끝난 경기가 그보다 이르면(비시즌) 마지막 경기일까지 `days`일로 대신한다(`isFallback: true`). 범위는 `fromKst`~`toKst`.
- `hitting`(타자)·`pitching`(투수): 기간 안에 뛴 경기 합계와 시즌(정규시즌) 타율 `seasonAvg`·평균자책점 `seasonEra`. 평균자책점·아웃·자책점은 아웃 수를 아는 경기만 센다.
- `streak`: 이번 시즌 최근 경기부터 이어진 연속 기록. 타자는 안타 경기(타수도 안타도 없는 경기는 건너뜀), 투수는 무실점 등판.
- 선수 기록을 받지 못한 상태면 `hitting`·`pitching`·`streak`이 null이다. 조합이 사람마다 달라 캐시하지 않는다.
- 경기 한 줄(`games`, 홈·선수 응답의 경기 줄도 같다)에는 그래프용 숫자 `batting`(타수·안타·홈런·타점·볼넷·도루)·`pitching`(아웃·이닝·실점·자책·탈삼진·승패세)이 붙는다. 뛴 경기에 그 기록이 있을 때만 나오고, 결장·경기 전이면 null이다.

### 결장 판단
- 경기가 끝났고(final) 선수 기록도 받았는데 그 선수 기록이 없으면 "결장"이다.
- 정규시즌·포스트시즌 경기 중, 선수가 그 팀에서 처음 기록을 남긴 경기부터 마지막 기록을 남긴 경기 사이만 본다.
  - 지금 소속 팀이 마지막 기록의 팀이면 마지막 출전 뒤 경기도 결장으로 본다.
  - 이적 전 팀의 이적 뒤 경기, 첫 출전 전 경기, 시범경기는 줄을 만들지 않는다.
- 선수 기록을 받지 못했으면(요금제, 아직 수집 전) "기록 없음"이다.

## 구조

```
com.korleaguer
├── config   설정(korleaguer.*), 데이터 모드, 클라이언트 선택, CORS, 캐시
├── client   balldontlie 호출(HttpBdlClient)과 샘플(SampleBdlClient), 응답 record
├── ingest   선수 짝짓기, 시즌·하루치 수집, DB 저장, 샘플 자동 수집, 스케줄
├── domain   JPA 엔티티와 리포지토리
├── api      컨트롤러, 응답 DTO, 조회 서비스, 시즌 성적 계산
└── support  한국 시간, 야구 계산(타율·ERA·이닝), 팀 한글 이름, 키·몸무게·투타·생년월일 변환, 한 줄 기록 문장, 요청 제한
```

- DB 스키마: `src/main/resources/db/migration/V1__init.sql`, 선수 프로필 칸은 `V2__player_profile.sql`. 시각은 UTC로 저장하고 응답에서만 한국 시간으로 바꾼다.
- 수집: 경기와 선수 기록을 다 받은 뒤 한 트랜잭션으로 저장한다. 값이 이상한 경기·기록은 그 건만 건너뛰고 수집 메시지에 개수를 남긴다.
- 요청 제한: 어느 60초 구간에도 N회(기본 5회)를 넘지 않는다. 429가 오면 1분을 쉬고, 5xx·네트워크 오류는 5·10·20초 기다리며 최대 3회 다시 시도한다.
- 캐시: 홈·선수 응답을 메모리(Caffeine)에 60초 캐시하고, 수집이 끝나면 비운다(나중에 Redis로 바꿀 자리). 내 선수 응답은 캐시하지 않는다.
- H2 콘솔: 기본은 꺼져 있다. `local` 프로필로 띄우면 `http://localhost:8080/h2-console`이 열린다.
  ```bash
  SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
  ```
  JDBC URL은 `application.properties`의 `spring.datasource.url`, 사용자 `sa`, 비밀번호 없음.
