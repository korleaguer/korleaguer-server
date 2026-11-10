package com.korleaguer.api;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.domain.IngestKind;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.IngestLogRepository;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 이적한 선수: SD에서 뛰다가 ATL로 옮겼다.
 * - gameLog에 나온 이전 팀(SD)의 시즌 일정도 받아서 SD 기록을 버리지 않는다.
 * - 결장은 그 팀에서 뛴 기간 안의 정규시즌 경기에만 붙인다. 지금 팀(ATL)은 마지막 출전 뒤도 결장이다.
 * - 시범경기는 저장하지 않는다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:transfer-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=김하성",
        "roster.baseball[0].mlb-id=673490"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransferRangeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngestLogRepository ingestLogs;

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        MlbClient transferClient() {
            MlbTeam sd = team(135, "San Diego Padres");
            MlbTeam mia = team(146, "Miami Marlins");
            MlbTeam atl = team(144, "Atlanta Braves");
            MlbTeam nym = team(121, "New York Mets");
            List<MlbScheduleGame> games = List.of(
                    game(9001, mia, sd, "2026-08-01T23:00:00Z", "R"),   // SD 출전
                    game(9002, mia, sd, "2026-08-02T23:00:00Z", "R"),   // SD 결장(기간 안)
                    game(9003, mia, sd, "2026-08-03T23:00:00Z", "R"),   // SD 출전(SD 마지막)
                    game(9004, mia, sd, "2026-08-10T23:00:00Z", "R"),   // 이적 뒤 SD 경기: 줄 없음
                    game(9101, nym, atl, "2026-08-05T23:00:00Z", "R"),  // ATL 첫 출전 전: 줄 없음
                    game(9102, nym, atl, "2026-08-06T23:00:00Z", "R"),  // ATL 출전
                    game(9103, nym, atl, "2026-08-07T23:00:00Z", "R"),  // ATL 결장(기간 안)
                    game(9104, nym, atl, "2026-08-08T23:00:00Z", "R"),  // ATL 출전(마지막)
                    game(9105, nym, atl, "2026-08-09T23:00:00Z", "R"),  // 지금 팀, 마지막 출전 뒤: 결장
                    game(9106, nym, atl, "2026-03-10T17:00:00Z", "S")); // 시범경기: 저장하지 않음
            return new SampleMlbClient(SampleMlbClient.Data.empty()
                    .withPeople(List.of(hitter(673490, "Ha-Seong Kim", atl)))
                    .withSchedule(games)
                    .withHittingLogs(List.of(
                            batting(673490, sd, games.get(0), 4, 1),
                            batting(673490, sd, games.get(2), 4, 2),
                            batting(673490, atl, games.get(5), 3, 1),
                            batting(673490, atl, games.get(7), 4, 0),
                            batting(673490, sd, 9999, "2026", 4, 1)))); // 일정에 없는 경기: 건너뛴다
        }
    }

    @Test
    void absenceOnlyWithinStints() throws Exception {
        mockMvc.perform(get("/api/players/1"))
                .andExpect(jsonPath("$.player.nameEn").value("Ha-Seong Kim"))
                .andExpect(jsonPath("$.player.teamAbbr").value("ATL"))
                .andExpect(jsonPath("$.games[*].gameId").value(contains(9105, 9104, 9103, 9102, 9003, 9002, 9001)))
                .andExpect(jsonPath("$.games[*].played").value(contains(false, true, false, true, true, false, true)))
                .andExpect(jsonPath("$.games[*].teamAbbr").value(contains("ATL", "ATL", "ATL", "ATL", "SD", "SD", "SD")))
                .andExpect(jsonPath("$.games[0].summary").value("결장"))
                .andExpect(jsonPath("$.games[5].summary").value("결장"))
                // SD 기록도 시즌 성적에 들어간다: 15타수 4안타
                .andExpect(jsonPath("$.hitting.atBats").value(15))
                .andExpect(jsonPath("$.hitting.hits").value(4));
    }

    @Test
    void homeShowsAbsenceForCurrentTeamAfterLastAppearance() throws Exception {
        mockMvc.perform(get("/api/home"))
                .andExpect(jsonPath("$.recent.isFallback").value(true))
                .andExpect(jsonPath("$.recent.dateKst").value("2026-08-10"))
                .andExpect(jsonPath("$.recent.lines", hasSize(1)))
                .andExpect(jsonPath("$.recent.lines[0].gameId").value(9105))
                .andExpect(jsonPath("$.recent.lines[0].summary").value("결장"));
    }

    @Test
    void startupRequestsAndSkippedLinesAreReported() {
        IngestLog startup = ingestLogs.findAll().stream()
                .filter(l -> l.getKind() == IngestKind.SCHEDULED)
                .findFirst()
                .orElseThrow();

        // 선수 정보 1 + 이동 기록 1 + gameLog 2(타격·투구) + 시즌 일정 1(SD·ATL 한 번에)
        // + 선발·교체 채우기 박스스코어 4(뛴 경기마다 한 번)
        assertThat(startup.getRequestsUsed()).isEqualTo(9);
        assertThat(startup.getMessage()).contains("선수 기록 4개", "건너뛴 기록 1개");
    }
}
