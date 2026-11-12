package com.korleaguer.api;

import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.news.NewsProperties;
import com.korleaguer.photo.PhotoSources;
import com.korleaguer.posting.PastPosting;
import com.korleaguer.posting.PostingCase;
import com.korleaguer.posting.PostingResult;
import com.korleaguer.posting.Postings;
import com.korleaguer.support.KoreanTime;
import com.korleaguer.support.MlbTeams;
import com.korleaguer.support.TeamNames;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 홈의 포스팅 묶음(PostingSummary)을 만든다. posting.yml(Postings)과 DB의 관련 기사만 읽는다.
 * 비시즌일 때만 부른다(BaseballQueryService). 포스팅 선수도 지난 기록도 없으면 null이다.
 */
@Component
public class PostingFinder {

    /** 협상 마감 뒤 이만큼 지나면 이번 겨울 선수 목록에서 뺀다(결과를 보여 줄 기간) */
    static final Duration SHOW_AFTER_DEADLINE = Duration.ofDays(60);
    static final int NEWS_PER_PLAYER = 5;

    private final Postings postings;
    private final NewsArticleRepository news;
    private final PhotoSources photos;
    /** 지금 news/sources.yml에 있는 언론사. 뺀 언론사(이용약관으로 뺀 뉴시스 등)의 예전 기사는 보이지 않는다 */
    private final Set<String> publishers;

    public PostingFinder(Postings postings, NewsArticleRepository news, PhotoSources photos, NewsProperties newsProperties) {
        this.postings = postings;
        this.news = news;
        this.photos = photos;
        this.publishers = newsProperties.publishers();
    }

    /**
     * 그 선수의 관련 기사(늦은 순 NEWS_PER_PLAYER건, 지금 수집하는 언론사만). 언론사만 다른 같은 기사는 한 줄로 묶어
     * 서로 다른 기사로 채운다(SameArticles, 이슈 #113).
     * 국내 기사가 많으면 영어 기사의 AI 요약이 밀려 안 보이므로, 그 안에 요약 기사가 없으면 가장 최근 요약 기사의 묶음을
     * 마지막 자리에 넣는다(덜 찼으면 맨 뒤에 붙인다. 사용자가 원한 것이 영어 기사의 한국어 요약이다). 늦은 순은 그대로다.
     */
    private List<SameArticles.Group> headlines(String player) {
        // 뺀 언론사의 기사와 같은 기사를 걸러 내고도 다섯 건이 남도록 넉넉히 읽는다
        List<NewsArticle> recent = kept(news.findLatest(player, PageRequest.of(0, NEWS_PER_PLAYER * 4)));
        List<SameArticles.Group> picked = first(SameArticles.group(recent));
        if (picked.stream().anyMatch(g -> g.article().getSummary() != null)) {
            return picked;
        }
        NewsArticle summarized = kept(news.findLatestSummarized(player, PageRequest.of(0, NEWS_PER_PLAYER))).stream()
                .findFirst()
                .orElse(null);
        if (summarized == null) {
            return picked;
        }
        // 요약 기사도 최근 기사와 함께 묶는다. 최근 기사에 든 그 사본(요약 없는)이 따로 한 줄로 남지 않고 alsoBy가 된다
        List<NewsArticle> all = new ArrayList<>(recent);
        if (recent.stream().noneMatch(a -> sameRow(a, summarized))) {
            all.add(summarized);
        }
        List<SameArticles.Group> groups = SameArticles.group(all);
        List<SameArticles.Group> result = new ArrayList<>(first(groups));
        if (result.stream().noneMatch(g -> g.article().getSummary() != null)) {
            // 요약 기사의 묶음은 고른 묶음보다 오래되어 마지막 자리(덜 찼으면 맨 뒤)에 두어도 늦은 순이 그대로다
            SameArticles.Group withSummary = groups.stream()
                    .filter(g -> g.members().stream().anyMatch(m -> sameRow(m, summarized)))
                    .findFirst()
                    .orElseThrow();
            if (result.size() == NEWS_PER_PLAYER) {
                result.set(NEWS_PER_PLAYER - 1, withSummary);
            } else {
                result.add(withSummary);
            }
        }
        return result;
    }

    /** 지금 수집하는 언론사의 기사만 */
    private List<NewsArticle> kept(List<NewsArticle> articles) {
        return articles.stream().filter(a -> publishers.contains(a.getPublisher())).toList();
    }

