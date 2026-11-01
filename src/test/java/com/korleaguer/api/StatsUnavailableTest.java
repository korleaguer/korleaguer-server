package com.korleaguer.api;

import com.korleaguer.client.BdlApiException;
import com.korleaguer.client.BdlClient;
import com.korleaguer.client.BdlGame;
import com.korleaguer.client.BdlPlayer;
import com.korleaguer.client.BdlStats;
import com.korleaguer.client.BdlTeam;
import com.korleaguer.client.SampleBdlClient;
import com.korleaguer.client.StatsUnavailableException;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.IngestStatus;
import com.korleaguer.ingest.IngestService;
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
import java.util.ArrayList;
import java.util.List;

import static com.korleaguer.client.BdlFixtures.batting;
import static com.korleaguer.client.BdlFixtures.game;
import static com.korleaguer.client.BdlFixtures.player;
import static com.korleaguer.client.BdlFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 선수 기록(stats)을 받지 못한 상태.
 * - 401/403: 경기만 저장되고, 기록이 없는 끝난 경기는 "결장"이 아니라 "기록 없음"이다.
 * - 기록을 받다가 끊기면: 경기도 저장하지 않는다(한 트랜잭션).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:no-stats-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=김하성",
        "roster.baseball[0].aliases[0]=Ha-Seong Kim"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StatsUnavailableTest {

    private static final BdlTeam ATL = team(2, "ATL");
    private static final BdlTeam NYM = team(18, "NYM");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SwitchableClient client;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GameRepository games;

    /** stats 호출에서 던질 예외를 바꿔 끼울 수 있는 샘플 클라이언트 */
    static class SwitchableClient extends SampleBdlClient {

        volatile RuntimeException statsError = new StatsUnavailableException("401", null);
        final List<BdlGame> gameList;

        SwitchableClient(List<BdlPlayer> players, List<BdlGame> games, List<BdlStats> stats) {
            super(players, games, stats);
            this.gameList = games;
        }

        @Override
        public List<BdlStats> getStats(List<Long> playerIds, List<Integer> seasons, List<Long> gameIds) {
            throw statsError;
        }
    }

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        SwitchableClient switchableClient() {
            BdlPlayer kim = player(2103, "Ha-Seong Kim", "Kim", "Bucheon, South Korea", ATL);
            List<BdlGame> gameList = new ArrayList<>(List.of(
                    game(9101, NYM, ATL, "2026-08-06T23:00:00Z", "regular"),
                    game(9102, NYM, ATL, "2026-08-07T23:00:00Z", "regular")));
            return new SwitchableClient(List.of(kim), gameList, List.of(batting(kim, ATL, 9101, 4, 1)));
        }
    }

    @Test
    void finishedGamesWithoutStatsAreNotAbsences() throws Exception {
        mockMvc.perform(get("/api/home"))
                .andExpect(jsonPath("$.statsAvailable").value(false))
                .andExpect(jsonPath("$.hitters", hasSize(0)))
                .andExpect(jsonPath("$.pitchers", hasSize(0)))
                .andExpect(jsonPath("$.recent.lines", hasSize(1)))
                .andExpect(jsonPath("$.recent.lines[0].played").value(false))
                .andExpect(jsonPath("$.recent.lines[0].summary").value("기록 없음"));

        mockMvc.perform(get("/api/players/1"))
                .andExpect(jsonPath("$.hitting").doesNotExist())
                .andExpect(jsonPath("$.games[*].summary").value(contains("기록 없음", "기록 없음")));
    }

    @Test
    void interruptedIngestSavesNothing() {
        client.statsError = new BdlApiException("network down", null);
        client.gameList.add(game(9103, NYM, ATL, "2026-08-08T23:00:00Z", "regular"));

        assertThat(ingestService.ingestSeason(2026).getStatus()).isEqualTo(IngestStatus.FAILED);
        assertThat(games.existsById(9103L)).isFalse();
    }
}
