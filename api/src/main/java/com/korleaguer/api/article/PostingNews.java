package com.korleaguer.api.article;

import com.korleaguer.domain.NewsArticle;
import com.korleaguer.support.MlbContext;

import java.util.Set;

/**
 * 포스팅 선수(아직 KBO 선수) 기사 가운데 화면에 보일 기사인지(이슈 #127). 이름만으로 모으면 KBO 기사가 섞여서, 국내 기사는
 * 제목에 MLB 쪽 말이 있어야 보인다. 영어 MLB 매체(AI 요약 출처)의 기사는 제목을 보지 않는다.
 * 홈 MLB 도전 카드·뉴스 탭 목록·기사 페이지가 같은 규칙을 쓴다. 수집(ingest.NewsCollector)도 받을 때 같은 뜻으로 거르지만,
 * 그 전에 모은 기사가 남아 있어 보일 때 한 번 더 거른다.
 */
public final class PostingNews {

    private PostingNews() {
    }

    /** @param aiPublishers 영어 MLB 매체 이름(NewsProperties.aiPublishers) */
    public static boolean isMlbStory(NewsArticle article, Set<String> aiPublishers) {
        return aiPublishers.contains(article.getPublisher()) || MlbContext.mentions(article.getTitle());
    }
}
