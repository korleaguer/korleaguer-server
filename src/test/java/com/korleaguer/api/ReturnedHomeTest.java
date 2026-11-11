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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 국내 리그로 돌아간 선수(명단의 returned-ko): MLB 기록상 아직 미네소타 소속이어도 '내 선수' 상태는 KBO(돌아간 팀 이름)이고
 * 시즌 합계 카드다. 선수 목록·선수 페이지에도 돌아간 팀이 실린다. 명단에 적지 않은 선수는 그대로다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:returned-home;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=가선수",
        "roster.baseball[0].mlb-id=900001",
        "roster.baseball[0].returned-ko=LG 트윈스",
        "roster.baseball[1].name-ko=나선수",
        "roster.baseball[1].mlb-id=900002"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReturnedHomeTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        Clock fixedClock() {
            // 2026 정규시즌이 끝난 뒤(비시즌)
            return Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        MlbClient returnedHomeClient() {
            MlbTeam min = team(142, "Minnesota Twins");
            MlbTeam sf = team(137, "San Francisco Giants");
            MlbTeam col = team(115, "Colorado Rockies");
            List<MlbScheduleGame> games = List.of(
                    game(9101, col, min, "2026-09-20T20:05:00Z", "R"),
                    game(9102, col, sf, "2026-09-26T20:05:00Z", "R"));
            return new SampleMlbClient(SampleMlbClient.Data.empty()
                    .withPeople(List.of(hitter(900001, "A", min), hitter(900002, "B", sf)))
                    .withSchedule(games)
                    .withHittingLogs(List.of(
                            batting(900001, min, games.get(0), 4, 2),
                            batting(900002, sf, games.get(1), 4, 1))));
        }
    }

    @Test
    void returnedPlayerIsKboNotFreeAgent() throws Exception {
        mockMvc.perform(get("/api/my").param("ids", "1,2").param("days", "7"))
                .andExpect(jsonPath("$.players[0].player.nameKo").value("가선수"))
                .andExpect(jsonPath("$.players[0].player.returnedKo").value("LG 트윈스"))
                .andExpect(jsonPath("$.players[0].status.kind").value("KBO"))
                .andExpect(jsonPath("$.players[0].status.teamName").value("LG 트윈스"))
                .andExpect(jsonPath("$.players[0].status.lastGameLevel").value("MLB"))
                .andExpect(jsonPath("$.players[0].basis").value("SEASON"))
                // 명단에 돌아간 팀이 없는 선수는 평소대로(비시즌이라 시즌 끝)
                .andExpect(jsonPath("$.players[1].player.returnedKo").value(nullValue()))
                .andExpect(jsonPath("$.players[1].status.kind").value(not("KBO")));
    }

    @Test
    void listAndPlayerPageCarryTheReturnedTeam() throws Exception {
        mockMvc.perform(get("/api/players"))
                .andExpect(jsonPath("$[0].returnedKo").value("LG 트윈스"))
                .andExpect(jsonPath("$[1].returnedKo").value(nullValue()));
        mockMvc.perform(get("/api/players/1"))
                .andExpect(jsonPath("$.player.returnedKo").value("LG 트윈스"));
    }
}
