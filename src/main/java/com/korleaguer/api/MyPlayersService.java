package com.korleaguer.api;

import com.korleaguer.config.DataMode;
import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.Team;
import com.korleaguer.support.KoreanTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GET /api/my(내 선수). 고른 선수마다 기간 합계, 시즌 타율·평균자책점, 연속 기록, 경기 한 줄 기록을 모은다.
 * <ul>
 *   <li>기간: 한국 날짜로 오늘까지 days일. 고른 선수들 팀의 마지막 끝난 경기가 그보다 이르면(비시즌)
 *       마지막 경기일까지 days일로 바꾼다(isFallback=true).</li>
 *   <li>기간 합계는 기간 안에 뛴 경기(경기 한 줄에서 played=true인 경기)로 센다.</li>
 *   <li>시즌 타율·평균자책점은 선수 페이지와 같은 정규시즌 값이다.</li>
 * </ul>
 * 선수 조합이 사람마다 달라 캐시하지 않는다(부를 때마다 DB를 읽는다).
 */
@Service
@Transactional(readOnly = true)
public class MyPlayersService {

    private static final int MAX_IDS = 20;
    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 14, 30);
    private static final String REGULAR = "regular";
    private static final List<String> STREAK_SEASON_TYPES = List.of(REGULAR, "postseason");

    private final PlayerRepository players;
    private final AffiliationRepository affiliations;
    private final AppearanceRepository appearances;
    private final GameRepository games;
    private final GameLineAssembler gameLines;
    private final BaseballQueryService queryService;
    private final RosterProfiles profiles;
    private final DataMode dataMode;
    private final int season;
    private final Clock clock;

    public MyPlayersService(PlayerRepository players, AffiliationRepository affiliations,
                            AppearanceRepository appearances, GameRepository games, GameLineAssembler gameLines,
                            BaseballQueryService queryService, RosterProfiles profiles, DataMode dataMode,
                            KorleaguerProperties properties, Clock clock) {
        this.players = players;
        this.affiliations = affiliations;
        this.appearances = appearances;
        this.games = games;
        this.gameLines = gameLines;
        this.queryService = queryService;
        this.profiles = profiles;
        this.dataMode = dataMode;
        this.season = properties.season();
        this.clock = clock;
    }

    /**
     * @param ids  쉼표로 이은 선수 id. 겹치면 처음 것만 남기고, 1~20개여야 한다. 없는 id는 건너뛴다.
     * @param days 7, 14, 30 중 하나
     * @throws BadRequestException ids·days가 규칙에 맞지 않을 때
     */
    public MyPlayersResponse find(String ids, int days) {
        List<Long> requested = parseIds(ids);
        if (!ALLOWED_DAYS.contains(days)) {
            throw new BadRequestException("days must be 7, 14 or 30");
        }

        Instant now = clock.instant();
        LocalDate today = KoreanTime.kstDate(now);
        boolean statsAvailable = queryService.statsAvailable();
        List<RosterMember> members = members(requested);

        // 기간: 오늘까지 days일. 마지막 경기일이 그보다 이르면 마지막 경기일까지 days일.
        LocalDate lastGameDay = lastGameDay(members, now);
        boolean fallback = lastGameDay != null && lastGameDay.isBefore(today.minusDays(days - 1));
        LocalDate toKst = fallback ? lastGameDay : today;
        LocalDate fromKst = toKst.minusDays(days - 1);
        Instant from = KoreanTime.startOfKstDay(fromKst);
        Instant to = KoreanTime.startOfKstDay(toKst.plusDays(1));

        // 선수 순, 같은 선수는 최신 경기부터 정렬돼 온다. 선수별로 나눠도 순서는 그대로다.
        Map<Long, List<GameLine>> linesByPlayer = gameLines.lines(members, from, to, statsAvailable).stream()
                .collect(Collectors.groupingBy(GameLine::playerId));

        // 기간 합계는 경기 한 줄과 같은 기록(기간 안에 뛴 경기), 연속 기록은 이번 시즌 기록으로 센다.
        boolean loadStats = statsAvailable && !members.isEmpty();
        List<Long> memberIds = members.stream().map(m -> m.player().getId()).toList();
        Map<Long, List<Appearance>> periodByPlayer = loadStats
                ? byPlayer(appearances.findWithGameBetween(memberIds, from, to)) : Map.of();
        Map<Long, List<Appearance>> seasonByPlayer = loadStats
                ? byPlayer(appearances.findSeason(memberIds, season, STREAK_SEASON_TYPES)) : Map.of();

        List<MyPlayersResponse.MyPlayer> result = members.stream()
                .map(m -> {
                    long id = m.player().getId();
                    return myPlayer(m, today, statsAvailable, linesByPlayer.getOrDefault(id, List.of()),
                            periodByPlayer.getOrDefault(id, List.of()), seasonByPlayer.getOrDefault(id, List.of()));
                })
                .toList();
        return new MyPlayersResponse(dataMode.value(), statsAvailable, season, now.truncatedTo(ChronoUnit.SECONDS),
                days, fromKst, toKst, fallback, result);
    }

    /** "1, 2,5" → [1, 2, 5]. 빈 칸(끝의 쉼표 등)은 건너뛴다. */
    static List<Long> parseIds(String ids) {
        Set<Long> unique = new LinkedHashSet<>();
        for (String token : ids.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                unique.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                throw new BadRequestException("ids must be numbers");
            }
        }
        if (unique.isEmpty() || unique.size() > MAX_IDS) {
            throw new BadRequestException("ids must have 1 to " + MAX_IDS + " player ids");
        }
        return List.copyOf(unique);
    }

    /** 요청한 순서대로, 있는 선수만. 팀은 현재 소속(없으면 null)이다. */
    private List<RosterMember> members(List<Long> requested) {
        Map<Long, Player> found = players.findAllById(requested).stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
        Map<Long, Team> teamByPlayer = affiliations.findAllCurrent().stream()
                .filter(a -> found.containsKey(a.getPlayer().getId()))
                .collect(Collectors.toMap(a -> a.getPlayer().getId(), Affiliation::getTeam));
        return requested.stream()
                .filter(found::containsKey)
                .map(id -> new RosterMember(found.get(id), teamByPlayer.get(id)))
                .toList();
    }

    /** 선수들 현재 팀의 끝난 경기 중 가장 늦은 경기의 한국 날짜. 없으면 null */
    private LocalDate lastGameDay(List<RosterMember> members, Instant now) {
        List<Long> teamIds = members.stream()
                .map(RosterMember::team)
                .filter(Objects::nonNull)
                .map(Team::getBdlTeamId)
                .distinct()
                .toList();
        Optional<Instant> last = teamIds.isEmpty() ? Optional.empty() : games.findLastFinalStartTime(teamIds, now);
        return last.map(KoreanTime::kstDate).orElse(null);
    }

    private MyPlayersResponse.MyPlayer myPlayer(RosterMember member, LocalDate today, boolean statsAvailable,
                                                List<GameLine> lines, List<Appearance> period,
                                                List<Appearance> seasonAppearances) {
        Player player = member.player();
        PlayerSummary summary = PlayerSummary.of(player, profiles.photoUrl(player), member.team(), today);
        if (!statsAvailable) {
            return new MyPlayersResponse.MyPlayer(summary, null, null, null, lines);
        }
        List<Appearance> regular = seasonAppearances.stream()
                .filter(a -> REGULAR.equals(a.getGame().getSeasonType()))
                .toList();
        if (player.role() == Role.PITCHER) {
            return new MyPlayersResponse.MyPlayer(summary, null, pitching(member, period, regular),
                    SeasonStats.scorelessStreak(seasonAppearances), lines);
        }
        return new MyPlayersResponse.MyPlayer(summary, hitting(member, period, regular), null,
                SeasonStats.hitStreak(seasonAppearances), lines);
    }

    private static MyPlayersResponse.PeriodHitting hitting(RosterMember member, List<Appearance> period,
                                                           List<Appearance> regular) {
        HitterSeason total = SeasonStats.hitter(member.player(), null, member.team(), period);
        HitterSeason seasonTotal = SeasonStats.hitter(member.player(), null, member.team(), regular);
        return new MyPlayersResponse.PeriodHitting(total.games(), total.atBats(), total.hits(), total.homeRuns(),
                total.rbi(), total.walks(), total.stolenBases(), total.avg(), seasonTotal.avg());
    }

    private static MyPlayersResponse.PeriodPitching pitching(RosterMember member, List<Appearance> period,
                                                             List<Appearance> regular) {
        PitcherSeason total = SeasonStats.pitcher(member.player(), null, member.team(), period);
        PitcherSeason seasonTotal = SeasonStats.pitcher(member.player(), null, member.team(), regular);
        return new MyPlayersResponse.PeriodPitching(total.games(), total.outs(), total.inningsText(),
                SeasonStats.earnedRuns(period), total.era(), total.strikeouts(), total.wins(), total.losses(),
                total.saves(), seasonTotal.era());
    }

    private static Map<Long, List<Appearance>> byPlayer(List<Appearance> list) {
        return list.stream().collect(Collectors.groupingBy(a -> a.getPlayer().getId()));
    }
}
