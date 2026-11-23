package com.korleaguer.api;

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
        Batting known = new Batting(4, 0, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 5, 1);
        // 타석이 비어 있으면 타수 3 + 볼넷 1 + 몸에 맞는 공 1 + 희생플라이 1
        Batting unknown = new Batting(3, 0, 1, 0, 0, 0, 1, 1, 0, 1, 0, 1, null, null);

        assertThat(known.plateAppearancesOrComputed()).isEqualTo(5);
        assertThat(unknown.plateAppearancesOrComputed()).isEqualTo(6);
    }
}
