package com.korleaguer.api.season;

import com.korleaguer.api.article.LinkedTeamsCounter;
import com.korleaguer.api.article.PostingSummary;
import com.korleaguer.api.article.SameArticles;
import com.korleaguer.api.article.ShownNews;
import com.korleaguer.api.player.RosterMember;
import com.korleaguer.api.player.TeamLabels;

import com.korleaguer.config.NewsProperties;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.Lineup;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerLeaguePosition;
import com.korleaguer.domain.PlayerLeaguePositionRepository;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.domain.PlayerSeasonMetricRepository;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonDates;
import com.korleaguer.domain.SeasonDatesRepository;
import com.korleaguer.domain.SeasonType;
import com.korleaguer.domain.Team;
import com.korleaguer.support.MlbTeams;
import com.korleaguer.support.MlbTime;
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
import java.util.HashMap;
import java.util.HashSet;
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
 *   <li>선수 한 명이 실패하면 그 선수만 빼고, 전체가 실패하면 빈 목록이다(홈 응답은 깨지지 않는다).
 *       실패해도 되는 DB 조회는 OptionalReads로 홈 조회 트랜잭션과 떼어 따로 돌린다(같은 트랜잭션에서 실패하면 예외를
 *       삼켜도 커밋에서 홈 전체가 500이 됐다, 이슈 #179 리뷰). 따로 돌리는 조회는 서로 겹치지 않게 둔다(연결 수).</li>
 *   <li>'이번 겨울' 기사: 정규시즌 마지막 날 뒤에 나온 이 선수 기사(지금 수집하는 언론사만, 같은 기사는 한 줄, 최대 NEWS건).
 *       같은 언론사가 연달아 오지 않게 섞는다(spreadPublishers, 이슈 #140).</li>
 *   <li>FA 선수(소속이 끝난 선수)는 행선지로 거론된 구단을 포스팅 카드와 같은 규칙으로 센다. 직전 구단은 세지 않는다(이슈 #140).</li>
 *   <li>'MLB 안 위치'(saber): 선수 화면과 같은 값(그 시즌 player_league_position 중 선수 역할의 묶음 → PlayerSaber.of).
 *       명단 전원을 한 번에 읽고, 못 읽으면 모두 null이다(성적표는 그대로 보인다, 이슈 #179).</li>
 * </ul>
 * 순서: 판정(SeasonTier) 순, 그 안에서 메이저 경기 많은 순.
 */
@Component
public class SeasonReportFinder {

    private static final Logger log = LoggerFactory.getLogger(SeasonReportFinder.class);
    private static final List<String> REGULAR_SEASON = List.of(SeasonType.REGULAR.stored());
    /** 선수마다 붙이는 '이번 겨울' 기사 수(화면은 앞 두 건 + 'n건 더 보기') */
    static final int NEWS = 12;

    private final AppearanceRepository appearances;
    private final MinorGameLogRepository minorGameLogs;
    private final PlayerMoveRepository moves;
    private final PlayerSeasonMetricRepository metrics;
    private final PlayerLeaguePositionRepository leaguePositions;
    private final SeasonDatesRepository seasonDates;
    private final NewsArticleRepository news;
    /** 지금 news/sources.yml에 있는 언론사(뺀 언론사의 예전 기사는 보이지 않는다) */
    private final Set<String> publishers;
    private final LinkedTeamsCounter linkedTeams;
    private final LeagueEnvironments leagues;
    private final Clock clock;
    private final OptionalReads reads;

    public SeasonReportFinder(AppearanceRepository appearances, MinorGameLogRepository minorGameLogs,
                              PlayerMoveRepository moves, PlayerSeasonMetricRepository metrics,
                              PlayerLeaguePositionRepository leaguePositions, SeasonDatesRepository seasonDates,
                              NewsArticleRepository news, LinkedTeamsCounter linkedTeams,
                              NewsProperties newsProperties, LeagueEnvironments leagues, Clock clock,
                              OptionalReads reads) {
        this.reads = reads;
        this.news = news;
        this.leagues = leagues;
        this.publishers = newsProperties.publishers();
        this.linkedTeams = linkedTeams;
        this.clock = clock;
        this.appearances = appearances;
        this.minorGameLogs = minorGameLogs;
        this.moves = moves;
        this.metrics = metrics;
        this.leaguePositions = leaguePositions;
        this.seasonDates = seasonDates;
    }

    /** 명단 선수들의 시즌 성적표. 재료를 못 읽거나 계산이 실패하면 빈 목록 */
    public List<SeasonReport> find(List<RosterMember> roster, int season) {
        if (roster.isEmpty()) {
            return List.of();
        }
        List<Long> ids = roster.stream().map(m -> m.player().getId()).toList();
        SeasonRecords records = reads.read("season report records", () -> records(ids, season), null);
        if (records == null) {
            return List.of();
        }
        try {
            return reports(roster, season, records);
        } catch (RuntimeException e) {
            // 계산 실패만 여기로 온다. 이 안의 DB 조회는 저마다 OptionalReads로 따로 돌고, 실패하면 그 부분만 빠진다
            log.warn("season report failed, home shows none: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 명단 전원을 한 번에 읽은 성적표 재료와 그것으로 정한 개막일·정규시즌 마지막 날. 경기는 화면용 값(GameDay)으로 바꿔 둔다.
     * 지표 줄은 엔티티 그대로지만 따로 읽는 트랜잭션이 끝난 뒤에는 기본 칸(시즌·묶음·숫자)만 쓴다
     */
    private record SeasonRecords(Map<Long, List<SeasonReports.GameDay>> majors,
                                 Map<Long, List<SeasonReports.GameDay>> minors,
                                 Map<Long, List<PlayerSeasonMetric>> metricRows,
                                 LocalDate openingDay, LocalDate lastRegularDay) {
    }

    /** 성적표 재료를 읽는다. OptionalReads 안에서 부른다(경기의 상대 팀처럼 지연 로딩되는 값은 여기서 꺼낸다) */
    private SeasonRecords records(List<Long> ids, int season) {
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
        return new SeasonRecords(majors, minors, metricRows, openingDay, lastRegularDay);
    }

    /** 달별 wRC+·ERA+의 리그 평균: 메이저와 우리 선수가 뛴 마이너 단계만 묻는다(LeagueEnvironments가 DB에서 읽는다) */
    private Map<String, LeagueEnv> environments(SeasonRecords records, int season) {
        Set<String> levels = new HashSet<>();
        levels.add(LeagueEnv.MLB);
        records.minors().values().forEach(games -> games.forEach(g -> levels.add(g.level())));
        return leagues.of(season, levels);
    }

    /**
     * 이동 연표(PlayerTimelineService)가 선수별 시즌 숫자를 같은 계산으로 내는 재료: 선수별 성적표 입력(기록이 하나도
     * 없는 선수는 빠진다)과 리그 평균. 기사·'MLB 안 위치'는 읽지 않는다. 재료를 못 읽으면 빈 값이다
     */
    public record TimelineMaterials(Map<Long, SeasonReports.Input> inputs, Map<String, LeagueEnv> environments) {
        static final TimelineMaterials NONE = new TimelineMaterials(Map.of(), Map.of());
    }

    public TimelineMaterials timelineMaterials(List<Player> players, int season) {
        if (players.isEmpty()) {
            return TimelineMaterials.NONE;
        }
        List<Long> ids = players.stream().map(Player::getId).toList();
        SeasonRecords records = reads.read("timeline records", () -> records(ids, season), null);
        if (records == null) {
            return TimelineMaterials.NONE;
        }
        Map<Long, SeasonReports.Input> inputs = new HashMap<>();
        for (Player player : players) {
            try {
                SeasonReports.Input input = input(player, season, records.openingDay(), records.lastRegularDay(),
                        records.majors().getOrDefault(player.getId(), List.of()),
                        records.minors().getOrDefault(player.getId(), List.of()),
                        records.metricRows().getOrDefault(player.getId(), List.of()));
                if (input != null) {
                    inputs.put(player.getId(), input);
                }
            } catch (RuntimeException e) {
                log.warn("timeline material for {} failed, left out: {}", player.getNameKo(), e.toString());
            }
        }
        return new TimelineMaterials(inputs, environments(records, season));
    }

    private List<SeasonReport> reports(List<RosterMember> roster, int season, SeasonRecords records) {
        Map<String, LeagueEnv> environments = environments(records, season);

        List<SeasonReports.Built> built = new ArrayList<>();
        for (RosterMember member : roster) {
            Player player = member.player();
            try {
                SeasonReports.Input input = input(player, season, records.openingDay(), records.lastRegularDay(),
                        records.majors().getOrDefault(player.getId(), List.of()),
                        records.minors().getOrDefault(player.getId(), List.of()),
                        records.metricRows().getOrDefault(player.getId(), List.of()));
                if (input != null) {
                    built.add(SeasonReports.build(input, environments));
                }
            } catch (RuntimeException e) {
                // 계산 실패(이동 기록 조회는 input 안에서 따로 돈다)
                log.warn("season report for {} failed, left out: {}", player.getNameKo(), e.toString());
            }
        }
        Map<Long, RosterMember> members = roster.stream()
                .collect(Collectors.toMap(m -> m.player().getId(), m -> m, (a, b) -> a));
        Instant winterStart = records.lastRegularDay().plusDays(1).atStartOfDay(MlbTime.US_EASTERN).toInstant();
        Instant now = clock.instant();
        Map<Long, PlayerSaber> sabers = sabers(roster, season);
        return built.stream()
                .sorted(Comparator.comparing((SeasonReports.Built b) -> b.report().tier())
                        .thenComparing(SeasonReports.Built::majorGames, Comparator.reverseOrder()))
                .map(SeasonReports.Built::report)
                .map(r -> {
                    RosterMember member = members.get(r.playerId());
                    String name = member.player().getNameKo();
                    return r.withNews(winterNews(name, winterStart), freeAgentLinkedTeams(member, now))
                            .withSaber(sabers.get(r.playerId()));
                })
                .toList();
    }

    /**
     * 명단 선수들의 그 시즌 'MLB 안 위치'(선수 번호 → 선수 화면 saber와 같은 값). 선수 역할의 묶음(타자 HITTING·투수
     * PITCHING) 줄만 쓴다(선수 화면과 같다). 줄이 없거나 그 시즌 MLB 기록이 없는 선수는 빠지고, 못 읽으면 빈 맵이다
     * (펼친 카드에서 위치만 빠지고 성적표는 그대로 보인다. OptionalReads로 따로 읽는다)
     */
    Map<Long, PlayerSaber> sabers(List<RosterMember> roster, int season) {
        Map<Long, PlayerSeasonMetric.Group> groups = new HashMap<>();
        roster.forEach(m -> groups.putIfAbsent(m.player().getId(), PlayerSeasonMetric.Group.of(m.player().role())));
        return reads.read("league positions for season report", () -> {
            Map<Long, PlayerSaber> out = new HashMap<>();
            for (PlayerLeaguePosition row : leaguePositions.findSeason(groups.keySet(), season)) {
                long playerId = row.getPlayer().getId();
                PlayerSaber saber = row.getStatGroup() == groups.get(playerId) ? PlayerSaber.of(row) : null;
                if (saber != null) {
                    out.put(playerId, saber);
                }
            }
            return out;
        }, Map.of());
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

    /**
     * FA 선수(소속이 끝났고 직전 구단을 안다)의 거론 구단. FA가 아니거나 거론된 구단이 없거나 못 읽으면 null
     * (기사·거론 구단은 OptionalReads로 따로 읽는다)
     */
    PostingSummary.LinkedTeams freeAgentLinkedTeams(RosterMember member, Instant now) {
        if (!member.teamEnded() || member.team() == null || member.player().getNameKo() == null) {
            return null;
        }
        String name = member.player().getNameKo();
        String leaveOut = lastTeam(member.team().getAbbreviation());
        return reads.read("linked teams for " + name, () -> {
            PostingSummary.LinkedTeams linked = linkedTeams.count(name, now,
                    articles -> articles.stream()
                            .filter(a -> ShownNews.isShown(a, publishers))
                            .toList(),
                    leaveOut);
            return linked.teams().stream().anyMatch(t -> t.linked() > 0) ? linked : null;
        }, null);
    }

    /**
     * 그 선수의 since 뒤 기사(늦은 순, 같은 기사는 한 줄, 최대 limit건). 지금 수집하는 언론사만, 이 선수가 주인공인 기사만 둔다.
     * 성적표의 '이번 겨울'과 선수 화면의 기사(이슈 #133)가 같은 규칙을 쓴다. 기사를 못 읽으면 빈 목록(화면은 그대로 보인다.
     * OptionalReads로 따로 읽는다)
     */
    public List<PostingSummary.Headline> news(String nameKo, Instant since, int limit) {
        if (nameKo == null) {
            return List.of();
        }
        return reads.read("news for " + nameKo, () -> {
            List<NewsArticle> recent = news.findLatest(nameKo, PageRequest.of(0, limit * 4)).stream()
                    .filter(a -> ShownNews.isShown(a, publishers) && !a.getPublishedAt().isBefore(since))
                    .toList();
            return SameArticles.group(recent).stream().limit(limit).map(PostingSummary.Headline::of).toList();
        }, List.of());
    }

    /** 선수 한 명의 재료. 이번 시즌 기록이 하나도 없거나 이동 기록을 못 읽으면 null(그 선수는 성적표에서 빠진다) */
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
        long playerId = player.getId();
        List<SeasonReports.Move> playerMoves = reads.read("moves of " + player.getNameKo(),
                () -> moves.findNewestFirst(playerId).stream()
                        .map(m -> new SeasonReports.Move(m.getMoveDate(), m.getTypeCode(), m.getTypeDesc(),
                                m.getDescription()))
                        .toList(), null);
        if (playerMoves == null) {
            return null;
        }
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
        return new SeasonReports.GameDay(LocalDate.ofInstant(game.getStartTimeUtc(), MlbTime.US_EASTERN), null,
                TeamLabels.korean(team), TeamLabels.korean(opponent), a.getBatting(), a.getPitching(),
                a.getLineup() == Lineup.STARTER && a.getPitching() != null, win);
    }

    private static SeasonReports.GameDay minorGame(MinorGameLog g) {
        return new SeasonReports.GameDay(g.getGameDate(), g.getLevel(), null, null, g.getBatting(), g.getPitching(),
                false, g.getWin());
    }
}
