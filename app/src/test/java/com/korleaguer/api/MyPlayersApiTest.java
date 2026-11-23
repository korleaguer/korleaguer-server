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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/my(내 선수). 샘플 데이터로 확인한다.
 * 지금을 2026-11-01로 고정: 오늘까지 7일(10-26~11-01)엔 경기가 없어 마지막 경기일(9월 27일)까지 7일로 대신한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyPlayersApiTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void offSeasonUsesEachPlayersLastGame() throws Exception {
        // 11월이라 최근 7일에 경기가 없다. 선수마다 본인이 마지막으로 뛴 날까지 7일로 당긴다.
        // 이정후는 9월 27일, 김혜성은 9월 26일(다저스는 27일에도 했지만 김혜성은 결장)
        mockMvc.perform(get("/api/my").param("ids", "1,2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("sample"))
                .andExpect(jsonPath("$.statsAvailable").value(true))
                .andExpect(jsonPath("$.season").value(2026))
                .andExpect(jsonPath("$.generatedAt").value("2026-11-01T11:00:00Z"))
                .andExpect(jsonPath("$.days").value(7))
                // 맨 위 기간은 최근 7일. 맨 위 isFallback은 '마지막으로 뛴 날까지' 기간을 보이는 카드가 있는지인데,
                // 11월에는 두 카드 모두 시즌 끝(시즌 합계)이라 기간을 보이지 않는다. 카드의 기간은 그대로 당겨 둔다.
                .andExpect(jsonPath("$.fromKst").value("2026-10-26"))
                .andExpect(jsonPath("$.toKst").value("2026-11-01"))
                .andExpect(jsonPath("$.isFallback").value(false))
                .andExpect(jsonPath("$.players[0].basis").value("SEASON"))
                .andExpect(jsonPath("$.players", hasSize(2)))
                .andExpect(jsonPath("$.players[0].fromKst").value("2026-09-21"))
                .andExpect(jsonPath("$.players[0].toKst").value("2026-09-27"))
                .andExpect(jsonPath("$.players[0].isFallback").value(true))
                .andExpect(jsonPath("$.players[1].fromKst").value("2026-09-20"))
                .andExpect(jsonPath("$.players[1].toKst").value("2026-09-26"))
                .andExpect(jsonPath("$.players[1].isFallback").value(true));

        // 30일이면 마지막으로 뛴 날(9월 27일)에서 29일 전부터
        mockMvc.perform(get("/api/my").param("ids", "1").param("days", "30"))
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.fromKst").value("2026-10-03"))
                .andExpect(jsonPath("$.toKst").value("2026-11-01"))
                .andExpect(jsonPath("$.players[0].fromKst").value("2026-08-29"))
                .andExpect(jsonPath("$.players[0].toKst").value("2026-09-27"));
    }

    @Test
    void playersFollowRequestedOrder() throws Exception {
        // 겹친 id(5)는 처음 것만 남긴다.
        mockMvc.perform(get("/api/my").param("ids", "5,1,5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players", hasSize(2)))
                .andExpect(jsonPath("$.players[0].player.nameKo").value("고우석"))
                .andExpect(jsonPath("$.players[1].player.nameKo").value("이정후"));
    }

    @Test
    void unknownIdIsSkipped() throws Exception {
        mockMvc.perform(get("/api/my").param("ids", "1,999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players", hasSize(1)))
                .andExpect(jsonPath("$.players[0].player.id").value(1));
    }

    @Test
    void badRequests() throws Exception {
        mockMvc.perform(get("/api/my").param("ids", "1").param("days", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("days must be 7, 14 or 30"));
        mockMvc.perform(get("/api/my").param("ids", "1").param("days", "week"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid parameter: days"));
        mockMvc.perform(get("/api/my"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing parameter: ids"));
        mockMvc.perform(get("/api/my").param("ids", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ids must have 1 to 20 player ids"));
        mockMvc.perform(get("/api/my").param("ids", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ids must be numbers"));

        String twentyOne = IntStream.rangeClosed(1, 21).mapToObj(String::valueOf).collect(Collectors.joining(","));
        mockMvc.perform(get("/api/my").param("ids", twentyOne))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ids must have 1 to 20 player ids"));
    }

    @Test
    void hitterTotalsAndGames() throws Exception {
        // 이정후 9월 21~27일: 1001 4타수 1안타 1볼넷, 1002 4타수 2안타(2루타) 1타점, 1003 4타수 2안타(2루타, 홈런) 2타점 1볼넷
        // → 3경기 12타수 5안타 1홈런 3타점 2볼넷, 타율 .417(시즌도 같은 세 경기라 .417). 세 경기 모두 안타 → 연속 3경기
        mockMvc.perform(get("/api/my").param("ids", "1"))
                .andExpect(jsonPath("$.players[0].player.id").value(1))
                .andExpect(jsonPath("$.players[0].player.nameEn").value("Jung Hoo Lee"))
                .andExpect(jsonPath("$.players[0].player.role").value("HITTER"))
                .andExpect(jsonPath("$.players[0].player.positionKo").value("중견수"))
                .andExpect(jsonPath("$.players[0].player.teamAbbr").value("SF"))
                .andExpect(jsonPath("$.players[0].player.age").value(28))
                .andExpect(jsonPath("$.players[0].player.jersey").value("51"))
                .andExpect(jsonPath("$.players[0].player.level").value("MLB"))
                .andExpect(jsonPath("$.players[0].hitting.games").value(3))
                .andExpect(jsonPath("$.players[0].hitting.atBats").value(12))
                .andExpect(jsonPath("$.players[0].hitting.hits").value(5))
                .andExpect(jsonPath("$.players[0].hitting.homeRuns").value(1))
                .andExpect(jsonPath("$.players[0].hitting.rbi").value(3))
                .andExpect(jsonPath("$.players[0].hitting.walks").value(2))
                .andExpect(jsonPath("$.players[0].hitting.stolenBases").value(0))
                .andExpect(jsonPath("$.players[0].hitting.avg").value(0.417))
                .andExpect(jsonPath("$.players[0].hitting.seasonAvg").value(0.417))
                .andExpect(jsonPath("$.players[0].pitching").doesNotExist())
                .andExpect(jsonPath("$.players[0].streak").value(3))
                // 최신 경기부터
                .andExpect(jsonPath("$.players[0].games[*].gameId").value(contains(1003, 1002, 1001)))
                .andExpect(jsonPath("$.players[0].games[0].startTimeKst").value("2026-09-27T05:05:00+09:00"))
                .andExpect(jsonPath("$.players[0].games[0].summary").value("4타수 2안타(2루타 1) 1홈런 2타점 1볼넷"))
                .andExpect(jsonPath("$.players[0].games[0].batting.atBats").value(4))
                .andExpect(jsonPath("$.players[0].games[0].batting.hits").value(2))
                .andExpect(jsonPath("$.players[0].games[0].batting.homeRuns").value(1))
                .andExpect(jsonPath("$.players[0].games[0].batting.rbi").value(2))
                .andExpect(jsonPath("$.players[0].games[0].batting.walks").value(1))
                .andExpect(jsonPath("$.players[0].games[0].batting.stolenBases").value(0))
                .andExpect(jsonPath("$.players[0].games[0].pitching").doesNotExist())
                .andExpect(jsonPath("$.players[0].games[*].batting.hits").value(contains(2, 2, 1)));
    }

    @Test
    void pitcherTotalsAndGames() throws Exception {
        // 고우석 9월 21~27일: 4002 1이닝(3아웃) 무실점 2탈삼진 세이브, 4003 ⅔이닝(2아웃) 2실점(2자책) 패
        // → 2경기 5아웃(1⅔이닝) 2자책, 평균자책점 2×27/5 = 10.8(시즌도 같은 두 경기라 10.8).
        // 가장 최근 등판(4003)에 실점 → 연속 무실점 0
        mockMvc.perform(get("/api/my").param("ids", "5"))
                .andExpect(jsonPath("$.players[0].player.role").value("PITCHER"))
                .andExpect(jsonPath("$.players[0].player.level").value("MINORS"))
                .andExpect(jsonPath("$.players[0].player.levelTeam").value("St. Paul Saints"))
                .andExpect(jsonPath("$.players[0].hitting").doesNotExist())
                .andExpect(jsonPath("$.players[0].pitching.games").value(2))
                .andExpect(jsonPath("$.players[0].pitching.outs").value(5))
                .andExpect(jsonPath("$.players[0].pitching.inningsText").value("1⅔"))
                .andExpect(jsonPath("$.players[0].pitching.earnedRuns").value(2))
                .andExpect(jsonPath("$.players[0].pitching.era").value(10.8))
                .andExpect(jsonPath("$.players[0].pitching.strikeouts").value(2))
                .andExpect(jsonPath("$.players[0].pitching.wins").value(0))
                .andExpect(jsonPath("$.players[0].pitching.losses").value(1))
                .andExpect(jsonPath("$.players[0].pitching.saves").value(1))
                .andExpect(jsonPath("$.players[0].pitching.seasonEra").value(10.8))
                .andExpect(jsonPath("$.players[0].streak").value(0))
                // 첫 등판(4002) 전 경기(4001)는 뛴 기간 밖이라 줄을 만들지 않는다.
                .andExpect(jsonPath("$.players[0].games[*].gameId").value(contains(4003, 4002)))
                .andExpect(jsonPath("$.players[0].games[0].pitching.outs").value(2))
                .andExpect(jsonPath("$.players[0].games[0].pitching.inningsText").value("⅔"))
                .andExpect(jsonPath("$.players[0].games[0].pitching.runs").value(2))
                .andExpect(jsonPath("$.players[0].games[0].pitching.earnedRuns").value(2))
                .andExpect(jsonPath("$.players[0].games[0].pitching.strikeouts").value(0))
                .andExpect(jsonPath("$.players[0].games[0].pitching.win").value(false))
                .andExpect(jsonPath("$.players[0].games[0].pitching.loss").value(true))
                .andExpect(jsonPath("$.players[0].games[0].pitching.save").value(false))
                .andExpect(jsonPath("$.players[0].games[0].batting").doesNotExist())
                .andExpect(jsonPath("$.players[0].games[1].pitching.inningsText").value("1"))
                .andExpect(jsonPath("$.players[0].games[1].pitching.save").value(true));
    }

    @Test
    void offSeasonCardEndsAtPlayersOwnLastGame() throws Exception {
        // 김혜성: 2001 3타수 무안타, 2002 4타수 3안타, 2003 결장(9월 27일)
        // 비시즌에는 본인이 마지막으로 뛴 2002(9월 26일)까지라 뒤의 결장(2003)은 카드에 넣지 않는다.
        // → 기간 2경기 7타수 3안타(.429). 2001 무안타에서 멈춰 연속 1경기
        mockMvc.perform(get("/api/my").param("ids", "2"))
                .andExpect(jsonPath("$.players[0].toKst").value("2026-09-26"))
                .andExpect(jsonPath("$.players[0].games[*].gameId").value(contains(2002, 2001)))
                .andExpect(jsonPath("$.players[0].games[0].batting.hits").value(3))
                .andExpect(jsonPath("$.players[0].hitting.games").value(2))
                .andExpect(jsonPath("$.players[0].hitting.avg").value(0.429))
                .andExpect(jsonPath("$.players[0].streak").value(1));
    }
}
