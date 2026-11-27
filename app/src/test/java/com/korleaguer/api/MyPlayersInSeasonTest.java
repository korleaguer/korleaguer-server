package com.korleaguer.api;

import com.jayway.jsonpath.JsonPath;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.SeasonType;
import com.korleaguer.domain.Team;
import com.korleaguer.domain.TeamRepository;
import com.korleaguer.support.GameStatuses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 시즌 중이면 내 선수 기간은 선수마다 오늘(한국 날짜)까지다(isFallback=false). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyPlayersInSeasonTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private GameRepository games;

    @Autowired
    private AppearanceRepository appearances;

    /** 진행 중 경기를 넣고 뜬 응답이 캐시에 남아 다른 테스트로 새지 않게 비운다 */
    @AfterEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    // 2026-09-27 22:00 KST. 마지막 경기(9월 27일 KST)가 오늘이라 대신하지 않는다.
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-27T13:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("시즌 중에는 내 선수 기간이 오늘까지다")
    void windowEndsToday() throws Exception {
        // when // then
        mockMvc.perform(get("/api/my").param("ids", "1").param("days", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isFallback").value(false))
                .andExpect(jsonPath("$.days").value(14))
                .andExpect(jsonPath("$.fromKst").value("2026-09-14"))
                .andExpect(jsonPath("$.toKst").value("2026-09-27"))
                .andExpect(jsonPath("$.players[0].games[*].gameId").value(contains(1003, 1002, 1001)))
                .andExpect(jsonPath("$.players[0].isFallback").value(false))
                .andExpect(jsonPath("$.players[0].fromKst").value("2026-09-14"))
                .andExpect(jsonPath("$.players[0].games[*].gameId").value(contains(1003, 1002, 1001)))
                .andExpect(jsonPath("$.players[0].hitting.games").value(3));
    }

    @Test
    @DisplayName("시즌 첫 홈런을 친 경기에 화제 문구가 붙고 결장 경기에는 없다")
    void buzzComesWithCardGames() throws Exception {
        // when // then
        // 이정후 1003: 올 시즌 첫 홈런. 같은 규칙이라 홈 recent.buzz와 같은 문구다(결장 줄 김혜성에는 없다)
        mockMvc.perform(get("/api/my").param("ids", "1,2").param("days", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buzz[?(@.playerId==1 && @.gameId==1003)].text").value(contains("올 시즌 첫 홈런")))
                .andExpect(jsonPath("$.buzz[?(@.playerId==2 && @.gameId==2003)]").isEmpty());
    }

    @Test
    @DisplayName("결장 경기는 숫자 없이 보이고 기간 성적에 들지 않으며 연속 안타를 끊지 않는다")
    void absenceLineHasNoNumbers() throws Exception {
        // when // then
        // 김혜성: 2001 3타수 무안타, 2002 4타수 3안타, 2003 결장
        // 시즌 중에는 구단이 경기하는데 본인이 빠져도 기간을 당기지 않고 결장 줄을 그대로 보인다.
        // → 기간 2경기 7타수 3안타(.429). 결장은 연속 기록을 끊지 않고, 2001 무안타에서 멈춰 연속 1경기
        mockMvc.perform(get("/api/my").param("ids", "2"))
                .andExpect(jsonPath("$.players[0].isFallback").value(false))
                .andExpect(jsonPath("$.players[0].toKst").value("2026-09-27"))
                .andExpect(jsonPath("$.players[0].games[*].gameId").value(contains(2003, 2002, 2001)))
                .andExpect(jsonPath("$.players[0].games[0].played").value(false))
                .andExpect(jsonPath("$.players[0].games[0].summary").value("결장"))
                .andExpect(jsonPath("$.players[0].games[0].batting").doesNotExist())
                .andExpect(jsonPath("$.players[0].games[0].pitching").doesNotExist())
                .andExpect(jsonPath("$.players[0].games[1].batting.hits").value(3))
                .andExpect(jsonPath("$.players[0].hitting.games").value(2))
                .andExpect(jsonPath("$.players[0].hitting.avg").value(0.429))
                .andExpect(jsonPath("$.players[0].streak").value(1));
    }

    @Test
    @Transactional
    @DisplayName("진행 중 경기에서 아직 안타가 없어도 연속 안타 숫자는 끝난 경기까지로 센다")
    void liveGameDoesNotBreakStreak() throws Exception {
        // given
        // 이정후는 9월 26일(미국) 1003에서 안타를 쳐 연속 안타가 이어지는 중이다. 지금(9월 27일 13:00 UTC) 진행 중인
        // 경기에서 2타수 무안타라도, 경기가 끝나기 전에는 한 줄 소식처럼 연속 기록을 끊지 않는다
        clearCaches();
        int before = streakOf("1");
        Player lee = players.findByNameKo("이정후").orElseThrow();
        Team giants = teams.findById(137L).orElseThrow();
        Team rockies = teams.findById(115L).orElseThrow();
        Game live = new Game(99_305L);
        live.update(2026, SeasonType.REGULAR.stored(), Instant.parse("2026-09-27T11:00:00Z"), giants, rockies,
                0, 0, GameStatuses.IN_PROGRESS, null);
        games.save(live);
        Appearance hitlessSoFar = new Appearance(lee, live);
        hitlessSoFar.update(giants, hitless(2), null);
        appearances.save(hitlessSoFar);
        clearCaches();

        // when
        int during = streakOf("1");

        // then
        assertThat(before).isPositive();
        assertThat(during).isEqualTo(before);
    }

    private int streakOf(String ids) throws Exception {
        String body = mockMvc.perform(get("/api/my").param("ids", ids).param("days", "14"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.players[0].streak");
    }

    /** 타수만 있고 안타가 없는 타격 기록(나머지 칸은 0) */
    private static Batting hitless(int atBats) {
        int hits = 0;
        int homeRuns = 0;
        return new Batting(atBats, 0, hits, 0, 0, homeRuns, 0, 0, 0, 0, 0, 0, atBats, 0);
    }
}
