package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbSeasonSplit;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.domain.IngestKind;
import com.korleaguer.domain.IngestLogRepository;
import com.korleaguer.domain.PlayerCareerSeasonRepository;
import com.korleaguer.domain.PlayerLeaguePositionRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 선수의 시즌별 기록(yearByYear)만 계속 실패해도 위치는 저장하고(오늘 받았음), 같은 날 리그 전체를 다시 받지 않는다(이슈 #133 리뷰).
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:league-career-failure-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class LeaguePositionCareerFailureTest {

    private static final long HA_SEONG_KIM = 673490L;

    @Autowired
    private LeaguePositionRefresher refresher;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private PlayerLeaguePositionRepository positions;

    @Autowired
    private PlayerCareerSeasonRepository careerSeasons;

    @Autowired
    private IngestLogRepository ingestLogs;

    @Autowired
    private MlbClient client;

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        MlbClient failingYearByYear(JsonMapper jsonMapper) {
            return new SampleMlbClient(jsonMapper) {
                @Override
                public List<MlbSeasonSplit> getYearByYear(long personId, Group group) {
                    if (personId == HA_SEONG_KIM) {
                        super.getYearByYear(personId, group); // 요청은 센다
                        throw new MlbApiException("MLB request failed: yearByYear (500)", null);
                    }
                    return super.getYearByYear(personId, group);
                }
            };
        }
    }

    @Test
    void careerFailureKeepsThePositionAndDoesNotRefetchTheLeagueToday() {
        String startup = ingestLogs.findAll().stream()
                .filter(l -> l.getKind() == IngestKind.SCHEDULED)
                .findFirst().orElseThrow().getMessage();
        assertThat(startup).contains("MLB 안 위치 6명, 시즌별 기록 실패 1명");

        long kim = players.findByNameKo("김하성").orElseThrow().getId();
        assertThat(positions.find(kim, 2026, PlayerSeasonMetric.Group.HITTING).orElseThrow().getWrcPlus())
                .isEqualTo(7.3);
        assertThat(careerSeasons.findNewestFirst(kim, PlayerSeasonMetric.Group.HITTING)).isEmpty();

        long before = client.requestCount();
        assertThat(refresher.refresh()).isEmpty();
        assertThat(client.requestCount()).isEqualTo(before);
    }
}
