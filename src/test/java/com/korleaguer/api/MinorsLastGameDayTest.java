package com.korleaguer.api;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 비시즌의 "마지막 경기일"은 메이저에 있는 선수의 구단 경기로만 정한다.
 * 마이너에 있는 선수(배지환)의 소속 구단(밀워키)이 더 늦게까지 경기를 했어도(포스트시즌 등)
 * 그 날짜를 잡으면 홈에 빈 날짜가 뜬다(2026-11-10 실제 데이터에서 "10월 29일 경기"가 비어 보였다).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:minors-last-day;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=이정후",
        "roster.baseball[0].mlb-id=808982",
        "roster.baseball[1].name-ko=배지환",
        "roster.baseball[1].mlb-id=678225"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MinorsLastGameDayTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        MlbClient minorsClient() {
            MlbTeam sf = team(137, "San Francisco Giants");
            MlbTeam col = team(115, "Colorado Rockies");
            MlbTeam mil = team(158, "Milwaukee Brewers");
            MlbTeam chc = team(112, "Chicago Cubs");
            MlbTeam nashville = new MlbTeam(556L, "Nashville Sounds", null, new MlbTeam.Sport(11L, "Triple-A"), 158L);
            List<MlbScheduleGame> games = List.of(
                    game(9001, col, sf, "2026-09-26T20:05:00Z", "R"),   // 이정후 출전: 한국 9월 27일
                    game(9101, chc, mil, "2026-08-01T23:00:00Z", "R"),  // 배지환 출전(그 뒤 마이너로)
                    game(9102, chc, mil, "2026-09-28T00:10:00Z", "R")); // 밀워키만 더 늦게 경기: 한국 9월 28일
            return new SampleMlbClient(SampleMlbClient.Data.empty()
                    .withPeople(List.of(hitter(808982, "Jung Hoo Lee", sf), hitter(678225, "Ji Hwan Bae", nashville)))
                    .withTeams(List.of(nashville))
                    .withSchedule(games)
                    .withHittingLogs(List.of(
                            batting(808982, sf, games.get(0), 4, 2),
                            batting(678225, mil, games.get(1), 3, 1))));
        }
    }

    @Test
    void homeFallbackIgnoresMinorsPlayersClub() throws Exception {
        mockMvc.perform(get("/api/players"))
                .andExpect(jsonPath("$[1].level").value("MINORS"));
        mockMvc.perform(get("/api/home"))
                .andExpect(jsonPath("$.recent.isFallback").value(true))
                .andExpect(jsonPath("$.recent.dateKst").value("2026-09-27"))
                .andExpect(jsonPath("$.recent.lines", hasSize(1)))
                .andExpect(jsonPath("$.recent.lines[0].gameId").value(9001));
    }

    @Test
    void myPlayersFallbackIgnoresMinorsPlayersClub() throws Exception {
        mockMvc.perform(get("/api/my").param("ids", "1,2").param("days", "7"))
                .andExpect(jsonPath("$.isFallback").value(true))
                .andExpect(jsonPath("$.toKst").value("2026-09-27"));
    }
}
