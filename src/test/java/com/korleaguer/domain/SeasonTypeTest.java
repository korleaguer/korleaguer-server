package com.korleaguer.domain;

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
    @CsvSource({"R, REGULAR", "F, POSTSEASON", "D, POSTSEASON", "L, POSTSEASON", "W, POSTSEASON"})
    void storedGameTypes(String gameType, SeasonType expected) {
        assertThat(SeasonType.fromGameType(gameType)).isEqualTo(expected);
    }

    @Test
    void springTrainingAllStarAndUnknownAreNotStored() {
        // 시범경기(S)·올스타(A)·친선경기(E)와 종류를 모르는 경기는 저장하지 않는다(IngestWriter.saveGame이 건너뛴다)
        assertThat(SeasonType.fromGameType("S")).isNull();
        assertThat(SeasonType.fromGameType("A")).isNull();
        assertThat(SeasonType.fromGameType("E")).isNull();
        assertThat(SeasonType.fromGameType(null)).isNull();
    }

    @Test
    void readsTheStoredValueBack() {
        // 운영 DB에 이 값으로 저장돼 있다. 바꾸려면 저장된 행도 함께 바꿔야 한다
        assertThat(SeasonType.REGULAR.stored()).isEqualTo("regular");
        assertThat(SeasonType.POSTSEASON.stored()).isEqualTo("postseason");
        assertThat(SeasonType.of("regular")).isEqualTo(SeasonType.REGULAR);
        assertThat(SeasonType.of("postseason")).isEqualTo(SeasonType.POSTSEASON);
        assertThat(SeasonType.of(null)).isNull();
        assertThat(SeasonType.of("spring")).isNull();
    }
}
