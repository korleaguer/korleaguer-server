package com.korleaguer.api.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BatsThrowsTest {

    @Test
    @DisplayName("타격·투구 순서의 영어 표기를 투구·타격 순서의 우리말로 바꾼다")
    void battingComesFirstInEnglishAndThrowingComesFirstInKorean() {
        // when // then
        assertThat(BatsThrows.korean("Left/Right")).isEqualTo("우투좌타");
        assertThat(BatsThrows.korean("Right/Left")).isEqualTo("좌투우타");
        assertThat(BatsThrows.korean("Right/Right")).isEqualTo("우투우타");
        assertThat(BatsThrows.korean("Left/Left")).isEqualTo("좌투좌타");
    }

    @Test
    @DisplayName("스위치 타자는 양타로 쓴다")
    void switchHitterIsBoth() {
        // when // then
        assertThat(BatsThrows.korean("Switch/Right")).isEqualTo("우투양타");
        assertThat(BatsThrows.korean("Switch/Left")).isEqualTo("좌투양타");
        assertThat(BatsThrows.korean("S/R")).isEqualTo("우투양타");
    }

    @Test
    @DisplayName("한 글자 표기나 공백, 대문자가 섞여도 같은 우리말로 바꾼다")
    void shortLettersSpacesAndCaseAreAccepted() {
        // when // then
        assertThat(BatsThrows.korean("L/R")).isEqualTo("우투좌타");
        assertThat(BatsThrows.korean("R/L")).isEqualTo("좌투우타");
        assertThat(BatsThrows.korean(" left / right ")).isEqualTo("우투좌타");
        assertThat(BatsThrows.korean("LEFT/RIGHT")).isEqualTo("우투좌타");
    }

    @Test
    @DisplayName("빈 값이나 한쪽만 적힌 값처럼 읽을 수 없는 투타 표기는 비워 둔다")
    void unreadableIsNull() {
        // when // then
        assertThat(BatsThrows.korean(null)).isNull();
        assertThat(BatsThrows.korean("")).isNull();
        assertThat(BatsThrows.korean("Left")).isNull();
        assertThat(BatsThrows.korean("Left/Right/Left")).isNull();
        assertThat(BatsThrows.korean("Right/Switch")).isNull(); // 투구에는 양쪽이 없다.
        assertThat(BatsThrows.korean("-/-")).isNull();
    }
}
