package com.korleaguer.api;

import com.korleaguer.config.AppConfig;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.SeasonType;
import com.korleaguer.domain.Team;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 같은 경기에 나온 우리 선수(GET /api/players/{id}/same-games, 이슈 #217). DB만 읽는다.
 * 경기 기록(appearance)은 우리 선수 것만 있으므로, 한 경기에 기록이 둘 이상이면 우리 선수가 같이 나온 경기다.
 * 마이너 경기는 경기별 기록 표가 달라(minor_game_log) 넣지 않는다.
 * 응답은 선수마다 캐시한다(AppConfig.SAME_GAMES_CACHE, 데이터가 바뀌면 ResponseCaches가 비운다).
 */
@Service
@Transactional(readOnly = true)
public class SameGamesService {

    private static final Set<String> SEASON_TYPES =
            Set.of(SeasonType.REGULAR.stored(), SeasonType.POSTSEASON.stored());

    private final PlayerRepository players;
    private final AffiliationRepository affiliations;
    private final AppearanceRepository appearances;
    private final GameLineAssembler gameLines;
    private final RosterProfiles profiles;
    private final CurrentSeason currentSeason;

    public SameGamesService(PlayerRepository players, AffiliationRepository affiliations,
                            AppearanceRepository appearances, GameLineAssembler gameLines, RosterProfiles profiles,
                            CurrentSeason currentSeason) {
        this.players = players;
        this.affiliations = affiliations;
        this.appearances = appearances;
        this.gameLines = gameLines;
        this.profiles = profiles;
        this.currentSeason = currentSeason;
    }

    @Cacheable(cacheNames = AppConfig.SAME_GAMES_CACHE, key = "#playerId")
    public SameGamesResponse find(long playerId) {
        if (!players.existsById(playerId)) {
            throw new PlayerNotFoundException(playerId);
        }
        int season = currentSeason.current();
        // 이 선수가 나온 경기의 기록 전부(이 선수 것 + 같은 경기의 다른 우리 선수 것)
        Map<Long, List<Appearance>> byGame = appearances.findSeasonInGamesOf(playerId, season, SEASON_TYPES).stream()
                .collect(Collectors.groupingBy(a -> a.getGame().getGamePk()));

        Map<Long, Player> others = new LinkedHashMap<>();
        Map<Long, List<SameGamesResponse.Meeting>> meetings = new LinkedHashMap<>();
        for (List<Appearance> game : byGame.values()) {
            Appearance mine = game.stream().filter(a -> a.getPlayer().getId() == playerId).findFirst().orElse(null);
            if (mine == null) {
                continue;
            }
            GameLine myLine = gameLines.playedLine(mine);
            for (Appearance other : game) {
                if (other == mine) {
                    continue;
                }
                long otherId = other.getPlayer().getId();
                others.putIfAbsent(otherId, other.getPlayer());
                meetings.computeIfAbsent(otherId, id -> new ArrayList<>())
                        .add(new SameGamesResponse.Meeting(myLine, gameLines.playedLine(other)));
            }
        }

        List<SameGamesResponse.Pair> pairs = others.values().stream()
                .map(other -> pair(other, meetings.get(other.getId())))
                .sorted(Comparator.comparingInt(SameGamesResponse.Pair::games).reversed()
                        .thenComparing(p -> p.meetings().getLast().mine().startTimeKst(), Comparator.reverseOrder()))
                .toList();
        return new SameGamesResponse(season, playerId, pairs);
    }

    private SameGamesResponse.Pair pair(Player other, List<SameGamesResponse.Meeting> unsorted) {
        List<SameGamesResponse.Meeting> meetings = unsorted.stream()
                .sorted(Comparator.comparing(m -> m.mine().startTimeKst()))
                .toList();
        Team team = affiliations.findByPlayer(other).map(a -> a.getTeam()).orElse(null);
        return new SameGamesResponse.Pair(
                new SameGamesResponse.Other(other.getId(), other.getNameKo(), profiles.photoUrl(other),
                        profiles.photoTeamAbbr(other, team), TeamLabels.abbreviation(team)),
                meetings.size(), count(meetings, "W"), count(meetings, "L"), singleTeam(meetings), meetings);
    }

    static int count(List<SameGamesResponse.Meeting> meetings, String result) {
        return (int) meetings.stream().filter(m -> result.equals(m.mine().result())).count();
    }

    /** 그 경기들에서 이 선수 팀이 하나면 그 이름, 팀을 옮겨 둘 이상이면 null */
    static String singleTeam(List<SameGamesResponse.Meeting> meetings) {
        List<String> teams = meetings.stream().map(m -> m.mine().teamKo()).distinct().toList();
        return teams.size() == 1 ? teams.getFirst() : null;
    }
}
