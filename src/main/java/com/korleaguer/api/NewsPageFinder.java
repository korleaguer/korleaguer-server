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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 우리 기사 페이지 한 건(NewsPageResponse)을 만든다. DB의 기사와 posting.yml(선수 사진)만 읽는다 */
@Component
@Transactional(readOnly = true)
public class NewsPageFinder {

    /** 아래에 함께 보여 줄 같은 선수의 다른 기사 수 */
    static final int MORE = 5;
    /**
     * 이 기사의 같은 기사 묶음을 찾으려고 읽는 횟수 상한. 묶음이 읽은 범위 끝에 걸리면 넓혀 다시 읽는데(sameAs), 같은 제목
     * 연재가 날마다 이어지는 식으로 묶음이 끝없이 이어져도 기사 한 건 열 때 쿼리가 한없이 늘지 않게 막는다
     */
    static final int MAX_SAME_READS = 4;

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
        // 포스팅 선수(아직 KBO 선수)면 이름만 나온 KBO 기사는 '다른 기사'에도 같은 기사 언론사에도 넣지 않는다(홈·뉴스 탭 목록과 같은 기준)
        boolean posting = postings.current().stream().anyMatch(p -> p.nameKo().equals(article.getSubject()));
        SameArticles.Group same = sameAs(article, posting);
        Set<Long> sameIds = same.members().stream().map(NewsArticle::getId).collect(Collectors.toSet());
        // 뺀 언론사의 기사와 이 기사(같은 글을 실은 다른 언론사 기사 포함)를 걸러 내고도 MORE건이 남도록 넉넉히 읽는다.
        // 이 기사가 최근 목록 밖이어도 같은 기사를 알아보게 함께 묶는다(SameArticles, 이슈 #113)
        List<NewsArticle> recent = withArticle(article, kept(
                news.findLatest(article.getSubject(), PageRequest.of(0, MORE * 4)), posting));
        // 이 기사의 묶음에 든 사본은 '다른 기사'에 넣지 않는다. 최근 기사만으로는 이어 주는 기사가 빠져 따로 묶일 수 있는데,
        // 그러면 같은 언론사가 위 '같은 기사'(alsoBy)와 아래 '다른 기사'에 함께 보인다
        List<PostingSummary.Headline> more = SameArticles.group(recent).stream()
                .filter(g -> g.members().stream().noneMatch(a -> sameIds.contains(a.getId())))
                .limit(MORE)
                .map(g -> PostingSummary.Headline.of(g))
                .toList();
        return new NewsPageResponse(article.getId(), article.getSubject(), playerPhoto(article.getSubject()),
                article.getTitle(), article.getTitleKo(), article.getUrl(), article.getPublisher(),
                article.getPublishedAt(), article.getImageUrl(), article.getSummary(), article.getLeadText(), more,
                same.alsoBy(article));
    }

    /**
     * 이 기사가 든 같은 기사 묶음(SameArticles). 그 선수 목록(뉴스 탭에서 선수를 고른 목록·홈·선수 화면)이 이 기사를 묶는
     * 기사들과 같게 찾는다. 최근 기사가 아니라 이 기사 앞뒤 NEAR를 읽는다: 뉴스 탭을 넘겨 연 오래된 기사는 최근 기사에 사본이
     * 없다. 목록은 사본을 이어 주는 기사(제목은 이 기사와, 첫머리는 다른 사본과 같은 식)로 이 기사에서 NEAR 넘게 떨어진
     * 사본까지 한 줄로 묶으므로, 묶음이 읽은 범위 끝에 걸리면 묶음 앞뒤 NEAR로 넓혀 다시 읽는다(MAX_SAME_READS번까지)
     */
    private SameArticles.Group sameAs(NewsArticle article, boolean posting) {
        Instant from = article.getPublishedAt().minus(SameArticles.NEAR);
        Instant to = article.getPublishedAt().plus(SameArticles.NEAR);
        for (int read = 1; ; read++) {
            List<NewsArticle> near = withArticle(article,
                    kept(news.findPublishedBetween(article.getSubject(), from, to), posting));
            SameArticles.Group same = SameArticles.group(near).stream()
                    .filter(g -> g.contains(article.getId()))
                    .findFirst()
                    .orElseThrow();
            Instant first = same.members().getFirst().getPublishedAt().minus(SameArticles.NEAR);
            Instant last = same.members().getLast().getPublishedAt().plus(SameArticles.NEAR);
            // 묶음의 어느 기사와도 NEAR 안인 기사를 다 읽었다: 더 넓혀 읽어도 이 묶음에 붙을 기사가 없다
            if ((!first.isBefore(from) && !last.isAfter(to)) || read == MAX_SAME_READS) {
                return same;
            }
            from = first.isBefore(from) ? first : from;
            to = last.isAfter(to) ? last : to;
        }
    }

    /**
     * 홈·뉴스 탭 목록과 같은 기준으로 거른다: 지금 수집하는 언론사의, 선수가 주인공인(AI 판단, 이슈 #129) 기사.
     * 포스팅 선수면 국내 기사는 제목이 MLB 이야기인 것만
     */
    private List<NewsArticle> kept(List<NewsArticle> articles, boolean posting) {
        return articles.stream()
                .filter(a -> publishers.contains(a.getPublisher()))
                .filter(a -> !posting || aiPublishers.contains(a.getPublisher()) || MlbContext.mentions(a.getTitle()))
                .filter(a -> !a.isOffTopic())
                .toList();
    }

    /** 읽어서 거른 기사에 이 기사가 없으면 넣는다. 읽은 범위 밖이거나 걸러진 기사여도 그 사본은 알아봐야 한다 */
    private static List<NewsArticle> withArticle(NewsArticle article, List<NewsArticle> articles) {
        if (articles.stream().anyMatch(a -> a.getId().equals(article.getId()))) {
            return articles;
        }
        List<NewsArticle> out = new ArrayList<>(articles);
        out.add(article);
        return out;
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