    private static List<SameArticles.Group> first(List<SameArticles.Group> groups) {
        return groups.stream().limit(NEWS_PER_PLAYER).toList();
    }

    /** 같은 기사 행인지. 저장한 기사는 번호로, 번호가 없으면(시험) 같은 객체인지로 본다 */
    private static boolean sameRow(NewsArticle a, NewsArticle b) {
        return a == b || (a.getId() != null && a.getId().equals(b.getId()));
    }

    PostingSummary find(Instant now) {
        List<PostingSummary.Player> players = postings.current().stream()
                .filter(p -> now.isBefore(p.deadline().plus(SHOW_AFTER_DEADLINE)))
                .sorted(Comparator.comparing(PostingCase::deadline))
                .map(p -> player(p, now))
                .toList();
        List<PostingSummary.Past> history = postings.history().stream().map(this::record).toList();
        if (players.isEmpty() && history.isEmpty()) {
            return null;
        }
        return new PostingSummary(players, history);
    }

    private PostingSummary.Player player(PostingCase p, Instant now) {
        String status = status(p, now);
        boolean open = status.equals("UPCOMING") || status.equals("NEGOTIATING");
        Integer daysLeft = open
                ? (int) ChronoUnit.DAYS.between(KoreanTime.kstDate(now), KoreanTime.kstDate(p.deadline()))
                : null;
        List<PostingSummary.Headline> headlines = headlines(p.nameKo()).stream()
                .map(g -> PostingSummary.Headline.of(g))
                .toList();
        return new PostingSummary.Player(p.nameKo(), p.kboTeam(), p.positionKo(), p.birthYear(),
                photos.postingPath(p.slug()), status,
                p.windowOpens(), p.deadline(), daysLeft, elapsedRatio(p, now), deal(p.result()), headlines);
    }

    static String status(PostingCase p, Instant now) {
        if (p.result() != null) {
            return p.result().outcome().name();
        }
        if (now.isBefore(p.windowOpens())) {
            return "UPCOMING";
        }
        return now.isBefore(p.deadline()) ? "NEGOTIATING" : "CLOSED";
    }

    /**
     * 시각이 지나며 바뀌는 이 선수의 포스팅 응답 값: 상태(협상 시작·마감), 홈에 보이는지(마감 뒤 SHOW_AFTER_DEADLINE),
     * 진행 막대(1% 단위). 이 값이 바뀌면 캐시한 응답을 다시 만든다(CacheWarmer). 남은 날(daysLeft)은 한국 날짜로 따로 본다.
     */
    static String timeKey(PostingCase p, Instant now) {
        boolean shown = now.isBefore(p.deadline().plus(SHOW_AFTER_DEADLINE));
        return status(p, now) + ":" + shown + ":" + Math.round(elapsedRatio(p, now) * 100);
    }

    /** 협상 기간 중 지난 비율(0~1). 소수 셋째 자리까지 */
    static double elapsedRatio(PostingCase p, Instant now) {
        double total = Duration.between(p.windowOpens(), p.deadline()).toSeconds();
        double passed = Duration.between(p.windowOpens(), now).toSeconds();
        double ratio = Math.max(0, Math.min(1, passed / total));
        return Math.round(ratio * 1000) / 1000.0;
    }

    private PostingSummary.Past record(PastPosting past) {
        return new PostingSummary.Past(past.nameKo(), past.season(), past.deadline(), past.daysBeforeDeadline(),
                deal(past.result()), photo(past));
    }

    /** 계약 구단 시절 MLB 공식 사진의 편집본(흰 바탕)이 있으면 그것, 없으면 포스팅 당시 KBO 공식 등록 사진 */
    private String photo(PastPosting past) {
        String mlb = photos.pastPostingPath(past.mlbId(), past.photoSeason());
        return mlb != null ? mlb : photos.careerPath(past.kboId(), past.season());
    }

    private static PostingSummary.Deal deal(PostingResult result) {
        if (result == null || result.outcome() != PostingResult.Outcome.SIGNED) {
            return null;
        }
        String abbreviation = MlbTeams.abbreviation(result.mlbTeamId());
        return new PostingSummary.Deal(result.mlbTeamId(), TeamNames.korean(abbreviation, abbreviation),
                result.agreedOn(), result.years(), result.guaranteedUsd(), result.krwPerUsd());
    }
}
