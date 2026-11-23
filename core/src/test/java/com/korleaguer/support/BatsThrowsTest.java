package com.korleaguer.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BatsThrowsTest {

    @Test
    void battingComesFirstInEnglishAndThrowingComesFirstInKorean() {
        assertThat(BatsThrows.korean("Left/Right")).isEqualTo("우투좌타");
        assertThat(BatsThrows.korean("Right/Left")).isEqualTo("좌투우타");
        assertThat(BatsThrows.korean("Right/Right")).isEqualTo("우투우타");
        assertThat(BatsThrows.korean("Left/Left")).isEqualTo("좌투좌타");
    }

    @Test
    void switchHitterIsBoth() {
        assertThat(BatsThrows.korean("Switch/Right")).isEqualTo("우투양타");
        assertThat(BatsThrows.korean("Switch/Left")).isEqualTo("좌투양타");
        assertThat(BatsThrows.korean("S/R")).isEqualTo("우투양타");
    }

    @Test
    void shortLettersSpacesAndCaseAreAccepted() {
        assertThat(BatsThrows.korean("L/R")).isEqualTo("우투좌타");
        assertThat(BatsThrows.korean("R/L")).isEqualTo("좌투우타");
        assertThat(BatsThrows.korean(" left / right ")).isEqualTo("우투좌타");
        assertThat(BatsThrows.korean("LEFT/RIGHT")).isEqualTo("우투좌타");
    }

    @Test
    void unreadableIsNull() {
        assertThat(BatsThrows.korean(null)).isNull();
        assertThat(BatsThrows.korean("")).isNull();
        assertThat(BatsThrows.korean("Left")).isNull();
        assertThat(BatsThrows.korean("Left/Right/Left")).isNull();
        assertThat(BatsThrows.korean("Right/Switch")).isNull(); // 투구에는 양쪽이 없다.
        assertThat(BatsThrows.korean("-/-")).isNull();
    }
}
