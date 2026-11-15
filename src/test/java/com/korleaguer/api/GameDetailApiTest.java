package com.korleaguer.api;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameContent;
import com.korleaguer.client.MlbPlayByPlay;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.domain.PlayerGameDetailRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 경기 상세 API. 샘플 데이터(경기 1001…)에 가짜 MLB 타석 기록(이정후 2026-09-18 실제 응답)을 붙여 본다.
 * 시계는 2026-11-01(샘플 경기는 9월에 끝남 → 하루 넘게 지나 받으므로 굳힌다).
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:game-detail-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GameDetailApiTest {

    /** 실패하는 경기(MLB 오류) */
    static final long BROKEN_GAME = 1002;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Fakes fakes;

    @Autowired
    private PlayerGameDetailRepository details;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void hitterDetailIsFetchedOnceThenReadFromTheDb() throws Exception {
        int before = fakes.mlbCalls.get();

        mockMvc.perform(get("/api/players/1/games/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.clips").value(true))
                .andExpect(jsonPath("$.role").value("HITTER"))
                .andExpect(jsonPath("$.plays", hasSize(4)))
                .andExpect(jsonPath("$.plays[0].resultKo").value("홈런"))
                .andExpect(jsonPath("$.plays[0].exitKmh").value(157))
                .andExpect(jsonPath("$.highlights", hasSize(1)))
                .andExpect(jsonPath("$.highlights[0].durationSec").value(30));
        // 타석별 기록 + 영상 목록
        assertThat(fakes.mlbCalls.get() - before).isEqualTo(2);
        assertThat(details.findByPlayerIdAndGamePk(1, 1001)).hasValueSatisfying(d -> assertThat(d.isComplete()).isTrue());

        mockMvc.perform(get("/api/players/1/games/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plays", hasSize(4)));
        assertThat(fakes.mlbCalls.get() - before).isEqualTo(2);
    }

    @Test
    void clipIsAskedOnlyForPlaysOfThatDetailAndKept() throws Exception {
        String body = mockMvc.perform(get("/api/players/1/games/1001")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String playId = json.readTree(body).at("/plays/0/playId").asString();
        fakes.savantAsked.clear();

        mockMvc.perform(get("/api/players/1/games/1001/clips/" + playId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mp4Url").value(Fakes.CLIP));
        mockMvc.perform(get("/api/players/1/games/1001/clips/" + playId)).andExpect(status().isOk());
        assertThat(fakes.savantAsked).containsExactly(playId);

        // 그 경기 상세에 없는 공 번호, 공 번호가 아닌 것은 Savant에 묻지 않는다
        mockMvc.perform(get("/api/players/1/games/1001/clips/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/players/1/games/1001/clips/not-a-play")).andExpect(status().isNotFound());
        assertThat(fakes.savantAsked).containsExactly(playId);
    }

    @Test
    void minorGameHasPlaysButNoVideos() throws Exception {
        long minorGame = firstMinorGame(5);
        int before = fakes.mlbCalls.get();

        mockMvc.perform(get("/api/players/5/games/" + minorGame))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PITCHER"))
                .andExpect(jsonPath("$.clips").value(false))
                .andExpect(jsonPath("$.highlights", hasSize(0)));
        // 마이너는 영상 목록을 묻지 않는다(타석별 기록만)
        assertThat(fakes.mlbCalls.get() - before).isEqualTo(1);
    }

    @Test
    void notThePlayersGameIs404() throws Exception {
        mockMvc.perform(get("/api/players/1/games/999999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/players/999/games/1001")).andExpect(status().isNotFound());
    }

    @Test
    void mlbFailureIsUnavailableAndNotKept() throws Exception {
        mockMvc.perform(get("/api/players/1/games/" + BROKEN_GAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.plays", hasSize(0)));
        assertThat(details.findByPlayerIdAndGamePk(1, BROKEN_GAME)).isEmpty();
    }

    private long firstMinorGame(long playerId) throws Exception {
        String body = mockMvc.perform(get("/api/players/" + playerId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode minors = json.readTree(body).get("minorGames");
        assertThat(minors.isEmpty()).isFalse();
        return minors.get(0).get("gamePk").asLong();
    }

    /** 가짜 MLB(샘플 + 타석별 기록·영상 목록)와 가짜 Savant. 부른 수를 센다 */
    static class Fakes {
        static final String CLIP = "https://sporty-clips.mlb.com/test.mp4";
        final AtomicInteger mlbCalls = new AtomicInteger();
        final List<String> savantAsked = new ArrayList<>();
    }

    @TestConfiguration
    static class Scenario {

        private static final JsonMapper JSON = JsonMapper.builder().build();

        @Bean
        Fakes fakes() {
            return new Fakes();
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        MlbClient detailClient(JsonMapper jsonMapper, Fakes fakes) {
            return new SampleMlbClient(jsonMapper) {
                @Override
                public MlbPlayByPlay getPlayByPlay(long gamePk) {
                    fakes.mlbCalls.incrementAndGet();
                    if (gamePk == BROKEN_GAME) {
                        throw new MlbApiException("MLB request failed: test", null);
                    }
                    return read("playbyplay-823898.json", MlbPlayByPlay.class);
                }

                @Override
                public MlbGameContent getGameContent(long gamePk) {
                    fakes.mlbCalls.incrementAndGet();
                    return read("content-823898.json", MlbGameContent.class);
                }
            };
        }

        @Bean
        @Primary
        SavantClips fakeSavant(Fakes fakes) {
            return new SavantClips(null) {
                @Override
                public Lookup find(String playId) {
                    fakes.savantAsked.add(playId);
                    return new Lookup(true, Optional.of(Fakes.CLIP));
                }
            };
        }

        private static <T> T read(String name, Class<T> type) {
            try (InputStream in = new ClassPathResource("mlb/" + name).getInputStream()) {
                return JSON.readValue(in, type);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
