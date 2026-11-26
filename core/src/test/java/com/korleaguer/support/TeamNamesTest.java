package com.korleaguer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 쓰는 곳마다 다른 구단 약어도 대표 약어와 같은 한글 이름·기사 검색 이름을 낸다 */
class TeamNamesTest {

    @ParameterizedTest(name = "{0}은 {1}")
    @DisplayName("다른 구단 약어도 그 구단의 한글 이름을 낸다")
    @CsvSource({
            "AZ, 애리조나",
            "OAK, 애슬레틱스",
            "CHW, 시카고 화이트삭스",
            "KCR, 캔자스시티",
            "SDP, 샌디에이고",
            "SFG, 샌프란시스코",
            "TBR, 탬파베이",
            "WSN, 워싱턴",
            "WAS, 워싱턴"})
    void aliasesGiveTheClubKoreanName(String alias, String korean) {
        // when // then
        assertThat(TeamNames.korean(alias, alias)).isEqualTo(korean);
    }

    @ParameterizedTest(name = "{0}의 첫 검색 이름은 {1}")
    @DisplayName("다른 구단 약어도 그 구단의 기사 검색 이름을 낸다")
    @CsvSource({
            "AZ, 애리조나 다이아몬드백스",
            "OAK, 오클랜드 애슬레틱스",
            "CHW, 시카고 화이트삭스",
            "KCR, 캔자스시티 로열스",
            "SDP, 샌디에이고 파드리스",
            "SFG, 샌프란시스코 자이언츠",
            "TBR, 탬파베이 레이스",
            "WSN, 워싱턴 내셔널스",
            "WAS, 워싱턴 내셔널스"})
    void aliasesGiveTheClubSearchNames(String alias, String firstName) {
        // when // then
        assertThat(TeamNames.searchNames(alias)).first().isEqualTo(firstName);
    }

    @ParameterizedTest(name = "MLB 구단 약어가 아닌 {0}")
    @DisplayName("MLB 구단 약어가 아니면 받은 이름을 그대로 쓰고 기사 검색 이름이 없다")
    @CsvSource({"KIA", "XYZ"})
    void unknownAbbreviationKeepsTheGivenName(String abbreviation) {
        // when // then
        assertThat(TeamNames.korean(abbreviation, "그대로")).isEqualTo("그대로");
        assertThat(TeamNames.searchNames(abbreviation)).isEqualTo(List.of());
    }
}
