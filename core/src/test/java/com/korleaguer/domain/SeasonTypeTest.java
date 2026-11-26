package com.korleaguer.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경기 종류 대응표(이슈 #190). 수집이 저장할 경기(MLB gameType → 종류)와 저장하지 않을 경기를 가른다.
 * 저장 값(stored)과 그 값을 읽는 쪽(of)은 같은 enum 값 하나라 따로 맞출 것이 없다.
 */
class SeasonTypeTest {

    @ParameterizedTest
    @DisplayName("정규시즌 경기와 가을 야구 네 단계 경기를 정규시즌·포스트시즌으로 가른다")
    @CsvSource({"R, REGULAR", "F, POSTSEASON", "D, POSTSEASON", "L, POSTSEASON", "W, POSTSEASON"})
    void storedGameTypes(String gameType, SeasonType expected) {
        // when // then
        assertThat(SeasonType.fromGameType(gameType)).isEqualTo(expected);
    }

    @Test
    @DisplayName("시범경기·올스타전·친선경기와 종류가 없는 경기는 어느 시즌에도 넣지 않는다")
    void springTrainingAllStarAndUnknownAreNotStored() {
        // when // then
        // 시범경기(S)·올스타(A)·친선경기(E)와 종류를 모르는 경기는 저장하지 않는다(GameWriter.saveGame이 건너뛴다)
        assertThat(SeasonType.fromGameType("S")).isNull();
        assertThat(SeasonType.fromGameType("A")).isNull();
        assertThat(SeasonType.fromGameType("E")).isNull();
        assertThat(SeasonType.fromGameType(null)).isNull();
    }

    @Test
    @DisplayName("저장된 정규시즌·포스트시즌 값은 그대로 되읽고, 없거나 모르는 값은 비워 둔다")
    void readsTheStoredValueBack() {
        // when // then
        // 운영 DB에 이 값으로 저장돼 있다. 바꾸려면 저장된 행도 함께 바꿔야 한다
        assertThat(SeasonType.REGULAR.stored()).isEqualTo("regular");
        assertThat(SeasonType.POSTSEASON.stored()).isEqualTo("postseason");
        assertThat(SeasonType.of("regular")).isEqualTo(SeasonType.REGULAR);
        assertThat(SeasonType.of("postseason")).isEqualTo(SeasonType.POSTSEASON);
        assertThat(SeasonType.of(null)).isNull();
        assertThat(SeasonType.of("spring")).isNull();
    }
}
