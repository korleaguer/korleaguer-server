# korleaguer-server — KOR Leaguer 야구 MVP 백엔드

한국 MLB 선수 6명의 최근 경기 한 줄 기록과 2026 시즌 성적을 JSON으로 준다. 화면([korleaguer-web](https://github.com/korleaguer/korleaguer-web))이 이 API를 부른다.

- Java 21, Spring Boot 4.1.2, H2 파일 DB(`./data/korleaguer`, PostgreSQL 호환 모드), Flyway
- 데이터 출처: MLB 공식 Stats API(`statsapi.mlb.com`, 키 없음). 경기·선수 기록·선수 정보·이동 기록을 모두 여기서 받는다(메이저 경기만).

## 실행

```bash
cd korleaguer-server
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # Java 21 경로
./gradlew build     # 빌드 + 테스트
./gradlew bootRun   # http://localhost:8080
```

기본은 **MLB 모드**다(`KORLEAGUER_DATA_SOURCE=mlb`). 앱이 뜨면 뒤에서 명단 선수의 정보와 이번 시즌 기록을 받고(몇십 초), 그 뒤로는 스케줄이 필요한 것만 받는다(아래 "받는 전략").
네트워크 없이 띄우려면 **샘플 모드**로 띄운다. 시작할 때 `src/main/resources/sample/mlb.json`(MLB 응답과 같은 모양, 지어낸 값)을 바로 수집하고, 어디에도 네트워크 요청을 보내지 않는다. 테스트도 샘플 모드로 돈다.

```bash
KORLEAGUER_DATA_SOURCE=sample ./gradlew bootRun
```

> **DB를 다시 만든다(2026-11-10, `V4__mlb_source.sql`, 이슈 #11).** balldontlie 번호로 저장하던 표를 지우고 MLB 번호(gamePk, 팀 번호, 선수 번호)로 다시 만든다.
> 배포 전이라 이전 데이터(샘플)는 버리고, 띄우면 다시 받는다. `data/` 폴더는 지우지 않아도 된다.
> (2026-11-01 이전에 띄운 DB라면 V1 체크섬 오류가 나므로 그때는 `data/`를 지운다.)
> 샘플 모드와 MLB 모드는 같은 DB 파일을 쓴다. 모드를 바꿀 때는 `data/` 폴더를 지운다(다른 쪽 데이터가 남는다).

## 환경변수

비밀값은 파일에 쓰지 않고 환경변수로만 넣는다.

| 이름 | 뜻 |
|---|---|
| `KORLEAGUER_DATA_SOURCE` | `mlb`(기본) / `sample` |
| `KORLEAGUER_ADMIN_TOKEN` | 관리용 엔드포인트 토큰. 없으면 관리용 엔드포인트는 전부 막힌다 |
| `KORLEAGUER_INGEST_SCHEDULE_ENABLED` | 자동 수집(12분마다 깨어나는 스케줄 + 앱 시작 때 따라잡기). 기본 `true`. `false`면 관리용 엔드포인트와 선수 페이지에서 시즌 받기만 MLB를 부른다 |
| `KORLEAGUER_OFFICIAL_PHOTOS` | `false`면 응답의 `photoUrl`을 모두 null로 둔다. 기본 `true`(MLB 공식 사진 주소) |

> **MLB 이용 고지:** 응답에 붙는 MLB 저작권 안내(`gdx.mlb.com/components/copyright.txt`)는 "개인적·비상업적·대량이 아닌 사용(individual, non-commercial, non-bulk use)"만 허락한다.
> 이 사이트는 개인적·비상업적으로 쓴다(사용자 결정, 2026-11-10). 대량 수집이 되지 않도록 요청은 1초에 1회 이하, 필요한 것만 받고, 받은 것은 DB에 두고 다시 받지 않는다.
> 상업적으로 쓰거나 크게 공개할 생각이 생기면 그 전에 MLB의 허락을 받는다. MLB 공식 사진·로고도 권리가 MLB에 있다(`KORLEAGUER_OFFICIAL_PHOTOS=false`로 끌 수 있다).

## 받는 전략(MLB 공식 Stats API)

화면용 API(`/api/**`)는 DB만 읽는다. MLB는 수집기(`ingest`)만 부른다. 같은 화면을 몇 번 열어도 MLB 요청은 늘지 않는다.

- **요청 예절:** 1초에 1회 이하, `User-Agent: korleaguer/0.1`, 연결 5초·읽기 10초 제한, 다시 시도하지 않는다(실패한 건은 다음 수집 때 다시). 403/429가 오면 그 실행의 남은 단계는 건너뛴다(`BLOCKED`).
- **선수 정보·이동 기록(하루 한 번):** `people?personIds=…&hydrate=currentTeam` 1번(명단 전원), `transactions?playerId=…` 선수마다 1번, 마이너 팀 단계 `teams/{id}`(한 번 물으면 기억).
- **시즌 백필(선수·시즌마다 한 번):** `people/{id}/stats?stats=gameLog&group=hitting|pitching&season=Y` 2번 + 그 선수의 지금 팀·뛴 팀 시즌 일정 `schedule?sportId=1&teamId=…&season=Y` 1번(여러 팀 한 번에) + 끝난 포스트시즌 경기 박스스코어(gameLog는 정규시즌만 준다). 다 받으면 선수·시즌별 "받았음" 표시(`player_season`)를 남기고 다시 받지 않는다.
  - 앱 시작 때, 그리고 명단에 새로 들어온 선수가 있으면 스케줄이 받는다.
  - 선수 페이지(`GET /api/players/{id}`)를 열었는데 그 선수 시즌이 비어 있으면 그 자리에서 한 번 받는다. 최대 8초(`korleaguer.ingest.on-demand-timeout`)만 기다리고, 늦거나 실패하면 DB에 있는 것만 준다(받기는 뒤에서 이어지고 끝나면 캐시를 비운다).
- **경기 갱신(시즌 중):** 우리 선수 팀들의 오늘·어제(미국 동부 날짜) 일정을 받고(1번), 진행 중인 경기와 다시 확인할 때가 된 끝난 경기만 `game/{gamePk}/boxscore`로 받는다.
  - 한국 시간 01:00~16:00(미국 경기 시간대)에는 12분마다, 그 밖에는 1시간마다(정각 근처 실행만).
  - 서버가 멈췄거나 MLB가 막아(403/429) 며칠 갱신을 못 했으면, 결과를 못 받은 경기(시작했는데 끝남·연기·취소가 아님)나 마지막으로 끝난 경기가 있는 날부터 오늘까지를 한 번에 받는다(일정 요청은 그대로 1번, 최대 14일 전까지). 그보다 오래 멈췄으면 `/admin/ingest/season`으로 시즌을 다시 받는다.
  - 끝난 경기는 끝난 직후 한 번, 그 뒤 하루(UTC 날짜)에 한 번 다시 받는다. 시작한 지 3일이 지나면 `settled`로 두고 다시 받지 않는다(기록 정정 대비).
  - 시즌 중 판단: 우리 팀 경기가 앞뒤 3일 안에 있으면 시즌 중이다(DB만 본다). 비시즌에는 선수 정보·이동 기록과 앞으로 7일 일정(`schedule?…&startDate=오늘&endDate=7일 뒤` 1번)만 하루(UTC 날짜) 한 번 받는다. 포스트시즌 경기는 그날 일정을 받을 때에야 DB에 들어오므로, 라운드 사이 쉬는 날이 길어 비시즌으로 넘어가도 이 일정으로 다음 경기를 보고 시즌 중으로 돌아온다.
- **선발·교체(`lineup`):** 박스스코어를 받는 경기(진행 중·최근·재확인)는 그때 같이 채운다. 지난 경기(settled)는 따라잡기 한 번에 최근 것부터 20경기씩(1초에 1회라 약 20초) 박스스코어로 채우고, 한 경기는 한 번만 받는다(기록 숫자는 건드리지 않는다). 단 그 뒤 시즌 백필로 그 경기에 새 기록이 생기면(나중에 명단에 들어온 선수 등) 한 번 더 받는다.
  - 타자: 타순(`battingOrder`)이 `00`으로 끝나면(100~900) `STARTER`, 그 밖의 타순(301 등)은 `SUB`. 투수: 그 편 `pitchers`의 첫 번째면 `STARTER`, 그 밖은 `SUB`. 투타겸업은 타석에 섰으면 타자 규칙. 타순도 투수 기록도 없고 `isSubstitute`면 `SUB`.
- **경기 상태 값(`gameStatus`, DB `status_state`):** MLB `abstractGameState`/`codedGameState`/`detailedState`를 `scheduled`·`in_progress`·`final`·`postponed`·`canceled`·`delayed`·`suspended`로 바꾼다(`support.GameStatuses`). 그 밖은 MLB 값을 소문자로.
- **받는 경기:** 정규시즌(R)과 포스트시즌(F·D·L·W)만 저장한다. 시범경기·올스타·마이너 경기는 받지 않는다. 연기된 경기는 같은 gamePk가 두 번 오므로 다시 잡힌 쪽을 쓴다.
- **수집 기록(`ingest_log`):** 실행마다 한 줄(`requestsUsed` = MLB 요청 수). 스케줄 실행이 MLB를 한 번도 부르지 않았으면 남기지 않는다.

## 엔드포인트

| 경로 | 설명 |
|---|---|
| `GET /api/home` | 최근 경기 한 줄 기록 + 시즌 성적(타자 타율순, 투수 평균자책점순) |
| `GET /api/players` | 선수 목록 |
| `GET /api/players/{id}` | 선수 정보 + 시즌 성적 + 경기 기록(최신순). 없으면 404 |
| `GET /api/my?ids=1,2,5&days=7` | 내 선수: 고른 선수마다 기간 합계 + 시즌 타율·평균자책점 + 연속 기록 + 경기 기록(최신순) |
| `GET /actuator/health` | 상태 확인 |
| `POST /admin/ingest/players` | 명단 선수의 정보·지금 팀·단계·이동 기록을 지금 다시 받기 |
| `POST /admin/ingest/season?season=2026` | 명단 선수 전원의 시즌을 다시 받기(받은 적이 있어도) |
| `POST /admin/ingest/games?date=2026-09-26` | 그 미국 날짜와 전날의 우리 팀 경기 다시 받기(끝난 경기도 박스스코어를 다시 받는다) |
| `POST /admin/ingest/catch-up` | 스케줄이 하는 따라잡기를 지금 한 번(경기 갱신 포함) |

관리용은 `X-Admin-Token` 헤더가 필요하다. MLB 요청이 1초에 1회라 시즌 다시 받기는 몇십 초 걸릴 수 있다.
수집은 한 번에 하나만 돈다. 도는 중에 또 부르면 409 `{"error":"ingest already running"}`이다.
응답의 `dataMode`는 `"mlb"` / `"sample"`이고, `statsAvailable`은 늘 `true`다(화면 호환 때문에 남긴 필드).

```bash
curl -X POST -H "X-Admin-Token: $KORLEAGUER_ADMIN_TOKEN" localhost:8080/admin/ingest/players
```

응답 모양은 `plan/2026-11-01-야구-MVP-스펙.md` 2절(v2.1)을 따른다.

### 선수 프로필(`GET /api/players`, `GET /api/players/{id}`)
- 명단(`roster/baseball.yml`)의 `mlb-id`(MLB 공식 선수 번호)로 모든 데이터를 받는다. `photoUrl`(공식 사진)과 `mlbUrl`(mlb.com/player/번호)도 이 번호로 만든다. `career-ko`(한국 시절 소속)는 사람이 적은 문장이라 공개 전에 다시 확인한다.
  이 값들은 MLB에서 받기 전에도 나온다. `mlbUrl`은 사진 설정과 무관하다.
- 생년월일·키·몸무게·투타·등번호·데뷔 연도·출생지·포지션은 MLB 선수 정보(`people`)를 DB(`player`)에 저장한다. 없으면 null이다.
  - 생년월일: `yyyy-MM-dd`. 못 읽으면 WARN 한 줄을 남기고 비운다.
  - `heightCm`·`weightKg`: 피트·인치(`6' 0"`), 파운드(`196`)를 정수로 반올림해 바꾼다.
  - `batsThrowsKo`: 타격 `batSide`/투구 `pitchHand`(`Left/Right`) → `우투좌타`(투구 먼저).
  - `position`: MLB 주 포지션 약어를 영어 이름으로(`CF` → `Center Fielder`). 투수는 MLB가 `P`만 주므로 `Pitcher`(투수)다(선발·구원 구분 없음).
  - `teamKo`·`teamAbbr`: 지금 소속 MLB 구단. 마이너 팀에 있으면 그 팀의 MLB 구단이다(그 마이너 팀 이름은 `levelTeam`).
    MLB 구단 소속이 없어지면(FA 등) 마지막 소속 구단을 그대로 둔다(`level`·`levelTeam`은 null). 시즌 기록이 있는 선수가 홈 시즌 성적·최근 경기에서 빠지지 않게 하려는 것이다. 처음 받을 때 이미 소속이 없으면 마지막으로 뛴 경기의 팀을 쓴다.
  - `age`: 생년월일이 있으면 한국 시간 오늘 기준 만 나이, 없으면 MLB가 준 나이.

### 내 선수(`GET /api/my`)
- 선수(`player`)에는 아래 "메이저/마이너와 이동 기록"의 `level`·`levelTeam`도 들어 있다.
- `ids`: 쉼표로 이은 선수 id. 겹치면 처음 것만 남기고 1~20개여야 한다. 응답 `players`는 요청한 순서이고, 없는 id는 조용히 뺀다.
- `days`: 7·14·30 중 하나(기본 7).
- 규칙에 안 맞으면(ids 없음·숫자 아님·21개 이상, days가 다른 값) 400 `{"error":"..."}`이다.
- 기간: 선수마다 정한다(`players[].fromKst`~`toKst`). 보통은 한국 날짜로 오늘까지 `days`일이다. 최근 `days`일에 구단 경기(메이저에 있을 때)도 본인 출전도 없으면(구단 시즌이 끝났거나 마이너에 있음) 그 선수가 마지막으로 뛴 날까지 `days`일로 당긴다(`players[].isFallback: true`). 시즌 중 구단은 경기하는데 본인이 빠졌으면(부상·벤치) 당기지 않고 결장 줄을 그대로 보인다. 맨 위 `fromKst`~`toKst`는 오늘까지 `days`일이고, 맨 위 `isFallback`은 한 명이라도 당겼으면 `true`다.
- `hitting`(타자)·`pitching`(투수): 기간 안에 뛴 경기 합계와 시즌(정규시즌) 타율 `seasonAvg`·평균자책점 `seasonEra`. 평균자책점·아웃·자책점은 아웃 수를 아는 경기만 센다.
- `streak`: 이번 시즌 최근 경기부터 이어진 연속 기록. 타자는 안타 경기(타수도 안타도 없는 경기는 건너뜀), 투수는 무실점 등판.
- 조합이 사람마다 달라 캐시하지 않는다.
- 경기 한 줄(`games`, 홈·선수 응답의 경기 줄도 같다)에는 그래프용 숫자 `batting`(타수·안타·홈런·타점·볼넷·도루)·`pitching`(아웃·이닝·실점·자책·탈삼진·승패세)이 붙는다. 뛴 경기에 그 기록이 있을 때만 나오고, 결장·경기 전이면 null이다.
- 경기 한 줄의 `lineup`(`pitching` 다음)은 `"STARTER"`(선발) / `"SUB"`(교체)다. 뛰지 않았거나 아직 모르면 null이다(위 "받는 전략"의 선발·교체).

### 메이저/마이너와 이동 기록(MLB 공식 Stats API)
- 위 "받는 전략"의 선수 정보·이동 기록 단계에서 하루(UTC 날짜)에 한 번 받는다. 실패한 선수는 다음 수집 때 다시 묻는다.
  - 이동 기록 기간: 지난 시즌 1월 1일 ~ 이번 시즌 12월 31일
  - 팀 단계: `sport.id` 1 = MLB, 11~16 = 마이너. MLB 30개 구단은 묻지 않고, 나머지 팀은 한 번 물어 앱이 떠 있는 동안 기억한다.
- 저장: `player.level`(`MLB`/`MINORS`, 소속이 없거나 모르면 null)·`level_team_name`(MLB가 준 지금 팀 이름)·`level_checked_at`, 이동 기록 표 `player_move`(MLB 이동 기록 번호로 upsert, 받은 줄은 모두 저장, 양쪽 팀 번호·이름·단계).
- `GET /api/players`·`GET /api/my`의 선수: `level`(`"MLB"`/`"MINORS"`/null), `levelTeam`(MLB가 준 지금 팀 이름, 예: `"St. Paul Saints"`, 확인 전이면 null).
- `GET /api/players/{id}`의 `moves`(최신순): `{ date, typeCode, labelKo, fromTeam, toTeam, toLevel }`
  - `labelKo`: OPT 마이너 강등(옵션), CU·SE 메이저 콜업, OUT 마이너 이관(아웃라이트), DES 지명 할당(DFA), TR 트레이드, SFA·SGN·"Signed" 계약, DFA(Declared Free Agency) FA 선언, REL 방출, CLW 웨이버 클레임, SU 출장 정지, ASG 재활 경기 배정·스프링캠프 초청·팀 배정. 표에 없으면 영어 `typeDesc` 그대로.
  - 선수 상태 변경(SC)은 설명 글에 부상자 명단(등재·해제·이동)이나 출장 정지가 있을 때만 남기고 나머지("roster status changed", 그냥 "activated")는 뺀다. MLB가 같은 날 같은 내용을 두 번 준 줄도 하나만 남긴다.
  - 팀 이름: MLB 구단은 한글 짧은 이름(`미네소타`), 마이너 팀 등은 MLB가 준 영어 이름 그대로(`St. Paul Saints`).

### 결장 판단
- 경기가 끝났고(final) 그 선수 기록이 없으면 "결장"이다.
- 정규시즌·포스트시즌 경기 중, 선수가 그 팀에서 처음 기록을 남긴 경기부터 마지막 기록을 남긴 경기 사이만 본다.
  - 지금 소속 팀이 마지막 기록의 팀이면 마지막 출전 뒤 경기도 결장으로 본다. 단 마이너에 있거나 소속이 끝난(FA 등) 선수는 마지막 출전에서 끝낸다(그 구단 경기는 결장으로도 마지막 경기일로도 보지 않고, 경기 갱신에서도 뺀다).
  - 이적 전 팀의 이적 뒤 경기, 첫 출전 전 경기는 줄을 만들지 않는다(시범경기는 저장하지 않는다).
- 그 선수의 시즌을 아직 받지 않았으면 기록이 하나도 없으므로 결장 줄도 만들지 않는다.

## 구조

```
com.korleaguer
├── config   설정(korleaguer.*), 데이터 출처, 클라이언트 선택, CORS, 캐시, 뒤에서 도는 수집용 실행기
├── client   MLB 공식 API 호출(HttpMlbClient)과 샘플(SampleMlbClient), 응답 record
├── ingest   선수 정보·이동 기록(PlayerRefresher), 시즌 백필·경기 갱신(GameIngest), 진입점·잠금·기록(IngestService),
│            스케줄(IngestScheduler), 앱 시작 때 따라잡기(StartupIngest), 선수 페이지에서 시즌 받기(OnDemandSeason), DB 저장(IngestWriter)
├── domain   JPA 엔티티와 리포지토리
├── api      컨트롤러, 응답 DTO, 조회 서비스, 시즌 성적 계산
└── support  한국 시간, 야구 계산(타율·ERA·이닝), 팀 한글 이름·MLB 팀 번호, 경기 상태 값, 포지션·키·몸무게·투타·생년월일 변환, 한 줄 기록 문장, 이동 기록 이름, 요청 제한
```

- DB 스키마: 지금 모양은 `src/main/resources/db/migration/V4__mlb_source.sql`(V1~V3 위에서 표를 MLB 번호로 다시 만든다)과 `V5__appearance_lineup.sql`(선발·교체). 시각은 UTC로 저장하고 응답에서만 한국 시간으로 바꾼다.
- 수집: 받은 뒤 저장만 트랜잭션으로 묶는다(시즌 백필은 일정·gameLog·"받았음" 표시를 한 번에). 값이 이상한 경기·기록은 그 건만 건너뛰고 수집 메시지에 개수를 남긴다.
- 캐시: 홈·선수 응답을 메모리(Caffeine)에 60초 캐시하고, 수집이 끝나면 비운다(나중에 Redis로 바꿀 자리). 내 선수 응답은 캐시하지 않는다.
- H2 콘솔: 기본은 꺼져 있다. `local` 프로필로 띄우면 `http://localhost:8080/h2-console`이 열린다.
  ```bash
  SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
  ```
  JDBC URL은 `application.properties`의 `spring.datasource.url`, 사용자 `sa`, 비밀번호 없음.
