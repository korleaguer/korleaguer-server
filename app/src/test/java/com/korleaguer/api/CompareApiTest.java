package com.korleaguer.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/players/{id}/compare(이슈 #221). 샘플 데이터로 홈 시즌 기록(/api/home hitters)·선수 페이지(saber)와 같은 숫자인지 본다.
 * 지금을 2026-11-01(한국)로 고정
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompareApiTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper json = new ObjectMapper();

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    private JsonNode getJson(String path) throws Exception {
        return json.readTree(mockMvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("비교 상대는 다른 메이저 타자 전원이고 숫자는 홈·선수 페이지와 같다")
    void linesMatchTheHomeSeasonTableAndThePlayerPage() throws Exception {
        // given
        JsonNode home = getJson("/api/home");
        // 메이저 타석이 있는 타자(홈 시즌 기록 줄)
        Map<Long, JsonNode> majorHitters = new HashMap<>();
        for (JsonNode h : home.get("hitters")) {
            if (h.get("plateAppearances").asInt() > 0) {
                majorHitters.put(h.get("playerId").asLong(), h);
            }
        }
        assertThat(majorHitters.size()).as("샘플에 메이저 타자가 둘 이상").isGreaterThanOrEqualTo(2);

        for (long me : majorHitters.keySet()) {
            // when
            JsonNode compare = getJson("/api/players/" + me + "/compare");

            // then
            assertThat(compare.get("season").asInt()).isEqualTo(home.get("season").asInt());
            assertThat(compare.get("playerId").asLong()).isEqualTo(me);
            assertSameNumbers(compare.get("me"), majorHitters.get(me));
            // 선수 페이지의 MLB 공식 wRC+와 같다(없으면 둘 다 null)
            JsonNode saber = getJson("/api/players/" + me).get("saber");
            JsonNode official = saber == null || saber.isNull() ? null : saber.get("wrcPlus");
            if (official == null || official.isNull()) {
                assertThat(compare.get("me").get("wrcPlus").isNull()).isTrue();
            } else {
                assertThat(compare.get("me").get("wrcPlus").asDouble()).isEqualTo(official.asDouble());
            }

            Set<Long> others = new HashSet<>();
            int previous = Integer.MAX_VALUE;
            for (JsonNode other : compare.get("others")) {
                long id = other.get("player").get("id").asLong();
                assertThat(id).isNotEqualTo(me);
                assertThat(others.add(id)).isTrue();
                assertSameNumbers(other, majorHitters.get(id));
                assertThat(other.get("plateAppearances").asInt()).as("타석 많은 순").isLessThanOrEqualTo(previous);
                previous = other.get("plateAppearances").asInt();
            }
            Set<Long> expected = new HashSet<>(majorHitters.keySet());
            expected.remove(me);
            assertThat(others).isEqualTo(expected);
        }
    }

    private static void assertSameNumbers(JsonNode line, JsonNode homeRow) {
        assertThat(homeRow).as("홈 시즌 기록에 있는 타자").isNotNull();
        assertThat(line.get("player").get("nameKo").asText()).isEqualTo(homeRow.get("nameKo").asText());
        for (String field : new String[]{"games", "plateAppearances", "homeRuns", "rbi", "stolenBases"}) {
            assertThat(line.get(field).asInt()).as(field).isEqualTo(homeRow.get(field).asInt());
        }
        for (String field : new String[]{"avg", "ops"}) {
            assertThat(line.get(field).asText()).as(field).isEqualTo(homeRow.get(field).asText());
        }
    }

    @Test
    @DisplayName("투수는 비교할 기록과 상대가 모두 비어 있다")
    void aPitcherHasNoLine() throws Exception {
        // given
        JsonNode home = getJson("/api/home");
        long pitcher = home.get("pitchers").get(0).get("playerId").asLong();

        // when
        JsonNode compare = getJson("/api/players/" + pitcher + "/compare");

        // then
        assertThat(compare.get("me").isNull()).isTrue();
        assertThat(compare.get("others")).isEmpty();
    }

    @Test
    @DisplayName("없는 선수의 비교를 열면 HTTP 404를 준다")
    void anUnknownPlayerIs404() throws Exception {
        // when // then
        mockMvc.perform(get("/api/players/999999/compare")).andExpect(status().isNotFound());
    }
}
