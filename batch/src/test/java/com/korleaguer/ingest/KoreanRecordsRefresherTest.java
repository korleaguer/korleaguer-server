package com.korleaguer.ingest;

import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbSeasonSplit;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.KoreanMlbSeason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 한국인 역대 기록용 시즌별 개수 기록(이슈 #177): 여러 팀 시즌은 합친 줄을 쓴다 */
class KoreanRecordsRefresherTest {

    @Test
    @DisplayName("여러 팀에서 뛴 시즌은 합친 줄의 기록을 쓴다")
    void usesTheCombinedLineForMultiTeamSeasons() {
        // given
        // 최지만 2018: 밀워키 7안타 + 탬파베이 43안타, 합친 줄 50안타(실제 기록)
        List<MlbSeasonSplit> splits = List.of(
                hitter("2018", null, team(158), 12, 7, 2, 5, 0),
                hitter("2018", null, team(139), 49, 43, 8, 27, 2),
                hitter("2018", 2, null, 61, 50, 10, 32, 2),
                hitter("2019", null, team(139), 127, 107, 19, 63, 2));

        // when
        Map<Integer, KoreanMlbSeason.Counts> counts = KoreanRecordsRefresher.seasonCounts(splits, false);

        // then
        assertThat(counts).containsOnlyKeys(2018, 2019);
        assertThat(counts.get(2018)).isEqualTo(new KoreanMlbSeason.Counts(61, 50, 10, 32, 2, null, null, null));
        assertThat(counts.get(2019).hits()).isEqualTo(107);
    }

    @Test
    @DisplayName("합친 줄이 없으면 팀별 기록을 더한다")
    void addsTeamLinesWhenThereIsNoCombinedLine() {
        // given
        List<MlbSeasonSplit> splits = List.of(
                hitter("2018", null, team(158), 12, 7, 2, 5, 0),
                hitter("2018", null, team(139), 49, 43, 8, 27, 2));

        // when // then
        assertThat(KoreanRecordsRefresher.seasonCounts(splits, false).get(2018))
                .isEqualTo(new KoreanMlbSeason.Counts(61, 50, 10, 32, 2, null, null, null));
    }

    @Test
    @DisplayName("투수는 경기 수와 승리, 세이브, 탈삼진을 남긴다")
    void pitchersKeepWinsSavesAndStrikeouts() {
        // given
        // 오승환 2016(실제): 76경기 6승 19세이브 103탈삼진
        MlbSeasonSplit.Stat stat = new MlbSeasonSplit.Stat(null, "79.2", 0, null, null, null, 76, 5, 103, 18, 300, null,
                null, "1.92", null, null, null, null, null, 55, null, null, 6, 19);

        // when
        Map<Integer, KoreanMlbSeason.Counts> counts = KoreanRecordsRefresher.seasonCounts(
                List.of(new MlbSeasonSplit("2016", null, team(138), null, null, stat)), true);

        // then
        assertThat(counts.get(2016)).isEqualTo(new KoreanMlbSeason.Counts(76, null, null, null, null, 6, 19, 103));
    }

    private static MlbTeam team(long id) {
        return new MlbTeam(id, null, null, null, null);
    }

    private static MlbSeasonSplit hitter(String season, Integer numTeams, MlbTeam team, int games, int hits,
                                         int homeRuns, int rbi, int stolenBases) {
        MlbSeasonSplit.Stat stat = new MlbSeasonSplit.Stat(null, null, null, null, null, null, games, homeRuns, null,
                null, null, null, null, null, null, null, null, null, null, hits, rbi, stolenBases, null, null);
        return new MlbSeasonSplit(season, numTeams, team, (MlbPerson) null, null, stat);
    }
}
