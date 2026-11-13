package com.korleaguer.api;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbMinorGames;
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
import java.util.Map;

import static com.korleaguer.client.MlbFixtures.batting;
import static com.korleaguer.client.MlbFixtures.game;
import static com.korleaguer.client.MlbFixtures.hitter;
import static com.korleaguer.client.MlbFixtures.team;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 올해 마이너 경기가 메이저 경기보다 많은 선수(mainlyMinors)는 홈 시즌 기록 맨 아래에 둔다.
 * 지금 메이저에 있어도(시즌 중 콜업) 시즌 경기 수로 정하고, 순위 기준을 채웠어도 기준을 못 채운 메이저 선수보다 아래다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mainly-minors;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=가타자",
        "roster.baseball[0].mlb-id=900001",
        "roster.baseball[1].name-ko=나타자",
        "roster.baseball[1].mlb-id=900002",
        "roster.baseball[2].name-ko=다타자",
        "roster.baseball[2].mlb-id=900003"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MainlyMinorsHomeTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        MlbClient mainlyMinorsClient() {
            MlbTeam sf = team(137, "San Francisco Giants");
            MlbTeam col = team(115, "Colorado Rockies");
            List<MlbScheduleGame> games = List.of(
                    game(9001, col, sf, "2026-09-26T20:05:00Z", "R"),
                    game(9002, col, sf, "2026-09-27T20:05:00Z", "R"),
                    game(9003, col, sf, "2026-09-28T20:05:00Z", "R"));
            return new SampleMlbClient(SampleMlbClient.Data.empty()
                    .withPeople(List.of(hitter(900001, "A", sf), hitter(900002, "B", sf), hitter(900003, "C", sf)))
                    .withSchedule(games)
                    .withHittingLogs(List.of(
                            // 가타자: 3경기 12타석 .250 (기준 충족, 주로 메이저)
                            batting(900001, sf, games.get(0), 4, 1),
                            batting(900001, sf, games.get(1), 4, 1),
                            batting(900001, sf, games.get(2), 4, 1),
                            // 나타자: 3경기 12타석 .500 (기준 충족, 하지만 마이너 40경기 → 주로 마이너)
                            batting(900002, sf, games.get(0), 4, 2),
                            batting(900002, sf, games.get(1), 4, 2),
                            batting(900002, sf, games.get(2), 4, 2),
                            // 다타자: 1경기 4타석 .750 (기준 미달, 주로 메이저)
                            batting(900003, sf, games.get(2), 4, 3)))
                    .withMinorGames(Map.of(900002L, new MlbMinorGames(40, 0), 900003L, new MlbMinorGames(1, 0))));
        }
    }

    @Test
    void mainlyMinorsGoLast() throws Exception {
        mockMvc.perform(get("/api/home"))
                .andExpect(jsonPath("$.hitters[*].nameKo").value(contains("가타자", "다타자", "나타자")))
                .andExpect(jsonPath("$.hitters[0].minorGames").value(0))
                .andExpect(jsonPath("$.hitters[0].mainlyMinors").value(false))
                // 마이너 1경기 = 메이저 1경기: 많지 않으므로 주로 메이저
                .andExpect(jsonPath("$.hitters[1].minorGames").value(1))
                .andExpect(jsonPath("$.hitters[1].mainlyMinors").value(false))
                .andExpect(jsonPath("$.hitters[2].games").value(3))
                .andExpect(jsonPath("$.hitters[2].minorGames").value(40))
                .andExpect(jsonPath("$.hitters[2].mainlyMinors").value(true))
                // 9/26~9/28에 우리 선수 구단 경기가 있었다(9/30 기준 일주일 안): 시즌 중이라 비시즌 정보가 없다
                .andExpect(jsonPath("$.offseason").value(nullValue()));
    }

    @Test
    void minorSeasonIsSeparate() throws Exception {
        // 마이너 시즌 성적(이슈 #37): 마이너 경기별 기록을 더한 것, 마이너 경기 많은 순. 메이저 줄에는 단계가 없다
        mockMvc.perform(get("/api/home"))
                .andExpect(jsonPath("$.minorHitters[*].nameKo").value(contains("나타자", "다타자")))
                .andExpect(jsonPath("$.minorHitters[0].games").value(40))
                .andExpect(jsonPath("$.minorHitters[0].levelKo").value("트리플A"))
                .andExpect(jsonPath("$.minorHitters[0].teamKo").value("샘플 트리플A 팀"))
                .andExpect(jsonPath("$.minorHitters[0].mainlyMinors").value(false))
                .andExpect(jsonPath("$.minorHitters[1].games").value(1))
                .andExpect(jsonPath("$.minorPitchers").isEmpty())
                .andExpect(jsonPath("$.hitters[0].levelKo").value(nullValue()));
    }

    @Test
    void playerPageHasMinorGames() throws Exception {
        mockMvc.perform(get("/api/players/2"))
                .andExpect(jsonPath("$.hitting.minorGames").value(40))
                .andExpect(jsonPath("$.hitting.mainlyMinors").value(true))
                // 선수 페이지도 마이너 시즌 성적을 따로 준다(홈 마이너 줄과 같은 값). 타자라 투수 칸은 비어 있다
                .andExpect(jsonPath("$.minorHitting.games").value(40))
                .andExpect(jsonPath("$.minorHitting.levelKo").value("트리플A"))
                .andExpect(jsonPath("$.minorPitching").value(nullValue()));
    }

    @Test
    void majorOnlyPlayerHasNoMinorSeason() throws Exception {
        // 가타자는 마이너 경기가 없다
        mockMvc.perform(get("/api/players/1"))
                .andExpect(jsonPath("$.hitting.games").value(3))
                .andExpect(jsonPath("$.minorHitting").value(nullValue()));
    }
}
