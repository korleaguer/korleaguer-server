package com.korleaguer.api.article;

import com.korleaguer.domain.NewsArticle;
import com.korleaguer.support.MlbContext;

import java.util.Set;

/**
 * 화면에 보일 기사인지. 홈 MLB 도전 카드·성적표·선수 화면·기사 페이지의 다른 기사 목록이 같은 기준을 쓴다(뉴스 탭 목록은
 * 같은 뜻을 쿼리로 거른다). 기사 페이지에서 주소로 연 기사 자체는 언론사만 본다(NewsPageFinder.shown).
 */
public final class ShownNews {

    private ShownNews() {
    }

    /**
     * 지금 수집하는 언론사의 기사이고, 이 선수가 주인공인 기사(주인공 아님은 AI 판단, 이슈 #129).
     * 수집을 멈춘 언론사의 기사는 DB에 남아 있어도 보이지 않는다.
     *
     * @param publishers 지금 수집하는 언론사 이름(NewsProperties.publishers)
     */
    public static boolean isShown(NewsArticle article, Set<String> publishers) {
        return publishers.contains(article.getPublisher()) && !article.isOffTopic();
    }

    /**
     * 포스팅 선수(아직 KBO 선수) 기사 가운데 MLB 이야기인지(이슈 #127). 이름만으로 모으면 KBO 기사가 섞여서, 국내 기사는
     * 제목에 MLB 쪽 말이 있어야 보인다. 영어 MLB 매체(AI 요약 출처)의 기사는 제목을 보지 않는다.
     * 수집(ingest.NewsCollector)도 받을 때 같은 뜻으로 거르지만, 그 전에 모은 기사가 남아 있어 보일 때 한 번 더 거른다.
     *
     * @param aiPublishers 영어 MLB 매체 이름(NewsProperties.aiPublishers)
     */
    public static boolean isMlbStory(NewsArticle article, Set<String> aiPublishers) {
        return aiPublishers.contains(article.getPublisher()) || MlbContext.mentions(article.getTitle());
    }
}
