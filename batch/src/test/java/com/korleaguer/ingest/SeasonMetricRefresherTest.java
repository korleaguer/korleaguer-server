package com.korleaguer.ingest;

import com.korleaguer.client.MlbSeasonSplit;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** 시즌 합계·지표 줄 합치기: 여러 팀에서 뛴 시즌은 합친 줄을 쓰고, 합친 줄이 없으면 더한다. */
class SeasonMetricRefresherTest {

    private static final MlbTeam TAMPA = new MlbTeam(139L, "Tampa Bay Rays", null, null, null);
    private static final MlbTeam ATLANTA = new MlbTeam(144L, "Atlanta Braves", null, null, null);

    @Test
    @DisplayName("시즌 중 팀을 옮긴 타자는 합친 줄의 타석 수를 쓴다")
    void tradedSeasonUsesTheCombinedLine() {
        // when
        var totals = SeasonMetricRefresher.totals(List.of(
                hitting("2025", null, TAMPA, 93), hitting("2025", null, ATLANTA, 98), hitting("2025", 2, null, 191),
                hitting("2026", null, ATLANTA, 147)), Role.HITTER);

        // then
        assertThat(totals.get(2025).plateAppearances()).isEqualTo(191);
        assertThat(totals.get(2026).plateAppearances()).isEqualTo(147);
        assertThat(totals.get(2026).inningsOuts()).isNull();
    }

    @Test
    @DisplayName("합친 줄이 없으면 투수의 팀별 이닝과 선발 수를 더한다")
    void withoutACombinedLineTeamLinesAreAdded() {
        // when
        var totals = SeasonMetricRefresher.totals(List.of(
                pitching("2024", TAMPA, "20.1", 2), pitching("2024", ATLANTA, "30.2", 3)), Role.PITCHER);

        // then
        assertThat(totals.get(2024).inningsOuts()).isEqualTo(61 + 92);
        assertThat(totals.get(2024).gamesStarted()).isEqualTo(5);
        assertThat(totals.get(2024).plateAppearances()).isNull();
    }

    @Test
    @DisplayName("여러 팀 시즌의 고급 지표는 합친 줄을 쓰고, 그 줄이 없으면 WAR만 더한다")
    void sabermetricsOfSeveralTeamsKeepOnlyTheWarSum() {
        // when
        var one = SeasonMetricRefresher.sabermetrics(List.of(metric(null, ATLANTA, 7.3, -1.17)));

        // then
        assertThat(one).isEqualTo(new SeasonStatWriter.SeasonMetrics(7.3, null, -1.17));

        // when
        var combined = SeasonMetricRefresher.sabermetrics(List.of(metric(null, TAMPA, 90.0, 0.4),
                metric(null, ATLANTA, 70.0, -0.2), metric(2, null, 80.0, 0.2)));

        // then
        assertThat(combined).isEqualTo(new SeasonStatWriter.SeasonMetrics(80.0, null, 0.2));

        // when
        var summed = SeasonMetricRefresher.sabermetrics(List.of(metric(null, TAMPA, 90.0, 0.4),
                metric(null, ATLANTA, 70.0, -0.2)));

        // then
        assertThat(summed.wrcPlus()).isNull();
        assertThat(summed.war()).isEqualTo(0.2, offset(1e-9));

        // when // then
        assertThat(SeasonMetricRefresher.sabermetrics(List.of()))
                .isEqualTo(new SeasonStatWriter.SeasonMetrics(null, null, null));
    }

    private static MlbSeasonSplit hitting(String season, Integer numTeams, MlbTeam team, int plateAppearances) {
        return new MlbSeasonSplit(season, numTeams, team, null, null,
                new MlbSeasonSplit.Stat(plateAppearances, null, null, null, null, null));
    }

    private static MlbSeasonSplit pitching(String season, MlbTeam team, String innings, int started) {
        return new MlbSeasonSplit(season, null, team, null, null,
                new MlbSeasonSplit.Stat(null, innings, started, null, null, null));
    }

    private static MlbSeasonSplit metric(Integer numTeams, MlbTeam team, double wrcPlus, double war) {
        return new MlbSeasonSplit("2025", numTeams, team, null, null,
                new MlbSeasonSplit.Stat(null, null, null, wrcPlus, null, war));
    }
}
