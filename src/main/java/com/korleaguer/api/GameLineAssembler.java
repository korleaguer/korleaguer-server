package com.korleaguer.api;

import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Team;
import com.korleaguer.support.BaseballMath;
import com.korleaguer.support.GameSummary;
import com.korleaguer.support.KoreanTime;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 선수 × 경기 한 줄(GameLine)을 조립한다.
 * <ul>
 *   <li>선수 기록이 있는 경기: played=true</li>
 *   <li>기록이 없는 정규시즌·포스트시즌 경기: 아래 "뛴 기간" 안에서만 played=false 줄을 만든다(시범경기는 뺀다).
 *     <ul>
 *       <li>선수 기록을 받은 상태: 그 팀에서 처음 기록을 남긴 경기부터 마지막 기록을 남긴 경기까지.
 *           단 지금 소속 팀이 마지막 기록의 팀과 같으면 끝을 열어 둔다(마지막 출전 뒤 경기도 결장으로 보인다).
 *           지금 마이너에 있는 선수(level=MINORS)는 끝을 열지 않는다(마이너로 간 뒤의 메이저 경기는 결장이 아니다).</li>
 *       <li>선수 기록을 받지 못한 상태: 지금 소속 팀 경기 전부. summary는 "기록 없음"이다.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
@Component
class GameLineAssembler {

    private static final Set<String> COUNTED_SEASON_TYPES = Set.of("regular", "postseason");
    private static final Set<String> NOT_STARTED = Set.of("scheduled", "postponed", "canceled");

    private final GameRepository games;
    private final AppearanceRepository appearances;
    private final RosterProfiles profiles;
    private final int season;

    GameLineAssembler(GameRepository games, AppearanceRepository appearances, RosterProfiles profiles,
                      KorleaguerProperties properties) {
        this.games = games;
        this.appearances = appearances;
        this.profiles = profiles;
        this.season = properties.season();
    }

    /** [from, to) 사이에 시작한 경기의 한 줄 기록. 선수 순서대로, 같은 선수는 최신 경기부터. */
    List<GameLine> lines(List<RosterMember> members, Instant from, Instant to, boolean statsReady) {
        if (members.isEmpty() || !from.isBefore(to)) {
            return List.of();
        }
        List<Long> playerIds = members.stream().map(m -> m.player().getId()).toList();
        List<Appearance> played = appearances.findWithGameBetween(playerIds, from, to);
        Map<Long, List<Stint>> stints = stintsByPlayer(members, playerIds, statsReady);
        List<Long> teamIds = stints.values().stream()
                .flatMap(List::stream)
                .map(stint -> stint.team().getMlbTeamId())
                .distinct()
                .toList();
        List<Game> teamGames = teamIds.isEmpty() ? List.of() : games.findTeamGamesBetween(teamIds, from, to).stream()
                .filter(game -> COUNTED_SEASON_TYPES.contains(game.getSeasonType()))
                .toList();

        List<GameLine> lines = new ArrayList<>();
        for (RosterMember member : members) {
            Player player = member.player();
            Set<Long> playedGameIds = new HashSet<>();
            for (Appearance appearance : played) {
                if (appearance.getPlayer().getId().equals(player.getId())) {
                    lines.add(line(player, appearance.getTeam(), appearance.getGame(), appearance, statsReady));
                    playedGameIds.add(appearance.getGame().getGamePk());
                }
            }
            List<Stint> playerStints = stints.getOrDefault(player.getId(), List.of());
            for (Game game : teamGames) {
                if (!playedGameIds.contains(game.getGamePk())) {
                    teamInRange(game, playerStints)
                            .ifPresent(team -> lines.add(line(player, team, game, null, statsReady)));
                }
            }
        }
        lines.sort(Comparator.comparingLong(GameLine::playerId)
                .thenComparing(GameLine::startTimeKst, Comparator.reverseOrder()));
        return lines;
    }

    /** 선수마다 결장 줄을 붙일 수 있는 (팀, 기간) 목록 */
    private Map<Long, List<Stint>> stintsByPlayer(List<RosterMember> members, List<Long> playerIds,
                                                  boolean statsReady) {
        if (!statsReady) {
            // 기록이 없으니 뛴 기간을 알 수 없다. 지금 소속 팀 경기 전부에 "기록 없음"을 단다.
            return members.stream()
                    .filter(m -> m.team() != null)
                    .collect(Collectors.toMap(m -> m.player().getId(), m -> List.of(new Stint(m.team(), null, null))));
        }
        Map<Long, List<Appearance>> byPlayer = appearances.findSeason(playerIds, season, COUNTED_SEASON_TYPES)
                .stream()
                .collect(Collectors.groupingBy(a -> a.getPlayer().getId()));
        return members.stream().collect(Collectors.toMap(
                m -> m.player().getId(),
                m -> stints(byPlayer.getOrDefault(m.player().getId(), List.of()),
                        m.majorTeam())));
    }

