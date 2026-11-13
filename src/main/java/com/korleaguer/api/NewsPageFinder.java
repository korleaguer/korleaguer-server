package com.korleaguer.api;

import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.news.MlbContext;
import com.korleaguer.news.NewsProperties;
import com.korleaguer.photo.PhotoSources;
import com.korleaguer.posting.Postings;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 우리 기사 페이지 한 건(NewsPageResponse)을 만든다. DB의 기사와 posting.yml(선수 사진)만 읽는다 */
@Component
@Transactional(readOnly = true)
public class NewsPageFinder {

    /** 아래에 함께 보여 줄 같은 선수의 다른 기사 수 */
    static final int MORE = 5;

    private final NewsArticleRepository news;
    private final Postings postings;
    private final PhotoSources photos;
    /** 지금 news/sources.yml에 있는 언론사. 뺀 언론사의 예전 기사는 홈처럼 여기서도 보이지 않는다 */
    private final Set<String> publishers;
    /** 영어 MLB 매체. 포스팅 선수의 국내 기사만 제목이 MLB 이야기인지 본다(MlbContext, 이슈 #127) */
    private final Set<String> aiPublishers;

    public NewsPageFinder(NewsArticleRepository news, Postings postings, PhotoSources photos,
                          NewsProperties newsProperties) {
        this.news = news;
        this.postings = postings;
        this.photos = photos;
        this.publishers = newsProperties.publishers();
        this.aiPublishers = newsProperties.aiPublishers();
    }

    /** 없는 번호이거나 뺀 언론사의 기사면 NewsNotFoundException(404) */
    public NewsPageResponse find(long id) {
        NewsArticle article = news.findById(id)
                .filter(a -> publishers.contains(a.getPublisher()))
                .orElseThrow(NewsNotFoundException::new);
        // 뺀 언론사의 기사와 이 기사(같은 글을 실은 다른 언론사 기사 포함)를 걸러 내고도 MORE건이 남도록 넉넉히 읽는다.
        // 이 기사가 최근 목록 밖이어도 같은 기사를 알아보게 함께 묶는다(SameArticles, 이슈 #113)
        // 포스팅 선수(아직 KBO 선수)면 이름만 나온 KBO 기사는 '다른 기사'에 넣지 않는다(홈 목록과 같은 기준)
        boolean posting = postings.current().stream().anyMatch(p -> p.nameKo().equals(article.getSubject()));
        List<NewsArticle> recent = new ArrayList<>(news.findLatest(article.getSubject(), PageRequest.of(0, MORE * 4))
                .stream()
                .filter(a -> publishers.contains(a.getPublisher()))
                .filter(a -> !posting || aiPublishers.contains(a.getPublisher()) || MlbContext.mentions(a.getTitle()))
                .toList());
        if (recent.stream().noneMatch(a -> a.getId().equals(article.getId()))) {
            recent.add(article);
        }
        List<PostingSummary.Headline> more = SameArticles.group(recent).stream()
                .filter(g -> !g.contains(article.getId()))
                .limit(MORE)
                .map(g -> PostingSummary.Headline.of(g))
                .toList();
        return new NewsPageResponse(article.getId(), article.getSubject(), playerPhoto(article.getSubject()),
                article.getTitle(), article.getTitleKo(), article.getUrl(), article.getPublisher(),
                article.getPublishedAt(), article.getImageUrl(), article.getSummary(), article.getLeadText(), more);
    }

    /** 이번 겨울 포스팅 선수면 그 선수 사진의 우리 주소(홈 포스팅 카드와 같은 사진) */
    private String playerPhoto(String player) {
        return postings.current().stream()
                .filter(p -> p.nameKo().equals(player))
                .findFirst()
                .map(p -> photos.postingPath(p.slug()))
                .orElse(null);
    }
}
