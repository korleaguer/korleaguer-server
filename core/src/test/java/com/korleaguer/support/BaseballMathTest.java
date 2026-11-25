package com.korleaguer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaseballMathTest {

    @Test
    @DisplayName("타율은 안타를 타수로 나눠 셋째 자리까지 구하고, 타수가 없으면 비워 둔다")
    void battingAverage() {
        // when // then
        assertThat(BaseballMath.avg(149, 540)).isEqualTo(0.276);
        assertThat(BaseballMath.avg(0, 0)).isNull();
    }

    @Test
    @DisplayName("OPS는 출루율과 장타율을 반올림하기 전 값으로 더하고, 타석이 없으면 비워 둔다")
    void onBasePlusSlugging() {
        // when // then
        // H 5, BB 2, HBP 0, AB 12, SF 0, TB 10
        assertThat(BaseballMath.obp(5, 2, 0, 12, 0)).isEqualTo(0.5);
        assertThat(BaseballMath.slg(10, 12)).isEqualTo(0.833);
        // 반올림 전 값끼리 더한다: 0.5 + 0.8333... = 1.333
        assertThat(BaseballMath.ops(5, 2, 0, 12, 0, 10)).isEqualTo(1.333);
        assertThat(BaseballMath.ops(0, 0, 0, 0, 0, 0)).isNull();
    }

    @Test
    @DisplayName("평균자책점은 잡은 아웃 수로 구하고, 아웃을 못 잡았으면 비워 둔다")
    void earnedRunAverage() {
        // when // then
        // 3자책, 5⅔이닝(17아웃) → 3 × 27 / 17 = 4.76
        assertThat(BaseballMath.era(3, 17)).isEqualTo(4.76);
        assertThat(BaseballMath.era(1, 0)).isNull();
    }

    @Test
    @DisplayName("이닝의 소수 한 자리는 아웃 개수로 읽고, 값이 없으면 비워 둔다")
    void inningsPitchedDecimalIsThirds() {
        // when // then
        assertThat(BaseballMath.outsFromIp(5.1)).isEqualTo(16);
        assertThat(BaseballMath.outsFromIp(5.2)).isEqualTo(17);
        assertThat(BaseballMath.outsFromIp(6.0)).isEqualTo(18);
        assertThat(BaseballMath.outsFromIp(0.2)).isEqualTo(2);
        assertThat(BaseballMath.outsFromIp(null)).isNull();
    }

    @Test
    @DisplayName("이닝 소수가 .0, .1, .2가 아니면 십진수 이닝으로 보고 아웃 수를 반올림한다")
    void unexpectedIpDecimalIsReadAsDecimalInnings() {
        // when // then
        // .0/.1/.2가 아니면 10진수 이닝: 5.333 × 3 = 16아웃, 5.5 × 3 = 16.5 → 17아웃
        assertThat(BaseballMath.outsFromIp(5.333)).isEqualTo(16);
        assertThat(BaseballMath.outsFromIp(5.5)).isEqualTo(17);
    }

    @Test
    @DisplayName("글자로 온 이닝도 아웃 수로 바꾸고, 기록이 없다는 표시는 비워 둔다")
    void inningsTextFromMlbIsReadAsThirds() {
        // when // then
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
    @DisplayName("아웃 수를 이닝으로 쓸 때 나머지 아웃은 ⅓·⅔로 쓴다")
    void inningsText() {
        // when // then
        assertThat(BaseballMath.inningsText(17)).isEqualTo("5⅔");
        assertThat(BaseballMath.inningsText(16)).isEqualTo("5⅓");
        assertThat(BaseballMath.inningsText(18)).isEqualTo("6");
        assertThat(BaseballMath.inningsText(2)).isEqualTo("⅔");
        assertThat(BaseballMath.inningsText(0)).isEqualTo("0");
    }

    @Test
    @DisplayName("글자로 온 타율·평균자책점은 숫자로 읽고, 값이 없다는 표시는 비워 둔다")
    void decimalFromMlbText() {
        // when // then
        // MLB는 타율·OPS·평균자책점을 글자로 주고, 값이 없으면 ".---"·"-.--"를 준다
        assertThat(BaseballMath.decimal(".258")).isEqualTo(0.258);
        assertThat(BaseballMath.decimal("1.167")).isEqualTo(1.167);
        assertThat(BaseballMath.decimal("9.00")).isEqualTo(9.0);
        assertThat(BaseballMath.decimal(".---")).isNull();
        assertThat(BaseballMath.decimal("-.--")).isNull();
        assertThat(BaseballMath.decimal("Infinity")).isNull();
        assertThat(BaseballMath.decimal(" ")).isNull();
        assertThat(BaseballMath.decimal(null)).isNull();
    }
}
