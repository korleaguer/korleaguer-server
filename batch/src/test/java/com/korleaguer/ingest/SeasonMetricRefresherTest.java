package com.korleaguer.ingest;

import com.korleaguer.client.MlbSeasonSplit;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** 시즌 합계·지표 줄 합치기: 여러 팀에서 뛴 시즌은 합친 줄을 쓰고, 합친 줄이 없으면 더한다. */
class SeasonMetricRefresherTest {

    private static final MlbTeam TAMPA = new MlbTeam(139L, "Tampa Bay Rays", null, null, null);
    private static final MlbTeam ATLANTA = new MlbTeam(144L, "Atlanta Braves", null, null, null);

    @Test
    void tradedSeasonUsesTheCombinedLine() {
        var totals = SeasonMetricRefresher.totals(List.of(
                hitting("2025", null, TAMPA, 93), hitting("2025", null, ATLANTA, 98), hitting("2025", 2, null, 191),
                hitting("2026", null, ATLANTA, 147)), Role.HITTER);

        assertThat(totals.get(2025).plateAppearances()).isEqualTo(191);
        assertThat(totals.get(2026).plateAppearances()).isEqualTo(147);
        assertThat(totals.get(2026).inningsOuts()).isNull();
    }

    @Test
    void withoutACombinedLineTeamLinesAreAdded() {
        var totals = SeasonMetricRefresher.totals(List.of(
                pitching("2024", TAMPA, "20.1", 2), pitching("2024", ATLANTA, "30.2", 3)), Role.PITCHER);

        assertThat(totals.get(2024).inningsOuts()).isEqualTo(61 + 92);
        assertThat(totals.get(2024).gamesStarted()).isEqualTo(5);
        assertThat(totals.get(2024).plateAppearances()).isNull();
    }

    @Test
    void sabermetricsOfSeveralTeamsKeepOnlyTheWarSum() {
        var one = SeasonMetricRefresher.sabermetrics(List.of(metric(null, ATLANTA, 7.3, -1.17)));
        assertThat(one).isEqualTo(new IngestWriter.SeasonMetrics(7.3, null, -1.17));

        var combined = SeasonMetricRefresher.sabermetrics(List.of(metric(null, TAMPA, 90.0, 0.4),
                metric(null, ATLANTA, 70.0, -0.2), metric(2, null, 80.0, 0.2)));
        assertThat(combined).isEqualTo(new IngestWriter.SeasonMetrics(80.0, null, 0.2));

        var summed = SeasonMetricRefresher.sabermetrics(List.of(metric(null, TAMPA, 90.0, 0.4),
                metric(null, ATLANTA, 70.0, -0.2)));
        assertThat(summed.wrcPlus()).isNull();
        assertThat(summed.war()).isEqualTo(0.2, offset(1e-9));

        assertThat(SeasonMetricRefresher.sabermetrics(List.of()))
                .isEqualTo(new IngestWriter.SeasonMetrics(null, null, null));
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
