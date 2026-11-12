package com.korleaguer.api;

import com.korleaguer.domain.DestinationMention;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.NewsDestination;
import com.korleaguer.domain.NewsDestinationRepository;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 홈의 포스팅 묶음(PostingSummary)을 만든다. posting.yml(Postings)과 DB의 관련 기사만 읽는다.
 * 비시즌일 때만 부른다(BaseballQueryService). 포스팅 선수도 지난 기록도 없으면 null이다.
 */
@Component
public class PostingFinder {

    /** 협상 마감 뒤 이만큼 지나면 이번 겨울 선수 목록에서 뺀다(결과를 보여 줄 기간) */
    static final Duration SHOW_AFTER_DEADLINE = Duration.ofDays(60);
    /** 관련 기사 수. PC는 카드 오른쪽 칸 전체가 기사다(사용자 11-12 결정, 5건에서 늘림) */
    static final int NEWS_PER_PLAYER = 12;
    /** '행선지로 거론된 구단'을 세는 날 수(한국 날짜, 오늘 포함) */
    static final int LINKED_DAYS = 7;

    private final Postings postings;
    private final NewsArticleRepository news;
    private final NewsDestinationRepository destinations;
    private final PhotoSources photos;
    /** 지금 news/sources.yml에 있는 언론사. 뺀 언론사(이용약관으로 뺀 뉴시스 등)의 예전 기사는 보이지 않는다 */
    private final Set<String> publishers;

    public PostingFinder(Postings postings, NewsArticleRepository news, NewsDestinationRepository destinations,
                         PhotoSources photos, NewsProperties newsProperties) {
        this.postings = postings;
        this.news = news;
        this.destinations = destinations;
        this.photos = photos;
        this.publishers = newsProperties.publishers();
    }

    /**
     * 그 선수의 관련 기사(늦은 순 NEWS_PER_PLAYER건, 지금 수집하는 언론사만).
     * 국내 기사가 많으면 영어 기사의 AI 요약이 밀려 안 보이므로, 그 안에 요약 기사가 없으면 가장 최근 요약 기사 하나를
     * 마지막 자리에 넣는다(덜 찼으면 맨 뒤에 붙인다. 사용자가 원한 것이 영어 기사의 한국어 요약이다). 늦은 순은 그대로다.
     */
    private List<NewsArticle> headlines(String player) {
        // 뺀 언론사의 기사를 걸러 내고도 다섯 건이 남도록 넉넉히 읽는다
        List<NewsArticle> picked = new ArrayList<>(news.findLatest(player, PageRequest.of(0, NEWS_PER_PLAYER * 4))
                .stream()
                .filter(a -> publishers.contains(a.getPublisher()))
                .limit(NEWS_PER_PLAYER)
                .toList());
        if (picked.stream().noneMatch(a -> a.getSummary() != null)) {
            // 고른 기사보다 오래된 기사라 마지막 자리(덜 찼으면 맨 뒤)에 두어도 늦은 순이 그대로다
            news.findLatestSummarized(player, PageRequest.of(0, NEWS_PER_PLAYER)).stream()
                    .filter(a -> publishers.contains(a.getPublisher()))
                    .findFirst()
                    .ifPresent(a -> {
                        if (picked.size() == NEWS_PER_PLAYER) {
                            picked.set(NEWS_PER_PLAYER - 1, a);
                        } else {
                            picked.add(a);
                        }
                    });
        }
        return picked;
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
                .map(PostingSummary.Headline::of)
                .toList();
        return new PostingSummary.Player(p.nameKo(), p.kboTeam(), p.positionKo(), p.birthYear(),
                photos.postingPath(p.slug()), status,
                p.windowOpens(), p.deadline(), daysLeft, elapsedRatio(p, now), deal(p.result()), headlines,
                linkedTeams(p.nameKo(), now));
    }

    private PostingSummary.LinkedTeams linkedTeams(String player, Instant now) {
        LocalDate today = KoreanTime.kstDate(now);
        Instant since = KoreanTime.startOfKstDay(today.minusDays(LINKED_DAYS - 1));
        return linkedTeams(destinations.findMentions(player, since), today);
    }

    /**
     * 구단별로 센다. 순서: 거론(linked)이 많은 순 → 오늘 거론된 구단 → 마지막 거론이 늦은 순 → 약어 순.
     * 오늘(today)은 한국 날짜로 오늘 나온 기사에서 거론(linked)된 구단이다(반대 전망은 '오늘'로 치지 않는다)
     */
    static PostingSummary.LinkedTeams linkedTeams(List<DestinationMention> mentions, LocalDate today) {
        record Tally(String team, int linked, int doubted, boolean today, Instant last) {
        }
        Map<String, Tally> byTeam = new LinkedHashMap<>();
        for (DestinationMention m : mentions) {
            boolean linked = m.stance() == NewsDestination.Stance.LINKED;
            boolean todayLinked = linked && KoreanTime.kstDate(m.publishedAt()).equals(today);
            byTeam.merge(m.team(), new Tally(m.team(), linked ? 1 : 0, linked ? 0 : 1, todayLinked,
                            linked ? m.publishedAt() : Instant.EPOCH),
                    (a, b) -> new Tally(a.team(), a.linked() + b.linked(), a.doubted() + b.doubted(),
                            a.today() || b.today(), a.last().isAfter(b.last()) ? a.last() : b.last()));
        }
        List<PostingSummary.LinkedTeam> teams = byTeam.values().stream()
                .sorted(Comparator.comparingInt(Tally::linked).reversed()
                        .thenComparing(Tally::today, Comparator.reverseOrder())
                        .thenComparing(Tally::last, Comparator.reverseOrder())
                        .thenComparing(Tally::team))
                .map(t -> new PostingSummary.LinkedTeam(t.team(), TeamNames.korean(t.team(), t.team()), t.linked(),
                        t.doubted(), t.today()))
                .toList();
        return new PostingSummary.LinkedTeams(LINKED_DAYS, teams);
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
