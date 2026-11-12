package com.korleaguer.api;

import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.Lineup;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 *   <li>'이번 겨울' 기사: 정규시즌 마지막 날 뒤에 나온 이 선수 기사(지금 수집하는 언론사만, 같은 기사는 한 줄, 최대 NEWS건).</li>
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

    public SeasonReportFinder(AppearanceRepository appearances, MinorGameLogRepository minorGameLogs,
                              PlayerMoveRepository moves, PlayerSeasonMetricRepository metrics,
                              SeasonDatesRepository seasonDates, NewsArticleRepository news,
                              NewsProperties newsProperties) {
        this.news = news;
        this.publishers = newsProperties.publishers();
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
        Map<Long, String> names = roster.stream()
                .collect(Collectors.toMap(m -> m.player().getId(), m -> m.player().getNameKo(), (a, b) -> a));
        Instant winterStart = lastRegularDay.plusDays(1).atStartOfDay(GameIngest.US_EASTERN).toInstant();
        return built.stream()
                .sorted(Comparator.comparing((SeasonReports.Built b) -> b.report().tier())
                        .thenComparing(SeasonReports.Built::majorGames, Comparator.reverseOrder()))
                .map(SeasonReports.Built::report)
                .map(r -> r.withNews(winterNews(names.get(r.playerId()), winterStart)))
                .toList();
    }

    /** 그 선수의 '이번 겨울' 기사(늦은 순, 같은 기사는 한 줄). 기사를 못 읽으면 빈 목록(성적표는 그대로 보인다) */
    List<PostingSummary.Headline> winterNews(String nameKo, Instant since) {
        if (nameKo == null) {
            return List.of();
        }
        try {
            List<NewsArticle> recent = news.findLatest(nameKo, PageRequest.of(0, NEWS * 4)).stream()
                    .filter(a -> publishers.contains(a.getPublisher()) && !a.getPublishedAt().isBefore(since))
                    .toList();
            return SameArticles.group(recent).stream().limit(NEWS).map(PostingSummary.Headline::of).toList();
        } catch (RuntimeException e) {
            log.warn("winter news for {} failed, left out: {}", nameKo, e.toString());
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
