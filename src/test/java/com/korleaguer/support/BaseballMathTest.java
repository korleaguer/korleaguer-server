package com.korleaguer.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaseballMathTest {

    @Test
    void battingAverage() {
        assertThat(BaseballMath.avg(149, 540)).isEqualTo(0.276);
        assertThat(BaseballMath.avg(0, 0)).isNull();
    }

    @Test
    void onBasePlusSlugging() {
        // H 5, BB 2, HBP 0, AB 12, SF 0, TB 10
        assertThat(BaseballMath.obp(5, 2, 0, 12, 0)).isEqualTo(0.5);
        assertThat(BaseballMath.slg(10, 12)).isEqualTo(0.833);
        // 반올림 전 값끼리 더한다: 0.5 + 0.8333... = 1.333
        assertThat(BaseballMath.ops(5, 2, 0, 12, 0, 10)).isEqualTo(1.333);
        assertThat(BaseballMath.ops(0, 0, 0, 0, 0, 0)).isNull();
    }

    @Test
    void earnedRunAverage() {
        // 3자책, 5⅔이닝(17아웃) → 3 × 27 / 17 = 4.76
        assertThat(BaseballMath.era(3, 17)).isEqualTo(4.76);
        assertThat(BaseballMath.era(1, 0)).isNull();
    }

    @Test
    void inningsPitchedDecimalIsThirds() {
        assertThat(BaseballMath.outsFromIp(5.1)).isEqualTo(16);
        assertThat(BaseballMath.outsFromIp(5.2)).isEqualTo(17);
        assertThat(BaseballMath.outsFromIp(6.0)).isEqualTo(18);
        assertThat(BaseballMath.outsFromIp(0.2)).isEqualTo(2);
        assertThat(BaseballMath.outsFromIp(null)).isNull();
    }

    @Test
    void unexpectedIpDecimalIsReadAsDecimalInnings() {
        // .0/.1/.2가 아니면 10진수 이닝: 5.333 × 3 = 16아웃, 5.5 × 3 = 16.5 → 17아웃
        assertThat(BaseballMath.outsFromIp(5.333)).isEqualTo(16);
        assertThat(BaseballMath.outsFromIp(5.5)).isEqualTo(17);
    }

    @Test
    void inningsTextFromMlbIsReadAsThirds() {
        // MLB 시즌 합계의 inningsPitched: "50.2" = 50⅔이닝 = 152아웃
        assertThat(BaseballMath.outsFromInnings("50.2")).isEqualTo(152);
        assertThat(BaseballMath.outsFromInnings("4.0")).isEqualTo(12);
        assertThat(BaseballMath.outsFromInnings("0.1")).isEqualTo(1);
        assertThat(BaseballMath.outsFromInnings(" 67.0 ")).isEqualTo(201);
        assertThat(BaseballMath.outsFromInnings(null)).isNull();
        assertThat(BaseballMath.outsFromInnings("")).isNull();
        assertThat(BaseballMath.outsFromInnings("-.--")).isNull();
    }

    @Test
    void inningsText() {
        assertThat(BaseballMath.inningsText(17)).isEqualTo("5⅔");
        assertThat(BaseballMath.inningsText(16)).isEqualTo("5⅓");
        assertThat(BaseballMath.inningsText(18)).isEqualTo("6");
        assertThat(BaseballMath.inningsText(2)).isEqualTo("⅔");
        assertThat(BaseballMath.inningsText(0)).isEqualTo("0");
    }
}
