package com.korleaguer.api.article;

import com.korleaguer.domain.NewsArticle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 언론사만 다른 같은 기사 알아보기와 묶기(이슈 #113). 기사 글은 지어낸 것이다 */
class SameArticlesTest {

    /** 통신사 원문(맨 앞 출처·기자 표시가 붙는다) */
    private static final String WIRE = "(서울=통신사) 홍길동 기자 = 메이저리그 진출을 노리는 노시환과 뉴욕 메츠 새 타격코치의 "
            + "한화 시절 인연이 미국 현지에서 눈길을 끌고 있다. 칼럼니스트는 코치가 노시환의 성장에 큰 영향을 줬다고 소개했다.";
    /** 다른 언론사가 제목만 바꿔 그대로 실은 것 */
    private static final String COPY = "메이저리그 진출을 노리는 노시환과 뉴욕 메츠 새 타격코치의 한화 시절 인연이 미국 현지에서 "
            + "눈길을 끌고 있다. 칼럼니스트는 코치가 노시환의 성장에 큰 영향을 줬다고 소개했다.";
    /** 같은 소식을 그 언론사가 따로 쓴 것 */
    private static final String OWN = "[스포츠신문 김기자] 뉴욕 메츠가 새로 데려온 타격코치가 한화에서 노시환을 가르쳤던 인연으로 "
            + "화제다. 현지 칼럼니스트가 두 사람의 과거를 짚으며 메츠행 가능성도 언급했다.";

    @Test
    @DisplayName("따옴표 모양만 다른 제목이면 같은 기사로 본다")
    void theSameTitleWithOtherQuoteMarksIsTheSameArticle() {
        // when // then
        // 뉴시스 기사를 동아일보가 실었다: 따옴표 모양만 다르다(운영 11-11)
        assertThat(SameArticles.same(
                article(1, "'MLB 도전' 노시환, 메츠 코치와의 인연도 '눈길'", "뉴시스", "2026-11-11T02:13:00Z", null),
                article(2, "‘MLB 도전’ 노시환, 메츠 코치와의 인연도 ‘눈길’", "동아일보", "2026-11-11T02:25:00Z", null)))
                .isTrue();
    }

    @Test
    @DisplayName("제목이 달라도 본문 첫머리가 같으면 같은 기사로 본다")
    void aWireStoryRunUnderAnotherTitleIsTheSameArticle() {
        // when // then
        assertThat(SameArticles.same(article(1, "노시환과 메츠 코치의 인연, 미국 현지서 화제", "연합뉴스", "2026-11-11T00:30:00Z", WIRE),
                article(2, "'노시환 특급 인맥?' 메츠 코치와 인연", "노컷뉴스", "2026-11-11T01:23:00Z", COPY))).isTrue();
    }

    @Test
    @DisplayName("같은 소식이라도 언론사가 따로 쓴 글이면 다른 기사로 본다")
    void theSameNewsWrittenByEachOutletIsNotTheSameArticle() {
        // when // then
        assertThat(SameArticles.same(article(1, "노시환과 메츠 코치의 인연, 미국 현지서 화제", "연합뉴스", "2026-11-11T00:30:00Z", WIRE),
                article(2, "메츠 새 코치와 노시환의 인연 화제", "스포츠신문", "2026-11-11T01:00:00Z", OWN))).isFalse();
    }

    @Test
    @DisplayName("본문 첫머리가 너무 짧으면 글이 같아도 같은 기사로 보지 않는다")
    void shortLeadsAreNotCompared() {
        // when // then
        assertThat(SameArticles.same(article(1, "노시환 포스팅", "마이데일리", "2026-11-02T00:00:00Z", "노시환이 포스팅됐다."),
                article(2, "노시환 공시", "스포츠신문", "2026-11-02T01:00:00Z", "노시환이 포스팅됐다."))).isFalse();
    }

    @Test
    @DisplayName("제목이 같아도 사흘 차이로 나온 기사는 묶지 않는다")
    void theSameTitleDaysApartIsNotTheSameArticle() {
        // given
        // 연재 제목처럼 다른 날 같은 제목으로 나온 새 기사는 옛 기사와 묶지 않는다(NEAR 이틀)
        NewsArticle earlier = article(1, "[MLB 오늘] 노시환 소식", "스포츠신문", "2026-11-02T00:00:00Z", null);
        NewsArticle later = article(2, "[MLB 오늘] 노시환 소식", "스포츠신문", "2026-11-05T00:00:00Z", null);

        // when // then
        assertThat(SameArticles.same(earlier, later)).isFalse();
        assertThat(SameArticles.group(List.of(later, earlier))).extracting(g -> g.article().getId())
                .containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("묶음은 가장 먼저 난 기사를 보이고 나머지 언론사를 낸 순서로 적는다")
    void aGroupShowsTheFirstPublishedArticleAndTheOtherOutletsInOrder() {
        // given
        NewsArticle wire = summarized(article(1, "노시환과 메츠 코치의 인연", "연합뉴스", "2026-11-11T00:30:00Z", WIRE));
        NewsArticle tv = summarized(article(2, "노시환과 메츠 코치의 인연, 현지도 관심", "SBS", "2026-11-11T01:44:00Z", COPY));
        NewsArticle paper = summarized(article(3, "'노시환 특급 인맥?'", "노컷뉴스", "2026-11-11T01:23:00Z", COPY));
        NewsArticle other = summarized(article(4, "노시환 수비 호평", "마이데일리", "2026-11-11T03:00:00Z", OWN));

        // when
        List<SameArticles.Group> groups = SameArticles.group(List.of(other, tv, paper, wire)); // 늦은 순으로 온다

        // then
        assertThat(groups).extracting(g -> g.article().getId()).containsExactly(4L, 1L); // 묶음은 대표의 늦은 순
        assertThat(groups.get(1).alsoBy()).containsExactly("노컷뉴스", "SBS"); // 낸 순
        assertThat(groups.get(1).contains(2L)).isTrue();
        assertThat(groups.getFirst().alsoBy()).isEmpty();
    }

    @Test
    @DisplayName("대표가 아닌 사본을 열면 그 언론사를 빼고 대표 언론사를 넣어 적는다")
    void otherOutletsAreCountedFromTheArticleBeingShown() {
        // given
        // 기사 페이지는 대표가 아닌 사본도 연다(이슈 #180): 그 사본의 언론사는 빠지고 대표 언론사가 들어간다
        NewsArticle wire = summarized(article(1, "노시환과 메츠 코치의 인연", "연합뉴스", "2026-11-11T00:30:00Z", WIRE));
        NewsArticle paper = article(2, "'노시환 특급 인맥?'", "노컷뉴스", "2026-11-11T01:23:00Z", COPY);
        NewsArticle tv = article(3, "노시환과 메츠 코치의 인연, 현지도 관심", "SBS", "2026-11-11T01:44:00Z", COPY);
        NewsArticle paperAgain = article(4, "'노시환 특급 인맥?' 메츠 코치", "노컷뉴스", "2026-11-11T02:00:00Z", COPY);

        // when
        SameArticles.Group group = SameArticles.group(List.of(paperAgain, tv, paper, wire)).getFirst();

        // then
        assertThat(group.article()).isSameAs(wire);
        assertThat(group.alsoBy(wire)).isEqualTo(group.alsoBy()).containsExactly("노컷뉴스", "SBS"); // 목록 한 줄과 같다
        assertThat(group.alsoBy(paper)).containsExactly("연합뉴스", "SBS"); // 낸 순, 두 번 실은 노컷뉴스도 빠진다
        assertThat(group.alsoBy(tv)).containsExactly("연합뉴스", "노컷뉴스"); // 같은 언론사는 한 번
    }

    @Test
    @DisplayName("같은 기사가 없으면 다른 언론사 목록이 비어 있다")
    void anArticleWithNoCopiesHasNoOtherOutlets() {
        // given
        NewsArticle alone = article(1, "노시환 수비 호평", "마이데일리", "2026-11-11T03:00:00Z", OWN);

        // when
        SameArticles.Group group = SameArticles.group(List.of(alone)).getFirst();

        // then
        assertThat(group.alsoBy()).isEmpty();
        assertThat(group.alsoBy(alone)).isEmpty();
    }

    @Test
    @DisplayName("먼저 난 기사에 요약이 없으면 요약이 있는 사본을 대표로 보인다")
    void aSummarizedCopyLeadsWhenTheFirstOneHasNoSummary() {
        // given
        // 조선일보처럼 본문을 못 읽어 요약이 없는 사본보다 요약이 있는 기사를 보인다
        NewsArticle first = article(1, "'307억 포기' 노시환이 유격수?", "스포츠조선", "2026-11-12T00:50:00Z", null);
        NewsArticle copy = summarized(article(2, "'307억 포기' 노시환이 유격수?", "조선일보", "2026-11-12T02:00:00Z", null));

        // when
        SameArticles.Group group = SameArticles.group(List.of(copy, first)).getFirst();

        // then
        assertThat(group.article().getId()).isEqualTo(2L);
        assertThat(group.alsoBy()).containsExactly("스포츠조선");
    }

    private static NewsArticle summarized(NewsArticle article) {
        article.enriched(null, null, "요약이다.", "claude-haiku-5-5/v4", article.getPublishedAt().plusSeconds(600));
        return article;
    }

    private static NewsArticle article(long id, String title, String publisher, String publishedAt, String lead) {
        NewsArticle article = new NewsArticle("노시환", "https://news.example/" + id, "h" + id, title, publisher,
                Instant.parse(publishedAt), Instant.parse(publishedAt).plusSeconds(60));
        ReflectionTestUtils.setField(article, "id", id);
        if (lead != null) {
            article.setLeadText(lead);
        }
        return article;
    }
}
