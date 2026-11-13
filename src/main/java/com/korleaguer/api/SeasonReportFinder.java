package com.korleaguer.api;

import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.Lineup;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.NewsDestinationRepository;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.domain.PlayerSeasonMetricRepository;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonDates;
import com.korleaguer.domain.SeasonDatesRepository;
import com.korleaguer.domain.Team;
import com.korleaguer.ingest.GameIngest;
import com.korleaguer.news.NewsProperties;
import com.korleaguer.support.MlbTeams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 비시즌 홈의 시즌 성적표(offseason.report)를 DB에서 읽어 만든다. 계산은 SeasonReports.
 * <ul>
 *   <li>명단 전원(국내 리그로 돌아간 선수 포함). 이번 시즌 메이저·마이너 기록이 하나도 없는 선수는 뺀다.</li>
 *   <li>메이저 합계·지표(player_season_metric)를 아직 받지 못했으면 경기별 기록으로 양만 세어 판정한다.</li>
 *   <li>정규시즌 날짜를 모르면 개막일은 그 시즌 첫 메이저 경기일, 마지막 날은 마지막 경기일로 본다.</li>
 *   <li>선수 한 명이 실패하면 그 선수만 빼고, 전체가 실패하면 빈 목록이다(홈 응답은 깨지지 않는다).</li>
 *   <li>'이번 겨울' 기사: 정규시즌 마지막 날 뒤에 나온 이 선수 기사(지금 수집하는 언론사만, 같은 기사는 한 줄, 최대 NEWS건).
 *       같은 언론사가 연달아 오지 않게 섞는다(spreadPublishers, 이슈 #140).</li>
 *   <li>FA 선수(소속이 끝난 선수)는 행선지로 거론된 구단을 포스팅 카드와 같은 규칙으로 센다. 직전 구단은 세지 않는다(이슈 #140).</li>
 * </ul>
 * 순서: 판정(SeasonTier) 순, 그 안에서 메이저 경기 많은 순.
 */
@Component
public class SeasonReportFinder {

    private static final Logger log = LoggerFactory.getLogger(SeasonReportFinder.class);
    private static final List<String> REGULAR_SEASON = List.of("regular");
    /** 선수마다 붙이는 '이번 겨울' 기사 수(화면은 앞 두 건 + 'n건 더 보기') */
    static final int NEWS = 12;

    private final AppearanceRepository appearances;
    private final MinorGameLogRepository minorGameLogs;
    private final PlayerMoveRepository moves;
    private final PlayerSeasonMetricRepository metrics;
    private final SeasonDatesRepository seasonDates;
    private final NewsArticleRepository news;
    /** 지금 news/sources.yml에 있는 언론사(뺀 언론사의 예전 기사는 보이지 않는다) */
    private final Set<String> publishers;
    private final LinkedTeamsCounter linkedTeams;
    private final Clock clock;

    public SeasonReportFinder(AppearanceRepository appearances, MinorGameLogRepository minorGameLogs,
                              PlayerMoveRepository moves, PlayerSeasonMetricRepository metrics,
                              SeasonDatesRepository seasonDates, NewsArticleRepository news,
                              NewsDestinationRepository destinations, NewsProperties newsProperties, Clock clock) {
        this.news = news;
        this.publishers = newsProperties.publishers();
        this.linkedTeams = new LinkedTeamsCounter(news, destinations, publishers);
        this.clock = clock;
        this.appearances = appearances;
        this.minorGameLogs = minorGameLogs;
        this.moves = moves;
        this.metrics = metrics;
        this.seasonDates = seasonDates;
    }

    /** 명단 선수들의 시즌 성적표. 실패하면 빈 목록 */
    List<SeasonReport> find(List<RosterMember> roster, int season) {
        if (roster.isEmpty()) {
            return List.of();
        }
        try {
            return reports(roster, season);
        } catch (RuntimeException e) {
            log.warn("season report failed, home shows none: {}", e.toString());
            return List.of();
        }
    }

    private List<SeasonReport> reports(List<RosterMember> roster, int season) {
        List<Long> ids = roster.stream().map(m -> m.player().getId()).toList();
        Map<Long, List<SeasonReports.GameDay>> majors = appearances.findSeason(ids, season, REGULAR_SEASON).stream()
                .collect(Collectors.groupingBy(a -> a.getPlayer().getId(),
                        Collectors.mapping(SeasonReportFinder::majorGame, Collectors.toList())));
        Map<Long, List<SeasonReports.GameDay>> minors = minorGameLogs.findSeason(ids, season).stream()
                .collect(Collectors.groupingBy(g -> g.getPlayer().getId(),
                        Collectors.mapping(SeasonReportFinder::minorGame, Collectors.toList())));
        Map<Long, List<PlayerSeasonMetric>> metricRows = metrics.findSeasons(ids,
                        season - SeasonVerdict.REGULAR_BEFORE_SEASONS, season).stream()
                .collect(Collectors.groupingBy(m -> m.getPlayer().getId()));

        Optional<SeasonDates> dates = seasonDates.findById(season);
        List<LocalDate> majorDays = majors.values().stream().flatMap(List::stream)
                .map(SeasonReports.GameDay::date).sorted().toList();
        LocalDate openingDay = dates.map(SeasonDates::getRegularSeasonStart)
                .orElse(majorDays.isEmpty() ? LocalDate.of(season, 3, 25) : majorDays.getFirst());
        LocalDate lastRegularDay = dates.map(SeasonDates::getRegularSeasonEnd)
                .orElse(Stream.concat(majorDays.stream(), minors.values().stream().flatMap(List::stream)
                                .map(SeasonReports.GameDay::date))
                        .max(Comparator.naturalOrder()).orElse(LocalDate.of(season, 9, 30)));

        List<SeasonReports.Built> built = new ArrayList<>();
        for (RosterMember member : roster) {
            Player player = member.player();
            try {
                SeasonReports.Input input = input(player, season, openingDay, lastRegularDay,
                        majors.getOrDefault(player.getId(), List.of()), minors.getOrDefault(player.getId(), List.of()),
                        metricRows.getOrDefault(player.getId(), List.of()));
                if (input != null) {
                    built.add(SeasonReports.build(input));
                }
            } catch (RuntimeException e) {
                log.warn("season report for {} failed, left out: {}", player.getNameKo(), e.toString());
            }
        }
        Map<Long, RosterMember> members = roster.stream()
                .collect(Collectors.toMap(m -> m.player().getId(), m -> m, (a, b) -> a));
        Instant winterStart = lastRegularDay.plusDays(1).atStartOfDay(GameIngest.US_EASTERN).toInstant();
        Instant now = clock.instant();
        return built.stream()
                .sorted(Comparator.comparing((SeasonReports.Built b) -> b.report().tier())
                        .thenComparing(SeasonReports.Built::majorGames, Comparator.reverseOrder()))
                .map(SeasonReports.Built::report)
                .map(r -> {
                    RosterMember member = members.get(r.playerId());
                    String name = member.player().getNameKo();
                    return r.withNews(winterNews(name, winterStart), freeAgentLinkedTeams(member, now));
                })
                .toList();
    }

    /**
     * 그 선수의 '이번 겨울' 기사(같은 기사는 한 줄, 늦은 순이되 같은 언론사가 연달아 오지 않게 섞는다).
     * 기사를 못 읽으면 빈 목록(성적표는 그대로 보인다)
     */
    List<PostingSummary.Headline> winterNews(String nameKo, Instant since) {
        return spreadPublishers(news(nameKo, since, NEWS));
    }

    /** 앞 기사보다 이만큼 안쪽으로 늦지 않은 다른 언론사 기사는 앞으로 당긴다(늦은 순을 크게 흐트리지 않는다) */
    static final Duration SPREAD_WINDOW = Duration.ofDays(1);

    /**
     * 늦은 순 기사를, 같은 언론사가 연달아 오지 않게 섞는다(이슈 #140: 김하성 30건 중 21건이 한 언론사라 '이번 겨울'
     * 앞 두 건이 한 언론사로 찼다). 매번 남은 기사 중 가장 늦은 것을 고르되, 그것이 바로 앞과 같은 언론사이면 남은 가장 늦은
     * 기사보다 SPREAD_WINDOW 안에 나온 다른 언론사 기사를 대신 고른다. 없으면 그대로 둔다
     */
    static List<PostingSummary.Headline> spreadPublishers(List<PostingSummary.Headline> newestFirst) {
        List<PostingSummary.Headline> left = new ArrayList<>(newestFirst);
        List<PostingSummary.Headline> out = new ArrayList<>(left.size());
        while (!left.isEmpty()) {
            PostingSummary.Headline next = left.getFirst();
            if (!out.isEmpty() && out.getLast().publisher().equals(next.publisher())) {
                Instant newest = next.publishedAt();
                String previous = out.getLast().publisher();
                next = left.stream()
                        .filter(h -> !h.publisher().equals(previous))
                        .filter(h -> !h.publishedAt().isBefore(newest.minus(SPREAD_WINDOW)))
                        .findFirst()
                        .orElse(next);
            }
            left.remove(next);
            out.add(next);
        }
        return out;
    }

    /** 직전 구단 약어를 분류기가 쓰는 대표 약어로(MLB의 AZ → ARI 등). 모르는 약어면 그대로 */
    private static String lastTeam(String abbreviation) {
        if (abbreviation == null) {
            return null;
        }
        String canonical = MlbTeams.canonical(abbreviation.toUpperCase(Locale.ROOT));
        return canonical != null ? canonical : abbreviation;
    }

    /** FA 선수(소속이 끝났고 직전 구단을 안다)의 거론 구단. FA가 아니거나 거론된 구단이 없거나 못 읽으면 null */
    PostingSummary.LinkedTeams freeAgentLinkedTeams(RosterMember member, Instant now) {
        if (!member.teamEnded() || member.team() == null || member.player().getNameKo() == null) {
            return null;
        }
        try {
            PostingSummary.LinkedTeams linked = linkedTeams.count(member.player().getNameKo(), now,
                    articles -> articles.stream()
                            .filter(a -> publishers.contains(a.getPublisher()))
                            .filter(a -> !a.isOffTopic())
                            .toList(),
                    lastTeam(member.team().getAbbreviation()));
            return linked.teams().stream().anyMatch(t -> t.linked() > 0) ? linked : null;
        } catch (RuntimeException e) {
            log.warn("linked teams for {} failed, left out: {}", member.player().getNameKo(), e.toString());
            return null;
        }
    }

    /**
     * 그 선수의 since 뒤 기사(늦은 순, 같은 기사는 한 줄, 최대 limit건). 지금 수집하는 언론사만, 이 선수가 주인공인 기사만 둔다.
     * 성적표의 '이번 겨울'과 선수 화면의 기사(이슈 #133)가 같은 규칙을 쓴다. 기사를 못 읽으면 빈 목록(화면은 그대로 보인다)
     */
    List<PostingSummary.Headline> news(String nameKo, Instant since, int limit) {
        if (nameKo == null) {
            return List.of();
        }
        try {
            List<NewsArticle> recent = news.findLatest(nameKo, PageRequest.of(0, limit * 4)).stream()
                    .filter(a -> publishers.contains(a.getPublisher()) && !a.getPublishedAt().isBefore(since))
                    .filter(a -> !a.isOffTopic()) // 이 선수가 주인공이 아닌 기사(AI 판단, 이슈 #129)
                    .toList();
            return SameArticles.group(recent).stream().limit(limit).map(PostingSummary.Headline::of).toList();
        } catch (RuntimeException e) {
            log.warn("news for {} failed, left out: {}", nameKo, e.toString());
            return List.of();
        }
    }

    /** 선수 한 명의 재료. 이번 시즌 기록이 하나도 없으면 null */
    private SeasonReports.Input input(Player player, int season, LocalDate openingDay, LocalDate lastRegularDay,
                                      List<SeasonReports.GameDay> majorGames, List<SeasonReports.GameDay> minorGames,
                                      List<PlayerSeasonMetric> rows) {
        Role role = player.role();
        PlayerSeasonMetric.Group group = PlayerSeasonMetric.Group.of(role);
        SeasonVerdict.Line current = null;
        List<SeasonVerdict.Line> previous = new ArrayList<>();
        for (PlayerSeasonMetric row : rows) {
            if (row.getStatGroup() != group) {
                continue;
            }
            SeasonVerdict.Line line = line(row, role);
            if (row.getSeason() == season) {
                current = line;
            } else {
                previous.add(line);
            }
        }
        boolean played = !majorGames.isEmpty() || !minorGames.isEmpty() || (current != null && current.volume() > 0);
        if (!played) {
            return null;
        }
        boolean firstMlbSeason = player.getDebutYear() != null ? player.getDebutYear() == season
                : previous.stream().noneMatch(l -> l.volume() > 0) && !majorGames.isEmpty();
        List<SeasonReports.Move> playerMoves = moves.findNewestFirst(player.getId()).stream()
                .map(m -> new SeasonReports.Move(m.getMoveDate(), m.getTypeCode(), m.getTypeDesc(), m.getDescription()))
                .toList();
        return new SeasonReports.Input(player.getId(), role, season, openingDay, lastRegularDay, firstMlbSeason,
                current, previous, majorGames, minorGames, playerMoves);
    }

    private static SeasonVerdict.Line line(PlayerSeasonMetric row, Role role) {
        if (role == Role.PITCHER) {
            double innings = row.getInningsOuts() == null ? 0 : row.getInningsOuts() / 3.0;
            int started = row.getGamesStarted() == null ? 0 : row.getGamesStarted();
            return new SeasonVerdict.Line(row.getSeason(), innings, started, row.getEraMinus(), row.getWar());
        }
        double plateAppearances = row.getPlateAppearances() == null ? 0 : row.getPlateAppearances();
        return new SeasonVerdict.Line(row.getSeason(), plateAppearances, 0, row.getWrcPlus(), row.getWar());
    }

    private static SeasonReports.GameDay majorGame(Appearance a) {
        Game game = a.getGame();
        Team team = a.getTeam();
        boolean home = team != null && game.isHome(team);
        Team opponent = team == null ? null : home ? game.getAwayTeam() : game.getHomeTeam();
        Integer ours = home ? game.getHomeScore() : game.getAwayScore();
        Integer theirs = home ? game.getAwayScore() : game.getHomeScore();
        Boolean win = team == null || ours == null || theirs == null || !game.isFinal() ? null : ours > theirs;
        return new SeasonReports.GameDay(LocalDate.ofInstant(game.getStartTimeUtc(), GameIngest.US_EASTERN), null,
                TeamLabels.korean(team), TeamLabels.korean(opponent), a.getBatting(), a.getPitching(),
                a.getLineup() == Lineup.STARTER && a.getPitching() != null, win);
    }

    private static SeasonReports.GameDay minorGame(MinorGameLog g) {
        return new SeasonReports.GameDay(g.getGameDate(), g.getLevel(), null, null, g.getBatting(), g.getPitching(),
                false, g.getWin());
    }
}
