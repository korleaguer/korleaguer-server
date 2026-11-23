package com.korleaguer.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** wOBA 더하기: 수집(리그 평균)과 조회(선수 wOBA)가 같이 쓰는 가중치 */
class WobaTest {

    @Test
    void weighsEachWayOfReachingBase() {
        // 타수 10, 안타 4(2루타 1·홈런 1 → 단타 2), 볼넷 2, 몸에 맞는 공 1, 희생플라이 1
        Woba.Sum sum = new Woba.Sum();
        sum.add(10, 4, 1, 0, 1, 2, 1, 1);

        double numerator = 0.689 * 2 + 0.72 + 0.882 * 2 + 1.254 + 2.05;
        assertThat(sum.woba()).isCloseTo(numerator / 14, within(1e-12));
    }

    @Test
    void addsLinesBeforeDividing() {
        Woba.Sum sum = new Woba.Sum();
        sum.add(4, 1, 0, 0, 0, 0, 0, 0);
        sum.add(4, 3, 0, 1, 0, 0, 0, 0);

        // 단타 3 + 3루타 1, 분모 8
        assertThat(sum.woba()).isCloseTo((0.882 * 3 + 1.59) / 8, within(1e-12));
    }

    @Test
    void noPlateAppearancesIsUnknown() {
        assertThat(new Woba.Sum().woba()).isNull();
    }
}
