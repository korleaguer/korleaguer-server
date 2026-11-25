package com.korleaguer.api.season;

import com.korleaguer.domain.Batting;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RankingMinimumsTest {

    @Test
    void fullSeasonNeedsHalfPlateAppearancePerGameAndFifthInningPerGame() {
        RankingMinimums full = RankingMinimums.of(162);

        assertThat(full.minPlateAppearances()).isEqualTo(81);
        assertThat(full.minOuts()).isEqualTo(98);          // 97.2를 올림
        assertThat(full.minInningsText()).isEqualTo("32⅔");
    }

    @Test
    void earlySeasonUsesFloor() {
        // 시즌 초에는 경기 수 비율보다 최소값(10타석, 15아웃)이 크다.
        assertThat(RankingMinimums.of(0).minPlateAppearances()).isEqualTo(10);
        assertThat(RankingMinimums.of(0).minOuts()).isEqualTo(15);
        assertThat(RankingMinimums.of(10).minPlateAppearances()).isEqualTo(10);
        assertThat(RankingMinimums.of(10).minOuts()).isEqualTo(15);
    }

    @Test
    void roundsUpWithoutFloatingPointError() {
        // 40 × 0.6을 소수로 계산하면 24.000…04라 올림이 25가 된다. 정수로 올림해서 24다.
        assertThat(RankingMinimums.of(40).minOuts()).isEqualTo(24);
        assertThat(RankingMinimums.of(25).minPlateAppearances()).isEqualTo(13);
    }

    @Test
    void plateAppearancesFallBackToComputedValue() {
        // 희생번트 1이 더해진 타석 6: 다른 칸에서 계산한 5가 아니라 준 값을 쓴다
        Batting known = BattingLine.atBats(4).hits(1).walks(1).strikeouts(1).plateAppearances(6).build();
        // 타석이 비어 있으면 타수 3 + 볼넷 1 + 몸에 맞는 공 1 + 희생플라이 1
        Batting unknown = BattingLine.atBats(3).hits(1).rbi(1).walks(1).hitByPitch(1).sacFlies(1).withoutTotals().build();

        assertThat(known.plateAppearancesOrComputed()).isEqualTo(6);
        assertThat(unknown.plateAppearancesOrComputed()).isEqualTo(6);
    }
}
