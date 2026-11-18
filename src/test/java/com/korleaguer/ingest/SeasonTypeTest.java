package com.korleaguer.ingest;

import com.korleaguer.domain.SeasonType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수집이 game.season_type에 저장하는 값(IngestWriter.seasonType)과 경기 줄 응답의 경기 종류(SeasonType, 이슈 #190)가
 * 맞물리는지. 저장 값을 바꾸면 응답이 조용히 null이 되지 않게 여기서 깨진다.
 */
class SeasonTypeTest {

    @ParameterizedTest
    @CsvSource({"R, REGULAR", "F, POSTSEASON", "D, POSTSEASON", "L, POSTSEASON", "W, POSTSEASON"})
    void everyStoredGameTypeHasASeasonType(String gameType, SeasonType expected) {
        assertThat(SeasonType.of(IngestWriter.seasonType(gameType))).isEqualTo(expected);
    }

    @Test
    void gamesWeDoNotStoreAndUnknownValuesAreNull() {
        // 시범경기(S)·올스타(A)는 저장하지 않는다(seasonType null)
        assertThat(SeasonType.of(IngestWriter.seasonType("S"))).isNull();
        assertThat(SeasonType.of(IngestWriter.seasonType("A"))).isNull();
        assertThat(SeasonType.of(null)).isNull();
        assertThat(SeasonType.of("spring")).isNull();
    }
}
