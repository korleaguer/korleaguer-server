package com.korleaguer.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마이너 경기별 기록은 정규시즌 줄만 남긴다(이슈 #190 리뷰). gameType을 묻지 않으면 MLB가 정규시즌만 주지만, 기본값이 바뀌거나
 * 요청을 넓혀 포스트시즌·시범경기 줄이 와도 마이너 경기 줄(seasonType REGULAR)·마이너 경기 수에 섞이지 않는다.
 */
class MlbMinorGameLogTest {

    private static final MlbGameLogSplit.Sport TRIPLE_A = new MlbGameLogSplit.Sport(11L, "AAA");

    @Test
    void keepsOnlyRegularSeasonGames() {
        MlbMinorGameLog log = new MlbMinorGameLog(
                List.of(split(1, "R"), split(2, "F"), split(3, "D"), split(4, "L"), split(5, "W"), split(6, "S"),
                        split(7, null)),
                List.of(split(8, "D"), split(9, "R")));

        assertThat(log.hitting()).extracting(MlbGameLogSplit::gamePk).containsExactly(1L);
        assertThat(log.pitching()).extracting(MlbGameLogSplit::gamePk).containsExactly(9L);
        // 마이너 경기 수(홈 '주로 마이너' 판단)도 정규시즌만 센다
        assertThat(log.counts()).isEqualTo(new MlbMinorGames(1, 1));
    }

    @Test
    void regularSeasonOnlyLogIsUnchanged() {
        List<MlbGameLogSplit> hitting = List.of(split(1, "R"), split(2, "R"));

        assertThat(new MlbMinorGameLog(hitting, null).hitting()).isEqualTo(hitting);
        assertThat(new MlbMinorGameLog(null, null)).isEqualTo(MlbMinorGameLog.NONE);
    }

    private static MlbGameLogSplit split(long gamePk, String gameType) {
        return new MlbGameLogSplit("2026", "2026-09-20", gameType, true, true, null, null,
                new MlbGameLogSplit.GameRef(gamePk), null, null, TRIPLE_A);
    }
}
