package com.korleaguer.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.SeasonType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/players/{id}/same-games(이슈 #217). 샘플 데이터의 경기 기록과 견줘 본다. 지금을 2026-11-01(한국)로 고정 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SameGamesApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private AppearanceRepository appearances;

    private final ObjectMapper json = new ObjectMapper();

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    private JsonNode sameGames(long playerId) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/players/" + playerId + "/same-games"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.season").value(2026))
                .andExpect(jsonPath("$.playerId").value(playerId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @Transactional
    void pairsAreExactlyTheGamesWhereBothPlayersHaveARecord() throws Exception {
        List<Long> ids = players.findAll(Sort.by("id")).stream().map(Player::getId).toList();
        // 기대값: 경기 기록에서 직접 센다(선수 → 그 선수가 나온 정규시즌·포스트시즌 경기)
        Map<Long, Set<Long>> gamesOf = new HashMap<>();
        for (Appearance a : appearances.findSeason(ids, 2026,
                List.of(SeasonType.REGULAR.stored(), SeasonType.POSTSEASON.stored()))) {
            gamesOf.computeIfAbsent(a.getPlayer().getId(), id -> new HashSet<>()).add(a.getGame().getGamePk());
        }

        int pairsSeen = 0;
        for (long me : ids) {
            JsonNode pairs = sameGames(me).get("pairs");
            Set<Long> othersSeen = new HashSet<>();
            int previousGames = Integer.MAX_VALUE;
            for (JsonNode pair : pairs) {
                long other = pair.get("other").get("playerId").asLong();
                assertThat(other).isNotEqualTo(me);
                assertThat(othersSeen.add(other)).as("상대 선수는 한 번만").isTrue();
                Set<Long> expected = new HashSet<>(gamesOf.getOrDefault(me, Set.of()));
                expected.retainAll(gamesOf.getOrDefault(other, Set.of()));

                JsonNode meetings = pair.get("meetings");
                assertThat(pair.get("games").asInt()).isEqualTo(meetings.size()).isEqualTo(expected.size());
                assertThat(pair.get("games").asInt()).as("많이 만난 순").isLessThanOrEqualTo(previousGames);
                previousGames = pair.get("games").asInt();
                int wins = 0;
                int losses = 0;
                String previousStart = "";
                for (JsonNode meeting : meetings) {
                    JsonNode mine = meeting.get("mine");
                    JsonNode theirs = meeting.get("other");
                    assertThat(mine.get("playerId").asLong()).isEqualTo(me);
                    assertThat(theirs.get("playerId").asLong()).isEqualTo(other);
                    assertThat(mine.get("gameId").asLong()).isEqualTo(theirs.get("gameId").asLong());
                    assertThat(expected).contains(mine.get("gameId").asLong());
                    assertThat(mine.get("played").asBoolean()).isTrue();
                    assertThat(theirs.get("played").asBoolean()).isTrue();
                    assertThat(mine.get("startTimeKst").asText()).as("오래된 경기부터")
                            .isGreaterThanOrEqualTo(previousStart);
                    previousStart = mine.get("startTimeKst").asText();
                    if ("W".equals(mine.get("result").asText())) wins++;
                    if ("L".equals(mine.get("result").asText())) losses++;
                }
                assertThat(pair.get("wins").asInt()).isEqualTo(wins);
                assertThat(pair.get("losses").asInt()).isEqualTo(losses);
                pairsSeen++;
            }
            // 같이 나온 경기가 있는 상대는 빠짐없이 나온다
            for (long other : ids) {
                if (other == me) continue;
                Set<Long> both = new HashSet<>(gamesOf.getOrDefault(me, Set.of()));
                both.retainAll(gamesOf.getOrDefault(other, Set.of()));
                assertThat(othersSeen.contains(other)).as("%d-%d", me, other).isEqualTo(!both.isEmpty());
            }
        }
        // 짝은 양쪽에서 한 번씩 보인다
        assertThat(pairsSeen % 2).isZero();
    }

    @Test
    void anUnknownPlayerIs404() throws Exception {
        mockMvc.perform(get("/api/players/999999/same-games")).andExpect(status().isNotFound());
    }
}
