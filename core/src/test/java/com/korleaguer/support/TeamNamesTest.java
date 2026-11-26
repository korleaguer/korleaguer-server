package com.korleaguer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 쓰는 곳마다 다른 구단 약어도 대표 약어와 같은 한글 이름·기사 검색 이름을 낸다 */
class TeamNamesTest {

    @ParameterizedTest(name = "{0}은 {1}와 같은 구단 {2}")
    @DisplayName("다른 구단 약어는 대표 약어와 같은 한글 이름과 기사 검색 이름을 낸다")
    @CsvSource({
            "AZ, ARI, 애리조나",
            "OAK, ATH, 애슬레틱스",
            "CHW, CWS, 시카고 화이트삭스",
            "KCR, KC, 캔자스시티",
            "SDP, SD, 샌디에이고",
            "SFG, SF, 샌프란시스코",
            "TBR, TB, 탬파베이",
            "WSN, WSH, 워싱턴",
            "WAS, WSH, 워싱턴"})
    void aliasesGiveTheSameNamesAsTheMainAbbreviation(String alias, String main, String korean) {
        // when // then
        assertThat(TeamNames.korean(alias, alias)).isEqualTo(korean);
        assertThat(TeamNames.searchNames(alias)).isNotEmpty().isEqualTo(TeamNames.searchNames(main));
    }

    @ParameterizedTest(name = "MLB 구단 약어가 아닌 {0}은 받은 이름을 그대로 쓰고 검색 이름이 없다")
    @DisplayName("MLB 구단 약어가 아니면 받은 이름을 그대로 쓰고 기사 검색 이름이 없다")
    @CsvSource({"KIA", "XYZ"})
    void unknownAbbreviationKeepsTheGivenName(String abbreviation) {
        // when // then
        assertThat(TeamNames.korean(abbreviation, "그대로")).isEqualTo("그대로");
        assertThat(TeamNames.searchNames(abbreviation)).isEmpty();
    }
}
