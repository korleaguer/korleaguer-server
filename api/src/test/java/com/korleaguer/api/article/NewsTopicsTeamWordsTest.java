package com.korleaguer.api.article;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 고른 선수의 지금 구단을 주제 버튼에서 뺄 때 쓰는 구단 이름: 다른 약어도 대표 약어와 같은 이름을 낸다 */
class NewsTopicsTeamWordsTest {

    @ParameterizedTest(name = "{0}은 {1}와 같은 이름")
    @DisplayName("다른 구단 약어는 대표 약어와 같은 주제 이름을 낸다")
    @CsvSource({"SFG, SF", "AZ, ARI", "WAS, WSH", "CHW, CWS", "OAK, ATH"})
    void aliasesGiveTheSameWords(String alias, String main) {
        // when // then
        assertThat(NewsTopics.teamWords(alias)).isNotEmpty().isEqualTo(NewsTopics.teamWords(main));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("모르는 약어는 빼는 이름이 없다")
    @CsvSource({"KIA", "XYZ"})
    void unknownAbbreviationHasNoWords(String abbreviation) {
        // when // then
        assertThat(NewsTopics.teamWords(abbreviation)).isEqualTo(List.of());
    }
}
