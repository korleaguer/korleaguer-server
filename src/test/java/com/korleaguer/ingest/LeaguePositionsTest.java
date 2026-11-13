package com.korleaguer.ingest;

import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbSeasonSplit;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.PlayerLeaguePosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * MLB 안 위치 계산 규칙(값은 지어낸 것). 실제 2026 리그 전체(타자 751명)로는 이정후 종합 40·타격 59·주루 60·수비 20,
 * 송성문 타격 9·주루 96·수비 93, 김하성 타격 1이 나오는 것을 따로 확인했다(PR 본문).
 */
class LeaguePositionsTest {

    @Test
    void percentileIsTheShareOfWorsePlayersClampedTo1And99() {
        List<Double> pool = List.of(1.0, 2.0, 3.0, 4.0);

        assertThat(LeaguePositions.percentile(2.5, pool, v -> v, true)).isEqualTo(50);
        // 같은 값은 나쁜 쪽으로 세지 않는다
        assertThat(LeaguePositions.percentile(2.0, pool, v -> v, true)).isEqualTo(25);
        // 가장 나빠도 1, 가장 좋아도 99
        assertThat(LeaguePositions.percentile(0.0, pool, v -> v, true)).isEqualTo(1);
        assertThat(LeaguePositions.percentile(9.0, pool, v -> v, true)).isEqualTo(99);
        // 낮을수록 좋은 값(ERA-·BB%)은 뒤집는다
        assertThat(LeaguePositions.percentile(1.5, pool, v -> v, false)).isEqualTo(75);
        // 값이 없는 선수는 비교에서 빼고, 이 선수 값이 없거나 견줄 선수가 없으면 null
        assertThat(LeaguePositions.percentile(2.5, Arrays.asList(1.0, null, 4.0), v -> v, true))
                .isEqualTo(50);
        assertThat(LeaguePositions.percentile(null, pool, v -> v, true)).isNull();
        assertThat(LeaguePositions.percentile(2.5, List.<Double>of(), v -> v, true)).isNull();
    }

    @Test
    void hittersAreComparedWithHundredPlateAppearanceHittersPerPlateAppearance() {
        List<MlbSeasonSplit> season = new ArrayList<>();
        List<MlbSeasonSplit> saber = new ArrayList<>();
        // 견줄 타자 4명(100타석 이상)과 견주지 않는 타자 1명(99타석, 값이 아무리 좋아도 빠진다)
        addHitter(season, saber, 1, 600, 80.0, 0.0, -2.0, -6.0, 0.0);
        addHitter(season, saber, 2, 300, 100.0, 1.0, 0.0, 0.0, 0.0);
        addHitter(season, saber, 3, 200, 120.0, 2.0, 1.0, 3.0, 1.0);
        addHitter(season, saber, 4, 100, 140.0, 3.0, 2.0, -1.0, 0.0);
        addHitter(season, saber, 5, 99, 300.0, 9.0, 9.0, 9.0, 9.0);
        // 우리 선수: 400타석, 타석당 WAR 0.005(=2/400)는 1·2번보다 높고 3·4번보다 낮다
        addHitter(season, saber, 10, 400, 110.0, 2.0, 0.4, -5.0, -3.0);

        Map<Long, LeaguePositions.HitterLine> league = LeaguePositions.hitters(season, saber);
        PlayerLeaguePosition.Hitting me = LeaguePositions.hitting(league.get(10L), league.values());

        assertThat(me.poolSize()).isEqualTo(5); // 1~4번과 이 선수
        assertThat(me.plateAppearances()).isEqualTo(400);
        assertThat(me.fieldingRuns()).isEqualTo(-8.0, offset(1e-9)); // 수비 = fielding + positional
        assertThat(me.pctOverall()).isEqualTo(40); // 5명 중 2명보다 낫다
        assertThat(me.pctBatting()).isEqualTo(40); // wRC+ 110 > 80, 100
        assertThat(me.pctBaseRunning()).isEqualTo(40); // 타석당 0.001 > -0.0033, 0
        assertThat(me.pctFielding()).isEqualTo(1); // 타석당 -0.02가 가장 나쁘다
    }

    @Test
    void fewPlateAppearancesKeepValuesButNoPositions() {
        List<MlbSeasonSplit> season = new ArrayList<>();
        List<MlbSeasonSplit> saber = new ArrayList<>();
        addHitter(season, saber, 1, 500, 100.0, 2.0, 0.0, 0.0, 0.0);
        addHitter(season, saber, 10, 49, 150.0, 0.5, 0.5, 0.5, 0.0);

        Map<Long, LeaguePositions.HitterLine> league = LeaguePositions.hitters(season, saber);
        PlayerLeaguePosition.Hitting me = LeaguePositions.hitting(league.get(10L), league.values());

        assertThat(me.wrcPlus()).isEqualTo(150.0);
        assertThat(me.poolSize()).isEqualTo(1);
        assertThat(me.pctOverall()).isNull();
        assertThat(me.pctBatting()).isNull();
        assertThat(me.pctBaseRunning()).isNull();
        assertThat(me.pctFielding()).isNull();
    }

