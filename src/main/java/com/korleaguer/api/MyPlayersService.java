package com.korleaguer.api;

import com.korleaguer.config.DataMode;
import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerLastStart;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GET /api/my(내 선수). 고른 선수마다 기간 합계, 시즌 타율·평균자책점, 연속 기록, 경기 한 줄 기록을 모은다.
 * <ul>
 *   <li>기간: 선수마다 정한다(periodOf). 보통은 한국 날짜로 오늘까지 days일이다. 최근 days일에 구단 경기(메이저에
 *       있을 때)도 본인 출전도 없으면(구단 시즌이 끝났거나 마이너에 있음) 그 선수가 마지막으로 뛴 날까지 days일로
 *       당긴다(isFallback=true). 시즌 중 구단은 경기하는데 본인이 빠졌으면(부상·벤치) 당기지 않고 결장을 그대로 보인다.</li>
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

        // 기간은 선수마다 정하고, 같은 기간인 선수끼리 묶어 한 번에 읽는다.
        Period recent = new Period(today.minusDays(days - 1), today, false);
        Map<Long, Period> periods = periods(members, recent, days, now);
        Map<Period, List<RosterMember>> groups = members.stream()
                .collect(Collectors.groupingBy(m -> periods.get(m.player().getId()), LinkedHashMap::new,
                        Collectors.toList()));

        boolean loadStats = statsAvailable && !members.isEmpty();
        Map<Long, List<GameLine>> linesByPlayer = new HashMap<>();
        Map<Long, List<Appearance>> periodByPlayer = new HashMap<>();
        groups.forEach((period, group) -> {
            // 선수 순, 같은 선수는 최신 경기부터 정렬돼 온다. 선수별로 나눠도 순서는 그대로다.
            gameLines.lines(group, period.from(), period.to(), statsAvailable)
                    .forEach(line -> linesByPlayer.computeIfAbsent(line.playerId(), id -> new ArrayList<>()).add(line));
            if (loadStats) {
                // 기간 합계는 경기 한 줄과 같은 기록(기간 안에 뛴 경기)으로 센다.
                // 시범경기는 빼고 센다(시즌 비교 값과 기준을 맞춘다).
                appearances.findWithGameBetween(ids(group), period.from(), period.to()).stream()
                        .filter(a -> STREAK_SEASON_TYPES.contains(a.getGame().getSeasonType()))
                        .forEach(a -> periodByPlayer.computeIfAbsent(a.getPlayer().getId(), id -> new ArrayList<>())
                                .add(a));
            }
        });

        // 연속 기록은 이번 시즌 기록으로 센다.
        Map<Long, List<Appearance>> seasonByPlayer = loadStats
                ? byPlayer(appearances.findSeason(ids(members), season, STREAK_SEASON_TYPES)) : Map.of();

        List<MyPlayersResponse.MyPlayer> result = members.stream()
                .map(m -> {
                    long id = m.player().getId();
                    return myPlayer(m, periods.get(id), today, statsAvailable, linesByPlayer.getOrDefault(id, List.of()),
                            periodByPlayer.getOrDefault(id, List.of()), seasonByPlayer.getOrDefault(id, List.of()));
                })
                .toList();
        boolean anyFallback = periods.values().stream().anyMatch(Period::fallback);
        return new MyPlayersResponse(dataMode.value(), statsAvailable, season, now.truncatedTo(ChronoUnit.SECONDS),
                days, recent.fromKst(), recent.toKst(), anyFallback, result);
    }

    /** 카드 한 장의 기간(한국 날짜 [fromKst, toKst]). fallback: 최근 기간에 경기가 없어 마지막으로 뛴 날까지로 당겼다 */
    record Period(LocalDate fromKst, LocalDate toKst, boolean fallback) {

        Instant from() {
            return KoreanTime.startOfKstDay(fromKst);
        }

        Instant to() {
            return KoreanTime.startOfKstDay(toKst.plusDays(1));
        }
    }

    /** 선수마다 기간. 구단 마지막 경기일은 구단마다 한 번만 읽는다. */
    private Map<Long, Period> periods(List<RosterMember> members, Period recent, int days, Instant now) {
        if (members.isEmpty()) {
            return Map.of();
        }
        Map<Long, LocalDate> ownLast = appearances.findLastStartTimes(ids(members), now).stream()
                .collect(Collectors.toMap(PlayerLastStart::playerId, s -> KoreanTime.kstDate(s.startTimeUtc())));
        Map<Long, Optional<LocalDate>> teamLast = new HashMap<>();
        Map<Long, Period> result = new HashMap<>();
        for (RosterMember member : members) {
            Team major = member.majorTeam();
            LocalDate team = major == null ? null : teamLast.computeIfAbsent(major.getMlbTeamId(),
                    teamId -> games.findLastFinalStartTime(List.of(teamId), now).map(KoreanTime::kstDate)).orElse(null);
            result.put(member.player().getId(), periodOf(recent, days, team, ownLast.get(member.player().getId())));
        }
        return result;
    }

    /**
     * 선수 한 명의 기간.
     * <ul>
     *   <li>최근 기간에 구단 경기(teamLast, 메이저에 있을 때만)나 본인 출전(ownLast)이 있으면 최근 기간 그대로.
     *       시즌 중 부상·벤치로 빠진 선수는 여기 걸려서 결장 줄이 그대로 보인다.</li>
     *   <li>없으면 본인이 마지막으로 뛴 날까지 days일. 뛴 적이 없으면 구단 마지막 경기일까지, 그것도 없으면 최근 기간.</li>
     * </ul>
     *
     * @param teamLast 메이저 구단의 마지막 끝난 경기일(마이너에 있거나 없으면 null)
     * @param ownLast  본인이 마지막으로 뛴 날(없으면 null)
     */
    static Period periodOf(Period recent, int days, LocalDate teamLast, LocalDate ownLast) {
        boolean active = onOrAfter(teamLast, recent.fromKst()) || onOrAfter(ownLast, recent.fromKst());
        LocalDate end = ownLast != null ? ownLast : teamLast;
        if (active || end == null) {
            return recent;
        }
        return new Period(end.minusDays(days - 1), end, true);
    }

    private static boolean onOrAfter(LocalDate date, LocalDate from) {
        return date != null && !date.isBefore(from);
    }

    private static List<Long> ids(List<RosterMember> members) {
        return members.stream().map(m -> m.player().getId()).toList();
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

    /** 요청한 순서대로, 있는 선수만. 팀은 지금 소속(소속이 끝났으면 마지막 소속, 없으면 null)이다. */
    private List<RosterMember> members(List<Long> requested) {
        Map<Long, Player> found = players.findAllById(requested).stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
        Map<Long, Affiliation> affiliationByPlayer = affiliations.findAllLatest().stream()
                .filter(a -> found.containsKey(a.getPlayer().getId()))
                .collect(Collectors.toMap(a -> a.getPlayer().getId(), Function.identity()));
        return requested.stream()
                .filter(found::containsKey)
                .map(id -> RosterMember.of(found.get(id), affiliationByPlayer.get(id)))
                .toList();
    }

    private MyPlayersResponse.MyPlayer myPlayer(RosterMember member, Period range, LocalDate today,
                                                boolean statsAvailable, List<GameLine> lines, List<Appearance> period,
                                                List<Appearance> seasonAppearances) {
        Player player = member.player();
        PlayerSummary summary = PlayerSummary.of(player, profiles.photoUrl(player), member.team(), today);
        if (!statsAvailable) {
            return new MyPlayersResponse.MyPlayer(summary, range.fromKst(), range.toKst(), range.fallback(),
                    null, null, null, lines);
        }
        List<Appearance> regular = seasonAppearances.stream()
                .filter(a -> REGULAR.equals(a.getGame().getSeasonType()))
                .toList();
        if (player.role() == Role.PITCHER) {
            return new MyPlayersResponse.MyPlayer(summary, range.fromKst(), range.toKst(), range.fallback(),
                    null, pitching(member, period, regular), SeasonStats.scorelessStreak(seasonAppearances), lines);
        }
        return new MyPlayersResponse.MyPlayer(summary, range.fromKst(), range.toKst(), range.fallback(),
                hitting(member, period, regular), null, SeasonStats.hitStreak(seasonAppearances), lines);
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
