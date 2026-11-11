package com.korleaguer.api;

import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.news.NewsProperties;
import com.korleaguer.photo.PhotoSources;
import com.korleaguer.posting.Postings;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    public NewsPageFinder(NewsArticleRepository news, Postings postings, PhotoSources photos,
                          NewsProperties newsProperties) {
        this.news = news;
        this.postings = postings;
        this.photos = photos;
        this.publishers = newsProperties.publishers();
    }

    /** 없는 번호이거나 뺀 언론사의 기사면 NewsNotFoundException(404) */
    public NewsPageResponse find(long id) {
        NewsArticle article = news.findById(id)
                .filter(a -> publishers.contains(a.getPublisher()))
                .orElseThrow(NewsNotFoundException::new);
        // 뺀 언론사의 기사와 이 기사를 걸러 내고도 MORE건이 남도록 넉넉히 읽는다
        List<PostingSummary.Headline> more = news.findLatest(article.getSubject(), PageRequest.of(0, MORE * 4))
                .stream()
                .filter(a -> publishers.contains(a.getPublisher()) && !a.getId().equals(article.getId()))
                .limit(MORE)
                .map(PostingSummary.Headline::of)
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
