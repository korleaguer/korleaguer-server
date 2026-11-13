package com.korleaguer.api;

import com.korleaguer.config.AppConfig;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.config.DataMode;
import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Level;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerLastStart;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonDates;
import com.korleaguer.domain.SeasonDatesRepository;
import com.korleaguer.domain.Team;
import com.korleaguer.ingest.GameIngest;
import com.korleaguer.support.KoreanTime;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 *   <li>기간: 선수마다 정한다(periodOf). 보통은 한국 날짜로 오늘까지 days일이다. 최근 days일에 구단 경기(메이저에
 *       있을 때)도 본인 출전도 없으면(구단 시즌이 끝났거나 마이너에 있음) 그 선수가 마지막으로 뛴 날까지 days일로
 *       당긴다(isFallback=true). 시즌 중 구단은 경기하는데 본인이 빠졌으면(부상·벤치) 당기지 않고 결장을 그대로 보인다.</li>
 *   <li>기간 합계는 기간 안에 뛴 경기(경기 한 줄에서 played=true인 경기)로 센다.</li>
 *   <li>시즌 타율·평균자책점은 선수 페이지와 같은 정규시즌 값이다.</li>
 *   <li>최근 폼 요약(이슈 #33): 상태 배지(CardStatuses), 출전 줄·한 줄 소식(FormFacts)을 붙인다.
 *       부상자 명단(메이저)이면 기간을 이번 시즌 마지막 출전까지로 당기고(다치기 전), 오른 뒤 마이너 재활 경기를 뛰었으면
 *       재활 경기를 보이며, 마이너에 있으면 큰 숫자·막대를 마이너 기록으로 채우고, FA·시즌 끝이면 시즌 합계(메이저·마이너)를
 *       준다. 상태·소식은 끝난 경기로만 정한다(진행 중 경기의 중간 기록으로 바뀌지 않게).</li>
 * </ul>
 * 카드는 선수·기간마다(AppConfig.CARD_CACHE), 응답은 선수 조합(ids 글자 그대로)과 days마다(AppConfig.MY_CACHE) 캐시한다.
 * 처음 보는 조합도 카드를 모으기만 하면 된다. 데이터나 날짜가 바뀔 때 비우고(ResponseCaches),
 * CacheWarmer가 명단 전원의 카드(7·14·30일)와 비시즌 홈의 명단 전원 조합을 미리 채워 둔다.
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
    private final CurrentSeason currentSeason;
    private final PlayerMoveRepository moves;
    private final MinorGameLogRepository minorGameLogs;
    private final SeasonDatesRepository seasonDates;
    private final Clock clock;
    private final CacheManager cacheManager;

    public MyPlayersService(PlayerRepository players, AffiliationRepository affiliations,
                            AppearanceRepository appearances, GameRepository games, GameLineAssembler gameLines,
                            BaseballQueryService queryService, RosterProfiles profiles, DataMode dataMode,
                            CurrentSeason currentSeason, PlayerMoveRepository moves,
                            MinorGameLogRepository minorGameLogs, SeasonDatesRepository seasonDates, Clock clock,
                            CacheManager cacheManager) {
        this.players = players;
        this.affiliations = affiliations;
        this.appearances = appearances;
        this.games = games;
        this.gameLines = gameLines;
        this.queryService = queryService;
        this.profiles = profiles;
        this.dataMode = dataMode;
        this.currentSeason = currentSeason;
        this.moves = moves;
        this.minorGameLogs = minorGameLogs;
        this.seasonDates = seasonDates;
        this.clock = clock;
        this.cacheManager = cacheManager;
    }

    /**
     * 고른 선수들의 카드를 요청한 순서대로 모은다. 카드는 선수·기간마다 캐시한 것(card)을 쓰므로, 처음 보는 조합도
     * 미리 채워 둔 카드로 바로 답한다.
     *
     * @param ids  쉼표로 이은 선수 id. 겹치면 처음 것만 남기고, 1~20개여야 한다. 없는 id는 건너뛴다.
     * @param days 7, 14, 30 중 하나
     * @throws BadRequestException ids·days가 규칙에 맞지 않을 때(캐시하지 않는다)
     */
    @Cacheable(cacheNames = AppConfig.MY_CACHE, key = "#ids + '|' + #days")
    public MyPlayersResponse find(String ids, int days) {
        List<Long> requested = parseIds(ids);
        checkDays(days);

        Instant now = clock.instant();
        LocalDate today = KoreanTime.kstDate(now);
        List<MyPlayersResponse.MyPlayer> result = new ArrayList<>();
        for (long id : requested) {
            result.addAll(card(id, days));
        }
        // 기간을 마지막으로 뛴 날까지 당긴 카드가 있는지(웹이 안내 한 줄을 띄운다). 카드가 실제로 보이는 기간으로 정한다
        // (마이너 카드는 마이너 기간, 부상자 명단 카드는 '다치기 전'이라 따로 적는다, 시즌 합계 카드는 기간이 없다).
        boolean anyFallback = result.stream().anyMatch(p -> p.basis() == MyPlayersResponse.Basis.LAST_PLAYED
                || (p.basis() == MyPlayersResponse.Basis.REHAB && p.isFallback()));
        return new MyPlayersResponse(dataMode.value(), queryService.statsAvailable(), currentSeason.current(),
                now.truncatedTo(ChronoUnit.SECONDS), days, today.minusDays(days - 1), today, anyFallback, result);
    }

    /**
     * 선수 한 명의 카드(없는 선수면 빈 목록). 선수·기간마다 캐시한다(AppConfig.CARD_CACHE).
     * 카드는 선수마다 따로 정해지므로(기간·상태·출전 줄·소식) 여럿을 함께 계산한 것과 같다.
     * 데이터·날짜가 바뀌면 다른 응답 캐시와 함께 비우고(ResponseCaches), CacheWarmer가 명단 전원을 미리 채운다.
     */
    public List<MyPlayersResponse.MyPlayer> card(long playerId, int days) {
        checkDays(days);
        Cache cache = cacheManager.getCache(AppConfig.CARD_CACHE);
        if (cache == null) {
            return cards(List.of(playerId), days);
        }
        return cache.get(playerId + "|" + days, () -> cards(List.of(playerId), days));
    }

    private static void checkDays(int days) {
        if (!ALLOWED_DAYS.contains(days)) {
            throw new BadRequestException("days must be 7, 14 or 30");
        }
    }

    /** 고른 선수들의 카드를 계산한다(캐시 없이). 같은 기간인 선수끼리 묶어 DB를 한 번에 읽는다 */
    private List<MyPlayersResponse.MyPlayer> cards(List<Long> requested, int days) {
        Instant now = clock.instant();
        LocalDate today = KoreanTime.kstDate(now);
        LocalDate usToday = LocalDate.ofInstant(now, GameIngest.US_EASTERN);
        int season = currentSeason.current();
        boolean statsAvailable = queryService.statsAvailable();
        List<RosterMember> members = members(requested);
        boolean loadStats = statsAvailable && !members.isEmpty();

        // 최근 폼 요약 재료: 이번 시즌 메이저 출전(정규+포스트, 오래된 것부터)·마이너 경기·이동 기록으로 상태를 정한다.
        // 끝난 경기만 본다(진행 중 경기의 중간 기록으로 연속 기록 소식이 사라지거나 미리 붙지 않게, 출전 줄과 같은 기준).
        Map<Long, List<Appearance>> majorSeason = loadStats ? oldestFirst(byPlayer(
                appearances.findSeason(ids(members), season, STREAK_SEASON_TYPES).stream()
                        .filter(a -> !a.getGame().getStartTimeUtc().isAfter(now) && a.getGame().isFinal())
                        .toList())) : Map.of();
        Map<Long, List<MinorGameLog>> minorSeason = loadStats ? minorGameLogs.findSeason(ids(members), season).stream()
                .filter(g -> !g.getGameDate().isAfter(usToday))
                .collect(Collectors.groupingBy(g -> g.getPlayer().getId())) : Map.of();
        SeasonSpan span = seasonSpan(season);
        boolean seasonEnded = usToday.isAfter(span.end());
        Map<Long, Boolean> teamPlaying = new HashMap<>();
        Map<Long, CardStatuses.Resolved> statuses = new HashMap<>();
        for (RosterMember member : members) {
            long id = member.player().getId();
            Team major = member.majorTeam();
            // 정규시즌이 끝난 뒤에만 본다: 메이저 팀이 아직 경기하면(포스트시즌) 본인 경기가 며칠 없어도 시즌 끝이 아니다.
            boolean playing = seasonEnded && major != null
                    && teamPlaying.computeIfAbsent(major.getMlbTeamId(), teamId -> teamStillPlaying(teamId, now));
            CardStatuses.Today day = new CardStatuses.Today(usToday, span.start(), seasonEnded, playing);
            statuses.put(id, resolveStatus(member.player(), majorSeason.getOrDefault(id, List.of()),
                    minorSeason.getOrDefault(id, List.of()), day));
        }

        // 기간은 선수마다 정하고, 같은 기간인 선수끼리 묶어 한 번에 읽는다.
        // 메이저 부상자 명단이면 이번 시즌 마지막 출전까지로 당긴다(다치기 전 기록).
        Period recent = new Period(today.minusDays(days - 1), today, false);
        Map<Long, Period> periods = new HashMap<>(periods(members, recent, days, now));
        for (RosterMember member : members) {
            long id = member.player().getId();
            List<Appearance> played = majorSeason.getOrDefault(id, List.of());
            if (isKind(statuses.get(id), "IL") && !played.isEmpty()) {
                LocalDate end = KoreanTime.kstDate(played.getLast().getGame().getStartTimeUtc());
                periods.put(id, new Period(end.minusDays(days - 1), end, true));
            }
        }
        Map<Period, List<RosterMember>> groups = members.stream()
                .collect(Collectors.groupingBy(m -> periods.get(m.player().getId()), LinkedHashMap::new,
                        Collectors.toList()));

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

        // 연속 기록·시즌 비교는 카드 기간이 속한 시즌 기록으로 센다.
        // 개막 직후 지난 시즌 끝으로 당긴 카드는 지난 시즌과 비교한다(새 시즌은 아직 기록이 없다).
        Map<Long, List<Appearance>> seasonByPlayer = new HashMap<>();
        if (loadStats) {
            Map<Integer, List<RosterMember>> bySeason = groups.entrySet().stream()
                    .collect(Collectors.groupingBy(e -> currentSeason.seasonAt(e.getKey().to().minusMillis(1)),
                            Collectors.flatMapping(e -> e.getValue().stream(), Collectors.toList())));
            bySeason.forEach((cardSeason, group) -> seasonByPlayer.putAll(
                    byPlayer(appearances.findSeason(ids(group), cardSeason, STREAK_SEASON_TYPES))));
        }

        return members.stream()
                .map(m -> {
                    long id = m.player().getId();
                    Card card = new Card(m, periods.get(id), statuses.get(id), majorSeason.getOrDefault(id, List.of()),
                            minorSeason.getOrDefault(id, List.of()));
                    return myPlayer(card, today, usToday, days, season, now, statsAvailable,
                            linesByPlayer.getOrDefault(id, List.of()), periodByPlayer.getOrDefault(id, List.of()),
                            seasonByPlayer.getOrDefault(id, List.of()));
                })
                .toList();
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

    /** 카드 한 장의 재료: 선수, 기간, 상태, 이번 시즌 메이저 출전(오래된 것부터)·마이너 경기 */
    private record Card(RosterMember member, Period range, CardStatuses.Resolved resolved, List<Appearance> majorSeason,
                        List<MinorGameLog> minorSeason) {

        String kind() {
            return resolved.status() == null ? null : resolved.status().kind();
        }
    }

    private MyPlayersResponse.MyPlayer myPlayer(Card card, LocalDate today, LocalDate usToday, int days, int season,
                                                Instant now, boolean statsAvailable, List<GameLine> lines,
                                                List<Appearance> period, List<Appearance> seasonAppearances) {
        RosterMember member = card.member();
        Player player = member.player();
        Period range = card.range();
        MyPlayersResponse.CardStatus status = card.resolved().status();
        PlayerSummary summary = PlayerSummary.of(player, profiles.photoUrl(player), member.team(), today,
                profiles.returnedKo(player));
        if (!statsAvailable) {
            return new MyPlayersResponse.MyPlayer(summary, range.fromKst(), range.toKst(), range.fallback(),
                    null, null, null, lines, status, null, null, MyPlayersResponse.Basis.NONE, null, null);
        }
        boolean pitcher = player.role() == Role.PITCHER;
        List<Appearance> regular = seasonAppearances.stream()
                .filter(a -> REGULAR.equals(a.getGame().getSeasonType()))
                .toList();
        Integer streak = pitcher ? SeasonStats.scorelessStreak(seasonAppearances)
                : SeasonStats.hitStreak(seasonAppearances);
        MyPlayersResponse.PeriodHitting hitting = pitcher ? null : hitting(member, period, regular);
        MyPlayersResponse.PeriodPitching pitching = pitcher ? pitching(member, period, regular) : null;
        List<FormFacts.GameStat> majorStats = card.majorSeason().stream().map(MinorRecords::stat).toList();
        List<FormFacts.GameStat> minorStats = card.minorSeason().stream().map(MinorRecords::stat).toList();
        String kind = card.kind();

        // FA·시즌 끝·KBO 복귀: 기간 대신 시즌 합계와 시즌 최장 연속 기록
        if ("FA".equals(kind) || "SEASON_OVER".equals(kind) || "KBO".equals(kind)) {
            List<Appearance> majorRegular = card.majorSeason().stream()
                    .filter(a -> REGULAR.equals(a.getGame().getSeasonType()))
                    .toList();
            List<FormFacts.GameStat> majorRegularStats = majorRegular.stream().map(MinorRecords::stat).toList();
            return new MyPlayersResponse.MyPlayer(summary, range.fromKst(), range.toKst(), range.fallback(), hitting,
                    pitching, streak, lines, status, null,
                    FormFacts.seasonBest(List.of(majorRegularStats, minorStats), pitcher),
                    MyPlayersResponse.Basis.SEASON, null, seasonTotals(player, season, majorRegular, card.minorSeason()));
        }

        // 마이너(부상자 명단·출장 정지·지명 할당 포함): 큰 숫자·막대를 마이너 기록으로
        if (inMinors(card, player)) {
            List<MinorGameLog> minor = card.minorSeason();
            List<FormFacts.GameStat> stint = since(minorStats, card.resolved().stintStart());
            // 새 팀으로 옮긴 뒤 아직 경기가 없으면 예전 팀 기록을 보이지 않는다(카드: "OOO 합류, 아직 경기 없음").
            boolean joinedNoGames = "MINORS".equals(kind) && card.resolved().stintStart() != null && stint.isEmpty();
            String prefix = MinorRecords.levelKo(minor) + " "; // 단계가 섞였으면 "마이너 "
            MyPlayersResponse.Fact fact = joinedNoGames ? null : pitcher
                    ? FormFacts.pitcher(minorStats, stint, prefix, card.resolved().start())
                    : FormFacts.hitter(minorStats, stint, prefix, card.resolved().start());
            MinorWindow window = joinedNoGames ? null : minorWindow(minor, today, days);
            if (window == null) {
                return new MyPlayersResponse.MyPlayer(summary, range.fromKst(), range.toKst(), range.fallback(), null,
                        null, streak, List.of(), status, null, fact, MyPlayersResponse.Basis.NONE, null, null);
            }
            // 마이너 부상자 명단은 오른 날을 믿기 어려워(배지에도 날수를 붙이지 않는다) "다치기 전"이라 하지 않는다.
            MyPlayersResponse.Basis basis = window.fallback() ? MyPlayersResponse.Basis.LAST_PLAYED
                    : MyPlayersResponse.Basis.RECENT;
            return new MyPlayersResponse.MyPlayer(summary, window.fromKst(), window.toKst(), window.fallback(),
                    pitcher ? null : MinorRecords.hitting(window.games(), minor),
                    pitcher ? MinorRecords.pitching(window.games(), minor) : null, streak,
                    MinorRecords.lines(player, profiles.photoUrl(player), window.games()), status, null, fact, basis,
                    window.games().getLast().getLevel(), null);
        }

        // 메이저(부상자 명단 포함)
        List<FormFacts.GameStat> stint = since(majorStats, card.resolved().stintStart());
        MyPlayersResponse.Fact fact = pitcher
                ? FormFacts.pitcher(majorStats, stint, "", card.resolved().start())
                : FormFacts.hitter(majorStats, stint, "", card.resolved().start());
        boolean injured = "IL".equals(kind);
        // 부상자 명단에 오른 뒤 마이너 재활 경기를 뛰었으면 재활 경기를 보여 준다(기획 4-1). 팀·단계는 마지막 재활 경기다.
        MinorWindow rehab = injured ? rehabWindow(card.minorSeason(), status.since(), today, days) : null;
        if (rehab != null) {
            MinorGameLog last = rehab.games().getLast();
            MyPlayersResponse.CardStatus withTeam = new MyPlayersResponse.CardStatus(status.kind(), status.since(),
                    status.days(), last.getLevel(), last.getTeamName(), status.lastGameDate(), status.lastGameLevel());
            // 재활 경기는 단계도 몸 상태도 달라 시즌 기록과 견주지 않는다(시즌 비교 값 없음).
            // 한 줄 소식도 없다: 다치기 전 메이저 소식('9경기 연속 무안타')을 재활 숫자 옆에 두면 지금 일처럼 읽힌다.
            return new MyPlayersResponse.MyPlayer(summary, rehab.fromKst(), rehab.toKst(), rehab.fallback(),
                    pitcher ? null : MinorRecords.hitting(rehab.games(), List.of()),
                    pitcher ? MinorRecords.pitching(rehab.games(), List.of()) : null, streak,
                    MinorRecords.lines(player, profiles.photoUrl(player), rehab.games()), withTeam, null, null,
                    MyPlayersResponse.Basis.REHAB, last.getLevel(), null);
        }
        MyPlayersResponse.Basis basis = injured
                ? (card.majorSeason().isEmpty() ? MyPlayersResponse.Basis.NONE : MyPlayersResponse.Basis.BEFORE_INJURY)
                : range.fallback() ? MyPlayersResponse.Basis.LAST_PLAYED : MyPlayersResponse.Basis.RECENT;
        MyPlayersResponse.Play play = null;
        if (status == null || "RETURNED".equals(kind) || "CALLED_UP".equals(kind)) {
            play = pitcher ? FormFacts.pitcherPlay(majorStats, usToday)
                    : FormFacts.hitterPlay(teamGames(member, card.resolved().stintStart(), now), card.resolved().start());
        }
        return new MyPlayersResponse.MyPlayer(summary, range.fromKst(), range.toKst(), range.fallback(), hitting,
                pitching, streak, lines, status, play, fact, basis, "MLB", null);
    }

    /** 마이너 기록으로 카드를 채울지: 마이너 상태이거나, 출장 정지·지명 할당인데 지금 단계가 마이너 */
    private static boolean inMinors(Card card, Player player) {
        String kind = card.kind();
        return "MINORS".equals(kind) || "MINORS_IL".equals(kind)
                || (("SUSPENDED".equals(kind) || "DFA".equals(kind)) && player.getLevel() == Level.MINORS);
    }

    private CardStatuses.Resolved resolveStatus(Player player, List<Appearance> majorSeason,
                                                List<MinorGameLog> minorSeason, CardStatuses.Today today) {
        CardStatuses.LastGame lastMajor = majorSeason.isEmpty() ? null : new CardStatuses.LastGame(
                LocalDate.ofInstant(majorSeason.getLast().getGame().getStartTimeUtc(), GameIngest.US_EASTERN), "MLB");
        CardStatuses.LastGame lastMinor = minorSeason.isEmpty() ? null : new CardStatuses.LastGame(
                minorSeason.getLast().getGameDate(), minorSeason.getLast().getLevel());
        CardStatuses.LastGame lastGame = lastMajor == null ? lastMinor : lastMinor == null ? lastMajor
                : lastMinor.date().isAfter(lastMajor.date()) ? lastMinor : lastMajor;
        // 마이너 팀 이름은 선수 정보의 지금 팀(마이너면)을 먼저 믿는다(새 팀으로 옮긴 뒤 아직 경기가 없을 수 있다).
        String teamName = player.getLevel() == Level.MINORS && player.getLevelTeamName() != null
                ? player.getLevelTeamName()
                : minorSeason.isEmpty() ? player.getLevelTeamName() : minorSeason.getLast().getTeamName();
        // 단계는 지금 팀에서 뛴 마지막 경기로 정한다. 새 팀에서 아직 안 뛰었으면 단계를 모른다(배지는 '마이너'만).
        MinorGameLog lastLog = minorSeason.isEmpty() ? null : minorSeason.getLast();
        String level = lastLog != null && Objects.equals(lastLog.getTeamName(), teamName) ? lastLog.getLevel() : null;
        // 국내 리그로 돌아간 선수(명단의 returned-ko): MLB 기록상 FA여도 'KBO 복귀'다. 이동 기록·시즌 끝보다 먼저 본다.
        String returnedKo = profiles.returnedKo(player);
        if (returnedKo != null) {
            return new CardStatuses.Resolved(new MyPlayersResponse.CardStatus("KBO", null, null, null, returnedKo,
                    lastGame == null ? null : lastGame.date(), lastGame == null ? null : lastGame.level()),
                    null, CardStatuses.Start.OTHER);
        }
        return CardStatuses.resolve(player, moves.findNewestFirst(player.getId()), lastGame,
                new CardStatuses.MinorSpot(level, teamName), today);
    }

    /** 이번 시즌 정규시즌 개막일·마지막 날(미국 날짜) */
    private record SeasonSpan(LocalDate start, LocalDate end) {
    }

    /** 시즌 날짜를 아직 못 받았으면 3월 1일 ~ 9월 30일로 본다. */
    private SeasonSpan seasonSpan(int season) {
        Optional<SeasonDates> dates = seasonDates.findById(season);
        LocalDate start = dates.map(SeasonDates::getRegularSeasonStart).orElse(LocalDate.of(season, 3, 1));
        LocalDate end = dates.map(SeasonDates::getRegularSeasonEnd).orElse(LocalDate.of(season, 9, 30));
        return new SeasonSpan(start, end);
    }

    /** 메이저 팀이 아직 경기하는지: 최근 3일 안에 경기했거나 앞으로 2주 안에 경기가 잡혀 있다(포스트시즌 등). */
    private boolean teamStillPlaying(long teamId, Instant now) {
        return games.findTeamGamesBetween(List.of(teamId),
                        now.minus(CardStatuses.SEASON_OVER_AFTER_DAYS, ChronoUnit.DAYS), now.plus(14, ChronoUnit.DAYS))
                .stream()
                .anyMatch(game -> !"canceled".equals(game.getStatusState()));
    }

    /** 부상자 명단에 오른 날(since, 미국 날짜)부터 뛴 마이너 재활 경기의 기간. 재활 경기가 없으면 null */
    private static MinorWindow rehabWindow(List<MinorGameLog> minor, LocalDate since, LocalDate today, int days) {
        if (since == null) {
            return null;
        }
        return minorWindow(minor.stream().filter(g -> !g.getGameDate().isBefore(since)).toList(), today, days);
    }

    private static boolean isKind(CardStatuses.Resolved resolved, String kind) {
        return resolved != null && resolved.status() != null && kind.equals(resolved.status().kind());
    }

    /** since(미국 날짜) 뒤 경기만. since가 없으면 그대로 */
    private static List<FormFacts.GameStat> since(List<FormFacts.GameStat> games, LocalDate since) {
        return since == null ? games : games.stream().filter(g -> !g.date().isBefore(since)).toList();
    }

    /**
     * 출전 줄에 쓸 팀의 끝난 경기(오래된 것부터 최근 10개). 지금 단계 기간이 시작된 날(stintStart, 미국 날짜) 뒤,
     * 그리고 최근 30일 안에서만 본다(부상자 명단·마이너에 있던 동안의 팀 경기를 결장으로 세지 않게).
     * 복귀·콜업 뒤면 그날부터 팀 경기를 모두 센다(콜업 당일처럼 그 팀에서 처음 뛰기 전 경기도 결장).
     * 그 밖에는 경기 줄처럼 그 팀에서 처음 뛴 날부터 센다(트레이드로 온 선수의 새 팀 예전 경기를 결장으로 세지 않게).
     */
    private List<GameLine> teamGames(RosterMember member, LocalDate stintStart, Instant now) {
        Instant from = now.minus(30, ChronoUnit.DAYS);
        Team team = member.majorTeam();
        List<GameLine> lines;
        if (stintStart != null && team != null) {
            Instant start = stintStart.atStartOfDay(GameIngest.US_EASTERN).toInstant();
            if (start.isAfter(from)) {
                from = start;
            }
            lines = gameLines.teamLines(member.player(), team, from, now);
        } else {
            lines = gameLines.lines(List.of(member), from, now, true);
        }
        List<GameLine> finals = lines.stream()
                .filter(line -> Game.STATUS_FINAL.equals(line.gameStatus()))
                .toList();
        return finals.subList(0, Math.min(FormFacts.RECENT_GAMES, finals.size())).reversed();
    }

    /** 마이너 기간: 오늘까지 days일(한국 날짜), 그 안에 경기가 없으면 마지막 마이너 경기까지 days일. 경기가 없으면 null */
    private record MinorWindow(LocalDate fromKst, LocalDate toKst, boolean fallback, List<MinorGameLog> games) {
    }

    private static MinorWindow minorWindow(List<MinorGameLog> season, LocalDate today, int days) {
        if (season.isEmpty()) {
            return null;
        }
        LocalDate from = today.minusDays(days - 1);
        List<MinorGameLog> recent = within(season, from, today);
        if (!recent.isEmpty()) {
            return new MinorWindow(from, today, false, recent);
        }
        LocalDate end = MinorRecords.kstDate(season.getLast());
        return new MinorWindow(end.minusDays(days - 1), end, true, within(season, end.minusDays(days - 1), end));
    }

    private static List<MinorGameLog> within(List<MinorGameLog> games, LocalDate from, LocalDate to) {
        return games.stream()
                .filter(g -> !MinorRecords.kstDate(g).isBefore(from) && !MinorRecords.kstDate(g).isAfter(to))
                .toList();
    }

    /** 시즌 합계. 메이저 20경기 이상이거나 메이저가 더 많으면 메이저를 크게 보인다. */
    private static MyPlayersResponse.SeasonTotals seasonTotals(Player player, int season, List<Appearance> majorRegular,
                                                               List<MinorGameLog> minor) {
        MyPlayersResponse.Totals majors = MinorRecords.majors(player, majorRegular);
        MyPlayersResponse.Totals minors = MinorRecords.minors(player, minor);
        int majorGames = majors == null ? 0 : majors.games();
        int minorGames = minors == null ? 0 : minors.games();
        String primary = majorGames == 0 && minorGames == 0 ? null
                : majorGames > 0 && (majorGames >= 20 || majorGames >= minorGames) ? "MLB" : "MINORS";
        return new MyPlayersResponse.SeasonTotals(season, primary, majors, minors);
    }

    /** 선수마다 경기 시작 순(오래된 것부터) */
    private static Map<Long, List<Appearance>> oldestFirst(Map<Long, List<Appearance>> byPlayer) {
        Map<Long, List<Appearance>> sorted = new HashMap<>();
        byPlayer.forEach((id, list) -> sorted.put(id, list.stream()
                .sorted(Comparator.comparing(a -> a.getGame().getStartTimeUtc()))
                .toList()));
        return sorted;
    }

    private static MyPlayersResponse.PeriodHitting hitting(RosterMember member, List<Appearance> period,
                                                           List<Appearance> regular) {
        // 기간 합계에는 마이너 경기 수를 쓰지 않는다(0).
        HitterSeason total = SeasonStats.hitter(member.player(), null, member.team(), period, 0);
        HitterSeason seasonTotal = SeasonStats.hitter(member.player(), null, member.team(), regular, 0);
        return new MyPlayersResponse.PeriodHitting(total.games(), total.atBats(), total.hits(), total.homeRuns(),
                total.rbi(), total.walks(), total.stolenBases(), total.avg(), seasonTotal.avg());
    }

    private static MyPlayersResponse.PeriodPitching pitching(RosterMember member, List<Appearance> period,
                                                             List<Appearance> regular) {
        PitcherSeason total = SeasonStats.pitcher(member.player(), null, member.team(), period, 0);
        PitcherSeason seasonTotal = SeasonStats.pitcher(member.player(), null, member.team(), regular, 0);
        return new MyPlayersResponse.PeriodPitching(total.games(), total.outs(), total.inningsText(),
                SeasonStats.earnedRuns(period), total.era(), total.strikeouts(), total.wins(), total.losses(),
                total.saves(), seasonTotal.era());
    }

    private static Map<Long, List<Appearance>> byPlayer(List<Appearance> list) {
        return list.stream().collect(Collectors.groupingBy(a -> a.getPlayer().getId()));
    }
}
