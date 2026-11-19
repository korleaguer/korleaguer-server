package com.korleaguer.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/players/timeline(이슈 #203). 샘플 데이터로 응답 모양을 확인한다. 지금을 2026-11-01(한국)로 고정 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlayerTimelineApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlayerTimelineService timelines;

    private final ObjectMapper json = new ObjectMapper();

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void givesEverySamplePlayerAContinuousBandInPlayerListOrder() throws Exception {
        String body = mockMvc.perform(get("/api/players/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.season").value(2026))
                .andExpect(jsonPath("$.seasons[0]").value(2025))
                .andExpect(jsonPath("$.seasons[1]").value(2026))
                .andExpect(jsonPath("$.from").value("2026-03-01"))
                .andExpect(jsonPath("$.to").value("2026-11-01"))
                .andExpect(jsonPath("$.today").value("2026-11-01"))
                .andExpect(jsonPath("$.inSeason").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode timeline = json.readTree(body);

        JsonNode list = json.readTree(mockMvc.perform(get("/api/players")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(timeline.get("players")).hasSameSizeAs(list);
        for (int i = 0; i < list.size(); i++) {
            JsonNode player = timeline.get("players").get(i);
            assertThat(player.get("playerId").asLong()).isEqualTo(list.get(i).get("id").asLong());
            JsonNode segments = player.get("segments");
            assertThat(segments.get(0).get("from").asText()).isEqualTo("2026-03-01");
            assertThat(segments.get(segments.size() - 1).get("to").asText()).isEqualTo("2026-11-01");
            for (int k = 1; k < segments.size(); k++) {
                assertThat(LocalDate.parse(segments.get(k).get("from").asText()))
                        .isEqualTo(LocalDate.parse(segments.get(k - 1).get("to").asText()).plusDays(1));
            }
            JsonNode moves = player.get("moves");
            assertThat(player.get("lastMoveOn").isNull()).isEqualTo(moves.isEmpty());
            if (!moves.isEmpty()) {
                assertThat(player.get("lastMoveOn").asText()).isEqualTo(moves.get(moves.size() - 1).get("date").asText());
                assertThat(moves.get(0).has("labelKo")).isTrue();
                assertThat(moves.get(0).has("toLevel")).isTrue();
            }
        }
    }

    @Test
    void segmentsAndMovesFollowTheMovesOnThePlayerPage() throws Exception {
        // 샘플 선수 5: 3월 24일 강등 → 9월 20일 콜업 → 10월 6일 이관. 이동은 선수 화면 moves와 같고 날짜 오름차순
        JsonNode five = json.readTree(mockMvc.perform(get("/api/players/timeline")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("players").get(4);
        assertThat(five.get("playerId").asLong()).isEqualTo(5);
        assertThat(five.get("moves")).extracting(m -> m.get("date").asText())
                .containsExactly("2026-03-24", "2026-09-20", "2026-10-02", "2026-10-06");
        assertThat(five.get("segments")).extracting(s -> s.get("kind").asText() + " " + s.get("from").asText())
                .containsExactly("MINORS 2026-03-01", "MLB 2026-09-20", "MINORS 2026-10-06");
        assertThat(five.get("segments").get(0).get("levelKo").asText()).isEqualTo("트리플A");
        assertThat(five.get("segments").get(0).get("teamKo").asText()).isEqualTo("세인트폴");
    }

    @Test
    void seasonNumbersMatchTheOffSeasonReportOnHome() throws Exception {
        // 11월이라 홈은 비시즌 성적표를 준다. 같은 선수·시즌의 wRC+·ERA+·단계는 그 성적표와 같은 값이다
        JsonNode home = json.readTree(mockMvc.perform(get("/api/home")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        JsonNode reports = home.get("offseason").get("report");
        assertThat(reports).isNotEmpty();
        JsonNode players = json.readTree(mockMvc.perform(get("/api/players/timeline")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("players");
        for (JsonNode report : reports) {
            JsonNode stat = null;
            for (JsonNode player : players) {
                if (player.get("playerId").asLong() == report.get("playerId").asLong()) {
                    stat = player.get("stat");
                }
            }
            assertThat(stat).as("stat of %s", report.get("playerId")).isNotNull();
            assertThat(stat.get("level").asText()).isEqualTo(report.get("level").asText());
            assertThat(stat.get("wrcPlus")).isEqualTo(report.get("wrcPlus"));
            assertThat(stat.get("eraPlus")).isEqualTo(report.get("eraPlus"));
        }
    }

    @Test
    void aSeasonWithoutRecordsIsStillOkWithEmptyBands() throws Exception {
        mockMvc.perform(get("/api/players/timeline").param("season", "2019"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.season").value(2019))
                .andExpect(jsonPath("$.from").value("2019-03-01"))
                .andExpect(jsonPath("$.to").value("2020-02-29"))
                .andExpect(jsonPath("$.inSeason").value(false))
                .andExpect(jsonPath("$.players", hasSize(6)))
                .andExpect(jsonPath("$.players[0].segments", hasSize(0)))
                .andExpect(jsonPath("$.players[0].moves", hasSize(0)))
                .andExpect(jsonPath("$.players[0].lastMoveOn").value(nullValue()))
                .andExpect(jsonPath("$.players[0].stat").value(nullValue()));
    }

    @Test
    void aPastSeasonEndsAtTheNextFebruary() throws Exception {
        mockMvc.perform(get("/api/players/timeline").param("season", "2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2025-03-01"))
                .andExpect(jsonPath("$.to").value("2026-02-28"))
                .andExpect(jsonPath("$.inSeason").value(false));
    }

    @Test
    void aSeasonThatIsNotANumberIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/players/timeline").param("season", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid parameter: season"));
        mockMvc.perform(get("/api/players/timeline").param("season", "99999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSameSeasonIsAnsweredFromTheCache() {
        assertThat(timelines.timeline(2026)).isSameAs(timelines.timeline(2026));
        assertThat(timelines.timeline(2025)).isNotSameAs(timelines.timeline(2026));
    }
}
