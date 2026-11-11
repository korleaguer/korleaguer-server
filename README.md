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
| `KORLEAGUER_NEWS_ENABLED` | 포스팅 선수 관련 기사 제목 수집(30분마다). 기본 `true`. 포스팅 협상 기간(시작 3일 전 ~ 마감 3일 뒤)이 아니면 켜 있어도 요청하지 않는다. `false`여도 홈은 DB에 있는 기사를 보여 준다 |
| `KORLEAGUER_NEWS_USER_AGENT` | 기사 피드를 받을 때 밝히는 이름. 기본 `korleaguer/0.1 (news headlines only; personal, non-commercial)` |
| `KORLEAGUER_PHOTOS_ENABLED` | 선수 사진 미리 받기(앱이 뜰 때와 6시간마다). 기본 `true`. 꺼도 사진은 처음 요청될 때 받아 저장한다 |
| `KORLEAGUER_SEASON` | 시즌을 이 연도로 고정한다(테스트·수동 고정용). 샘플 모드는 샘플 데이터의 시즌(2026)으로 고정된다. 기본은 비움 = 자동: 미국 날짜로 올해 정규시즌 개막일이 지났으면 올해, 아니면 작년. 개막일을 아직 못 받았으면 3월 20일로 본다. 앱을 다시 띄우지 않아도 개막일에 새 시즌으로 넘어간다 |

> **MLB 이용 고지:** 응답에 붙는 MLB 저작권 안내(`gdx.mlb.com/components/copyright.txt`)는 "개인적·비상업적·대량이 아닌 사용(individual, non-commercial, non-bulk use)"만 허락한다.
> 이 사이트는 개인적·비상업적으로 쓴다(사용자 결정, 2026-11-10). 대량 수집이 되지 않도록 요청은 1초에 1회 이하, 필요한 것만 받고, 받은 것은 DB에 두고 다시 받지 않는다.
> 상업적으로 쓰거나 크게 공개할 생각이 생기면 그 전에 MLB의 허락을 받는다. MLB 공식 사진·로고도 권리가 MLB에 있다(`KORLEAGUER_OFFICIAL_PHOTOS=false`로 끌 수 있다).

## 받는 전략(MLB 공식 Stats API)

화면용 API(`/api/**`)는 DB만 읽는다. MLB는 수집기(`ingest`)만 부른다. 같은 화면을 몇 번 열어도 MLB 요청은 늘지 않는다.

- **요청 예절:** 1초에 1회 이하, `User-Agent: korleaguer/0.1`, 연결 5초·읽기 10초 제한, 다시 시도하지 않는다(실패한 건은 다음 수집 때 다시). 403/429가 오면 그 실행의 남은 단계는 건너뛴다(`BLOCKED`).
- **시즌 날짜(개막 전까지 하루 한 번):** `seasons/{year}?sportId=1` 올해(미국 날짜) 1번. 지금 시즌은 올해 정규시즌 개막일만으로 정해지므로(`season_dates`), 1월 1일부터 올해 개막일 전날까지만 묻고 개막한 뒤에는 묻지 않는다(1년에 80여 번). 발표 전인 해는 저장하지 않고, 시즌을 고정했으면(`KORLEAGUER_SEASON`, 샘플 모드) 묻지 않는다.
- **선수 정보·이동 기록(하루 한 번):** `people?personIds=…&hydrate=currentTeam` 1번(명단 전원), `transactions?playerId=…` 선수마다 1번, 마이너 경기별 기록 `people/{id}/stats?stats=gameLog&group=hitting,pitching&leagueListId=milb_all` 선수마다 1번(그 시즌 전체를 받아 `minor_game_log`를 통째로 바꾸고, 마이너 경기 수도 여기서 센다), 마이너 팀 단계 `teams/{id}`(한 번 물으면 기억).
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
| `GET /api/home` | 최근 경기 한 줄 기록 + 시즌 성적(주로 메이저인 선수 먼저, 그 안에서 순위 기준을 채운 선수 먼저 타자 타율순, 투수 평균자책점순) + 순위 기준(`ranking`) |
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
- 응답은 선수 조합(`ids` 글자 그대로)과 `days`마다 캐시한다. 데이터는 수집 때만 바뀌고 그때 비운다. 비시즌 홈이 부르는 명단 전원·7일 응답은 따라잡기 뒤에 미리 채워 둔다(아래 "캐시").
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
- 응답의 `photoUrl`은 우리 주소다: `/photos/mlb-{MLB 선수 번호}`, 포스팅 선수는 `/photos/kbo-{slug}`. 웹이 `/photos/…`를 이 엔드포인트로 넘긴다(api는 바깥에 열려 있지 않다).
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

### 관련 기사 제목(`news_article`)
- 포스팅 선수 이름이 제목에 든 기사의 **제목·링크·언론사·발행 시각만** 모은다. 본문·사진·요약은 저장하지 않고, 화면은 원문(언론사)으로 연결한다.
- 출처는 `news/sources.yml`(지금: MLB Trade Rumors 노시환·KBO 태그 피드, 마이데일리 해외야구·야구, 뉴시스 스포츠). robots.txt가 허용하고 RSS가 있는 곳만 넣었다.
  네이버·구글 뉴스(robots.txt가 모든 수집기를 막음), OSEN·일간스포츠(저작권 규약이 허가 없는 정보서비스·DB 이용을 금지), 스타뉴스(무단 이용 단속 공지)는 넣지 않았다.
