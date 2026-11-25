package com.korleaguer.api.article;

import com.korleaguer.config.NewsProperties;
import com.korleaguer.domain.DestinationMention;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.NewsDestination;
import com.korleaguer.domain.NewsDestinationRepository;
import com.korleaguer.support.KoreanTime;
import com.korleaguer.support.TeamNames;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 최근 LINKED_DAYS일 기사에서 한 선수의 행선지로 거론된 MLB 구단을 센다(기사마다 AI로 고른 것, ingest.NewsTagger).
 * 홈 MLB 도전 카드(포스팅 선수)와 시즌 성적표의 FA 선수(이슈 #140)가 같은 규칙을 쓴다: 지금 수집하는 언론사의 기사만,
 * 같은 글을 실은 여러 언론사 기사(SameArticles 묶음)는 한 번만 센다.
 * DB(기사·거론 구단)를 읽으므로 빈이다. 두 화면(PostingFinder·SeasonReportFinder)이 같은 하나를 받아 쓴다.
 */
@Component
public class LinkedTeamsCounter {

    /** '행선지로 거론된 구단'을 세는 날 수(한국 날짜, 오늘 포함) */
    static final int LINKED_DAYS = 7;
    /** 거론 수를 셀 때 같은 글 묶음을 만들려고 읽는 최근 기사 수(7일치를 넉넉히) */
    static final int ARTICLES_FOR_STORIES = 300;

    private final NewsArticleRepository news;
    private final NewsDestinationRepository destinations;
    private final Set<String> publishers;

    public LinkedTeamsCounter(NewsArticleRepository news, NewsDestinationRepository destinations,
                              NewsProperties newsProperties) {
        this.news = news;
        this.destinations = destinations;
        this.publishers = newsProperties.publishers();
    }

    /** 거론된 구단이 없을 때(또는 세지 못했을 때)의 값 */
    public static PostingSummary.LinkedTeams none() {
        return new PostingSummary.LinkedTeams(LINKED_DAYS, List.of());
    }

    /**
     * @param kept     화면 기사 목록과 같은 거르기(같은 글 묶음을 목록과 같은 기사로 만든다)
     * @param leaveOut 세지 않을 구단 약어(FA 선수의 직전 구단). 없으면 null
     */
    public PostingSummary.LinkedTeams count(String player, Instant now, UnaryOperator<List<NewsArticle>> kept, String leaveOut) {
        LocalDate today = KoreanTime.kstDate(now);
        Instant since = KoreanTime.startOfKstDay(today.minusDays(LINKED_DAYS - 1));
        // 관련 기사 목록처럼 지금 수집하는 언론사의 기사만 센다(뺀 언론사 기사는 숫자에도 넣지 않는다)
        List<DestinationMention> mentions = destinations.findMentions(player, since, publishers).stream()
                .filter(m -> !m.team().equals(leaveOut))
                .toList();
        if (mentions.isEmpty()) {
            return none();
        }
        // 같은 글을 실은 여러 언론사 기사(#113 묶음)는 한 번만 센다(사용자 11-13). 묶음은 목록과 같은 규칙
        List<NewsArticle> recent = kept.apply(news.findLatest(player, PageRequest.of(0, ARTICLES_FOR_STORIES))).stream()
                .filter(a -> !a.getPublishedAt().isBefore(since))
                .toList();
        Map<Long, Long> storyOf = new HashMap<>();
        Map<Long, SameArticles.Group> groupOf = new HashMap<>();
        Map<Long, NewsArticle> byId = new HashMap<>();
        for (SameArticles.Group group : SameArticles.group(recent)) {
            groupOf.put(group.article().getId(), group);
            for (NewsArticle member : group.members()) {
                storyOf.put(member.getId(), group.article().getId());
                byId.put(member.getId(), member);
            }
        }
        List<DestinationMention> stories = onePerStory(mentions, storyOf);
        return linkedTeams(stories, today, linkedArticles(stories, mentions, storyOf, groupOf, byId));
    }

    /**
     * 거론(linked)으로 센 글마다 대표 기사 한 줄을 구단별로 모은다(최근 순). 숫자와 건수가 같도록 센 글 그대로 쓴다.
     * 근거 문장은 대표 기사 → 그 묶음에서 그 구단을 고른 기사 순으로 찾는다(대표 기사 요약에 그 구단이 없을 수 있다).
     * 묶음 밖 기사(목록 거르기에서 빠졌거나 읽은 기사 수를 넘은 기사)는 따로 읽는다
     */
    private Map<String, List<PostingSummary.LinkedArticle>> linkedArticles(List<DestinationMention> stories,
                                                                           List<DestinationMention> mentions,
                                                                           Map<Long, Long> storyOf,
                                                                           Map<Long, SameArticles.Group> groupOf,
                                                                           Map<Long, NewsArticle> byId) {
        List<DestinationMention> linked = stories.stream()
                .filter(m -> m.stance() == NewsDestination.Stance.LINKED)
                .toList();
        if (linked.isEmpty()) {
            return Map.of();
        }
        // 이야기·구단마다 그 구단을 거론(linked)으로 고른 기사들(근거 문장을 찾을 곳)
        record Key(Long story, String team) {
        }
        Map<Key, List<Long>> taggedBy = new HashMap<>();
        for (DestinationMention m : mentions) {
            if (m.stance() == NewsDestination.Stance.LINKED) {
                Long story = storyOf.getOrDefault(m.articleId(), m.articleId());
                taggedBy.computeIfAbsent(new Key(story, m.team()), k -> new ArrayList<>()).add(m.articleId());
            }
        }
        Set<Long> missing = new HashSet<>();
        linked.forEach(m -> missing.add(m.articleId()));
        taggedBy.values().forEach(missing::addAll);
        missing.removeAll(byId.keySet());
        Map<Long, NewsArticle> articles = new HashMap<>(byId);
        if (!missing.isEmpty()) {
            news.findAllById(missing).forEach(a -> articles.put(a.getId(), a));
        }
        Map<String, List<PostingSummary.LinkedArticle>> byTeam = new HashMap<>();
        for (DestinationMention story : linked) {
            NewsArticle lead = articles.get(story.articleId());
            if (lead == null) {
                continue;
            }
            List<NewsArticle> sources = new ArrayList<>();
            sources.add(lead);
            taggedBy.getOrDefault(new Key(story.articleId(), story.team()), List.of()).stream()
                    .map(articles::get)
                    .filter(a -> a != null && a != lead)
                    .forEach(sources::add);
            LinkedReasons.Reason reason = LinkedReasons.find(story.team(), sources);
            SameArticles.Group group = groupOf.get(story.articleId());
            byTeam.computeIfAbsent(story.team(), k -> new ArrayList<>()).add(new PostingSummary.LinkedArticle(
                    lead.getId(), lead.getTitle(), lead.getTitleKo(), lead.getPublisher(), lead.getPublishedAt(),
                    lead.getImageUrl(), group == null ? List.of() : group.alsoBy(), reason.sentence(), reason.name()));
        }
        byTeam.values().forEach(list -> list.sort(Comparator.comparing(PostingSummary.LinkedArticle::publishedAt)
                .thenComparing(PostingSummary.LinkedArticle::id).reversed()));
        return byTeam;
    }

    /**
     * 같은 글(묶음)·같은 구단은 한 번만 남긴다. 묶음 안에서 방향이 갈리면 많은 쪽(같으면 거론), 시각은 가장 이른 기사.
     * 묶음을 모르는 기사(storyOf에 없음)는 따로 한 글로 본다
     */
    public static List<DestinationMention> onePerStory(List<DestinationMention> mentions, Map<Long, Long> storyOf) {
        record Key(Long story, String team) {
        }
        Map<Key, List<DestinationMention>> byStory = new LinkedHashMap<>();
        for (DestinationMention m : mentions) {
            Long story = storyOf.getOrDefault(m.articleId(), m.articleId());
            byStory.computeIfAbsent(new Key(story, m.team()), k -> new ArrayList<>()).add(m);
        }
        List<DestinationMention> out = new ArrayList<>();
        byStory.forEach((key, same) -> {
            long linked = same.stream().filter(m -> m.stance() == NewsDestination.Stance.LINKED).count();
            NewsDestination.Stance stance = linked * 2 >= same.size()
                    ? NewsDestination.Stance.LINKED : NewsDestination.Stance.DOUBTED;
            Instant first = same.stream().map(DestinationMention::publishedAt).min(Comparator.naturalOrder()).orElseThrow();
            out.add(new DestinationMention(key.story(), key.team(), stance, first));
        });
        return out;
    }

    /**
     * 구단별로 센다. 순서: 거론(linked)이 많은 순 → 오늘 거론된 구단 → 마지막 거론이 늦은 순 → 약어 순.
     * 오늘(today)은 한국 날짜로 오늘 나온 기사에서 거론(linked)된 구단이다(반대 전망은 '오늘'로 치지 않는다)
     */
    public static PostingSummary.LinkedTeams linkedTeams(List<DestinationMention> mentions, LocalDate today) {
        return linkedTeams(mentions, today, Map.of());
    }

    /** @param articles 구단별 거론 기사(linkedArticles). 없는 구단은 빈 목록 */
    public static PostingSummary.LinkedTeams linkedTeams(List<DestinationMention> mentions, LocalDate today,
                                                  Map<String, List<PostingSummary.LinkedArticle>> articles) {
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
                        t.doubted(), t.today(), articles.getOrDefault(t.team(), List.of())))
                .toList();
        return new PostingSummary.LinkedTeams(LINKED_DAYS, teams);
    }
}
