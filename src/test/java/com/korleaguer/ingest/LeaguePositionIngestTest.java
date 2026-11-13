package com.korleaguer.ingest;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbSeasonSplit;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.PlayerCareerSeason;
import com.korleaguer.domain.PlayerCareerSeasonRepository;
import com.korleaguer.domain.PlayerLeaguePosition;
import com.korleaguer.domain.PlayerLeaguePositionRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.domain.Role;
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
import static org.assertj.core.api.Assertions.offset;

/**
 * 따라잡기가 하루 한 번 MLB 안 위치와 시즌별 기록을 받는지 확인한다(샘플 모드, 이슈 #133).
 * 샘플 리그는 우리 선수의 2026 MLB 값과 지어낸 선수 240명씩이라 위치는 실제와 조금 다르다(실제 값은 LeaguePositionsTest 주석).
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:league-position-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LeaguePositionIngestTest {

    private static final Instant START = Instant.parse("2026-11-01T11:00:00Z");

    @Autowired
    private IngestService ingestService;

    @Autowired
    private LeaguePositionRefresher refresher;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private PlayerLeaguePositionRepository positions;

    @Autowired
    private PlayerCareerSeasonRepository careerSeasons;

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
    void startupCatchUpStoresPositions() {
        PlayerLeaguePosition lee = position("이정후", PlayerSeasonMetric.Group.HITTING);
        assertThat(lee.inLeague()).isTrue();
        assertThat(lee.getPlateAppearances()).isEqualTo(579);
        assertThat(lee.getWrcPlus()).isEqualTo(101.8);
        assertThat(lee.getWar()).isEqualTo(0.97);
        assertThat(lee.getBattingRuns()).isEqualTo(1.25);
        assertThat(lee.getBaseRunningRuns()).isEqualTo(0.68);
        assertThat(lee.getFieldingRuns()).isEqualTo(-11.26, offset(1e-9)); // fielding + positional
        // 100타석 이상: 지어낸 선수 224명 + 우리 넷(이정후·김혜성·김하성·송성문)
        assertThat(lee.getPoolSize()).isEqualTo(228);
        assertThat(List.of(lee.getPctOverall(), lee.getPctBatting(), lee.getPctBaseRunning(), lee.getPctFielding()))
                .containsExactly(36, 55, 56, 11);
        assertThat(lee.getPctRunPrevention()).isNull();
        assertThat(lee.getFetchedAt()).isEqualTo(START);

        PlayerLeaguePosition kim = position("김하성", PlayerSeasonMetric.Group.HITTING);
        assertThat(kim.getPctBatting()).isEqualTo(1); // wRC+ 7.3: 가장 낮다

        // 배지환 4타석: 값은 있고 위치는 없다(50타석 미만)
        PlayerLeaguePosition bae = position("배지환", PlayerSeasonMetric.Group.HITTING);
        assertThat(bae.getWrcPlus()).isEqualTo(249.4);
        assertThat(bae.getPctOverall()).isNull();
        assertThat(bae.getPctBatting()).isNull();

        // 고우석 4이닝(12아웃): K% 5/22, BB% 2/22, 10이닝 미만이라 위치는 없다
        PlayerLeaguePosition go = position("고우석", PlayerSeasonMetric.Group.PITCHING);
        assertThat(go.getInningsOuts()).isEqualTo(12);
        assertThat(go.getEraMinus()).isEqualTo(216.1);
        assertThat(go.getFipMinus()).isEqualTo(209.7);
        assertThat(go.getStrikeoutRate()).isEqualTo(5 * 100.0 / 22, offset(1e-9));
        assertThat(go.getWalkRate()).isEqualTo(2 * 100.0 / 22, offset(1e-9));
        assertThat(go.getPoolSize()).isEqualTo(218);
        assertThat(go.getPctOverall()).isNull();
        assertThat(go.getPlateAppearances()).isNull();
    }

    @Test
    @Order(2)
    void startupCatchUpStoresCareerSeasons() {
        long kim = players.findByNameKo("김하성").orElseThrow().getId();
        List<PlayerCareerSeason> seasons = careerSeasons.findNewestFirst(kim, PlayerSeasonMetric.Group.HITTING);

        assertThat(seasons).extracting(PlayerCareerSeason::getSeason).containsExactly(2026, 2024, 2023, 2022);
        PlayerCareerSeason current = seasons.getFirst();
        assertThat(current.getTeamIds()).containsExactly(144L);
        assertThat(current.getGames()).isEqualTo(51);
        assertThat(current.getBattingAvg()).isEqualTo(0.116);
        assertThat(current.getOps()).isEqualTo(0.359);
        // 지금 시즌 지표는 리그 전체 값, 지난 시즌은 선수별 sabermetrics(샘플에 없는 2022는 물었지만 모른다)
        assertThat(current.getWrcPlus()).isEqualTo(7.3);
        assertThat(current.getWar()).isEqualTo(-1.17);
        assertThat(seasons.get(2).getWrcPlus()).isEqualTo(110.0);
        assertThat(seasons.get(3).getWar()).isNull();
        assertThat(seasons.get(3).getMetricsFetchedAt()).isEqualTo(START);
        // 지금 시즌 지표는 '물었음' 표시를 남기지 않는다(시즌이 지나면 선수별 지표를 한 번 다시 묻는다)
        assertThat(current.getMetricsFetchedAt()).isNull();

        long go = players.findByNameKo("고우석").orElseThrow().getId();
        PlayerCareerSeason pitching = careerSeasons.findNewestFirst(go, PlayerSeasonMetric.Group.PITCHING).getFirst();
        assertThat(pitching.getInningsOuts()).isEqualTo(12);
        assertThat(pitching.getEra()).isEqualTo(9.0);
        assertThat(pitching.getStrikeouts()).isEqualTo(5);
        assertThat(pitching.getEraMinus()).isEqualTo(216.1);
        assertThat(pitching.getPlateAppearances()).isNull();
    }

    @Test
    @Order(3)
    void sameDayIsNotAskedAgain() {
        long before = client.requestCount();

        assertThat(refresher.refresh()).isEmpty();
        assertThat(client.requestCount()).isEqualTo(before);
    }

    @Test
    @Order(4)
    void nextDayAsksOnlyTheLeagueAndYearByYear() {
        clock.set(START.plus(Duration.ofDays(1)));
        long before = client.requestCount();

        IngestLog log = ingestService.catchUp(false);

        assertThat(log.getMessage()).contains("MLB 안 위치 6명");
        assertThat(position("이정후", PlayerSeasonMetric.Group.HITTING).getFetchedAt()).isEqualTo(clock.instant());
        // 그날 첫 따라잡기의 다른 단계(선수 정보 1 + 이동 기록 6 + 마이너 경기 수 6)와
        // 리그 전체 타격·투구 4 + yearByYear 6. 지난 시즌 지표는 어제 물었으므로 다시 묻지 않는다.
        assertThat(client.requestCount() - before).isEqualTo(13 + 10);
    }

    @Test
    void tradedSeasonUsesTheCombinedLineAndTeamsInOrder() {
        MlbTeam tampa = new MlbTeam(139L, "Tampa Bay Rays", null, null, null);
        MlbTeam atlanta = new MlbTeam(144L, "Atlanta Braves", null, null, null);
        var totals = LeaguePositionRefresher.careerTotals(List.of(
                hitting("2025", null, tampa, 24, 93, ".214"), hitting("2025", null, atlanta, 24, 98, ".253"),
                hitting("2025", 2, null, 48, 191, ".234"), hitting("2024", null, tampa, 10, 30, null)), Role.HITTER);

        assertThat(totals.get(2025).teamIds()).containsExactly(139L, 144L);
        assertThat(totals.get(2025).games()).isEqualTo(48);
        assertThat(totals.get(2025).plateAppearances()).isEqualTo(191);
        assertThat(totals.get(2025).battingAvg()).isEqualTo(0.234);
        assertThat(totals.get(2024).battingAvg()).isNull();

        // 합친 줄이 없으면 개수만 더하고 비율은 모른다
        var summed = LeaguePositionRefresher.careerTotals(List.of(
                hitting("2025", null, tampa, 24, 93, ".214"), hitting("2025", null, atlanta, 24, 98, ".253")),
                Role.HITTER);
        assertThat(summed.get(2025).plateAppearances()).isEqualTo(191);
        assertThat(summed.get(2025).battingAvg()).isNull();
    }

    private PlayerLeaguePosition position(String nameKo, PlayerSeasonMetric.Group group) {
        long id = players.findByNameKo(nameKo).orElseThrow().getId();
        return positions.find(id, 2026, group).orElseThrow();
    }

    private static MlbSeasonSplit hitting(String season, Integer numTeams, MlbTeam team, int games,
                                          int plateAppearances, String avg) {
        return new MlbSeasonSplit(season, numTeams, team, null, null, new MlbSeasonSplit.Stat(plateAppearances, null,
                null, null, null, null, games, null, null, null, null, avg, null, null, null, null, null, null, null));
    }
}
