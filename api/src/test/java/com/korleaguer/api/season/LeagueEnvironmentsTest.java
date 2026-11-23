package com.korleaguer.api.season;

import com.korleaguer.domain.LeagueAverage;
import com.korleaguer.domain.LeagueAverageRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 리그 단계별 평균 읽기(이슈 #175·#233): 저장된 줄을 그대로 쓰고, 없으면 표준값. MLB는 부르지 않는다(MLB 클라이언트를 모른다).
 * 받아서 저장하는 쪽은 ingest.LeagueAverageRefresherTest
 */
class LeagueEnvironmentsTest {

    private final LeagueAverageRepository averages = mock(LeagueAverageRepository.class);
    private final LeagueEnvironments leagues = new LeagueEnvironments(averages);

    @Test
    void readsTheStoredRowAsIs() {
        when(averages.findBySeasonAndLevel(anyInt(), anyString())).thenReturn(Optional.empty());
        when(averages.findBySeasonAndLevel(2026, "AAA")).thenReturn(Optional.of(row(2026, "AAA", 0.3382, 0.138, 4.99)));

        assertThat(leagues.get(2026, "AAA")).isEqualTo(new LeagueEnv(0.3382, 0.138, 4.99));
    }

    @Test
    void missingRowFallsBackToTheStandardValues() {
        when(averages.findBySeasonAndLevel(anyInt(), anyString())).thenReturn(Optional.empty());
        when(averages.findBySeasonAndLevel(2026, "AAA")).thenReturn(Optional.of(row(2026, "AAA", 0.3382, 0.138, 4.99)));

        Map<String, LeagueEnv> envs = leagues.of(2026, Set.of("AAA", "AA", "XYZ"));

        assertThat(envs.get("AAA").era()).isEqualTo(4.99);
        // 줄이 없는 더블A는 표준값 그 객체(내 선수 카드가 '못 받음'을 이 객체로 가린다), 모르는 단계는 넣지 않는다
        assertThat(envs.get("AA")).isSameAs(LeagueEnv.STANDARD);
        assertThat(envs).doesNotContainKey("XYZ");
        // 다른 시즌의 줄을 대신 쓰지 않는다
        assertThat(leagues.get(2025, "AAA")).isSameAs(LeagueEnv.STANDARD);
    }

    private static LeagueAverage row(int season, String level, double woba, double runsPerPa, double era) {
        LeagueAverage row = new LeagueAverage(season, level);
        row.update(new LeagueAverage.Values(woba, runsPerPa, era), Instant.parse("2026-11-01T00:00:00Z"));
        return row;
    }
}