    @Test
    void pitchersUseRatesAndLowerIsBetterForEraMinusAndWalks() {
        List<MlbSeasonSplit> season = new ArrayList<>();
        List<MlbSeasonSplit> saber = new ArrayList<>();
        addPitcher(season, saber, 1, "100.0", 400, 80, 40, 120.0, 0.0);
        addPitcher(season, saber, 2, "50.0", 200, 60, 10, 80.0, 1.0);
        addPitcher(season, saber, 3, "29.2", 100, 50, 0, 10.0, 5.0); // 30이닝 미만: 견주지 않는다
        addPitcher(season, saber, 10, "30.0", 125, 25, 10, 100.0, 0.3);

        Map<Long, LeaguePositions.PitcherLine> league = LeaguePositions.pitchers(season, saber);
        PlayerLeaguePosition.Pitching me = LeaguePositions.pitching(league.get(10L), league.values());

        assertThat(me.inningsOuts()).isEqualTo(90);
        assertThat(me.strikeoutRate()).isEqualTo(20.0, offset(1e-9)); // 25 / 125
        assertThat(me.walkRate()).isEqualTo(8.0, offset(1e-9));
        assertThat(me.poolSize()).isEqualTo(3); // 1·2번과 이 선수
        assertThat(me.pctOverall()).isEqualTo(33); // 아웃당 WAR 0.0033 > 0 (1번)
        assertThat(me.pctRunPrevention()).isEqualTo(33); // ERA- 100은 120보다 낫고 80보다 나쁘다
        assertThat(me.pctStrikeouts()).isEqualTo(1); // K% 20이 가장 낮다
        assertThat(me.pctWalks()).isEqualTo(33); // BB% 8은 10(1번)보다 낫고 5(2번)보다 나쁘다

        // 10이닝 미만이면 위치는 없다
        addPitcher(season, saber, 11, "9.2", 40, 10, 2, 50.0, 0.1);
        league = LeaguePositions.pitchers(season, saber);
        assertThat(LeaguePositions.pitching(league.get(11L), league.values()).pctOverall()).isNull();
    }

    @Test
    void aPlayerListedTwiceUsesTheLineWithMoreTeams() {
        MlbTeam team = new MlbTeam(143L, "Philadelphia Phillies", null, null, null);
        List<MlbSeasonSplit> season = List.of(
                new MlbSeasonSplit("2026", 1, team, MlbPerson.of(7L, null, null), null,
                        new MlbSeasonSplit.Stat(200, null, null, null, null, null)),
                new MlbSeasonSplit("2026", 2, team, MlbPerson.of(7L, null, null), null,
                        new MlbSeasonSplit.Stat(500, null, null, null, null, null)));

        assertThat(LeaguePositions.hitters(season, List.of()).get(7L).plateAppearances()).isEqualTo(500);
    }

    private static void addHitter(List<MlbSeasonSplit> season, List<MlbSeasonSplit> saber, long id,
                                  int plateAppearances, double wrcPlus, double war, double baseRunning,
                                  double fielding, double positional) {
        MlbPerson person = MlbPerson.of(id, null, null);
        season.add(new MlbSeasonSplit("2026", 1, null, person, null,
                new MlbSeasonSplit.Stat(plateAppearances, null, null, null, null, null)));
        saber.add(new MlbSeasonSplit("2026", 1, null, person, null, new MlbSeasonSplit.Stat(null, null, null,
                wrcPlus, null, war, null, null, null, null, null, null, null, null, null, baseRunning, fielding,
                positional, null)));
    }

    private static void addPitcher(List<MlbSeasonSplit> season, List<MlbSeasonSplit> saber, long id, String innings,
                                   int battersFaced, int strikeouts, int walks, double eraMinus, double war) {
        MlbPerson person = MlbPerson.of(id, null, null);
        season.add(new MlbSeasonSplit("2026", 1, null, person, null, new MlbSeasonSplit.Stat(null, innings, null,
                null, null, null, null, null, strikeouts, walks, battersFaced, null, null, null, null, null, null,
                null, null)));
        saber.add(new MlbSeasonSplit("2026", 1, null, person, null,
                new MlbSeasonSplit.Stat(null, null, null, null, eraMinus, war)));
    }
}
