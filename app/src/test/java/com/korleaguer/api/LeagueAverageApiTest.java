package com.korleaguer.api;

import com.korleaguer.api.season.LeagueEnv;

import com.korleaguer.api.season.LeagueEnvironments;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbStat;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.config.ResponseCaches;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.LeagueAverage;
import com.korleaguer.domain.LeagueAverageRepository;
import com.korleaguer.ingest.IngestService;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 리그 평균을 수집이 받아 DB에 두고 화면용 API는 DB만 읽는다(이슈 #233). 샘플 데이터에 팀 합계(teams/stats)만 더한 가짜
 * 클라이언트로 앱 전체를 띄워 확인한다. 지금은 2026-11-01(비시즌).
 * 샘플 클라이언트는 팀 합계를 주지 않아 다른 테스트에서는 줄이 생기지 않고 화면 계산은 표준값을 쓴다.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:league-average-api-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LeagueAverageApiTest {

    private static final Instant START = Instant.parse("2026-11-01T11:00:00Z");
    /** 가짜 클라이언트에 팀 합계를 물은 횟수 */
    private static final AtomicInteger TOTALS_CALLS = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private LeagueAverageRepository averages;

    @Autowired
    private LeagueEnvironments leagues;

    @Autowired
    private MlbClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ResponseCaches responseCaches;

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }

        /** 샘플 데이터 + 단계마다 다른 팀 합계. 실제 클라이언트처럼 요청 수를 센다 */
        @Bean
        @Primary
        MlbClient withTeamTotals(JsonMapper jsonMapper) {
            return new SampleMlbClient(jsonMapper) {
                @Override
                public List<MlbGameLogSplit> getTeamSeasonTotals(int sportId, Group group, int season) {
                    TOTALS_CALLS.incrementAndGet();
                    getSeason(season); // 요청 하나로 센다(값은 쓰지 않는다)
                    return group == Group.HITTING ? List.of(team(hitting(sportId))) : List.of(team(pitching(sportId)));
                }
            };
        }
    }

    @Test
    @DisplayName("서버를 켜면 화면에 필요한 시즌과 리그 단계(MLB·AAA)의 평균만 받아 둔다")
    @Order(1)
    void startupCatchUpStoresTheCombinationsTheScreensAsk() {
        // when // then
        // 샘플 데이터: 메이저 기록은 2026, 마이너 경기는 2026 트리플A뿐이다. 조합마다 타격·투구 한 번씩 물었다
        assertThat(averages.findAll()).extracting(row -> row.getSeason() + " " + row.getLevel())
                .containsExactlyInAnyOrder("2026 MLB", "2026 AAA");
        assertThat(TOTALS_CALLS.get()).isEqualTo(4);
        LeagueAverage major = averages.findBySeasonAndLevel(2026, "MLB").orElseThrow();
        assertThat(major.getFetchedAt()).isEqualTo(START);
        // 메이저(sportId 1) 팀 합계: 타석 6,200·득점 701, 아웃 4,320·자책 641
        assertThat(major.values().runsPerPa()).isCloseTo(701 / 6200.0, within(1e-12));
        assertThat(major.values().era()).isCloseTo(641 * 27.0 / 4320, within(1e-12));
    }

    @Test
    @DisplayName("화면은 저장된 리그 평균을 쓰고 MLB에 묻지 않는다")
    @Order(2)
    void screensReadTheStoredValuesWithoutCallingMlb() throws Exception {
        // given
        long requests = client.requestCount();
        LeagueAverage.Values tripleA = averages.findBySeasonAndLevel(2026, "AAA").orElseThrow().values();

        // when // then
        assertThat(leagues.get(2026, "AAA"))
                .isEqualTo(new LeagueEnv(tripleA.woba(), tripleA.runsPerPa(), tripleA.era()));
        // 서버를 다시 띄운 것처럼 새로 만든(메모리가 빈) 객체도 DB 값을 그대로 쓴다
        assertThat(new LeagueEnvironments(averages).of(2026, List.of("MLB", "AAA")))
                .isEqualTo(leagues.of(2026, List.of("MLB", "AAA")))
                .doesNotContainValue(LeagueEnv.STANDARD);
        // 받지 않은 조합은 표준값
        assertThat(leagues.get(2025, "MLB")).isSameAs(LeagueEnv.STANDARD);

        // when
        mockMvc.perform(get("/api/home"));
        mockMvc.perform(get("/api/players/timeline"));
        mockMvc.perform(get("/api/players/timeline").param("season", "2025"));
        mockMvc.perform(get("/api/my").param("ids", "1,2,3,4,5,6").param("days", "30"));

        // then
        assertThat(client.requestCount()).isEqualTo(requests);
        assertThat(TOTALS_CALLS.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("비시즌에는 하루 뒤 다시 수집해도 리그 평균을 새로 받지 않는다")
    @Order(3)
    void offSeasonCatchUpDoesNotFetchAgain() {
        // when
        ingestService.catchUp(false, true);

        // given
        clock.set(START.plus(Duration.ofDays(1)));

        // when
        ingestService.catchUp(false, true);

        // then
        assertThat(TOTALS_CALLS.get()).isEqualTo(4);
        assertThat(averages.findBySeasonAndLevel(2026, "MLB").orElseThrow().getFetchedAt()).isEqualTo(START);
    }

    @Test
    @DisplayName("없던 리그 평균을 다음 수집이 받으면 ERA+와 wRC+도 따라 바뀐다")
    @Order(4)
    void numbersFollowTheStoredAveragesAfterTheNextIngest() throws Exception {
        // given
        // 줄이 없으면 표준값(평균자책점 4.20, wOBA .310)으로 계산하고 MLB는 부르지 않는다
        averages.deleteAll();
        responseCaches.clearAll("시험: 리그 평균 줄을 지움");
        long requests = client.requestCount();

        // when // then
        // 고우석(메이저 5아웃 2자책 = 평균자책점 10.80): 4.20 ÷ 10.80 = 38.9
        assertThat(majorEraPlus()).isEqualTo(38.9);
        int tripleAStandard = minorWrcPlus();
        assertThat(client.requestCount()).isEqualTo(requests);
        assertThat(averages.count()).isZero();

        // when
        // 다음 수집이 없는 줄을 받는다. 리그 평균만 바뀌어도 응답 캐시가 비워져(DataChanges) 숫자가 저장된 평균을 따른다
        IngestLog log = ingestService.catchUp(false, true);

        // then
        assertThat(log.getMessage()).isEqualTo("리그 평균 2개");
        assertThat(log.getRequestsUsed()).isEqualTo(4);
        // 메이저 리그 평균자책점 641 × 27 ÷ 4,320 = 4.00625 → 37.1
        assertThat(majorEraPlus()).isEqualTo(37.1);
        // 트리플A 리그 wOBA가 표준값보다 높아(.321) 같은 기록의 wRC+가 내려간다
        assertThat(minorWrcPlus()).isLessThan(tripleAStandard);
    }

    /** 고우석(선수 5)의 내 선수 카드: 메이저 기간 ERA+(MLB 리그 평균 대비) */
    private double majorEraPlus() throws Exception {
        JsonNode card = json.readTree(mockMvc.perform(get("/api/my").param("ids", "5").param("days", "30"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("players").get(0);
        assertThat(card.get("pitching").get("plusLevel").asString()).isEqualTo("MLB");
        return card.get("pitching").get("eraPlus").asDouble();
    }

    /** 배지환(선수 6)의 비시즌 홈 성적표: 트리플A 경기로 낸 시즌 wRC+(트리플A 리그 평균 대비) */
    private int minorWrcPlus() throws Exception {
        JsonNode reports = json.readTree(mockMvc.perform(get("/api/home")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("offseason").get("report");
        for (JsonNode report : reports) {
            if (report.get("playerId").asLong() == 6) {
                assertThat(report.get("level").asString()).isEqualTo("MINORS");
                return report.get("wrcPlus").asInt();
            }
        }
        throw new AssertionError("no season report for player 6");
    }

    private static MlbGameLogSplit team(MlbStat stat) {
        return new MlbGameLogSplit("2026", null, null, null, null, null, null, null, null, stat, null);
    }

    /** 단계마다 값이 다르게: 득점에 sportId를 더한다 */
    private static MlbStat hitting(int sportId) {
        return new MlbStat(162, null, 5500, 700 + sportId, 1400, 280, 25, 180, null, 520, null, 60, null, 45, 6200,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static MlbStat pitching(int sportId) {
        return new MlbStat(162, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                4320, null, 640 + sportId, null, null, null, null, null, null);
    }
}