    /**
     * @param currentTeam 지금 소속 팀. 마이너에 있는 선수는 null로 넘긴다(메이저 팀의 그 뒤 경기를 결장으로 보지 않는다).
     */
    static List<Stint> stints(List<Appearance> seasonAppearances, Team currentTeam) {
        if (seasonAppearances.isEmpty()) {
            return List.of();
        }
        Appearance latest = seasonAppearances.stream()
                .max(Comparator.comparing(a -> a.getGame().getStartTimeUtc()))
                .orElseThrow();
        Map<Long, List<Appearance>> byTeam = seasonAppearances.stream()
                .collect(Collectors.groupingBy(a -> a.getTeam().getMlbTeamId()));

        List<Stint> result = new ArrayList<>();
        for (List<Appearance> teamAppearances : byTeam.values()) {
            Team team = teamAppearances.getFirst().getTeam();
            List<Instant> starts = teamAppearances.stream().map(a -> a.getGame().getStartTimeUtc()).sorted().toList();
            boolean stillThere = sameTeam(team, currentTeam) && sameTeam(team, latest.getTeam());
            result.add(new Stint(team, starts.getFirst(), stillThere ? null : starts.getLast()));
        }
        return result;
    }

    private static Optional<Team> teamInRange(Game game, List<Stint> stints) {
        return stints.stream()
                .filter(stint -> game.involves(stint.team()) && stint.covers(game.getStartTimeUtc()))
                .map(Stint::team)
                .findFirst();
    }

    private GameLine line(Player player, Team team, Game game, Appearance appearance, boolean statsReady) {
        boolean home = game.isHome(team);
        Team opponent = home ? game.getAwayTeam() : game.getHomeTeam();
        boolean notStarted = NOT_STARTED.contains(game.getStatusState());
        Integer teamScore = notStarted ? null : (home ? game.getHomeScore() : game.getAwayScore());
        Integer opponentScore = notStarted ? null : (home ? game.getAwayScore() : game.getHomeScore());
        boolean played = appearance != null;
        String summary = played
                ? GameSummary.played(player.role(), appearance.getBatting(), appearance.getPitching())
                : GameSummary.notPlayed(game.getStatusState(), statsReady);

        return new GameLine(
                player.getId(),
                player.getNameKo(),
                profiles.photoUrl(player),
                player.role(),
                game.getGamePk(),
                KoreanTime.toKst(game.getStartTimeUtc()),
                TeamLabels.korean(team),
                TeamLabels.abbreviation(team),
                TeamLabels.korean(opponent),
                TeamLabels.abbreviation(opponent),
                home,
                teamScore,
                opponentScore,
                result(game, teamScore, opponentScore),
                game.getStatusState(),
                played,
                summary,
                played ? batting(appearance.getBatting()) : null,
                played ? pitching(appearance.getPitching()) : null);
    }

    private static GameBatting batting(Batting b) {
        if (b == null) {
            return null;
        }
        return new GameBatting(orZero(b.atBats()), orZero(b.hits()), orZero(b.homeRuns()), orZero(b.rbi()),
                orZero(b.walks()), orZero(b.stolenBases()));
    }

    private static GamePitching pitching(Pitching p) {
        if (p == null) {
            return null;
        }
        Integer outs = p.pitchingOuts();
        return new GamePitching(outs, outs == null ? null : BaseballMath.inningsText(outs),
                orZero(p.runsAllowed()), orZero(p.earnedRuns()), orZero(p.pitcherStrikeouts()),
                orZero(p.wins()) > 0, orZero(p.losses()) > 0, orZero(p.saves()) > 0);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** 끝난 경기만 W/L/T, 경기 전·중이면 null */
    private static String result(Game game, Integer teamScore, Integer opponentScore) {
        if (!Game.STATUS_FINAL.equals(game.getStatusState()) || teamScore == null || opponentScore == null) {
            return null;
        }
        int compared = Integer.compare(teamScore, opponentScore);
        return compared > 0 ? "W" : compared < 0 ? "L" : "T";
    }

    private static boolean sameTeam(Team a, Team b) {
        return a != null && b != null && Objects.equals(a.getMlbTeamId(), b.getMlbTeamId());
    }

    /** 선수가 한 팀에서 뛴 기간. first/last가 null이면 그쪽 끝이 열려 있다. */
    record Stint(Team team, Instant first, Instant last) {

        boolean covers(Instant time) {
            return (first == null || !time.isBefore(first)) && (last == null || !time.isAfter(last));
        }
    }
}
