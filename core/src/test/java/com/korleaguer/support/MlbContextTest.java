package com.korleaguer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 포스팅 선수 국내 기사가 MLB 이야기인지(운영 노시환 기사 제목으로, 이슈 #127) */
class MlbContextTest {

    @Test
    @DisplayName("선수 이름만 스치는 국내 기사와 제목이 없는 기사는 메이저리그 기사로 보지 않는다")
    void kboArticlesThatOnlyNameThePlayerAreNotMlb() {
        // when // then
        assertThat(MlbContext.mentions("KIA가 현실적으로 필요한 FA는 구자욱보다 원태인…노시환급 초대형계약? 짧고 굵게 가는 방법도 있다"))
                .isFalse();
        assertThat(MlbContext.mentions("“오타니는 계약할 의사가 전혀 없었다” 日스타에게 恨 맺혔다? 사토 외야수로 영입추진…노시환 귀 쫑긋"))
                .isFalse();
        assertThat(MlbContext.mentions(null)).isFalse();
    }

    @Test
    @DisplayName("MLB라는 말이 없어도 미국행 이야기가 담긴 제목은 메이저리그 기사로 본다")
    void mlbArticlesWithoutTheWordMlbStillCount() {
        // when // then
        assertThat(MlbContext.mentions("'307억 포기' 노시환이 유격수? 美서 소문 확산 → \"유격수도 된대! 수비 최고래!\"")).isTrue();
        assertThat(MlbContext.mentions("'한화 307억 포기' 노시환 날벼락! 뉴욕행 대형 변수 직면했다")).isTrue();
        assertThat(MlbContext.mentions("메츠 합류한 워싱턴 코치, 2021년 한화에서 노시환과 인연에 관심 집중")).isTrue();
        assertThat(MlbContext.mentions("“큰 영향 미쳤다” 노시환 성장 도운 과거의 인연, 그의 빅리그행 도울까?")).isTrue();
        assertThat(MlbContext.mentions("한화 노시환, 메이저리그 포스팅 공시")).isTrue();
        assertThat(MlbContext.mentions("노시환, 30개 구단과 협상 시작")).isTrue();
    }

    @Test
    @DisplayName("영어 제목 기사는 메이저리그 기사로 본다")
    void englishTitlesAreFromMlbOutlets() {
        // when // then
        assertThat(MlbContext.mentions("Padres Interested In Si Hwan Roh")).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "노시환 이미 마음 굳혔다, 한화 잔류 유력",
            "노시환 100홈런의 의미 되짚어 보기",
            "노시환이 말하는 야구의 재미 하나"})
    @DisplayName("다른 말 끝에 붙은 '미'가 있는 국내 기사는 메이저리그 기사로 보지 않는다")
    void miInsideAnotherWordIsNotAmerica(String title) {
        // when // then
        assertThat(MlbContext.mentions(title)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "미 SI가 평가한 ‘한화 노시환’…“수비수로 어깨 강하지만 좌우 움직임 개선 필요”",
            "노시환 향한 미 언론의 평가",
            "\"노시환 관심\" 보도한 '미 매체'",
            "노시환, 전미 야구팬이 주목",
            "노시환 북미 진출 노린다"})
    @DisplayName("미국을 뜻하는 '미'·'전미'·'북미'가 있는 제목은 메이저리그 기사로 본다")
    void miAloneMeansAmerica(String title) {
        // when // then
        assertThat(MlbContext.mentions(title)).isTrue();
    }
}
