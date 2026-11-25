package com.korleaguer.api.article;

import com.korleaguer.domain.NewsArticle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 거론 기사의 근거 한 줄: 요약에서 그 구단 이름이 처음 나온 문장 */
class LinkedReasonsTest {

    private static final Instant AT = Instant.parse("2026-11-12T01:00:00Z");

    @Test
    @DisplayName("그 구단이 처음 나온 요약 문장과, 그 구단을 부른 가장 긴 이름을 고른다")
    void picksTheFirstSummarySentenceThatNamesTheTeamAndTheLongestName() {
        // given
        NewsArticle a = article("노시환 MLB", "노시환이 포스팅을 신청했다.\n샌디에이고 파드리스, 필라델피아 필리스, 뉴욕 메츠가 관심을 보였다.\n메츠가 가장 적극적이다.");

        // when // then
        assertThat(LinkedReasons.find("NYM", List.of(a)))
                .isEqualTo(new LinkedReasons.Reason("샌디에이고 파드리스, 필라델피아 필리스, 뉴욕 메츠가 관심을 보였다.", "뉴욕 메츠"));
        assertThat(LinkedReasons.find("PHI", List.of(a)).name()).isEqualTo("필라델피아 필리스");
    }

    @Test
    @DisplayName("조니 워싱턴 코치가 나온 문장은 워싱턴 내셔널스의 근거로 쓰지 않는다")
    void washingtonAloneIsAPersonNotTheNationals() {
        // given
        // 조니 워싱턴 코치(메츠)를 워싱턴 내셔널스로 읽지 않는다
        NewsArticle a = article("노시환 MLB", "조니 워싱턴 코치가 메츠에 합류했다.\n워싱턴 내셔널스도 3루수를 찾는다.");

        // when // then
        assertThat(LinkedReasons.find("WSH", List.of(a)))
                .isEqualTo(new LinkedReasons.Reason("워싱턴 내셔널스도 3루수를 찾는다.", "워싱턴 내셔널스"));
        assertThat(LinkedReasons.find("NYM", List.of(a)).sentence()).isEqualTo("조니 워싱턴 코치가 메츠에 합류했다.");
    }

    @Test
    @DisplayName("앞 기사 요약에 구단이 없으면 다음 기사 요약에서 문장을 찾는다")
    void laterArticlesAreTriedWhenTheLeadSummaryDoesNotNameTheTeam() {
        // given
        NewsArticle lead = article("노시환 MLB 도전", "노시환이 포스팅을 신청했다.");
        NewsArticle copy = article("노시환 MLB 도전", "시애틀 매리너스가 노시환을 꼽았다.");

        // when // then
        assertThat(LinkedReasons.find("SEA", List.of(lead, copy)).sentence()).isEqualTo("시애틀 매리너스가 노시환을 꼽았다.");
    }

    @Test
    @DisplayName("요약이 없으면 제목에 나온 구단 이름만 주고 문장은 비운다")
    void withoutASentenceOnlyTheNameInTheLeadTitleIsGiven() {
        // given
        NewsArticle english = article("Roh could fit the Mariners", null);
        english.enriched(null, "노시환, 매리너스에 어울린다", null, "m", AT);

        // when // then
        assertThat(LinkedReasons.find("SEA", List.of(english))).isEqualTo(new LinkedReasons.Reason(null, "매리너스"));
        assertThat(LinkedReasons.find("TOR", List.of(english))).isEqualTo(new LinkedReasons.Reason(null, null));
        assertThat(LinkedReasons.find("XYZ", List.of(english))).isEqualTo(new LinkedReasons.Reason(null, null));
    }

    private static NewsArticle article(String title, String summary) {
        NewsArticle a = new NewsArticle("노시환", "https://a.example/" + title.hashCode(), "h" + title.hashCode(), title,
                "마이데일리", AT, AT);
        if (summary != null) {
            a.enriched(null, null, summary, "m", AT);
        }
        return a;
    }
}
