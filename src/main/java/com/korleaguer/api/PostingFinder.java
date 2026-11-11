package com.korleaguer.api;

import com.korleaguer.domain.NewsArticleRepository;
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
import java.util.Comparator;
import java.util.List;

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

    public PostingFinder(Postings postings, NewsArticleRepository news, PhotoSources photos) {
        this.postings = postings;
        this.news = news;
        this.photos = photos;
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
        List<PostingSummary.Headline> headlines = news.findLatest(p.nameKo(), PageRequest.of(0, NEWS_PER_PLAYER))
                .stream()
                .map(a -> new PostingSummary.Headline(a.getTitle(), a.getUrl(), a.getPublisher(), a.getPublishedAt()))
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

    /** 계약 구단 시절 MLB 공식 사진이 있으면 그것, 없으면 포스팅 당시 KBO 공식 등록 사진 */
    private String photo(PastPosting past) {
        String mlb = photos.mlbSeasonPath(past.mlbId(), past.photoSeason());
        return mlb != null ? mlb : photos.careerPath(past.kboId(), past.season());
    }

    private static PostingSummary.Deal deal(PostingResult result) {
        if (result == null || result.outcome() != PostingResult.Outcome.SIGNED) {
            return null;
        }
        String abbreviation = MlbTeams.abbreviation(result.mlbTeamId());
        return new PostingSummary.Deal(result.mlbTeamId(), TeamNames.korean(abbreviation, abbreviation),
                result.agreedOn(), result.years(), result.guaranteedUsd());
    }
}
