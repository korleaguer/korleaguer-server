# korleaguer-server — KOR Leaguer 야구 MVP 백엔드

한국 MLB 선수 6명의 최근 경기 한 줄 기록과 2026 시즌 성적을 JSON으로 준다. 화면([korleaguer-web](https://github.com/korleaguer/korleaguer-web))이 이 API를 부른다.

- Java 21, Spring Boot 4.1.2, Flyway
- DB: 운영은 MySQL 8.4(서버의 Docker, `deploy/`). 로컬 실행과 테스트는 Docker 없이 돌도록 H2를 MySQL 모드로 쓴다(로컬 파일 `./data/korleaguer-mysql`). 실제 MySQL 확인은 `MySqlCompatibilityTest`(Testcontainers, Docker가 있을 때만)가 맡는다.
- 데이터 출처: MLB 공식 Stats API(`statsapi.mlb.com`, 키 없음). 경기·선수 기록·선수 정보·이동 기록을 모두 여기서 받는다(경기는 메이저만, 마이너는 시즌 출전 경기 수만).

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

> **DB를 MySQL로 바꿨다(2026-11-10, 이슈 #25).** 배포 전이라 예전 마이그레이션 V1~V6을 지금 모양 그대로 `V1__init.sql` 하나로 합쳤다(MySQL 문법).
> 로컬 DB 파일도 `./data/korleaguer-mysql`로 새로 만든다. 예전 `./data/korleaguer*` 파일은 더 쓰지 않으니 지워도 된다(띄우면 MLB에서 다시 받는다).
> 샘플 모드와 MLB 모드는 같은 DB 파일을 쓴다. 모드를 바꿀 때는 `data/` 폴더를 지운다(다른 쪽 데이터가 남는다).

## 환경변수

비밀값은 파일에 쓰지 않고 환경변수로만 넣는다.

| 이름 | 뜻 |
|---|---|
| `KORLEAGUER_DATA_SOURCE` | `mlb`(기본) / `sample` |
| `KORLEAGUER_ADMIN_TOKEN` | 관리용 엔드포인트 토큰. 없으면 관리용 엔드포인트는 전부 막힌다 |
| `KORLEAGUER_INGEST_SCHEDULE_ENABLED` | 자동 수집(12분마다 깨어나는 스케줄 + 앱 시작 때 따라잡기). 기본 `true`. `false`면 관리용 엔드포인트와 선수 페이지에서 시즌 받기만 MLB를 부른다 |
| `KORLEAGUER_OFFICIAL_PHOTOS` | `false`면 응답의 `photoUrl`을 모두 null로 둔다. 기본 `true`(MLB 공식 사진 주소) |
| `KORLEAGUER_NEWS_ENABLED` | 포스팅 선수와 명단 선수 관련 기사 수집·요약(30분마다). 기본 `true`. 포스팅 선수는 협상 기간(시작 3일 전 ~ 마감 3일 뒤)에만, 명단 선수(국내 리그로 돌아간 선수 빼고)는 늘 최근 30일 기사를 모은다(이슈 #119). `false`여도 홈은 DB에 있는 기사를 보여 준다 |
| `KORLEAGUER_NEWS_USER_AGENT` | 기사 피드를 받을 때 밝히는 이름. 기본 `korleaguer/0.1 (news headlines and summaries; personal, non-commercial)` |
| `KORLEAGUER_PHOTOS_ENABLED` | 선수 사진 미리 받기(앱이 뜰 때와 6시간마다). 기본 `true`. 꺼도 사진은 처음 요청될 때 받아 저장한다 |
| `KORLEAGUER_SEASON` | 시즌을 이 연도로 고정한다(테스트·수동 고정용). 샘플 모드는 샘플 데이터의 시즌(2026)으로 고정된다. 기본은 비움 = 자동: 미국 날짜로 올해 정규시즌 개막일이 지났으면 올해, 아니면 작년. 개막일을 아직 못 받았으면 3월 20일로 본다. 앱을 다시 띄우지 않아도 개막일에 새 시즌으로 넘어간다 |

> **MLB 이용 고지:** 응답에 붙는 MLB 저작권 안내(`gdx.mlb.com/components/copyright.txt`)는 "개인적·비상업적·대량이 아닌 사용(individual, non-commercial, non-bulk use)"만 허락한다.
> 이 사이트는 개인적·비상업적으로 쓴다(사용자 결정, 2026-11-10). 대량 수집이 되지 않도록 요청은 1초에 1회 이하, 필요한 것만 받고, 받은 것은 DB에 두고 다시 받지 않는다.
> 상업적으로 쓰거나 크게 공개할 생각이 생기면 그 전에 MLB의 허락을 받는다. MLB 공식 사진·로고도 권리가 MLB에 있다(`KORLEAGUER_OFFICIAL_PHOTOS=false`로 끌 수 있다).

## 받는 전략(MLB 공식 Stats API)

화면용 API(`/api/**`)는 DB만 읽는다. MLB는 수집기(`ingest`)만 부른다. 같은 화면을 몇 번 열어도 MLB 요청은 늘지 않는다.

- **요청 예절:** 1초에 1회 이하, `User-Agent: korleaguer/0.1`, 연결 5초·읽기 10초 제한, 다시 시도하지 않는다(실패한 건은 다음 수집 때 다시). 403/429가 오면 그 실행의 남은 단계는 건너뛴다(`BLOCKED`).
- **시즌 날짜(개막 전까지 하루 한 번):** `seasons/{year}?sportId=1` 올해(미국 날짜) 1번. 지금 시즌은 올해 정규시즌 개막일만으로 정해지므로(`season_dates`), 1월 1일부터 올해 개막일 전날까지만 묻고 개막한 뒤에는 묻지 않는다(1년에 80여 번). 발표 전인 해는 저장하지 않고, 시즌을 고정했으면(`KORLEAGUER_SEASON`, 샘플 모드) 묻지 않는다.
- **선수 정보·이동 기록(하루 한 번):** `people?personIds=…&hydrate=currentTeam` 1번(명단 전원), `transactions?playerId=…` 선수마다 1번, 마이너 경기별 기록 `people/{id}/stats?stats=gameLog&group=hitting,pitching&leagueListId=milb_all` 선수마다 1번(그 시즌 전체를 받아 `minor_game_log`를 통째로 바꾸고, 마이너 경기 수도 여기서 센다), 그 경기들의 시작 시각·점수·팀 지명 `schedule?gamePks=…&hydrate=team`(선수 화면 마이너 경기 줄, 40경기씩 묶어서. 끝나 점수를 받은 경기는 다시 묻지 않아 보통 선수마다 하루 0~1번), 마이너 팀 단계 `teams/{id}`(한 번 물으면 기억).
- **시즌 백필(선수·시즌마다 한 번):** `people/{id}/stats?stats=gameLog&group=hitting|pitching&season=Y` 2번 + 그 선수의 지금 팀·뛴 팀 시즌 일정 `schedule?sportId=1&teamId=…&season=Y` 1번(여러 팀 한 번에) + 끝난 포스트시즌 경기 박스스코어(gameLog는 정규시즌만 준다). 다 받으면 선수·시즌별 "받았음" 표시(`player_season`)를 남기고 다시 받지 않는다.
  - 앱 시작 때, 그리고 명단에 새로 들어온 선수가 있으면 스케줄이 받는다.
  - 선수 페이지(`GET /api/players/{id}`)를 열었는데 그 선수 시즌이 비어 있으면 그 자리에서 한 번 받는다. 최대 8초(`korleaguer.ingest.on-demand-timeout`)만 기다리고, 늦거나 실패하면 DB에 있는 것만 준다(받기는 뒤에서 이어지고 끝나면 캐시를 비운다).
- **MLB 안 위치·시즌별 기록(하루 한 번, 시즌 중·비시즌 모두):** 리그 전체 `stats?stats=season|sabermetrics&group=hitting|pitching&season=Y&sportIds=1&playerPool=ALL&limit=2000` 묶음마다 2번(우리 선수에 투수가 없으면 타격만), `people/{id}/stats?stats=yearByYear` 선수마다 1번, 지난 시즌 지표 `people/{id}/stats?stats=sabermetrics&season=Y`는 선수·시즌마다 처음 한 번만. 아래 "MLB 안 위치와 지난 시즌 표".
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
| `GET /api/home` | 최근 경기 한 줄 기록 + 시즌 성적(주로 메이저인 선수 먼저, 그 안에서 순위 기준을 채운 선수 먼저 타자 타율순, 투수 평균자책점순) + 순위 기준(`ranking`). 비시즌이면 `offseason`(다음 개막일, 선수별 시즌 성적표 `report`)과 `posting` |
| `GET /api/players` | 선수 목록 |
| `GET /api/players/{id}` | 선수 정보 + 시즌 성적 + 경기 기록(최신순) + MLB 안 위치(`saber`)·시즌별 기록(`seasons`)·기사(`news`) + 이번 시즌 마이너 경기(`minorGames`). 없으면 404 |
| `GET /api/my?ids=1,2,5&days=7` | 내 선수: 고른 선수마다 기간 합계 + 시즌 타율·평균자책점 + 연속 기록 + 경기 기록(최신순) |
| `GET /api/news/{id}` | 관련 기사 한 건: 제목(영어 기사는 한국어 제목도)·대표 사진 주소·AI 요약·원문 주소 + 같은 선수의 다른 기사 5건. 없거나 뺀 언론사의 기사면 404 |
| `GET /actuator/health` | 상태 확인 |
| `POST /admin/ingest/players` | 명단 선수의 정보·지금 팀·단계·이동 기록을 지금 다시 받기 |
| `POST /admin/ingest/season?season=2026` | 명단 선수 전원의 시즌을 다시 받기(받은 적이 있어도) |
| `POST /admin/ingest/games?date=2026-09-26` | 그 미국 날짜와 전날의 우리 팀 경기 다시 받기(끝난 경기도 박스스코어를 다시 받는다) |
| `POST /admin/ingest/catch-up` | 스케줄이 하는 따라잡기를 지금 한 번(경기 갱신 포함) |

관리용은 `X-Admin-Token` 헤더가 필요하다. MLB 요청이 1초에 1회라 시즌 다시 받기는 몇십 초 걸릴 수 있다.
수집은 한 번에 하나만 돈다. 도는 중에 또 부르면 409 `{"error":"ingest already running"}`이다.
순위 기준(`ranking`)은 표본이 아주 적은 선수가 타율·OPS·평균자책점 1위가 되지 않게 거르는 최소값이다. 규정타석(팀 경기 수 × 3.1)보다 느슨하게 타자는 팀 경기 수 × 0.5타석(최소 10), 투수는 팀 경기 수 × 0.2이닝(최소 5이닝)이다. 팀 경기 수(`teamGames`)는 메이저에 있는 우리 선수 구단 중 이번 정규시즌에 끝난 경기가 가장 많은 구단의 경기 수다.

"올해 주로 마이너"(`mainlyMinors`)는 같은 시즌 마이너(트리플A ~ 루키) 경기 수(`minorGames`, 타자는 타자로 나온 경기, 투수는 등판)가 메이저 정규시즌 경기 수(`games`)보다 많은 선수다. 지금 단계(`level`)가 아니라 시즌 경기 수로 정하므로, 시즌 중에 콜업돼 지금 메이저에 있어도 마이너에서 더 많이 뛰었으면 true다. 홈 시즌 성적에서는 맨 아래에 두고, 선수 페이지의 시즌 성적에도 같은 두 값이 있다. 마이너 경기 수를 아직 받지 않았으면 0이다.

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
- 기간: 선수마다 정한다(`players[].fromKst`~`toKst`). 보통은 한국 날짜로 오늘까지 `days`일이다. 최근 `days`일에 구단 경기(메이저에 있을 때)도 본인 출전도 없으면(구단 시즌이 끝났거나 마이너에 있음) 그 선수가 마지막으로 뛴 날까지 `days`일로 당긴다(`players[].isFallback: true`). 시즌 중 구단은 경기하는데 본인이 빠졌으면(부상·벤치) 당기지 않고 결장 줄을 그대로 보인다. 맨 위 `fromKst`~`toKst`는 오늘까지 `days`일이고, 맨 위 `isFallback`은 마지막으로 뛴 날까지 당긴 기간을 보이는 카드(`basis`가 `LAST_PLAYED`, 또는 마지막 재활 경기까지 당긴 `REHAB`)가 한 장이라도 있으면 `true`다.
- `hitting`(타자)·`pitching`(투수): 기간 안에 뛴 경기 합계와 시즌(정규시즌) 타율 `seasonAvg`·평균자책점 `seasonEra`. 평균자책점·아웃·자책점은 아웃 수를 아는 경기만 센다.
- `streak`: 이번 시즌 최근 경기부터 이어진 연속 기록. 타자는 안타 경기(타수도 안타도 없는 경기는 건너뜀), 투수는 무실점 등판.
- 카드는 선수·`days`마다, 응답은 선수 조합(`ids` 글자 그대로)·`days`마다 캐시한다. 처음 보는 조합도 캐시한 카드를 요청 순서대로 모으기만 한다(카드는 선수마다 따로 정해져 함께 계산한 것과 같다). 데이터나 날짜가 바뀔 때 비우고, 명단 전원의 카드(7·14·30일)와 비시즌 홈이 부르는 명단 전원·7일 응답은 미리 채워 둔다(아래 "캐시").
- 경기 한 줄(`games`, 홈·선수 응답의 경기 줄도 같다)에는 그래프용 숫자 `batting`(타수·안타·홈런·타점·볼넷·도루)·`pitching`(아웃·이닝·실점·자책·탈삼진·승패세)이 붙는다. 뛴 경기에 그 기록이 있을 때만 나오고, 결장·경기 전이면 null이다.
- 경기 한 줄의 `lineup`(`pitching` 다음)은 `"STARTER"`(선발) / `"SUB"`(교체)다. 뛰지 않았거나 아직 모르면 null이다(위 "받는 전략"의 선발·교체).
- 최근 폼 요약(이슈 #33). 판정('좋음'·'주춤')은 만들지 않고 사실만 준다.
  - `status`: 상태 배지. 메이저에서 평소대로 뛰면 null. `kind`는 `IL`(부상자 명단) · `RETURNED`(복귀, 7일 안) · `CALLED_UP`(콜업, 7일 안) · `MINORS` · `MINORS_IL` · `SUSPENDED`(출장 정지) · `DFA`(지명 할당) · `FA` · `SEASON_OVER`(정규시즌이 끝났고, 메이저 팀이 더 경기하지 않으며(포스트시즌이 아니며), 본인도 3일 넘게 경기 없음). `since`·`days`는 시작한 미국 날짜와 며칠째(부상자 명단은 소급 등재일, 10일→60일 이동은 처음 오른 날), `level`·`teamName`은 마이너 단계 약어·팀(새 팀에서 아직 안 뛰었으면 단계는 null, 메이저 부상자 명단에서 재활 경기 중이면 재활 팀), `lastGameDate`·`lastGameLevel`은 FA·시즌 끝일 때 마지막 경기. 이동 기록을 최신부터 보며 정하고, 메이저·마이너 여부는 선수 정보의 지금 단계를 믿는다. 국가대표 등 메이저·마이너 팀이 아닌 곳의 활성화는 건너뛰고, 콜업과 같은 날 붙은 활성화는 콜업으로 본다. 지명 할당 뒤 트레이드·클레임·계약·방출이 있으면 지명 할당은 끝난 것으로 본다. 복귀·콜업·강등이 이번 정규시즌 개막 전이면(지난 시즌·스프링캠프) 배지도 '복귀 후' 같은 기간도 없다.
  - `play`: 출전 줄(메이저에 있을 때만). 타자는 `recent`(지금 단계 기간이 시작된 뒤 팀의 끝난 경기 최근 10개, 오래된 것부터 `STARTER`·`SUB`·`PLAYED`·`ABSENT`. 복귀·콜업 뒤면 그날부터 팀 경기를 모두 세서 그 팀에서 처음 뛰기 전 경기도 결장이다)와 `after`(10개가 안 되고 복귀·콜업 뒤면 `RETURNED`·`CALLED_UP`), 투수는 `appearances14`(최근 14일 등판)·`daysSinceLast`.
  - `fact`: 한 줄 소식 `{tone: HOT|COLD, text}`. 연속 안타 5경기↑, 연속 무안타 4경기↑(2타석 이상 경기만), 최근 10경기 2홈런↑·멀티히트 3번↑, 연속 무실점 등판 3번↑, 최근 5번 등판 중 무실점 4번↑·실점 3번↑. 연속 기록은 시즌 전체, 최근 경기는 지금 단계 기간에서 센다(다 차지 않으면 "복귀 후 6경기"). 끝난 경기만 센다(진행 중 경기는 끝나면 반영). 재활 경기 카드에는 없다. 마이너면 앞에 "트리플A " 등. FA·시즌 끝이면 시즌 최장 연속 기록.
  - `basis`·`level`: 큰 숫자·막대(`hitting`·`pitching`·`games`)가 어떤 기간·단계인지. `RECENT`(오늘까지) · `LAST_PLAYED`(마지막으로 뛴 날까지) · `BEFORE_INJURY`(메이저 부상자 명단이면 이번 시즌 마지막 출전까지) · `REHAB`(메이저 부상자 명단에 오른 뒤 마이너 재활 경기를 뛰었으면 그 재활 경기: 오늘까지, 없으면 마지막 재활 경기까지 `days`일, 시즌 비교 값 없음) · `SEASON`(FA·시즌 끝: `seasonTotals`를 쓴다) · `NONE`(이번 시즌 경기가 없거나 새 마이너 팀에서 아직 안 뜀). 마이너에 있으면 마이너 경기별 기록으로 채우고 `level`은 `AAA` 등이다. 마이너 경기는 미국 날짜만 있어 한국 날짜로는 다음 날 08:00으로 보고, 점수 없이 결과(W/L)만 있다.
  - `seasonTotals`: FA·시즌 끝일 때 `{season, primary, mlb, minors}`. `primary`는 메이저 20경기 이상이거나 메이저가 더 많으면 `MLB`, 아니면 `MINORS`.

### 메이저/마이너와 이동 기록(MLB 공식 Stats API)
- 위 "받는 전략"의 선수 정보·이동 기록 단계에서 하루(UTC 날짜)에 한 번 받는다. 실패한 선수는 다음 수집 때 다시 묻는다.
  - 이동 기록 기간: 지난 시즌 1월 1일 ~ 이번 시즌 12월 31일
  - 팀 단계: `sport.id` 1 = MLB, 11~16 = 마이너. MLB 30개 구단은 묻지 않고, 나머지 팀은 한 번 물어 앱이 떠 있는 동안 기억한다.
- 저장: `player.level`(`MLB`/`MINORS`, 소속이 없거나 모르면 null)·`level_team_name`(MLB가 준 지금 팀 이름)·`level_checked_at`, 이동 기록 표 `player_move`(MLB 이동 기록 번호로 upsert, 받은 줄은 모두 저장, 양쪽 팀 번호·이름·단계).
- `GET /api/players`·`GET /api/my`·`GET /api/players/{id}`의 선수: `level`(`"MLB"`/`"MINORS"`/null), `levelTeam`(MLB가 준 지금 팀 이름, 예: `"St. Paul Saints"`, 확인 전이면 null), `freeAgent`.
- FA(`freeAgent`): MLB의 지금 소속(`currentTeam`)은 FA를 선언해도 바로 비지 않으므로 이동 기록으로 정한다. 마지막 FA 선언(`DFA`, Declared Free Agency)이나 방출(`REL`) 뒤에 새 팀이 생기는 기록이 없으면 FA다. 뒤 날짜 기록은 FA 선언·방출·상태 변경(`SC`)·출장 정지(`SU`)·등번호(`NUM`) 말고 모두 새 팀으로 보고, 같은 날 기록은 계약(`SFA`·`SGN`·"Signed")만 새 팀으로 본다(같은 날 아웃라이트를 거부하고 FA를 선언하는 경우). FA면 `level`·`levelTeam`은 null, 소속 줄은 끝난 것으로 남아 팀 칸에는 마지막 팀이 보인다. 한국 팀 등 MLB 밖 계약은 MLB 기록에 없어서 FA까지만 안다. 저장: `player.free_agent`(V7).
- `GET /api/players/{id}`의 `moves`(최신순): `{ date, typeCode, labelKo, fromTeam, toTeam, toLevel }`
  - `labelKo`: OPT 마이너 강등(옵션), CU·SE 메이저 콜업, OUT 마이너 이관(아웃라이트), DES 지명 할당(DFA), TR 트레이드, SFA·SGN·"Signed" 계약, DFA(Declared Free Agency) FA 선언, REL 방출, CLW 웨이버 클레임, SU 출장 정지, ASG 재활 경기 배정·스프링캠프 초청·팀 배정. 표에 없으면 영어 `typeDesc` 그대로.
  - 선수 상태 변경(SC)은 설명 글에 부상자 명단(등재·해제·이동)이나 출장 정지가 있을 때만 남기고 나머지("roster status changed", 그냥 "activated")는 뺀다. MLB가 같은 날 같은 내용을 두 번 준 줄도 하나만 남긴다.
  - 팀 이름: MLB 구단은 한글 짧은 이름(`미네소타`), 마이너 팀 등은 MLB가 준 영어 이름 그대로(`St. Paul Saints`).

### 선수 사진 호스팅(`GET /api/photos/{이름}`, 표 `photo`)
- 응답의 `photoUrl`은 우리 주소다: `/photos/mlb-{MLB 선수 번호}`, 포스팅 선수는 `/photos/kbo-{slug}`, 선수 페이지 과거 소속팀 사진은 `/photos/career-{KBO 선수 번호}-{시즌}`. 웹이 `/photos/…`를 이 엔드포인트로 넘긴다(api는 바깥에 열려 있지 않다).
- 우리가 만든 편집본은 원본에서 받지 않고 앱에 든 파일(`src/main/resources/photos/`)을 그대로 준다: 포스팅 선배(`posting-…`, 흰 바탕 JPEG)와 과거 소속팀(`career-…`, MLB 배경 없는 사진과 같은 틀의 투명 PNG). 만드는 법은 `tools/posting-photo/README.md`.
- 원본(MLB 사진 서버 `korleaguer.images.player-photo-template`, 포스팅 선수는 `posting.yml`의 `photo-url`)에서 **처음 한 번만** 받아 DB에 저장한다(`PhotoStore`). 앱이 뜰 때와 6시간마다 없는 사진을 미리 받고, 처음 요청된 사진은 그 자리에서 받는다.
- 원본에 마지막으로 물어본 지 7일이 지나면 조건부 요청(If-None-Match·If-Modified-Since)으로 바뀌었는지만 묻는다. 304거나 본문(SHA-256)이 같으면 다시 쓰지 않는다. 원본 주소가 바뀌면 새로 받는다.
- 명단·포스팅에 있는 사진만 받고 준다(아무 주소나 대신 받아 주지 않는다). 사진만(image/*, 2MB까지). 요청 예절은 기사 수집과 같은 `PoliteFetcher`(robots.txt·사이트당 1초·실패하면 쉬기). 단 사진용은 robots.txt가 401·403이면 표준대로 '없음'으로 본다(그림 말고는 다 막는 사진 서버).
- 응답은 `ETag`(SHA-256)와 `Cache-Control: public, max-age=86400`. 같은 ETag로 물으면 304.
- 사진의 권리는 MLB·각 구단에 있다(공식 사진을 끄면 `photoUrl`이 모두 null).

### 이번 겨울 MLB 도전(홈의 `posting`)
- 비시즌 홈에만 붙는다. 포스팅 선수(`players`)와 지금 포스팅 제도(2018~)의 지난 기록(`history`)이다.
- 자료는 사람이 `roster/posting.yml`에 적는다. 협상 시작·마감 시각, 지난 기록의 마감일·합의 보도일·보장 계약은 근거 기사(MLB Trade Rumors 등) 원문으로 확인한 값만 넣는다.
  칸이 빠졌거나 형식이 틀리면 앱이 뜰 때 어느 선수의 어느 칸인지 밝히고 실패한다(`Postings`).
- 선수 상태: 협상 시작 전(`UPCOMING`) → 협상 중(`NEGOTIATING`, 한국 날짜로 마감까지 남은 날 `daysLeft`) → 결과를 적으면 `SIGNED`/`NO_DEAL`.
  마감이 지났는데 결과를 아직 안 적었으면 `CLOSED`(결과 확인 중). 마감 60일 뒤에는 목록에서 빠지니 그 전에 `history`로 옮긴다.
- 지난 기록은 마감 며칠 전에 합의했는지(`daysBeforeDeadline`, 미국 날짜)를 같이 준다.

### 시즌 성적표(홈의 `offseason.report`)
- 비시즌 홈에만 붙는다. 명단 선수마다(국내 리그로 돌아간 선수 포함, 이번 시즌 기록이 없는 선수는 뺀다) 한 장:
  `{playerId, tier, label, headline, reason, level, war, stories, winterMoves, news}`. 순서는 `tier`(`GOOD` → `SHORT_GOOD` → `SOLID` → `MINORS` → `SHORT` → `POOR`), 그 안에서 메이저 경기 많은 순.
- 판정(`SeasonVerdict`)은 메이저 타석(투수는 이닝·선발)과 wRC+(ERA-)·WAR로 한다. 2020년은 기준을 60/162로 줄인다. 지표를 아직 받지 못했으면 경기별 기록의 양만으로 판정한다.
- 지표는 비시즌 따라잡기가 정규시즌이 끝난 뒤 하루 한 번 받는다(`player_season_metric`, 선수마다 `yearByYear` 1번 + `sabermetrics` 이번 시즌과 아직 없는 지난 시즌). 앞 3시즌까지 둔다.
- `headline`·`reason`은 정해진 틀로 만든다(부상자 명단·트리플A 강등·콜업·짧은 메이저 출전 중 두 개까지. 다 뛴 타자는 전후반 타율 차이가 .050 이상이면 그것 하나). `stories`(연속 안타·최고 경기)는 `SHORT`·`POOR`면 늘 비어 있다. `level`은 카드가 앞세울 합계(`MINORS`면 마이너). `winterMoves`는 정규시즌 마지막 날 3일 뒤부터의 이동 기록, 최신순 세 개까지(그 사이 포스트시즌 명단 콜업 등은 `reason` 쪽 시즌 이야기). `news`는 정규시즌이 끝난 뒤 나온 그 선수 기사(지금 수집하는 언론사만, 같은 기사는 한 줄, 늦은 순 12건까지, 이슈 #119로 모은 것).
- 성적표를 만들다 실패하면 그 선수만 빠지고, 전체가 실패하면 빈 목록이다(홈 응답은 그대로).

### MLB 안 위치와 지난 시즌 표(`GET /api/players/{id}`의 `saber`·`seasons`·`news`, 이슈 #133)
- 값은 MLB 공식 Stats API sabermetrics 그대로 쓰고, 누구와 무엇으로 견주나만 우리가 정한다(`ingest.LeaguePositions`). 따라잡기가 선수마다 하루(UTC 날짜) 한 번 받아 계산해 저장한다(`player_league_position`, `player_career_season`, V22).
- `saber`(지금 시즌, 아직 받지 않았거나 그 시즌 MLB 기록이 없으면 null). 위치(`percentiles`)는 값마다 1~99 정수로, 견준 선수 중 이 선수보다 값이 나쁜 비율(반올림)이다. 높을수록 좋다.
  - 타자 `{season, war, poolSize, plateAppearances, wrcPlus, batting, baseRunning, fielding, percentiles: {overall, batting, baseRunning, fielding}}`: 그 시즌 100타석 이상 MLB 타자와 견준다. `overall`은 타석당 WAR, `batting`은 wRC+, `baseRunning`·`fielding`은 타석당 득점(`fielding` = MLB `fielding` + `positional`, 포지션 난도 포함). 50타석 미만이면 `percentiles`는 null.
  - 투수 `{season, war, poolSize, inningsOuts, eraMinus, fipMinus, strikeoutRate, walkRate, percentiles: {overall, runPrevention, strikeouts, walks}}`: 30이닝 이상 MLB 투수와 견준다. `overall`은 아웃당 WAR, `runPrevention`은 ERA-(낮을수록 좋다), `strikeouts`는 K%, `walks`는 BB%(낮을수록 좋다). `strikeoutRate`·`walkRate`는 상대 타자 중 비율(%). 10이닝 미만이면 `percentiles`는 null.
  - 시즌 초에는 견줄 선수(100타석·30이닝 이상)가 아직 없어 위치 칸이 null일 수 있다.
- `seasons`(MLB 정규시즌 기록, 최신 시즌부터, 한 시즌 여러 팀이면 합친 한 줄): 타자 `{season, teamKo, games, plateAppearances, avg, homeRuns, ops, wrcPlus, war}`, 투수 `{season, teamKo, games, inningsOuts, era, strikeouts, eraMinus, war}`. `teamKo`는 뛴 순서로 `"탬파베이·애틀랜타"`. 지금 시즌 지표는 리그 전체 값, 지난 시즌 지표는 선수별 sabermetrics를 한 번 물은 값이다. 모르는 값은 null. KBO 기록은 없다.
- `news`: 그 선수 기사(늦은 순, 같은 기사는 한 줄, 최대 10건). 성적표 `news`와 같은 모양·규칙(지금 수집하는 언론사만, 주인공이 아닌 기사 뺌)이고 기간 제한만 없다.
- `games[].batting`에 타격 흐름 그래프용 `doubles`·`triples`·`hitByPitch`·`sacFlies`·`plateAppearances`·`strikeouts`(MLB가 주지 않았으면 null)가 있다.
- 샘플 모드: 리그 전체는 샘플 선수 줄(`sample/mlb.json`의 `yearByYear`·`sabermetrics`)에 지어낸 선수 240명씩을 더해 만든다(`SampleMlbClient.madeUpLeague`).

### 마이너 경기 줄(`GET /api/players/{id}`의 `minorGames`, 이슈 #145)
- 이번 시즌 마이너 경기를 최신 경기부터 모두 준다(없으면 빈 목록). 웹이 `games`(메이저)와 날짜순으로 섞어 '경기 기록'에 보인다. 마이너에서 잘해야 콜업되니 마이너에 오래 있는 선수도 경기 소식이 보이게 한다(11-13 결정).
- 한 줄: `{gamePk, startTimeKst, level, levelKo, teamId, teamKo, opponentId, opponentKo, home, teamScore, opponentScore, result, summary, batting, pitching}`. `summary`·`batting`·`pitching`은 `games`와 같은 규칙·모양이다(선수 주 역할 기준).
- `teamKo`·`opponentKo`: 트리플A·더블A 60팀은 한국어 도시 이름(`roster/minor-teams.yml`), 그 밖의 팀은 MLB 영어 지명(`franchiseName`, 예: `Quad Cities`), 그것도 모르면 영어 팀 이름. `teamId`·`opponentId`로 웹이 `https://www.mlbstatic.com/team-logos/{id}.svg` 로고를 그린다.
- 시작 시각·점수는 `schedule?gamePks=…&hydrate=team`으로 받아 `minor_game_log`에 둔다(V23). 끝나 점수를 받은 경기는 다시 묻지 않고, 끝나지 않은 경기(경기 중에 받음·중단·응답에 없음)는 공식 날짜 7일까지만 다시 묻는다. 홈·원정을 가릴 수 없으면 점수는 비운다. 아직 받지 못했으면 시작 시각은 공식 날짜 다음 날 08:00(한국)으로 어림하고 점수는 null, `result`는 경기별 기록의 승패다(경기 중·지연·중단이면 null).
- 샘플 모드: 지어낸 마이너 경기에 지어낸 일정(미국 저녁 7시 5분 시작, 승패에 맞춘 점수, 상대는 실제 트리플A 팀 번호)을 붙인다(`SampleMlbClient.madeUpMinorGame`).

### 관련 기사(`news_article`)
- 포스팅 선수·명단 선수 이름(한글, 명단 선수는 MLB 영어 이름도)이 제목에 든 기사의 제목·링크·언론사·발행 시각과 기사 첫머리(200자 안팎)·대표 사진 원본 주소·Claude 한국어 요약(영어 기사는 한국어 제목도)을 모은다. 본문은 요약에만 쓰고 저장하지 않는다. 사진 파일은 받지 않는다(브라우저가 언론사 서버에서 받는다).
- Claude 요약(`ClaudeSummarizer`)은 3~4문장이다. 긴 기사만 한 문장 더 쓴다.
  - 사용자 의견(11-12) "쓸 내용이 많은데 지나치게 줄이지 말고, 내용이 없는데 억지로 늘리지도 말 것"(이슈 #103)으로 3~7문장까지 늘렸다가, "2문장은 너무 짧다고 그런 것만 좀 늘려달란 거였는데 전체적인 길이가 너무 길어졌어"(이슈 #111)로 줄였다.
  - 코드가 본문 길이로 문장 수를 정해 요청에 넣는다(`maxSentences`, `요약 분량: N문장`). 본문 450자마다 1문장이라 1,350자까지 3문장, 넘으면 4문장이다. 영어 본문은 같은 내용도 길어 2.5로 나눠 센다.
  - 지시문은 그 수에 맞추라고 한다(많이도 적게도 쓰지 않는다). "N문장 이내, 쓸 사실이 떨어지면 멈춘다"로 두면 3문장 상한에서 2문장으로 멈추는 일이 있었다(시험 30건 중 2건).
  - 모델이 그 수를 넘기면(시험 30건 중 6건, 4문장에 6문장까지) 넘긴 문장은 코드가 버린다(`tidy`, `LENGTH_SLACK` 0). 3~7문장일 때는 1문장 여유를 뒀다(딱 맞춰 자르면 뒤쪽 계약 금액이 자주 빠졌다).
  - 자르다 기사가 다루는 선수 이야기가 모두 빠지면, 버린 쪽에서 그 선수가 나오는 첫 문장을 마지막 자리에 넣는다(다른 선수가 주인공인 기사도 이 선수의 기사 페이지에 뜨기 때문이다).
  - 중요한 사실부터 쓰게 했으므로 버리는 것은 뒤쪽이다. 본문은 길지만 내용이 적은 기사는 상한까지 채워질 수 있어, 배포 뒤 로그(본문 글자 수 대 요약 줄 수)로 본다.
  - 요청에 기사 날짜(한국 날짜)를 함께 준다. 날짜를 모르면 모델이 '올해'·'last February'를 학습 시점의 해로 옮겼다('2025년', '지난해 2월').
  - 날짜를 줘도 영어 'last February'(11월 기사)를 '지난해 2월'로 옮기는 일이 잦아(같은 요청 30번 중 7번, 이슈 #105), 영어 본문의 'last <달>'·'this past <달>'은 코드가 기사 날짜 기준 가장 가까운 지난 그 달로 바꿔 보낸다(`absoluteMonths`, 11월 기사면 `February 2026`, `November 2025`). 바꾼 뒤 같은 요청 20번에서 틀린 답은 0번이었다.
  - 숫자는 원문 값 그대로 옮긴다. 계약 금액은 총액·연봉·연평균을 구분한다('연봉'·'연평균'·'AAV' 같은 말이 없으면 총액). 금액 예시는 가상 금액이다(실제 선수 금액이 다른 기사 요약에 섞이지 않게).
  - 기사가 다루는 선수 이야기가 기사에 있으면 꼭 한 문장 넣는다. 다른 선수가 주인공인 기사도 이 선수의 기사 페이지에 뜨기 때문이다.
  - 요약 대신 '요약 분량' 지시를 옮겨 쓴 답(시험 319번 중 1번)은 버리고 다음 차례에 다시 요약한다.
  - 3~4문장 시험(운영 기사 30건, 11-12, 이슈 #111): 모두 3~4문장(3문장 21건, 4문장 9건), 2문장 요약 0건. 글자 중간값 230자(예전 v1 209자, 3~7문장 v3 263자), 짧은 기사(900자 미만) 233자(v1 205자), 긴 기사(1,800자 이상) 278자(v1 191자, v3 395자), 가장 긴 것 359자. 영어 기사 1번 5회 중 3회에 계약 금액이 남았다.
  - 그 전(3~7문장) 기록: 운영 기사 30건으로 지시문을 고쳐 가며 시험했다. 모델에게 분량 판단만 맡기면 기사 길이와 상관없이 5~9문장을 써서 짧은 기사까지 부풀렸다. 마지막 안의 결과:
    - 본문 길이와 요약 글자 수의 순위 상관: 0.04 → 0.75
    - 긴 기사(1,800자 이상) 요약 글자 중간값: 191자 → 371자
    - 짧은 기사(900자 미만) 요약 글자 중간값: 205자 → 177자
    - 요약은 3~8문장, 글자 중간값 257자(가장 긴 것 503자)
    - 원문에 없는 숫자 없음, 선수 이야기가 빠진 요약 없음
    - 영어 기사 1번을 10번 돌려 계약 금액은 8번 요약에 남았고 모두 총액으로 맞게 옮겼다. '오후 4시'를 '오전 4시'로 쓴 것(10번 중 1번) 같은 작은 실수는 남는다.
    - 'last February'를 '작년 2월'로 옮긴 답이 10번 중 1번 있었는데 처음에 '지난해'만 세어 0번으로 잘못 적었다. 배포 뒤 운영 1번 요약에서 드러나 위의 연도 붙이기로 고쳤다(이슈 #105).
  - 배포 뒤 운영 요약 32건(11-12, v2): 본문 길이와 요약 글자 수의 순위 상관 0.04 → 0.85, 짧은 기사(900자 미만 7건) 글자 중간값 205 → 200자, 긴 기사(1,800자 이상 5건) 191 → 417자, 3~8문장, 선수 이야기가 빠진 요약 없음.
  - 요약마다 모델과 지시문 판을 적는다(`summary_model`, 예 `claude-haiku-5-5/v4`). 지시문·요청 틀·본문 손질을 바꾸면 `ClaudeSummarizer.PROMPT_VERSION`을 올리고, 테스트의 지문도 고친다.
  - 옛 판 요약은 새 요약을 받을 때까지 그대로 보이고, 30분마다 새 기사 다음 차례로 다시 요약된다(한 번에 10건, 최근 45일 기사). 모델을 바꿔도(`KORLEAGUER_NEWS_SUMMARY_MODEL`) 같이 다시 요약된다.
- 행선지로 거론된 구단(이슈 #115): 요약이 붙은 기사마다 같은 Claude 모델이 요약·제목을 읽고 '이 선수의 행선지로 거론된 MLB 구단'과 방향(거론 `LINKED` / '필요 없다'는 전망 `DOUBTED`)을 고른다(`NewsTagger`, `ClaudeDestinationTagger`, 표 `news_destination`). 키워드로 세면 틀려서('워싱턴 코치'는 사람, 애틀랜타는 김하성 이야기) 글을 읽고 고른다.
  - 30분마다 요약 다음에 최근 기사부터 40건씩 한다(최근 30일). 요약을 포기한 기사는 제목·앞부분으로 고른다. 답을 읽을 수 없으면 3번까지만 다시 한다.
  - 같은 호출이 첫 줄에 '이 선수가 주인공인 기사인지'(주인공: 예/아니오)도 적는다(이슈 #129). 다른 이야기에 이름만 곁들여 나온 기사('노시환→미국행…KBO 최고 3루수 자리' 등)는 `off_topic`으로 적어 홈 기사 목록·거론 수·기사 페이지 다른 기사·성적표 이번 겨울 기사에서 뺀다. 판단 전(null)이면 보인다.
  - 지시문을 바꾸면 `ClaudeDestinationTagger.PROMPT_VERSION`을 올린다. 기사마다 적은 판(`destinations_model`)과 다르면 다시 고른다.
  - 홈은 포스팅 선수마다 최근 7일(한국 날짜, 오늘 포함) 구단별 거론 수·반대 전망 수·오늘 거론 여부를 준다(`linkedTeams`) 같은 글을 여러 언론사가 실은 기사(`SameArticles` 묶음)는 묶음·구단마다 한 번만 세고, 묶음 안에서 방향이 갈리면 많은 쪽(같으면 거론)으로 센다(이슈 #125).
- 홈(`GET /api/home`)은 선수마다 최근 기사 12건(제목과 기사 번호 `id`)을, 기사 페이지(`GET /api/news/{id}`)는 한 건의 사진·요약·원문 주소와 같은 선수의 다른 기사 5건을 준다. `sources.yml`에서 뺀 언론사의 기사는 둘 다 내지 않는다.
  - 언론사만 다른 같은 기사(전재)는 한 줄로 묶어 서로 다른 기사 12건을 준다(`SameArticles`, 이슈 #113). 발행 시각이 이틀 안이고, 제목 글자(한글·영문·숫자)가 같거나 기사 첫머리(맨 앞 통신사·기자 표시를 뗀 것)의 3글자 조각이 45% 넘게 겹치면 같은 기사다. 이틀 넘게 떨어지면 묶지 않는다(연재 제목처럼 다른 날 같은 제목으로 나온 새 기사가 옛 기사 뒤에 숨지 않게). 대표는 요약이 있는 기사 가운데 가장 먼저 낸 것(대개 원문을 쓴 언론사)이고, 같은 글을 실은 다른 언론사는 `alsoBy`로 준다. 기사 페이지 '다른 기사'에는 지금 보는 기사와 같은 글을 넣지 않는다. 홈에 요약 기사를 끼워 넣을 때도 최근 기사와 함께 묶어, 그 사본이 따로 한 줄로 남지 않는다.
  - 운영 기사 37건(11-12)으로 정했다: 31건으로 묶인다. 스포츠조선·조선일보 두 쌍, 뉴시스·동아일보, 매일경제·MK스포츠(제목이 같다)와 연합뉴스·노컷뉴스·SBS(제목만 바꿔 실었다, 첫머리 겹침 0.48)가 묶이고, 같은 소식을 각자 쓴 연합뉴스·뉴시스(0.39)는 묶이지 않는다. 묶인 쌍은 모두 같은 날 2시간 안에 나왔다.
- 출처는 `news/sources.yml`(넣은 곳과 뺀 곳의 근거는 그 파일 머리말). robots.txt가 우리 수집기(`korleaguer`)를 허용하고 피드(RSS·Atom·뉴스 사이트맵)가 있는 곳만 넣었다. 네이버·구글 뉴스는 robots.txt가 막아 넣지 않았다.
- 요청 예절(`PoliteFetcher`):
  - 사이트마다 robots.txt를 하루 한 번 받아 지킨다(RFC 9309). 우리 이름(`korleaguer`)이 막혀 있으면 받지 않는다. 영어 출처(`preview: ai`)는 Claude 계열 이름도 본다.
    robots.txt가 없으면(404) 다 허용, 서버 오류·연결 실패면 다 막힘으로 보고 나중에 다시 묻는다.
  - 같은 사이트 요청 사이 1초 이상(crawl-delay가 더 길면 그만큼), 조건부 요청(ETag·Last-Modified), gzip, 본문 2MB까지.
  - 429·503은 Retry-After만큼, 401·403·404·410·451은 하루, 그 밖의 실패는 30분부터 두 배씩(최대 하루) 그 사이트를 쉰다.
  - 큰 피드는 출처마다 간격(`interval`)을 둔다.
- 기사 본문 읽기(`ArticlePage`): WordPress 기사 칸, 국내 언론사 본문 칸, article 칸의 긴 문단, 줄바꿈으로 쓴 긴 줄 순으로 찾는다. 그래도 없고 알려진 본문 칸도 없으면 모든 article 칸, 그다음 페이지 전체(스크립트·메뉴·꼬리 칸 뺀)의 긴 문단을 저작권 안내 줄까지 읽는다(연합뉴스: 본문이 첫 article 칸 밖, 이슈 #109). Arc XP 사이트(조선일보)는 화면을 자바스크립트로 그려 HTML에 문단이 없으므로 페이지 속 본문 JSON(`Fusion.globalContent`)의 글 요소를 읽는다(이슈 #107).
  - 본문이 200자 안 되면 요약하지 않고 시도로 센다(3번이면 포기). 그때 로그에 페이지 모양(크기, 문단 수와 그 가운데 article 칸 안의 긴 문단 수, 첫 article 칸, 찾은 본문 칸, 첫 긴 문단의 순서와 담은 칸, 처음 멈춤 줄의 순서, 본문 JSON 여부)을 남긴다. 기사 내용은 남기지 않는다(태그는 이름과 id·class·itemprop만).
  - 연합뉴스는 robots.txt가 우리 수집기는 허용하지만 Claude 계열(ClaudeBot·Claude-Web·anthropic-ai)을 막아, 개발 중 조사로는 페이지를 열지 않는다. 본문을 못 찾으면 이 로그로 원인을 본다.
- 같은 선수에 같은 주소(추적 매개변수를 뺀 주소)는 한 번만 저장한다. 언론사가 내려 달라고 하면 `sources.yml`에서 빼고 그 언론사 줄을 지운다(`delete from news_article where publisher = '...'`).

### 결장 판단
- 경기가 끝났고(final) 그 선수 기록이 없으면 "결장"이다.
- 정규시즌·포스트시즌 경기 중, 선수가 그 팀에서 처음 기록을 남긴 경기부터 마지막 기록을 남긴 경기 사이만 본다.
  - 지금 소속 팀이 마지막 기록의 팀이면 마지막 출전 뒤 경기도 결장으로 본다. 단 마이너에 있거나 소속이 끝난(FA 등) 선수는 마지막 출전에서 끝낸다(그 구단 경기는 결장으로도 마지막 경기일로도 보지 않고, 경기 갱신에서도 뺀다).
  - 이적 전 팀의 이적 뒤 경기, 첫 출전 전 경기는 줄을 만들지 않는다(시범경기는 저장하지 않는다).
- 그 선수의 시즌을 아직 받지 않았으면 기록이 하나도 없으므로 결장 줄도 만들지 않는다.

## 배포

develop에 병합되면 Actions(`.github/workflows/deploy.yml`)가 테스트와 이미지(`Dockerfile`, GHCR `ghcr.io/korleaguer/korleaguer-server:sha-<커밋>`)를 동시에 만들고 →
SSH로 서버(pigeon.ddns.net)에 접속해 새 컨테이너로 바꾸기(끊김 없이, 새 것이 정상이 안 되면 옛 것 유지)까지 알아서 한다(지속 배포, 버전 번호 없이 커밋 해시로 구분). 서버 구성(Caddy·web·api·MySQL),
처음 준비, 되돌리기, 백업은 [`deploy/README.md`](deploy/README.md).
PR에서 테스트를 통과한 코드와 병합한 코드가 똑같으면 테스트를 다시 돌리지 않는다. 배포 차례가 왔을 때 develop에 더 새 배포 대상 커밋이 있으면 그 커밋이 함께 내보내므로 건너뛴다(최신만 배포).

```bash
docker build -t korleaguer-server:local .   # 이미지만 만들어 보기
```

## 구조

```
com.korleaguer
├── config   설정(korleaguer.*), 데이터 출처, 클라이언트 선택, CORS, 캐시, 뒤에서 도는 수집용 실행기
├── client   MLB 공식 API 호출(HttpMlbClient)과 샘플(SampleMlbClient), 응답 record
├── ingest   선수 정보·이동 기록(PlayerRefresher), 시즌 백필·경기 갱신(GameIngest), 진입점·잠금·기록(IngestService),
│            스케줄(IngestScheduler), 앱 시작 때 따라잡기(StartupIngest), 선수 페이지에서 시즌 받기(OnDemandSeason), DB 저장(IngestWriter)
├── domain   JPA 엔티티와 리포지토리
├── api      컨트롤러, 응답 DTO, 조회 서비스, 시즌 성적 계산, 비시즌·포스팅 묶음(OffSeasonFinder·PostingFinder)
├── posting  포스팅 자료(roster/posting.yml) 읽기와 검사(Postings)
├── photo    선수 사진 호스팅: 원본 목록(PhotoSources), 한 번 받아 저장·재확인(PhotoStore)·주기 작업
├── news     관련 기사 수집: 피드 읽기(FeedParser), robots.txt(RobotsTxt), 요청 예절(PoliteFetcher), 수집(NewsCollector)·기사 페이지 읽기(ArticlePage)·요약(NewsEnricher, ClaudeSummarizer)·행선지 구단 고르기(NewsTagger, ClaudeDestinationTagger)·스케줄
└── support  한국 시간, 야구 계산(타율·ERA·이닝), 팀 한글 이름·MLB 팀 번호, 경기 상태 값, 포지션·키·몸무게·투타·생년월일 변환, 한 줄 기록 문장, 이동 기록 이름, 요청 제한
```

- DB 스키마: `src/main/resources/db/migration/V1__init.sql`(MySQL 8 문법, H2 MySQL 모드에서도 그대로 돈다)과 `V7__player_free_agent.sql`(FA, V2~V6은 V1에 합쳐 없다). 시각은 `datetime(6)`에 UTC로 저장하고(`hibernate.jdbc.time_zone=UTC`, Instant는 TIMESTAMP로) 응답에서만 한국 시간으로 바꾼다. 외래 키는 표 단위로 적는다(MySQL은 열 옆의 `references`를 무시한다). 소속(`affiliation`)은 선수마다 한 줄(`unique (player_id)`).
- 운영 DB 연결: `SPRING_DATASOURCE_URL`(예: `jdbc:mysql://mysql:3306/korleaguer?connectionTimeZone=UTC`)·`SPRING_DATASOURCE_USERNAME`·`SPRING_DATASOURCE_PASSWORD` 환경변수로 덮어쓴다.
- 수집: 받은 뒤 저장만 트랜잭션으로 묶는다(시즌 백필은 일정·gameLog·"받았음" 표시를 한 번에). 값이 이상한 경기·기록은 그 건만 건너뛰고 수집 메시지에 개수를 남긴다.
- 캐시: 홈·선수·내 선수 응답을 메모리(Caffeine)에 두고, 보이는 것이 실제로 바뀔 때만 비운다(`ResponseCaches`, 나중에 Redis로 바꿀 자리).
  - 데이터: 수집이나 기사 수집이 DB에 실제로 썼을 때. Hibernate가 보내는 INSERT·UPDATE·DELETE를 센다(`DataChanges`). Hibernate는 값이 바뀐 줄만 UPDATE하므로 같은 일정·기록을 다시 받으면 세지 않는다. 수집 기록(`ingest_log`)·사진(`photo`)은 응답에 나오지 않아 세지 않는다. 수집은 끝날 때 바로, 그 밖의 쓰기(기사)는 다음 따라잡기(12분 안) 때 본다. 기사(`news_article`)는 홈·선수 페이지·기사 페이지에만 나오고 기사 페이지는 캐시하지 않으므로 홈·선수 캐시만 비운다.
  - 시각: 한국·미국 날짜가 바뀌면(D-day, 최근 N일, 'N일째', 나이) 모두, 포스팅 상태·보이는지·진행 막대(1% 단위)만 바뀌면 홈만. 따라잡기 때마다 본다.
  - 비우면 `CacheWarmer`가 홈, 선수 페이지 전부, 내 선수 카드(명단 전원 × 7·14·30일), 비시즌 홈의 선수 카드(`/api/my` 명단 전원·7일)를 뒤에서 다시 계산해 넣는다(자동 수집이 켜졌을 때). 따라잡기(12분마다, 앱 시작 때) 끝에도 빠진 것을 채운다. 운영 장비에서는 응답 하나를 처음 만드는 데 1~2초 걸려서, 사람이 처음 부를 때 기다리지 않게 하려는 것이다.
  - 사람마다 다른 내 선수 조합은 미리 채운 카드를 모아 바로 만들고, 데이터나 날짜가 바뀔 때까지 둔다. 비시즌에는 거의 하루, 시즌 중에는 다음 경기 갱신까지 남는다.
  - 만료(1일)는 안전망이다. 날짜가 매일 바뀌므로 그 전에 비워진다.
- H2 콘솔: 기본은 꺼져 있다. `local` 프로필로 띄우면 `http://localhost:8080/h2-console`이 열린다.
  ```bash
  SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
  ```
  JDBC URL은 `application.properties`의 `spring.datasource.url`, 사용자 `sa`, 비밀번호 없음.
