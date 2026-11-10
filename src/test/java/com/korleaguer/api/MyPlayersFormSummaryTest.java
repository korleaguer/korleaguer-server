package com.korleaguer.api;

import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.PlayerRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 최근 폼 요약(이슈 #33)을 샘플 데이터로 본다. 2026-09-27 22:00 KST.
 * 고우석은 지금 소속이 마이너 팀(St. Paul Saints)이고, 샘플 마이너 경기 12번 등판(9월 9일 ~ 20일, 지어낸 값)이 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyPlayersFormSummaryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MinorGameLogRepository minorGameLogs;

    @Autowired
    private PlayerRepository players;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-27T13:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void minorLeaguerCardUsesMinorGames() throws Exception {
        // 마이너 경기는 한국 날짜로 다음 날이라, 최근 7일(9/21 ~ 9/27)에는 9월 20일(미국) 등판 하나만 든다.
        mockMvc.perform(get("/api/my").param("ids", "5").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[0].status.kind").value("MINORS"))
                .andExpect(jsonPath("$.players[0].status.level").value("AAA"))
                .andExpect(jsonPath("$.players[0].status.teamName").value("St. Paul Saints"))
                .andExpect(jsonPath("$.players[0].status.since").value("2026-03-24"))
                .andExpect(jsonPath("$.players[0].basis").value("RECENT"))
                .andExpect(jsonPath("$.players[0].level").value("AAA"))
                .andExpect(jsonPath("$.players[0].play").doesNotExist())
                .andExpect(jsonPath("$.players[0].pitching.games").value(1))
                .andExpect(jsonPath("$.players[0].pitching.era").value(0.0))
                .andExpect(jsonPath("$.players[0].games.length()").value(1))
                .andExpect(jsonPath("$.players[0].games[0].teamKo").value("St. Paul Saints"))
                .andExpect(jsonPath("$.players[0].games[0].startTimeKst").value("2026-09-21T08:00:00+09:00"))
                .andExpect(jsonPath("$.players[0].games[0].played").value(true))
                .andExpect(jsonPath("$.players[0].fact.tone").value("HOT"))
                .andExpect(jsonPath("$.players[0].fact.text").value("트리플A 최근 5번 등판 중 4번 무실점"));
    }

    @Test
    void majorLeaguerCardHasPlayLineAndNoBadge() throws Exception {
        mockMvc.perform(get("/api/my").param("ids", "1").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[0].status").doesNotExist())
                .andExpect(jsonPath("$.players[0].basis").value("RECENT"))
                .andExpect(jsonPath("$.players[0].level").value("MLB"))
                .andExpect(jsonPath("$.players[0].play.recent").isArray())
                .andExpect(jsonPath("$.players[0].seasonTotals").doesNotExist());
    }

    @Test
    void minorGameLogIsStoredOncePerGame() {
        long go = players.findByNameKo("고우석").orElseThrow().getId();
        List<MinorGameLog> logs = minorGameLogs.findSeason(List.of(go), 2026);
        assertThat(logs).hasSize(12);
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.getLevel()).isEqualTo("AAA");
            assertThat(log.getPitching()).isNotNull();
            assertThat(log.getBatting()).isNull();
        });
        assertThat(logs.getFirst().getGameDate()).isBefore(logs.getLast().getGameDate());
    }
}
