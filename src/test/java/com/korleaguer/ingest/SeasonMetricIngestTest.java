package com.korleaguer.ingest;

import com.korleaguer.client.MlbClient;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.domain.PlayerSeasonMetricRepository;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비시즌 따라잡기(lookAhead)가 시즌 성적표용 시즌 합계·고급 지표를 받는지, 하루 한 번만 묻는지 확인한다(샘플 모드).
 * 앱이 뜰 때의 따라잡기(lookAhead 없음)는 받지 않는다.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:season-metric-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeasonMetricIngestTest {

    private static final Instant START = Instant.parse("2026-11-01T11:00:00Z");

    @Autowired
    private IngestService ingestService;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private PlayerSeasonMetricRepository metrics;

    @Autowired
    private MlbClient client;

    @Autowired
    private MutableClock clock;

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    @Test
    @Order(1)
    void startupCatchUpDoesNotFetchMetrics() {
        assertThat(metrics.count()).isZero();
    }

    @Test
    @Order(2)
    void offSeasonCatchUpStoresTotalsAndMetrics() {
        IngestLog log = ingestService.catchUp(false, true);

        assertThat(log.getMessage()).contains("시즌 지표 6명");
        PlayerSeasonMetric lee = metric("이정후", 2026, PlayerSeasonMetric.Group.HITTING);
        assertThat(lee.getPlateAppearances()).isEqualTo(579);
        assertThat(lee.getWrcPlus()).isEqualTo(101.8);
        assertThat(lee.getWar()).isEqualTo(0.97);
        assertThat(lee.getInningsOuts()).isNull();
        assertThat(metric("이정후", 2025, PlayerSeasonMetric.Group.HITTING).getWar()).isEqualTo(2.0);

        PlayerSeasonMetric go = metric("고우석", 2026, PlayerSeasonMetric.Group.PITCHING);
        assertThat(go.getInningsOuts()).isEqualTo(12);
        assertThat(go.getGamesStarted()).isZero();
        assertThat(go.getEraMinus()).isEqualTo(216.1);

        // 앞 3시즌 중 메이저 기록이 있는 시즌만 줄이 생긴다(김하성 2025는 샘플에 없다)
        long kim = players.findByNameKo("김하성").orElseThrow().getId();
        assertThat(metrics.findSeasons(List.of(kim), 2023, 2026))
                .extracting(PlayerSeasonMetric::getSeason)
                .containsExactlyInAnyOrder(2023, 2024, 2026);
    }

    @Test
    @Order(3)
    void sameDayIsNotAskedAgain() {
        long before = client.requestCount();

        IngestLog log = ingestService.catchUp(false, true);

        assertThat(client.requestCount()).isEqualTo(before);
        assertThat(log.getMessage()).doesNotContain("시즌 지표");
    }

    @Test
    @Order(4)
    void nextDayAsksAgain() {
        clock.set(START.plus(Duration.ofDays(1)));

        IngestLog log = ingestService.catchUp(false, true);

        assertThat(log.getMessage()).contains("시즌 지표 6명");
        assertThat(metric("송성문", 2026, PlayerSeasonMetric.Group.HITTING).getFetchedAt()).isEqualTo(clock.instant());
    }

    private PlayerSeasonMetric metric(String nameKo, int season, PlayerSeasonMetric.Group group) {
        Player player = players.findByNameKo(nameKo).orElseThrow();
        return metrics.find(player.getId(), season, group).orElseThrow();
    }
}