- 요청 예절(`PoliteFetcher`):
  - 사이트마다 robots.txt를 하루 한 번 받아 지킨다(RFC 9309). 우리 이름(`korleaguer`)과 Claude 계열 이름 중 하나라도 막혀 있으면 받지 않는다.
    robots.txt가 없으면(404) 다 허용, 서버 오류·연결 실패면 다 막힘으로 보고 나중에 다시 묻는다.
  - 같은 사이트 요청 사이 1초 이상(crawl-delay가 더 길면 그만큼), 조건부 요청(ETag·Last-Modified), gzip, 본문 2MB까지.
  - 429·503은 Retry-After만큼, 401·403·404·410·451은 하루, 그 밖의 실패는 30분부터 두 배씩(최대 하루) 그 사이트를 쉰다.
  - 큰 피드는 출처마다 간격(`interval`)을 둔다(마이데일리 2시간, 뉴시스 1시간).
- 같은 선수에 같은 주소(추적 매개변수를 뺀 주소)는 한 번만 저장한다. 언론사가 내려 달라고 하면 `sources.yml`에서 빼고 그 언론사 줄을 지운다(`delete from news_article where publisher = '...'`).

### 결장 판단
- 경기가 끝났고(final) 그 선수 기록이 없으면 "결장"이다.
- 정규시즌·포스트시즌 경기 중, 선수가 그 팀에서 처음 기록을 남긴 경기부터 마지막 기록을 남긴 경기 사이만 본다.
  - 지금 소속 팀이 마지막 기록의 팀이면 마지막 출전 뒤 경기도 결장으로 본다. 단 마이너에 있거나 소속이 끝난(FA 등) 선수는 마지막 출전에서 끝낸다(그 구단 경기는 결장으로도 마지막 경기일로도 보지 않고, 경기 갱신에서도 뺀다).
  - 이적 전 팀의 이적 뒤 경기, 첫 출전 전 경기는 줄을 만들지 않는다(시범경기는 저장하지 않는다).
- 그 선수의 시즌을 아직 받지 않았으면 기록이 하나도 없으므로 결장 줄도 만들지 않는다.

## 배포

develop에 병합되면 Actions(`.github/workflows/deploy.yml`)가 테스트 → 이미지(`Dockerfile`, GHCR `ghcr.io/korleaguer/korleaguer-server:sha-<커밋>`) →
SSH로 서버(pigeon.ddns.net)에 접속해 새 컨테이너로 바꾸기(끊김 없이, 새 것이 정상이 안 되면 옛 것 유지)까지 알아서 한다(지속 배포, 버전 번호 없이 커밋 해시로 구분). 서버 구성(Caddy·web·api·MySQL),
처음 준비, 되돌리기, 백업은 [`deploy/README.md`](deploy/README.md).

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
├── news     관련 기사 제목 수집: 피드 읽기(FeedParser), robots.txt(RobotsTxt), 요청 예절(PoliteFetcher), 수집(NewsCollector)·스케줄
└── support  한국 시간, 야구 계산(타율·ERA·이닝), 팀 한글 이름·MLB 팀 번호, 경기 상태 값, 포지션·키·몸무게·투타·생년월일 변환, 한 줄 기록 문장, 이동 기록 이름, 요청 제한
```

- DB 스키마: `src/main/resources/db/migration/V1__init.sql`(MySQL 8 문법, H2 MySQL 모드에서도 그대로 돈다)과 `V7__player_free_agent.sql`(FA, V2~V6은 V1에 합쳐 없다). 시각은 `datetime(6)`에 UTC로 저장하고(`hibernate.jdbc.time_zone=UTC`, Instant는 TIMESTAMP로) 응답에서만 한국 시간으로 바꾼다. 외래 키는 표 단위로 적는다(MySQL은 열 옆의 `references`를 무시한다). 소속(`affiliation`)은 선수마다 한 줄(`unique (player_id)`).
- 운영 DB 연결: `SPRING_DATASOURCE_URL`(예: `jdbc:mysql://mysql:3306/korleaguer?connectionTimeZone=UTC`)·`SPRING_DATASOURCE_USERNAME`·`SPRING_DATASOURCE_PASSWORD` 환경변수로 덮어쓴다.
- 수집: 받은 뒤 저장만 트랜잭션으로 묶는다(시즌 백필은 일정·gameLog·"받았음" 표시를 한 번에). 값이 이상한 경기·기록은 그 건만 건너뛰고 수집 메시지에 개수를 남긴다.
- 캐시: 홈·선수·내 선수 응답을 메모리(Caffeine)에 15분까지 두고, 수집이 끝나면 비운다(나중에 Redis로 바꿀 자리).
  - 따라잡기(12분마다, 앱 시작 때)가 끝나면 `CacheWarmer`가 홈, 선수 페이지 전부, 비시즌 홈의 선수 카드(`/api/my` 명단 전원·7일)를 미리 계산해 넣는다. 운영 장비에서는 응답 하나를 처음 만드는 데 1~2초 걸려서, 사람이 처음 부를 때 기다리지 않게 하려는 것이다.
  - 15분은 따라잡기 한 번 사이(12분)를 넘기려는 값이다. 데이터는 수집 때만 바뀌므로 그 사이 응답은 같다.
- H2 콘솔: 기본은 꺼져 있다. `local` 프로필로 띄우면 `http://localhost:8080/h2-console`이 열린다.
  ```bash
  SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
  ```
  JDBC URL은 `application.properties`의 `spring.datasource.url`, 사용자 `sa`, 비밀번호 없음.
