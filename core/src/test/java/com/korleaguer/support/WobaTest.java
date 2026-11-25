package com.korleaguer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** wOBA 더하기: 수집(리그 평균)과 조회(선수 wOBA)가 같이 쓰는 가중치 */
class WobaTest {

    @Test
    @DisplayName("출루한 방법마다 다른 무게를 매겨 wOBA를 구한다")
    void weighsEachWayOfReachingBase() {
        // given
        // 타수 10, 안타 4(2루타 1·홈런 1 → 단타 2), 볼넷 2, 몸에 맞는 공 1, 희생플라이 1
        Woba.Sum sum = new Woba.Sum();
        sum.add(10, 4, 1, 0, 1, 2, 1, 1);

        double numerator = 0.689 * 2 + 0.72 + 0.882 * 2 + 1.254 + 2.05;

        // when // then
        assertThat(sum.woba()).isCloseTo(numerator / 14, within(1e-12));
    }

    @Test
    @DisplayName("여러 기록을 먼저 더한 다음 나눠 wOBA를 구한다")
    void addsLinesBeforeDividing() {
        // given
        Woba.Sum sum = new Woba.Sum();
        sum.add(4, 1, 0, 0, 0, 0, 0, 0);
        sum.add(4, 3, 0, 1, 0, 0, 0, 0);

        // when // then
        // 단타 3 + 3루타 1, 분모 8
        assertThat(sum.woba()).isCloseTo((0.882 * 3 + 1.59) / 8, within(1e-12));
    }

    @Test
    @DisplayName("타석이 하나도 없으면 wOBA를 비워 둔다")
    void noPlateAppearancesIsUnknown() {
        // when // then
        assertThat(new Woba.Sum().woba()).isNull();
    }
}
