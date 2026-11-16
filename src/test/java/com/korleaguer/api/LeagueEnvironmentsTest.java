package com.korleaguer.api;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbStat;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 리그 단계별 평균(이슈 #175): 팀 합계 더하기, 메모리에 두기, 못 받으면 표준값 */
class LeagueEnvironmentsTest {

    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");

    @Test
    void addsTeamTotalsIntoLeagueAverages() {
        // 두 팀: 타석 1,000·득점 120, 아웃 2,700·자책 450 → 타석당 0.120, 평균자책점 4.50
        List<MlbGameLogSplit> hitting = List.of(team(hitting(400, 100, 20, 2, 12, 40, 5, 3, 450, 50)),
                team(hitting(480, 120, 25, 3, 15, 50, 6, 4, 550, 70)));
        List<MlbGameLogSplit> pitching = List.of(team(pitching(1350, 200)), team(pitching(1350, 250)));

        LeagueEnvironments.Env env = LeagueEnvironments.fromTotals(hitting, pitching);

        assertThat(env.runsPerPa()).isCloseTo(0.120, within(1e-9));
        assertThat(env.era()).isCloseTo(4.50, within(1e-9));
        assertThat(env.woba()).isBetween(0.30, 0.36);
    }

    @Test
    void keepsWhatItGotAndFallsBackToStandardValues() {
        MlbClient mlb = mock(MlbClient.class);
        when(mlb.getTeamSeasonTotals(eq(11), eq(MlbClient.Group.HITTING), anyInt()))
                .thenReturn(List.of(team(hitting(480, 120, 25, 3, 15, 50, 6, 4, 550, 70))));
        when(mlb.getTeamSeasonTotals(eq(11), eq(MlbClient.Group.PITCHING), anyInt()))
                .thenReturn(List.of(team(pitching(1350, 250))));
        when(mlb.getTeamSeasonTotals(eq(12), eq(MlbClient.Group.HITTING), anyInt()))
                .thenThrow(new MlbApiException("down", null));
        LeagueEnvironments leagues = new LeagueEnvironments(mlb, Clock.fixed(NOW, ZoneOffset.UTC));

        Map<String, LeagueEnvironments.Env> envs = leagues.of(2026, Set.of("AAA", "AA", "XYZ"));
        leagues.of(2026, Set.of("AAA"));

        assertThat(envs.get("AAA").era()).isCloseTo(5.0, within(1e-9));
        // 못 받은 더블A는 표준값, 모르는 단계는 넣지 않는다
        assertThat(envs.get("AA")).isEqualTo(LeagueEnvironments.STANDARD);
        assertThat(envs).doesNotContainKey("XYZ");
        // 트리플A는 두 번 물어도 한 번만 받는다
        verify(mlb, times(1)).getTeamSeasonTotals(11, MlbClient.Group.HITTING, 2026);
    }

    private static MlbGameLogSplit team(MlbStat stat) {
        return new MlbGameLogSplit("2026", null, null, null, null, null, null, null, null, stat, null);
    }

    private static MlbStat hitting(int atBats, int hits, int doubles, int triples, int homeRuns, int walks,
                                   int hitByPitch, int sacFlies, int plateAppearances, int runs) {
        return new MlbStat(162, null, atBats, runs, hits, doubles, triples, homeRuns, null, walks, null, hitByPitch,
                null, sacFlies, plateAppearances, null, null, null, null, null, null, null, null, null, null);
    }

    private static MlbStat pitching(int outs, int earnedRuns) {
        return new MlbStat(162, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                outs, null, earnedRuns, null, null, null, null, null, null);
    }
}
