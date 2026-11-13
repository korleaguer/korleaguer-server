package com.korleaguer.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 포스팅 선수 국내 기사가 MLB 이야기인지(운영 노시환 기사 제목으로, 이슈 #127) */
class MlbContextTest {

    @Test
    void kboArticlesThatOnlyNameThePlayerAreNotMlb() {
        assertThat(MlbContext.mentions("KIA가 현실적으로 필요한 FA는 구자욱보다 원태인…노시환급 초대형계약? 짧고 굵게 가는 방법도 있다"))
                .isFalse();
        assertThat(MlbContext.mentions("“오타니는 계약할 의사가 전혀 없었다” 日스타에게 恨 맺혔다? 사토 외야수로 영입추진…노시환 귀 쫑긋"))
                .isFalse();
        assertThat(MlbContext.mentions(null)).isFalse();
    }

    @Test
    void mlbArticlesWithoutTheWordMlbStillCount() {
        assertThat(MlbContext.mentions("'307억 포기' 노시환이 유격수? 美서 소문 확산 → \"유격수도 된대! 수비 최고래!\"")).isTrue();
        assertThat(MlbContext.mentions("'한화 307억 포기' 노시환 날벼락! 뉴욕행 대형 변수 직면했다")).isTrue();
        assertThat(MlbContext.mentions("메츠 합류한 워싱턴 코치, 2021년 한화에서 노시환과 인연에 관심 집중")).isTrue();
        assertThat(MlbContext.mentions("“큰 영향 미쳤다” 노시환 성장 도운 과거의 인연, 그의 빅리그행 도울까?")).isTrue();
        assertThat(MlbContext.mentions("한화 노시환, 메이저리그 포스팅 공시")).isTrue();
        assertThat(MlbContext.mentions("노시환, 30개 구단과 협상 시작")).isTrue();
    }

    @Test
    void englishTitlesAreFromMlbOutlets() {
        assertThat(MlbContext.mentions("Padres Interested In Si Hwan Roh")).isTrue();
    }
}
