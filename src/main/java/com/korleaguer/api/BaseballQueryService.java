package com.korleaguer.api;

import com.korleaguer.config.AppConfig;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.config.DataMode;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.MinorSeason;
import com.korleaguer.domain.MinorSeasonRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerMove;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.Team;
import com.korleaguer.support.BatsThrows;
import com.korleaguer.support.BirthDates;
import com.korleaguer.support.BodyMeasures;
import com.korleaguer.support.KoreanTime;
import com.korleaguer.support.MoveLabels;
import com.korleaguer.support.PositionNames;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DB를 읽어 화면용 응답을 만든다. 홈과 선수 응답은 60초 캐시하고, 수집이 끝나면 IngestService가 비운다.
 * 시즌은 응답을 만들 때마다 CurrentSeason에 한 번 묻는다(개막일이 지나면 다시 띄우지 않아도 새 시즌을 보인다).
 */
@Service
@Transactional(readOnly = true)
public class BaseballQueryService {

    private static final List<String> REGULAR_SEASON = List.of("regular");

    private final PlayerRepository players;
    private final AffiliationRepository affiliations;
    private final AppearanceRepository appearances;
    private final GameRepository games;
    private final PlayerMoveRepository moves;
    private final MinorSeasonRepository minorSeasons;
    private final GameLineAssembler gameLines;
    private final RecentGamesFinder recentGames;
    private final RosterProfiles profiles;
    private final DataMode dataMode;
    private final CurrentSeason currentSeason;
    private final Clock clock;

    public BaseballQueryService(PlayerRepository players, AffiliationRepository affiliations,
                                AppearanceRepository appearances, GameRepository games, PlayerMoveRepository moves,
                                MinorSeasonRepository minorSeasons, GameLineAssembler gameLines,
                                RecentGamesFinder recentGames, RosterProfiles profiles, DataMode dataMode,
                                CurrentSeason currentSeason, Clock clock) {
        this.players = players;
        this.affiliations = affiliations;
        this.appearances = appearances;
        this.games = games;
        this.moves = moves;
        this.minorSeasons = minorSeasons;
        this.gameLines = gameLines;
        this.recentGames = recentGames;
        this.profiles = profiles;
        this.dataMode = dataMode;
        this.currentSeason = currentSeason;
        this.clock = clock;
    }

