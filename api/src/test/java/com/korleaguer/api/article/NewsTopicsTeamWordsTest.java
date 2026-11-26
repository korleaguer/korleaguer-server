package com.korleaguer.api.article;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 고른 선수의 지금 구단을 주제 버튼에서 뺄 때 쓰는 구단 이름: 다른 약어도 그 구단 이름을 낸다 */
class NewsTopicsTeamWordsTest {

    @ParameterizedTest(name = "{0}은 {1}")
    @DisplayName("다른 구단 약어도 그 구단의 주제 이름을 낸다")
    @CsvSource({
            "AZ, 다이아몬드백스|애리조나",
            "OAK, 애슬레틱스",
            "CHW, 화이트삭스",
            "KCR, 로열스|캔자스시티",
            "SDP, 파드리스|샌디에이고",
            "SFG, 자이언츠|샌프란시스코",
            "TBR, 탬파베이",
            "WSN, 내셔널스|워싱턴",
            "WAS, 내셔널스|워싱턴"})
    void aliasesGiveTheClubWords(String alias, String words) {
        // when // then
        assertThat(NewsTopics.teamWords(alias)).isEqualTo(Arrays.asList(words.split("\\|")));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("모르는 약어는 빼는 이름이 없다")
    @CsvSource({"KIA", "XYZ"})
    void unknownAbbreviationHasNoWords(String abbreviation) {
        // when // then
        assertThat(NewsTopics.teamWords(abbreviation)).isEqualTo(List.of());
    }
}
