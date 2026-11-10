package com.korleaguer.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 샘플 모드로 앱을 띄우면(시작할 때 자동 수집) API가 샘플 데이터를 돌려주는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    private static final String PHOTO_PREFIX = "https://img.mlbstatic.com/mlb-photos/image/upload/"
            + "d_people:generic:headshot:silo:current.png/w_360,q_auto:best,f_auto/v1/people/";
    private static final String PHOTO_SUFFIX = "/headshot/silo/current";
    private static final String JUNG_HOO_PHOTO = PHOTO_PREFIX + "808982" + PHOTO_SUFFIX;

    @Autowired
    private MockMvc mockMvc;

    // 지금을 2026-11-01로 고정: 최근 24시간 경기가 없어 마지막 경기일(9월 27일)로 대신한다.
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void homeIsNotEmptyAfterSampleIngest() throws Exception {
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("sample"))
                .andExpect(jsonPath("$.statsAvailable").value(true))
                .andExpect(jsonPath("$.season").value(2026))
                .andExpect(jsonPath("$.generatedAt").value("2026-11-01T11:00:00Z"))
                .andExpect(jsonPath("$.recent.isFallback").value(true))
                .andExpect(jsonPath("$.recent.dateKst").value("2026-09-27"))
                .andExpect(jsonPath("$.recent.lines", hasSize(6)))
                .andExpect(jsonPath("$.recent.lines[0].nameKo").value("이정후"))
                .andExpect(jsonPath("$.recent.lines[0].startTimeKst").value("2026-09-27T05:05:00+09:00"))
                .andExpect(jsonPath("$.recent.lines[0].teamKo").value("샌프란시스코"))
                .andExpect(jsonPath("$.recent.lines[0].teamAbbr").value("SF"))
                .andExpect(jsonPath("$.recent.lines[0].opponentKo").value("콜로라도"))
                .andExpect(jsonPath("$.recent.lines[0].opponentAbbr").value("COL"))
                .andExpect(jsonPath("$.recent.lines[0].photoUrl").value(JUNG_HOO_PHOTO))
                .andExpect(jsonPath("$.recent.lines[?(@.nameKo == '고우석')].photoUrl")
                        .value(contains(PHOTO_PREFIX + "808970" + PHOTO_SUFFIX)))
                .andExpect(jsonPath("$.recent.lines[0].result").value("W"))
                .andExpect(jsonPath("$.recent.lines[0].summary").value("4타수 2안타(2루타 1) 1홈런 2타점 1볼넷"))
                // 같은 경기 숫자(그래프용)
                .andExpect(jsonPath("$.recent.lines[0].batting.atBats").value(4))
                .andExpect(jsonPath("$.recent.lines[0].batting.hits").value(2))
                .andExpect(jsonPath("$.recent.lines[0].batting.homeRuns").value(1))
                .andExpect(jsonPath("$.recent.lines[0].pitching").doesNotExist())
                .andExpect(jsonPath("$.recent.lines[0].lineup").value("STARTER"))
                .andExpect(jsonPath("$.recent.lines[?(@.nameKo == '고우석')].lineup").value(contains("SUB")))
                .andExpect(jsonPath("$.recent.lines[?(@.nameKo == '김혜성')].lineup").value(contains(nullValue())))
                .andExpect(jsonPath("$.recent.lines[?(@.nameKo == '김혜성')].batting").value(contains(nullValue())))
                .andExpect(jsonPath("$.recent.lines[?(@.nameKo == '김혜성')].played").value(contains(false)))
                .andExpect(jsonPath("$.recent.lines[?(@.nameKo == '김혜성')].summary").value(contains("결장")))
                .andExpect(jsonPath("$.recent.lines[?(@.nameKo == '고우석')].summary")
                        .value(contains("⅔이닝 2피안타 2실점(2자책) 1볼넷 · 패")))
                .andExpect(jsonPath("$.recent.lines[?(@.nameKo == '고우석')].pitching.outs").value(contains(2)))
                .andExpect(jsonPath("$.recent.lines[?(@.nameKo == '고우석')].pitching.loss").value(contains(true)))
                // 3경기 × 0.5 = 2타석이지만 최소 10타석, 아웃은 최소 15(5이닝)
                .andExpect(jsonPath("$.ranking.teamGames").value(3))
                .andExpect(jsonPath("$.ranking.minPlateAppearances").value(10))
                .andExpect(jsonPath("$.ranking.minOuts").value(15))
                .andExpect(jsonPath("$.ranking.minInningsText").value("5"))
                .andExpect(jsonPath("$.hitters", hasSize(5)))
                // 순위 기준(10타석)을 채운 선수 먼저 타율 순, 못 채운 선수는 그 뒤에 타율 순.
                // 김혜성(8타석 .429)은 타율이 가장 높아도 기준을 채운 이정후(14타석 .417) 뒤로 간다.
                .andExpect(jsonPath("$.hitters[*].nameKo").value(contains("이정후", "송성문", "김하성", "김혜성", "배지환")))
                .andExpect(jsonPath("$.hitters[0].plateAppearances").value(14))
                .andExpect(jsonPath("$.hitters[3].plateAppearances").value(8))
                .andExpect(jsonPath("$.hitters[3].avg").value(0.429))
                .andExpect(jsonPath("$.hitters[3].teamAbbr").value("LAD"))
                .andExpect(jsonPath("$.hitters[3].photoUrl").value(PHOTO_PREFIX + "808975" + PHOTO_SUFFIX))
                // 마이너 경기 수(샘플은 지어낸 값): 김혜성 2 < 메이저 경기라 주로 메이저, 배지환은 주로 마이너
                .andExpect(jsonPath("$.hitters[3].minorGames").value(2))
                .andExpect(jsonPath("$.hitters[3].mainlyMinors").value(false))
                .andExpect(jsonPath("$.hitters[4].minorGames").value(20))
                .andExpect(jsonPath("$.hitters[4].mainlyMinors").value(true))
                .andExpect(jsonPath("$.pitchers", hasSize(1)))
                .andExpect(jsonPath("$.pitchers[0].nameKo").value("고우석"))
                .andExpect(jsonPath("$.pitchers[0].teamAbbr").value("MIN"))
                .andExpect(jsonPath("$.pitchers[0].photoUrl").value(PHOTO_PREFIX + "808970" + PHOTO_SUFFIX))
                .andExpect(jsonPath("$.pitchers[0].outs").value(5))
                .andExpect(jsonPath("$.pitchers[0].inningsText").value("1⅔"))
                .andExpect(jsonPath("$.pitchers[0].era").value(10.8))
                .andExpect(jsonPath("$.pitchers[0].minorGames").value(12))
                .andExpect(jsonPath("$.pitchers[0].mainlyMinors").value(true));
    }

    @Test
    void hitterSeasonTotals() throws Exception {
        // 이정후: 12타수 5안타(2루타 2, 홈런 1) 2볼넷 → .417 / .500 / .833 / 1.333
        mockMvc.perform(get("/api/home"))
                .andExpect(jsonPath("$.hitters[?(@.nameKo == '이정후')].atBats").value(contains(12)))
                .andExpect(jsonPath("$.hitters[?(@.nameKo == '이정후')].obp").value(contains(0.5)))
                .andExpect(jsonPath("$.hitters[?(@.nameKo == '이정후')].slg").value(contains(0.833)))
                .andExpect(jsonPath("$.hitters[?(@.nameKo == '이정후')].ops").value(contains(1.333)));
    }

    @Test
    void playersList() throws Exception {
        mockMvc.perform(get("/api/players"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[0].nameKo").value("이정후"))
                .andExpect(jsonPath("$[0].nameEn").value("Jung Hoo Lee"))
                .andExpect(jsonPath("$[0].role").value("HITTER"))
                .andExpect(jsonPath("$[0].position").value("Center Fielder"))
                .andExpect(jsonPath("$[0].positionKo").value("중견수"))
                .andExpect(jsonPath("$[0].teamKo").value("샌프란시스코"))
                .andExpect(jsonPath("$[0].teamAbbr").value("SF"))
                .andExpect(jsonPath("$[0].photoUrl").value(JUNG_HOO_PHOTO))
                // 생년월일 1998-08-20, 한국 시간 2026-11-01 기준 만 28세
                .andExpect(jsonPath("$[0].age").value(28))
                .andExpect(jsonPath("$[0].jersey").value("51"))
                .andExpect(jsonPath("$[4].nameKo").value("고우석"))
                .andExpect(jsonPath("$[4].role").value("PITCHER"))
                // MLB 주 포지션은 투수(P)만 알려 준다(선발·구원 구분 없음).
                .andExpect(jsonPath("$[4].positionKo").value("투수"))
                .andExpect(jsonPath("$[4].jersey").value(nullValue()));
    }

    @Test
    void playersListHasLevel() throws Exception {
        // 샘플 MLB 데이터(sample/mlb.json): 고우석만 마이너(St. Paul Saints, 트리플A), 나머지는 메이저
        mockMvc.perform(get("/api/players"))
                .andExpect(jsonPath("$[0].level").value("MLB"))
                .andExpect(jsonPath("$[0].levelTeam").value("San Francisco Giants"))
                .andExpect(jsonPath("$[4].nameKo").value("고우석"))
                .andExpect(jsonPath("$[4].level").value("MINORS"))
                .andExpect(jsonPath("$[4].levelTeam").value("St. Paul Saints"))
                // 마이너 팀에 있으면 소속(teamAbbr)은 그 팀의 MLB 구단이다.
                .andExpect(jsonPath("$[4].teamAbbr").value("MIN"))
                .andExpect(jsonPath("$[*].level").value(contains("MLB", "MLB", "MLB", "MLB", "MINORS", "MLB")));
    }

    @Test
    void playerPageMoves() throws Exception {
        // 고우석: 상태 변경 줄(roster status changed)은 빠지고 최신순 4줄
        mockMvc.perform(get("/api/players/5"))
                // 선수 페이지도 목록과 같이 지금 단계를 준다.
                .andExpect(jsonPath("$.player.level").value("MINORS"))
                .andExpect(jsonPath("$.player.levelTeam").value("St. Paul Saints"))
                .andExpect(jsonPath("$.moves", hasSize(4)))
                .andExpect(jsonPath("$.moves[*].date").value(contains("2026-10-06", "2026-10-02", "2026-09-20", "2026-03-24")))
                .andExpect(jsonPath("$.moves[0].typeCode").value("OUT"))
                .andExpect(jsonPath("$.moves[0].labelKo").value("마이너 이관(아웃라이트)"))
                .andExpect(jsonPath("$.moves[0].fromTeam").value("미네소타"))
                .andExpect(jsonPath("$.moves[0].toTeam").value("St. Paul Saints"))
                .andExpect(jsonPath("$.moves[0].toLevel").value("MINORS"))
                .andExpect(jsonPath("$.moves[1].labelKo").value("지명 할당(DFA)"))
                .andExpect(jsonPath("$.moves[1].fromTeam").value(nullValue()))
                .andExpect(jsonPath("$.moves[1].toTeam").value("미네소타"))
                .andExpect(jsonPath("$.moves[1].toLevel").value("MLB"))
                .andExpect(jsonPath("$.moves[2].labelKo").value("메이저 콜업"))
                .andExpect(jsonPath("$.moves[2].fromTeam").value("St. Paul Saints"))
                .andExpect(jsonPath("$.moves[3].labelKo").value("마이너 강등(옵션)"));

        // 부상자 명단 등재·해제(상태 변경 줄 중 남기는 것)
        mockMvc.perform(get("/api/players/2"))
                .andExpect(jsonPath("$.moves[*].labelKo").value(contains("부상자 명단 해제", "부상자 명단 등재")))
                .andExpect(jsonPath("$.moves[0].toTeam").value("LA 다저스"));
        // 트레이드: 양쪽 다 MLB 구단이면 한글 이름
        mockMvc.perform(get("/api/players/6"))
                .andExpect(jsonPath("$.moves[0].labelKo").value("트레이드"))
                .andExpect(jsonPath("$.moves[0].fromTeam").value("피츠버그"))
                .andExpect(jsonPath("$.moves[0].toTeam").value("밀워키"));
        // 이동 기록이 없으면 빈 목록
        mockMvc.perform(get("/api/players/1"))
                .andExpect(jsonPath("$.moves", hasSize(0)));
    }

    @Test
    void playerInfoComesFromMlbPeople() throws Exception {
        // 샘플 MLB 선수 정보: 출생지 Nagoya, Japan(birthCity, birthCountry), 주 포지션 CF
        mockMvc.perform(get("/api/players/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.player.nameEn").value("Jung Hoo Lee"))
                .andExpect(jsonPath("$.player.birthPlace").value("Nagoya, Japan"))
                .andExpect(jsonPath("$.player.teamAbbr").value("SF"))
                .andExpect(jsonPath("$.player.teamEn").value("San Francisco Giants"))
                .andExpect(jsonPath("$.player.position").value("Center Fielder"))
                .andExpect(jsonPath("$.hitting.photoUrl").value(JUNG_HOO_PHOTO));
    }

    @Test
    void playerProfile() throws Exception {
        mockMvc.perform(get("/api/players/1"))
                .andExpect(jsonPath("$.player.photoUrl").value(JUNG_HOO_PHOTO))
                .andExpect(jsonPath("$.player.mlbUrl").value("https://www.mlb.com/player/808982"))
                .andExpect(jsonPath("$.player.jersey").value("51"))
                .andExpect(jsonPath("$.player.birthDate").value("1998-08-20"))
                .andExpect(jsonPath("$.player.age").value(28))
                .andExpect(jsonPath("$.player.heightCm").value(188))
                .andExpect(jsonPath("$.player.weightKg").value(86))
                .andExpect(jsonPath("$.player.batsThrowsKo").value("우투좌타"))
                .andExpect(jsonPath("$.player.debutYear").value(2024))
                .andExpect(jsonPath("$.player.careerKo").value("넥센·키움 히어로즈 (2017–2023)"));
    }

    @Test
    void pitcherProfileWithoutJersey() throws Exception {
        // 고우석: 등번호(primaryNumber) 없음, 키 5' 11", 몸무게 198
        mockMvc.perform(get("/api/players/5"))
                .andExpect(jsonPath("$.player.birthDate").value("1998-08-06"))
                .andExpect(jsonPath("$.player.age").value(28))
                .andExpect(jsonPath("$.player.jersey").value(nullValue()))
                .andExpect(jsonPath("$.player.heightCm").value(180))
                .andExpect(jsonPath("$.player.weightKg").value(90))
                .andExpect(jsonPath("$.player.batsThrowsKo").value("우투우타"))
                .andExpect(jsonPath("$.player.debutYear").value(2026))
                .andExpect(jsonPath("$.player.mlbUrl").value("https://www.mlb.com/player/808970"))
                .andExpect(jsonPath("$.player.careerKo").value("LG 트윈스 (2017–2023)"))
                .andExpect(jsonPath("$.pitching.photoUrl").value(PHOTO_PREFIX + "808970" + PHOTO_SUFFIX))
                .andExpect(jsonPath("$.games[0].photoUrl").value(PHOTO_PREFIX + "808970" + PHOTO_SUFFIX))
                .andExpect(jsonPath("$.games[0].opponentAbbr").value("KC"));
    }

    @Test
    void pitcherPage() throws Exception {
        mockMvc.perform(get("/api/players/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.player.nameKo").value("고우석"))
                .andExpect(jsonPath("$.player.teamEn").value("Minnesota Twins"))
                .andExpect(jsonPath("$.player.teamAbbr").value("MIN"))
                .andExpect(jsonPath("$.player.positionKo").value("투수"))
                .andExpect(jsonPath("$.player.birthPlace").value("Incheon, South Korea"))
                .andExpect(jsonPath("$.hitting").doesNotExist())
                .andExpect(jsonPath("$.pitching.saves").value(1))
                // 첫 등판(4002) 전 경기(4001)는 뛴 기간 밖이라 줄을 만들지 않는다.
                .andExpect(jsonPath("$.games", hasSize(2)))
                .andExpect(jsonPath("$.games[0].gameId").value(4003))
                .andExpect(jsonPath("$.games[1].summary").value("1이닝 1피안타 무실점 2탈삼진 · 세"));
    }

    @Test
    void absenceBetweenAppearances() throws Exception {
        // 배지환: 5001 출전, 5002 결장, 5003 출전
        mockMvc.perform(get("/api/players/6"))
                .andExpect(jsonPath("$.games", hasSize(3)))
                .andExpect(jsonPath("$.games[1].gameId").value(5002))
                .andExpect(jsonPath("$.games[1].played").value(false))
                .andExpect(jsonPath("$.games[1].summary").value("결장"));
    }

    @Test
    void lineupPerGame() throws Exception {
        // 김혜성: 2003 결장(null), 2002 선발, 2001 교체(타순 901)
        mockMvc.perform(get("/api/players/2"))
                .andExpect(jsonPath("$.games[*].gameId").value(contains(2003, 2002, 2001)))
                .andExpect(jsonPath("$.games[*].lineup").value(contains(nullValue(), is("STARTER"), is("SUB"))));
    }

    @Test
    void unknownPlayerIs404() throws Exception {
        mockMvc.perform(get("/api/players/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("player not found"));
    }

    @Test
    void adminNeedsToken() throws Exception {
        mockMvc.perform(post("/admin/ingest/games").param("date", "2026-09-26"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/ingest/games").param("date", "2026-09-26")
                        .header("X-Admin-Token", "test-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("GAMES"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void corsAllowsLocalWebOnly() throws Exception {
        mockMvc.perform(get("/api/players").header("Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mockMvc.perform(get("/api/players").header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden());
    }
}
