package com.korleaguer.api;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameContent;
import com.korleaguer.client.MlbPlayByPlay;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.domain.HighlightTitle;
import com.korleaguer.domain.HighlightTitleRepository;
import com.korleaguer.domain.PlayerGameDetail;
import com.korleaguer.domain.PlayerGameDetailRepository;
import com.korleaguer.ingest.GameDetailIngest;
import com.korleaguer.video.PlayVideoFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 경기 상세 API. 샘플 데이터(경기 1001…)에 가짜 MLB 타석 기록(이정후 2026-09-18 실제 응답)을 붙여 본다.
 * 상세는 수집이 미리 받아 두고(GameDetailIngest), API는 DB만 읽는다(이슈 #229). API 요청으로는 MLB를 한 번도 부르지 않는다.
 * 시계는 2026-11-01(샘플 경기는 9월에 끝남 → 하루 넘게 지나 받으므로 굳힌다).
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:game-detail-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GameDetailApiTest {

    /** 실패하는 경기(MLB 오류). 수집이 받지 못해 상세가 없다 */
    static final long BROKEN_GAME = 1002;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Fakes fakes;

    @Autowired
    private GameDetailIngest gameDetailIngest;

    @Autowired
    private PlayerGameDetailRepository details;

    @Autowired
    private HighlightTitleRepository highlightTitles;

    private final JsonMapper json = JsonMapper.builder().build();

    /** 수집이 남은 상세를 모두 받아 둔다(앱 시작 때 따라잡기는 한 번에 10경기까지만 받는다) */
    @BeforeEach
    void ingestDetails() {
        gameDetailIngest.fill(1000);
    }

    @Test
    @DisplayName("저장된 경기 상세는 여러 번 열어도 MLB에 묻지 않고 보여 준다")
    void savedDetailIsReadWithoutCallingMlb() throws Exception {
        // given
        int before = fakes.mlbCalls();

        // when // then
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/players/1/games/1001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.playerId").value(1))
                    .andExpect(jsonPath("$.gamePk").value(1001))
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.clips").value(true))
                    .andExpect(jsonPath("$.role").value("HITTER"))
                    .andExpect(jsonPath("$.plays", hasSize(4)))
                    .andExpect(jsonPath("$.plays[0].resultKo").value("홈런"))
                    .andExpect(jsonPath("$.plays[0].exitKmh").value(157))
                    .andExpect(jsonPath("$.highlights", hasSize(1)))
                    .andExpect(jsonPath("$.highlights[0].durationSec").value(30));
        }
        assertThat(fakes.mlbCalls()).isEqualTo(before);
        assertThat(details.findByPlayerIdAndGamePk(1, 1001)).hasValueSatisfying(d -> assertThat(d.isComplete()).isTrue());
    }

    @Test
    @DisplayName("나중에 저장된 한국어 제목이 이미 받아 둔 하이라이트에 붙는다")
    void highlightKoreanTitleComesFromTheTitleTableAndShowsOnTheAlreadySavedDetail() throws Exception {
        // given
        String title = "Jung Hoo Lee's solo home run (10)";

        // when // then
        mockMvc.perform(get("/api/players/1/games/1001"))
                .andExpect(jsonPath("$.highlights[0].title").value(title))
                .andExpect(jsonPath("$.highlights[0].titleKo").doesNotExist());

        // given
        int before = fakes.mlbCalls();

        // 번역이 나중에 생겨도 이미 굳은 상세(DB)에 바로 붙는다. MLB는 부르지 않는다
        HighlightTitle row = new HighlightTitle(title);
        row.translated("솔로 홈런 (시즌 10호)", "test/t1", Instant.parse("2026-11-01T11:00:00Z"));
        highlightTitles.save(row);
        try {
            // when // then
            mockMvc.perform(get("/api/players/1/games/1001"))
                    .andExpect(jsonPath("$.highlights[0].titleKo").value("솔로 홈런 (시즌 10호)"));
            assertThat(fakes.mlbCalls()).isEqualTo(before);
            // 저장된 상세 JSON에는 한국어 제목을 넣지 않는다
            assertThat(details.findByPlayerIdAndGamePk(1, 1001).orElseThrow().getPayload())
                    .doesNotContain("솔로 홈런");
        } finally {
            highlightTitles.delete(row);
        }
    }

    @Test
    @DisplayName("하이라이트 제목이 모두 비어 있어도 경기 상세가 열리고 한국어 제목은 없다")
    void detailOpensWhenEveryHighlightTitleIsMissing() throws Exception {
        // given
        PlayerGameDetail saved = details.findByPlayerIdAndGamePk(1, 1001).orElseThrow();
        String original = saved.getPayload();
        String untitled = original.replace("\"title\":\"Jung Hoo Lee's solo home run (10)\"", "\"title\":null");
        assertThat(untitled).isNotEqualTo(original);
        saved.update(untitled, true, Instant.parse("2026-11-01T11:00:00Z"));
        details.save(saved);
        try {
            // when // then
            mockMvc.perform(get("/api/players/1/games/1001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.highlights", hasSize(1)))
                    .andExpect(jsonPath("$.highlights[0].title").doesNotExist())
                    .andExpect(jsonPath("$.highlights[0].titleKo").doesNotExist());
        } finally {
            saved.update(original, true, Instant.parse("2026-11-01T11:00:00Z"));
            details.save(saved);
        }
    }

    @Test
    @DisplayName("공 영상은 그 경기 상세에 있는 공만 Savant에 묻고 두 번 묻지 않는다")
    void clipIsAskedOnlyForPlaysOfThatDetailAndKept() throws Exception {
        // given
        String body = mockMvc.perform(get("/api/players/1/games/1001")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String playId = json.readTree(body).at("/plays/0/playId").asString();
        fakes.savantAsked.clear();

        // when // then
        mockMvc.perform(get("/api/players/1/games/1001/clips/" + playId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mp4Url").value(Fakes.CLIP));
        mockMvc.perform(get("/api/players/1/games/1001/clips/" + playId)).andExpect(status().isOk());
        assertThat(fakes.savantAsked).containsExactly(playId);

        // when // then
        // 그 경기 상세에 없는 공 번호, 공 번호가 아닌 것은 Savant에 묻지 않는다
        mockMvc.perform(get("/api/players/1/games/1001/clips/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/players/1/games/1001/clips/not-a-play")).andExpect(status().isNotFound());
        assertThat(fakes.savantAsked).containsExactly(playId);
    }

    @Test
    @DisplayName("마이너 경기는 상세는 있어도 영상이 없고 영상 목록을 묻지 않는다")
    void minorGameHasPlaysButNoVideos() throws Exception {
        // given
        long minorGame = firstMinorGame(5);
        int before = fakes.mlbCalls();

        // when // then
        mockMvc.perform(get("/api/players/5/games/" + minorGame))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.role").value("PITCHER"))
                .andExpect(jsonPath("$.clips").value(false))
                .andExpect(jsonPath("$.highlights", hasSize(0)));
        assertThat(fakes.mlbCalls()).isEqualTo(before);
        // 수집은 마이너 경기의 영상 목록을 묻지 않았다(타석별 기록만 한 번)
        assertThat(fakes.playByPlayAsked).containsOnlyOnce(minorGame);
        assertThat(fakes.contentAsked).doesNotContain(minorGame);
    }

    @Test
    @DisplayName("없는 경기나 없는 선수의 경기 상세를 열면 HTTP 404를 준다")
    void unknownGameOrPlayerIs404() throws Exception {
        // when // then
        mockMvc.perform(get("/api/players/1/games/999999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/players/999/games/1001")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("그 선수 기록이 없는 경기는 빈 상세를, 남의 마이너 경기는 404를 준다")
    void knownGameWithoutThatPlayersRecordIsUnavailable() throws Exception {
        // given
        // 2001은 김혜성·송성문 경기다(둘의 상세는 저장돼 있다). 이정후(1)는 그 경기에 기록이 없다
        assertThat(details.findByPlayerIdAndGamePk(2, 2001)).isPresent();
        int before = fakes.mlbCalls();

        // when // then
        mockMvc.perform(get("/api/players/1/games/2001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(1))
                .andExpect(jsonPath("$.gamePk").value(2001))
                .andExpect(jsonPath("$.role").value("HITTER"))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.clips").value(false))
                .andExpect(jsonPath("$.plays", hasSize(0)))
                .andExpect(jsonPath("$.highlights", hasSize(0)));

        // when // then
        // 다른 선수의 마이너 경기도 그 선수 것이 아니면 모르는 경기다
        mockMvc.perform(get("/api/players/1/games/" + firstMinorGame(5))).andExpect(status().isNotFound());
        assertThat(fakes.mlbCalls()).isEqualTo(before);
    }

    @Test
    @DisplayName("아직 받지 못한 경기는 빈 상세를 주고 그 자리에서 MLB에 묻지 않는다")
    void detailNotIngestedYetIsUnavailableAndTheApiDoesNotFetchIt() throws Exception {
        // given
        // 수집이 받지 못한 경기(MLB 오류)에는 굳지 않은 '없음'만 남는다. API는 그 자리에서 받지 않고 '아직 없음'으로 답한다
        assertThat(details.findByPlayerIdAndGamePk(1, BROKEN_GAME))
                .hasValueSatisfying(row -> assertThat(row.isComplete()).isFalse());
        int before = fakes.mlbCalls();

        // when // then
        mockMvc.perform(get("/api/players/1/games/" + BROKEN_GAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.plays", hasSize(0)));
        assertThat(fakes.mlbCalls()).isEqualTo(before);
        // API 호출로는 달라지지 않는다: 수집이 남긴 굳지 않은 '없음' 그대로
        assertThat(details.findByPlayerIdAndGamePk(1, BROKEN_GAME))
                .hasValueSatisfying(row -> assertThat(row.isComplete()).isFalse());
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
        final List<Long> playByPlayAsked = new ArrayList<>();
        final List<Long> contentAsked = new ArrayList<>();
        final List<String> savantAsked = new ArrayList<>();

        int mlbCalls() {
            return playByPlayAsked.size() + contentAsked.size();
        }
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
                    fakes.playByPlayAsked.add(gamePk);
                    if (gamePk == BROKEN_GAME) {
                        throw new MlbApiException("MLB request failed: test", null);
                    }
                    return read("playbyplay-823898.json", MlbPlayByPlay.class);
                }

                @Override
                public MlbGameContent getGameContent(long gamePk) {
                    fakes.contentAsked.add(gamePk);
                    return read("content-823898.json", MlbGameContent.class);
                }
            };
        }

        @Bean
        @Primary
        PlayVideoFinder fakeSavant(Fakes fakes) {
            return playId -> {
                fakes.savantAsked.add(playId);
                return new PlayVideoFinder.Lookup(true, Optional.of(Fakes.CLIP));
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