    @Cacheable(AppConfig.HOME_CACHE)
    public HomeResponse home() {
        Instant now = clock.instant();
        int season = currentSeason.current();
        boolean statsAvailable = statsAvailable();
        List<RosterMember> roster = roster();
        RankingMinimums ranking = RankingMinimums.of(teamGamesPlayed(roster, season));

        List<HitterSeason> hitters = List.of();
        List<PitcherSeason> pitchers = List.of();
        if (statsAvailable && !roster.isEmpty()) {
            List<Long> ids = roster.stream().map(m -> m.player().getId()).toList();
            Map<Long, List<Appearance>> byPlayer = regularSeason(ids, season);
            Map<Long, MinorSeason> minors = minorSeasonsOf(ids, season);
            // 올해 주로 메이저에서 뛴 선수 먼저(주로 마이너면 맨 아래), 그 안에서 순위 기준을 채운 선수 먼저,
            // 그 안에서 타율(투수는 평균자책점) 순
            hitters = roster.stream()
                    .filter(m -> m.player().role() == Role.HITTER)
                    .map(m -> SeasonStats.hitter(m.player(), profiles.photoUrl(m.player()), m.team(),
                            seasonOf(byPlayer, m.player()), hittingGames(minors.get(m.player().getId()))))
                    .sorted(Comparator.comparing(HitterSeason::mainlyMinors)
                            .thenComparing((HitterSeason h) -> !ranking.qualifies(h))
                            .thenComparing(HitterSeason::avg, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            pitchers = roster.stream()
                    .filter(m -> m.player().role() == Role.PITCHER)
                    .map(m -> SeasonStats.pitcher(m.player(), profiles.photoUrl(m.player()), m.team(),
                            seasonOf(byPlayer, m.player()), pitchingGames(minors.get(m.player().getId()))))
                    .sorted(Comparator.comparing(PitcherSeason::mainlyMinors)
                            .thenComparing((PitcherSeason p) -> !ranking.qualifies(p))
                            .thenComparing(PitcherSeason::era, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        }

        return new HomeResponse(dataMode.value(), statsAvailable, season, now.truncatedTo(ChronoUnit.SECONDS),
                recentGames.find(roster, now, statsAvailable), ranking, hitters, pitchers);
    }

    /** 선수 번호 → 이번 시즌 마이너 출전 경기 수. 아직 받지 않은 선수는 빠진다. */
    private Map<Long, MinorSeason> minorSeasonsOf(List<Long> playerIds, int season) {
        return minorSeasons.findAll(playerIds, season).stream()
                .collect(Collectors.toMap(m -> m.getPlayer().getId(), m -> m));
    }

    private static int hittingGames(MinorSeason minor) {
        return minor == null ? 0 : minor.getHittingGames();
    }

    private static int pitchingGames(MinorSeason minor) {
        return minor == null ? 0 : minor.getPitchingGames();
    }

    /** 시즌이 얼마나 지났는지: 메이저에 있는 선수들의 구단 중 이번 정규시즌에 끝난 경기가 가장 많은 구단의 경기 수 */
    private int teamGamesPlayed(List<RosterMember> roster, int season) {
        return (int) roster.stream()
                .map(RosterMember::majorTeam)
                .filter(Objects::nonNull)
                .map(Team::getMlbTeamId)
                .distinct()
                .mapToLong(teamId -> games.countFinalRegularGames(teamId, season))
                .max()
                .orElse(0);
    }

    /** MLB에서 받기 전인 선수도 포함한다(영어 이름·포지션·팀·나이·등번호가 null). */
    public List<PlayerSummary> players() {
        Map<Long, Team> teamByPlayer = roster().stream()
                .collect(Collectors.toMap(m -> m.player().getId(), RosterMember::team));
        LocalDate today = KoreanTime.kstDate(clock.instant());
        return players.findAll(Sort.by("id")).stream()
                .map(p -> PlayerSummary.of(p, profiles.photoUrl(p), teamByPlayer.get(p.getId()), today))
                .toList();
    }

    @Cacheable(cacheNames = AppConfig.PLAYER_CACHE, key = "#id")
    public PlayerPageResponse player(long id) {
        Player player = players.findById(id).orElseThrow(() -> new PlayerNotFoundException(id));
        RosterMember member = RosterMember.of(player, affiliations.findByPlayer(player).orElse(null));
        Team team = member.team();
        int season = currentSeason.current();
        boolean statsAvailable = statsAvailable();

        HitterSeason hitting = null;
        PitcherSeason pitching = null;
        if (statsAvailable) {
            List<Appearance> mine = seasonOf(regularSeason(List.of(player.getId()), season), player);
            MinorSeason minor = minorSeasons.find(player.getId(), season).orElse(null);
            if (player.role() == Role.PITCHER) {
                pitching = SeasonStats.pitcher(player, profiles.photoUrl(player), team, mine, pitchingGames(minor));
            } else {
                hitting = SeasonStats.hitter(player, profiles.photoUrl(player), team, mine, hittingGames(minor));
            }
        }

        // 시즌(1월 1일 ~ 지금) 동안 시작한 경기
        Instant seasonStart = LocalDate.of(season, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant seasonEnd = LocalDate.of(season + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant now = clock.instant();
        List<GameLine> games = gameLines.lines(List.of(member), seasonStart,
                now.isBefore(seasonEnd) ? now : seasonEnd, statsAvailable);

        PlayerPageResponse.PlayerInfo info = new PlayerPageResponse.PlayerInfo(
                player.getId(), player.getNameKo(), profiles.photoUrl(player), profiles.mlbUrl(player),
                player.getNameEn(), player.role(), player.getPosition(), PositionNames.korean(player.getPosition()),
                TeamLabels.korean(team), TeamLabels.abbreviation(team), TeamLabels.english(team),
                player.getBirthPlace(), player.getJersey(), player.getBirthDate(),
                BirthDates.age(player.getBirthDate(), player.getAge(), KoreanTime.kstDate(now)),
                BodyMeasures.heightCm(player.getHeight()), BodyMeasures.weightKg(player.getWeight()),
                BatsThrows.korean(player.getBatsThrows()), player.getDebutYear(), profiles.careerKo(player),
                player.getLevel(), player.getLevelTeamName(), player.isFreeAgent());
        return new PlayerPageResponse(dataMode.value(), statsAvailable, season, info, hitting, pitching, games,
                fanMoves(moves.findNewestFirst(player.getId())));
    }

    /**
     * 이동 기록(최신순) 중 팬에게 보일 것만 고른다. 이름(labelKo)이 없는 줄(의미 없는 상태 변경)은 빼고,
     * MLB가 같은 날 같은 내용을 두 번 준 줄은 하나만 남긴다.
     */
    static List<PlayerPageResponse.Move> fanMoves(List<PlayerMove> newestFirst) {
        record Key(LocalDate date, String typeCode, String description) {
        }
        Set<Key> seen = new HashSet<>();
        List<PlayerPageResponse.Move> result = new ArrayList<>();
        for (PlayerMove move : newestFirst) {
            String label = MoveLabels.korean(move.getTypeCode(), move.getTypeDesc(), move.getDescription());
            if (label == null || !seen.add(new Key(move.getMoveDate(), move.getTypeCode(), move.getDescription()))) {
                continue;
            }
            result.add(new PlayerPageResponse.Move(move.getMoveDate(), move.getTypeCode(), label,
                    TeamLabels.mlbTeam(move.getFromTeamId(), move.getFromTeamName()),
                    TeamLabels.mlbTeam(move.getToTeamId(), move.getToTeamName()),
                    move.getToLevel()));
        }
        return result;
    }

    /**
     * 선수 기록을 받는 상태인지. MLB 공식 API(와 샘플)는 경기별 선수 기록을 늘 주므로 true다.
     * 응답의 statsAvailable 필드는 화면 호환 때문에 남겨 둔다. 내 선수 응답(MyPlayersService)도 이 값을 쓴다.
     */
    boolean statsAvailable() {
        return true;
    }

    // 소속 줄이 있는 선수들. 소속이 끝난 선수(FA 등)도 마지막 소속으로 넣는다(시즌 기록이 홈에서 빠지지 않게).
    private List<RosterMember> roster() {
        return affiliations.findAllLatest().stream()
                .map(a -> RosterMember.of(a.getPlayer(), a))
                .toList();
    }

    private Map<Long, List<Appearance>> regularSeason(Collection<Long> playerIds, int season) {
        return appearances.findSeason(playerIds, season, REGULAR_SEASON).stream()
                .collect(Collectors.groupingBy(a -> a.getPlayer().getId()));
    }

    private static List<Appearance> seasonOf(Map<Long, List<Appearance>> byPlayer, Player player) {
        return byPlayer.getOrDefault(player.getId(), List.of());
    }
}
