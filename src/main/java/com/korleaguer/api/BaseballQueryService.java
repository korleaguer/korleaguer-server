package com.korleaguer.api;

import com.korleaguer.config.AppConfig;
import com.korleaguer.config.DataMode;
import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.IngestKind;
import com.korleaguer.domain.IngestLogRepository;
import com.korleaguer.domain.IngestStatus;
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
import java.util.Set;
import java.util.stream.Collectors;

/** DB를 읽어 화면용 응답을 만든다. 홈과 선수 응답은 60초 캐시하고, 수집이 끝나면 IngestService가 비운다. */
@Service
@Transactional(readOnly = true)
public class BaseballQueryService {

    private static final List<String> REGULAR_SEASON = List.of("regular");

    private final PlayerRepository players;
    private final AffiliationRepository affiliations;
    private final AppearanceRepository appearances;
    private final IngestLogRepository ingestLogs;
    private final PlayerMoveRepository moves;
    private final GameLineAssembler gameLines;
    private final RecentGamesFinder recentGames;
    private final RosterProfiles profiles;
    private final DataMode dataMode;
    private final int season;
    private final Clock clock;

    public BaseballQueryService(PlayerRepository players, AffiliationRepository affiliations,
                                AppearanceRepository appearances, IngestLogRepository ingestLogs,
                                PlayerMoveRepository moves, GameLineAssembler gameLines,
                                RecentGamesFinder recentGames, RosterProfiles profiles, DataMode dataMode, KorleaguerProperties properties,
                                Clock clock) {
        this.players = players;
        this.affiliations = affiliations;
        this.appearances = appearances;
        this.ingestLogs = ingestLogs;
        this.moves = moves;
        this.gameLines = gameLines;
        this.recentGames = recentGames;
        this.profiles = profiles;
        this.dataMode = dataMode;
        this.season = properties.season();
        this.clock = clock;
    }

    @Cacheable(AppConfig.HOME_CACHE)
    public HomeResponse home() {
        Instant now = clock.instant();
        boolean statsAvailable = statsAvailable();
        List<RosterMember> roster = currentRoster();

        List<HitterSeason> hitters = List.of();
        List<PitcherSeason> pitchers = List.of();
        if (statsAvailable && !roster.isEmpty()) {
            Map<Long, List<Appearance>> byPlayer = regularSeason(roster.stream().map(m -> m.player().getId()).toList());
            hitters = roster.stream()
                    .filter(m -> m.player().role() == Role.HITTER)
                    .map(m -> SeasonStats.hitter(m.player(), profiles.photoUrl(m.player()), m.team(),
                            seasonOf(byPlayer, m.player())))
                    .sorted(Comparator.comparing(HitterSeason::avg, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            pitchers = roster.stream()
                    .filter(m -> m.player().role() == Role.PITCHER)
                    .map(m -> SeasonStats.pitcher(m.player(), profiles.photoUrl(m.player()), m.team(),
                            seasonOf(byPlayer, m.player())))
                    .sorted(Comparator.comparing(PitcherSeason::era, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        }

        return new HomeResponse(dataMode.value(), statsAvailable, season, now.truncatedTo(ChronoUnit.SECONDS),
                recentGames.find(roster, now, statsAvailable), hitters, pitchers);
    }

    /** 매칭 전인 선수도 포함한다(영어 이름·포지션·팀·나이·등번호가 null). */
    public List<PlayerSummary> players() {
        Map<Long, Team> teamByPlayer = currentRoster().stream()
                .collect(Collectors.toMap(m -> m.player().getId(), RosterMember::team));
        LocalDate today = KoreanTime.kstDate(clock.instant());
        return players.findAll(Sort.by("id")).stream()
                .map(p -> PlayerSummary.of(p, profiles.photoUrl(p), teamByPlayer.get(p.getId()), today))
                .toList();
    }

    @Cacheable(cacheNames = AppConfig.PLAYER_CACHE, key = "#id")
    public PlayerPageResponse player(long id) {
        Player player = players.findById(id).orElseThrow(() -> new PlayerNotFoundException(id));
        Team team = affiliations.findByPlayerAndEndDateIsNull(player).map(Affiliation::getTeam).orElse(null);
        boolean statsAvailable = statsAvailable();

        HitterSeason hitting = null;
        PitcherSeason pitching = null;
        if (statsAvailable) {
            List<Appearance> mine = seasonOf(regularSeason(List.of(player.getId())), player);
            if (player.role() == Role.PITCHER) {
                pitching = SeasonStats.pitcher(player, profiles.photoUrl(player), team, mine);
            } else {
                hitting = SeasonStats.hitter(player, profiles.photoUrl(player), team, mine);
            }
        }

        // 시즌(1월 1일 ~ 지금) 동안 시작한 경기
        Instant seasonStart = LocalDate.of(season, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant seasonEnd = LocalDate.of(season + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant now = clock.instant();
        List<GameLine> games = gameLines.lines(List.of(new RosterMember(player, team)), seasonStart,
                now.isBefore(seasonEnd) ? now : seasonEnd, statsAvailable);

        PlayerPageResponse.PlayerInfo info = new PlayerPageResponse.PlayerInfo(
                player.getId(), player.getNameKo(), profiles.photoUrl(player), profiles.mlbUrl(player),
                player.getNameEn(), player.role(), player.getPosition(), PositionNames.korean(player.getPosition()),
                TeamLabels.korean(team), TeamLabels.abbreviation(team), TeamLabels.english(team),
                player.getBirthPlace(), player.getJersey(), player.getBirthDate(),
                BirthDates.age(player.getBirthDate(), player.getAge(), KoreanTime.kstDate(now)),
                BodyMeasures.heightCm(player.getHeight()), BodyMeasures.weightKg(player.getWeight()),
                BatsThrows.korean(player.getBatsThrows()), player.getDebutYear(), profiles.careerKo(player));
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
     * 선수 기록을 받은 상태인지. 가장 최근에 끝난 시즌·하루치 수집이 기록까지 받았으면(SUCCESS) true다.
     * 401/403으로 막혔거나(STATS_UNAVAILABLE) 아직 한 번도 끝나지 않았으면 false다.
     * 경기와 기록은 한 트랜잭션으로 저장하므로, 수집이 도는 중에도 DB에는 지난번 수집 결과만 보인다.
     * 내 선수 응답(MyPlayersService)도 이 값을 쓴다.
     */
    boolean statsAvailable() {
        return ingestLogs.findFirstByKindInAndStatusInOrderByIdDesc(
                        List.of(IngestKind.SEASON, IngestKind.DAILY),
                        List.of(IngestStatus.SUCCESS, IngestStatus.STATS_UNAVAILABLE))
                .map(log -> log.getStatus() == IngestStatus.SUCCESS)
                .orElse(false);
    }

    private List<RosterMember> currentRoster() {
        return affiliations.findAllCurrent().stream()
                .map(a -> new RosterMember(a.getPlayer(), a.getTeam()))
                .toList();
    }

    private Map<Long, List<Appearance>> regularSeason(Collection<Long> playerIds) {
        return appearances.findSeason(playerIds, season, REGULAR_SEASON).stream()
                .collect(Collectors.groupingBy(a -> a.getPlayer().getId()));
    }

    private static List<Appearance> seasonOf(Map<Long, List<Appearance>> byPlayer, Player player) {
        return byPlayer.getOrDefault(player.getId(), List.of());
    }
}
