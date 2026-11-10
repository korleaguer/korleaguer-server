package com.korleaguer.api;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbPerson;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * MLB '지금 소속'은 FA 선언 뒤에도 마이너 팀으로 남는다(배지환 2026: 11/2 FA 선언, 지금 소속 Nashville Sounds).
 * 이동 기록으로 FA를 정해 단계·팀 이름을 비우고, 홈 시즌 성적에는 마지막 팀(밀워키)으로 남긴다.
 * 이정후는 FA 선언 기록이 없어 그대로다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:declared-free-agent;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=이정후",
        "roster.baseball[0].mlb-id=808982",
        "roster.baseball[1].name-ko=배지환",
        "roster.baseball[1].mlb-id=678225"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeclaredFreeAgentTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        MlbClient declaredFreeAgentClient() {
            MlbTeam sf = team(137, "San Francisco Giants");
            MlbTeam col = team(115, "Colorado Rockies");
            MlbTeam mil = team(158, "Milwaukee Brewers");
            MlbTeam chc = team(112, "Chicago Cubs");
            MlbTeam nashville = new MlbTeam(556L, "Nashville Sounds", null, new MlbTeam.Sport(11L, "Triple-A"), 158L);
            List<MlbScheduleGame> games = List.of(
                    game(9001, col, sf, "2026-09-26T20:05:00Z", "R"),
                    game(9101, chc, mil, "2026-08-01T23:00:00Z", "R"));
            MlbPerson bae = hitter(678225, "Ji Hwan Bae", nashville);
            return new SampleMlbClient(SampleMlbClient.Data.empty()
                    .withPeople(List.of(hitter(808982, "Jung Hoo Lee", sf), bae))
                    .withTeams(List.of(nashville))
                    .withTransactions(List.of(
                            new MlbTransaction(1L, bae, "2026-08-19", "2026-08-19", "OUT", "Outrighted",
                                    "Milwaukee Brewers sent CF Ji Hwan Bae outright to Nashville Sounds.", mil, nashville),
                            new MlbTransaction(2L, bae, "2026-11-02", "2026-11-02", "DFA", "Declared Free Agency",
                                    "CF Ji Hwan Bae elected free agency.", null, nashville)))
                    .withSchedule(games)
                    .withHittingLogs(List.of(
                            batting(808982, sf, games.get(0), 4, 2),
                            batting(678225, mil, games.get(1), 3, 1))));
        }
    }

    @Test
    void declaredFreeAgentHasNoLevel() throws Exception {
        mockMvc.perform(get("/api/players"))
                .andExpect(jsonPath("$[0].freeAgent").value(false))
                .andExpect(jsonPath("$[0].level").value("MLB"))
                .andExpect(jsonPath("$[1].freeAgent").value(true))
                .andExpect(jsonPath("$[1].level").doesNotExist())
                .andExpect(jsonPath("$[1].levelTeam").doesNotExist())
                // 팀은 마지막 소속(마지막으로 뛴 경기의 팀)
                .andExpect(jsonPath("$[1].teamAbbr").value("MIL"));
        mockMvc.perform(get("/api/players/2"))
                .andExpect(jsonPath("$.player.freeAgent").value(true))
                .andExpect(jsonPath("$.player.level").doesNotExist());
        mockMvc.perform(get("/api/home"))
                .andExpect(jsonPath("$.hitters[?(@.nameKo == '배지환')].teamAbbr").value(org.hamcrest.Matchers.contains("MIL")));
        mockMvc.perform(get("/api/my").param("ids", "2"))
                .andExpect(jsonPath("$.players[0].player.freeAgent").value(true));
    }
}
