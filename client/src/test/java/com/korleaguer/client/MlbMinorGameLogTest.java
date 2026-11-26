package com.korleaguer.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마이너 경기별 기록은 정규시즌 줄만 남긴다(이슈 #190 리뷰). gameType을 묻지 않으면 MLB가 정규시즌만 주지만, 기본값이 바뀌거나
 * 요청을 넓혀 포스트시즌·시범경기 줄이 와도 마이너 경기 줄(seasonType REGULAR)·마이너 경기 수에 섞이지 않는다.
 */
class MlbMinorGameLogTest {

    private static final MlbGameLogSplit.Sport TRIPLE_A = new MlbGameLogSplit.Sport(11L, "AAA");

    @Test
    @DisplayName("마이너 경기 기록은 정규시즌 경기만 남기고 센다")
    void keepsOnlyRegularSeasonGames() {
        // when
        MlbMinorGameLog log = new MlbMinorGameLog(
                List.of(split(1, "R"), split(2, "F"), split(3, "D"), split(4, "L"), split(5, "W"), split(6, "S"),
                        split(7, null)),
                List.of(split(8, "D"), split(9, "R")));

        // then
        assertThat(log.hitting()).extracting(MlbGameLogSplit::gamePk).containsExactly(1L);
        assertThat(log.pitching()).extracting(MlbGameLogSplit::gamePk).containsExactly(9L);
        // 마이너 경기 수(홈 '주로 마이너' 판단)도 정규시즌만 센다
        assertThat(log.counts()).isEqualTo(new MlbMinorGames(1, 1));
    }

    @Test
    @DisplayName("정규시즌뿐인 기록은 그대로 두고 기록이 없으면 빈 기록이 된다")
    void regularSeasonOnlyLogIsUnchanged() {
        // given
        List<MlbGameLogSplit> hitting = List.of(split(1, "R"), split(2, "R"));

        // when // then
        assertThat(new MlbMinorGameLog(hitting, null).hitting()).isEqualTo(hitting);
        assertThat(new MlbMinorGameLog(null, null)).isEqualTo(MlbMinorGameLog.NONE);
    }

    @ParameterizedTest(name = "sport.id {0} 경기는 마이너로 {1}개 센다")
    @CsvSource({"10, 0", "11, 1", "16, 1", "17, 0"})
    @DisplayName("트리플A(11)부터 루키(16)까지만 마이너 경기로 센다")
    void minorsRunFromTripleAToRookie(long sportId, int counted) {
        // given
        MlbGameLogSplit game = split(1, "R", new MlbGameLogSplit.Sport(sportId, "X"));

        // when
        MlbMinorGameLog log = new MlbMinorGameLog(List.of(game), List.of(game));

        // then
        assertThat(MlbMinorGameLog.isMinors(game)).isEqualTo(counted == 1);
        assertThat(log.counts()).isEqualTo(new MlbMinorGames(counted, counted));
    }

    private static MlbGameLogSplit split(long gamePk, String gameType) {
        return split(gamePk, gameType, TRIPLE_A);
    }

    private static MlbGameLogSplit split(long gamePk, String gameType, MlbGameLogSplit.Sport sport) {
        return new MlbGameLogSplit("2026", "2026-09-20", gameType, true, true, null, null,
                new MlbGameLogSplit.GameRef(gamePk), null, null, sport);
    }
}
