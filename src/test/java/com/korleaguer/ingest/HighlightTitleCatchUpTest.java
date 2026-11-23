package com.korleaguer.ingest;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameContent;
import com.korleaguer.client.MlbPlayByPlay;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.config.DataMode;
import com.korleaguer.domain.GameDetail;
import com.korleaguer.domain.HighlightTitleRepository;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.IngestLogRepository;
import com.korleaguer.domain.IngestStatus;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerGameDetailRepository;
import com.korleaguer.domain.PlayerRepository;
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
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 따라잡기 안의 하이라이트 제목 번역 단계(이슈 #237)를 앱 전체로 본다: 수집이 경기 상세를 저장하며 제목을 넘기고, 같은 따라잡기가 옮겨 저장하고,
 * 화면용 API는 저장된 번역만 읽는다. 샘플 경기에 가짜 MLB 타석 기록·영상 목록(이정후 2026-09-18 실제 응답)을 붙였다.
 * 테스트는 샘플 모드라 번역 단계가 꺼져 있으므로, 번역 단계만 MLB 모드와 가짜 번역기로 바꿔 넣는다.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:highlight-title-catch-up-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HighlightTitleCatchUpTest {

    private static final String TITLE = "Jung Hoo Lee's solo home run (10)";

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GameDetailIngest gameDetailIngest;

    @Autowired
    private HighlightTitleIngest highlightTitleIngest;

    @Autowired
    private HighlightTitleIngestTest.FakeTranslator translator;

    @Autowired
    private HighlightTitleRepository titles;

    @Autowired
    private PlayerGameDetailRepository details;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private IngestLogRepository ingestLogs;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void catchUpTranslatesTitlesOfIngestedDetailsAndTheApiOnlyReadsThem() throws Exception {
        Player lee = players.findByNameKo("이정후").orElseThrow();
        // 앱 시작 때 따라잡기는 경기 상세를 10경기까지만 받는다. 남은 상세를 모두 받고 따라잡기를 한 번 더 돈다
        gameDetailIngest.fill(1000);
        ingestService.catchUp(false);

        // 이정후의 모든 경기 상세에 같은 제목이 있다: 한 번만 옮기고, 이정후 이름을 넘긴다
        assertThat(details.count()).isGreaterThan(10);
        assertThat(translator.asked).containsExactly(TITLE);
        assertThat(translator.players).containsExactly("이정후/" + lee.getNameEn());
        assertThat(titles.findByTitleEn(TITLE)).hasValueSatisfying(
                row -> assertThat(row.getTitleKo()).isEqualTo("옮긴 제목 1"));
        // 번역한 수는 그 실행의 수집 기록에 남는다
        assertThat(ingestLogs.findAll()).anySatisfy(
                log -> assertThat(log.getMessage()).contains("하이라이트 제목 번역 1개"));

        // 화면용 API는 저장된 번역을 붙인다. 화면 요청으로는 번역기를 부르지 않는다
        mockMvc.perform(get("/api/players/" + lee.getId() + "/games/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.highlights[0].title").value(TITLE))
                .andExpect(jsonPath("$.highlights[0].titleKo").value("옮긴 제목 1"));
        assertThat(translator.asked).hasSize(1);

        // 할 일이 없는 스케줄 실행은 기록을 남기지 않는다
        IngestLog idle = ingestService.catchUp(false);
        assertThat(idle.getMessage()).isEqualTo("할 일 없음");
        assertThat(ingestLogs.findById(idle.getId())).isEmpty();
        assertThat(translator.asked).hasSize(1);

        // MLB를 부르지 않았어도 제목을 옮긴 실행은 기록을 남긴다(MLB 요청 0번)
        String later = "Jung Hoo Lee's RBI single";
        highlightTitleIngest.offer(new GameDetail(lee.getId(), 1001, "HITTER", true, true, List.of(),
                List.of(new GameDetail.GameHighlight(later, null, 20, null, null, null))), lee);
        IngestLog run = ingestService.catchUp(false);
        assertThat(run.getMessage()).isEqualTo("하이라이트 제목 번역 1개");
        assertThat(run.getRequestsUsed()).isZero();
        assertThat(ingestLogs.findById(run.getId())).isPresent();

        // 번역기가 답하지 못해도 따라잡기는 성공으로 끝난다(번역은 부가 기능)
        translator.fail = true;
        highlightTitleIngest.offer(new GameDetail(lee.getId(), 1001, "HITTER", true, true, List.of(),
                List.of(new GameDetail.GameHighlight("Jung Hoo Lee's sliding catch", null, 20, null, null, null))), lee);
        IngestLog failed = ingestService.catchUp(false);
        assertThat(failed.getStatus()).isEqualTo(IngestStatus.SUCCESS);
        assertThat(failed.getMessage()).isEqualTo("할 일 없음");
        assertThat(translator.asked).hasSize(3);
    }

    @TestConfiguration
    static class Scenario {

        private static final JsonMapper JSON = JsonMapper.builder().build();

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        HighlightTitleIngestTest.FakeTranslator fakeTranslator() {
            return new HighlightTitleIngestTest.FakeTranslator();
        }

        /** 번역 단계만 MLB 모드로(샘플 모드면 번역하지 않는다), 가짜 번역기로 */
        @Bean
        @Primary
        HighlightTitleIngest translatingIngest(HighlightTitleRepository titles, PlayerGameDetailRepository details,
                                               PlayerRepository players, HighlightTitleIngestTest.FakeTranslator translator,
                                               JsonMapper json, Clock clock) {
            return new HighlightTitleIngest(titles, details, players, translator, DataMode.MLB, json, clock);
        }

        @Bean
        @Primary
        MlbClient detailClient(JsonMapper jsonMapper) {
            return new SampleMlbClient(jsonMapper) {
                @Override
                public MlbPlayByPlay getPlayByPlay(long gamePk) {
                    return read("playbyplay-823898.json", MlbPlayByPlay.class);
                }

                @Override
                public MlbGameContent getGameContent(long gamePk) {
                    return read("content-823898.json", MlbGameContent.class);
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
