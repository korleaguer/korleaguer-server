package com.korleaguer.api;

import com.korleaguer.domain.NewsArticle;
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
    void theSameTitleWithOtherQuoteMarksIsTheSameArticle() {
        // 뉴시스 기사를 동아일보가 실었다: 따옴표 모양만 다르다(운영 11-11)
        assertThat(SameArticles.same(
                article(1, "'MLB 도전' 노시환, 메츠 코치와의 인연도 '눈길'", "뉴시스", "2026-11-11T02:13:00Z", null),
                article(2, "‘MLB 도전’ 노시환, 메츠 코치와의 인연도 ‘눈길’", "동아일보", "2026-11-11T02:25:00Z", null)))
                .isTrue();
    }

    @Test
    void aWireStoryRunUnderAnotherTitleIsTheSameArticle() {
        assertThat(SameArticles.same(article(1, "노시환과 메츠 코치의 인연, 미국 현지서 화제", "연합뉴스", "2026-11-11T00:30:00Z", WIRE),
                article(2, "'노시환 특급 인맥?' 메츠 코치와 인연", "노컷뉴스", "2026-11-11T01:23:00Z", COPY))).isTrue();
    }

    @Test
    void theSameNewsWrittenByEachOutletIsNotTheSameArticle() {
        assertThat(SameArticles.same(article(1, "노시환과 메츠 코치의 인연, 미국 현지서 화제", "연합뉴스", "2026-11-11T00:30:00Z", WIRE),
                article(2, "메츠 새 코치와 노시환의 인연 화제", "스포츠신문", "2026-11-11T01:00:00Z", OWN))).isFalse();
    }

    @Test
    void shortLeadsAreNotCompared() {
        assertThat(SameArticles.same(article(1, "노시환 포스팅", "마이데일리", "2026-11-02T00:00:00Z", "노시환이 포스팅됐다."),
                article(2, "노시환 공시", "스포츠신문", "2026-11-02T01:00:00Z", "노시환이 포스팅됐다."))).isFalse();
    }

    @Test
    void aGroupShowsTheFirstPublishedArticleAndTheOtherOutletsInOrder() {
        NewsArticle wire = summarized(article(1, "노시환과 메츠 코치의 인연", "연합뉴스", "2026-11-11T00:30:00Z", WIRE));
        NewsArticle tv = summarized(article(2, "노시환과 메츠 코치의 인연, 현지도 관심", "SBS", "2026-11-11T01:44:00Z", COPY));
        NewsArticle paper = summarized(article(3, "'노시환 특급 인맥?'", "노컷뉴스", "2026-11-11T01:23:00Z", COPY));
        NewsArticle other = summarized(article(4, "노시환 수비 호평", "마이데일리", "2026-11-11T03:00:00Z", OWN));

        List<SameArticles.Group> groups = SameArticles.group(List.of(other, tv, paper, wire)); // 늦은 순으로 온다

        assertThat(groups).extracting(g -> g.article().getId()).containsExactly(4L, 1L); // 묶음은 대표의 늦은 순
        assertThat(groups.get(1).alsoBy()).containsExactly("노컷뉴스", "SBS"); // 낸 순
        assertThat(groups.get(1).contains(2L)).isTrue();
        assertThat(groups.getFirst().alsoBy()).isEmpty();
    }

    @Test
    void aSummarizedCopyLeadsWhenTheFirstOneHasNoSummary() {
        // 조선일보처럼 본문을 못 읽어 요약이 없는 사본보다 요약이 있는 기사를 보인다
        NewsArticle first = article(1, "'307억 포기' 노시환이 유격수?", "스포츠조선", "2026-11-12T00:50:00Z", null);
        NewsArticle copy = summarized(article(2, "'307억 포기' 노시환이 유격수?", "조선일보", "2026-11-12T02:00:00Z", null));

        SameArticles.Group group = SameArticles.group(List.of(copy, first)).getFirst();

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
