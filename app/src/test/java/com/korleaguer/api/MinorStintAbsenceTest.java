package com.korleaguer.api;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.MlbTransaction;
import com.korleaguer.client.SampleMlbClient;
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
import java.util.List;

import static com.korleaguer.client.MlbFixtures.batting;
import static com.korleaguer.client.MlbFixtures.game;
import static com.korleaguer.client.MlbFixtures.hitter;
import static com.korleaguer.client.MlbFixtures.team;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 시즌 중 트리플A로 내려갔다 올라온 선수(운영 김혜성 같은 경우, 이슈 #134).
 * 뛴 기간(첫 출전 ~ 마지막 출전) 안이라도 옵션 당일부터 콜업 전날까지의 다저스 경기는 결장 줄을 만들지 않는다.
 * 옵션 전·콜업 당일부터는 메이저 명단에 있으니 안 뛴 경기는 결장이다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:minor-stint-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=김혜성",
        "roster.baseball[0].mlb-id=808975"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MinorStintAbsenceTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        MlbClient minorStintClient() {
            MlbTeam lad = team(119, "Los Angeles Dodgers");
            MlbTeam sf = team(137, "San Francisco Giants");
            MlbTeam okc = new MlbTeam(238L, "Oklahoma City Comets", null, new MlbTeam.Sport(11L, "Triple-A"), 119L);
            List<MlbScheduleGame> games = List.of(
                    game(9001, sf, lad, "2026-08-01T23:00:00Z", "R"),   // 출전
                    game(9002, sf, lad, "2026-08-02T23:00:00Z", "R"),   // 결장(옵션 전)
                    game(9003, sf, lad, "2026-08-03T23:00:00Z", "R"),   // 옵션 당일: 줄 없음
                    game(9004, sf, lad, "2026-08-04T23:00:00Z", "R"),   // 트리플A: 줄 없음
                    game(9005, sf, lad, "2026-08-05T23:00:00Z", "R"),   // 콜업 당일: 결장
                    game(9006, sf, lad, "2026-08-06T23:00:00Z", "R"));  // 출전
            return new SampleMlbClient(SampleMlbClient.Data.empty()
                    .withPeople(List.of(hitter(808975, "Hyeseong Kim", lad)))
                    .withTeams(List.of(okc))
                    .withTransactions(List.of(
                            new MlbTransaction(1L, hitter(808975, "Hyeseong Kim", lad), "2026-08-03", "2026-08-03",
                                    "OPT", "Optioned", "Los Angeles Dodgers optioned SS Hyeseong Kim to Oklahoma City Comets.",
                                    lad, okc),
                            new MlbTransaction(2L, hitter(808975, "Hyeseong Kim", lad), "2026-08-05", "2026-08-05",
                                    "CU", "Recalled", "Los Angeles Dodgers recalled SS Hyeseong Kim from Oklahoma City Comets.",
                                    okc, lad)))
                    .withSchedule(games)
                    .withHittingLogs(List.of(
                            batting(808975, lad, games.get(0), 4, 1),
                            batting(808975, lad, games.get(5), 3, 1))));
        }
    }

    @Test
    void gamesWhileInTripleAHaveNoAbsenceLine() throws Exception {
        mockMvc.perform(get("/api/players/1"))
                .andExpect(jsonPath("$.games[*].gameId").value(contains(9006, 9005, 9002, 9001)))
                .andExpect(jsonPath("$.games[*].played").value(contains(true, false, false, true)))
                .andExpect(jsonPath("$.games[1].summary").value("결장"))
                .andExpect(jsonPath("$.games[2].summary").value("결장"));
    }
}
