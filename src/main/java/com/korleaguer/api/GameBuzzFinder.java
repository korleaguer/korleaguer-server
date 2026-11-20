package com.korleaguer.api;

import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.KoreanMlbSeason;
import com.korleaguer.domain.KoreanMlbSeasonRepository;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 홈 최근 경기 줄마다 화제 문구(GameBuzz)를 붙인다. 우선순위는 넘은 기록 > 데뷔·시즌 첫 > 연속 안타 > 시즌 최고 경기.
 * 넘은 기록은 /api/records의 passed(캐시된 응답)를, 나머지는 그 시즌 정규시즌 경기 기록(GameBuzzRules)을 본다.
 * 뛰지 않은 줄과 포스트시즌 경기는 문구를 만들지 않는다(시즌 기록 기준이라서).
 */
@Component
class GameBuzzFinder {

    private static final List<String> REGULAR = List.of(SeasonType.REGULAR.stored());

    private final AppearanceRepository appearances;
    private final KoreanMlbSeasonRepository mlbSeasons;
    private final RecordsService records;
    private final CurrentSeason currentSeason;

    GameBuzzFinder(AppearanceRepository appearances, KoreanMlbSeasonRepository mlbSeasons, RecordsService records,
                   CurrentSeason currentSeason) {
        this.appearances = appearances;
        this.mlbSeasons = mlbSeasons;
        this.records = records;
        this.currentSeason = currentSeason;
    }

    List<GameBuzz> find(List<RosterMember> roster, List<GameLine> lines) {
        List<GameLine> candidates = lines.stream()
                .filter(line -> line.played() && line.seasonType() == SeasonType.REGULAR)
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, Player> players = roster.stream().map(RosterMember::player)
                .collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));
        int season = currentSeason.seasonAt(candidates.getFirst().startTimeKst().toInstant());
        Map<Long, List<Appearance>> seasonGames = appearances
                .findSeason(candidates.stream().map(GameLine::playerId).distinct().toList(), season, REGULAR).stream()
                .sorted(Comparator.comparing((Appearance a) -> a.getGame().getStartTimeUtc()))
                .collect(Collectors.groupingBy(a -> a.getPlayer().getId()));
        List<RecordsResponse.Passed> passed = records.records().passed();

        List<GameBuzz> result = new ArrayList<>();
        for (GameLine line : candidates) {
            Player player = players.get(line.playerId());
            if (player == null) {
                continue;
            }
            passed(line, passed).or(() -> fromSeason(line, player, season, seasonGames))
                    .ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    /** 그 경기 날(한국 날짜)에 이 선수가 넘은 선배 기록. 같은 날 여럿이면 passed의 순서(종목 순)로 첫 번째 */
    private static Optional<GameBuzz> passed(GameLine line, List<RecordsResponse.Passed> passed) {
        return passed.stream()
                .filter(p -> p.playerId() != null && p.playerId() == line.playerId()
                        && p.dateKst().equals(line.startTimeKst().toLocalDate()))
                .findFirst()
                .map(p -> new GameBuzz(line.playerId(), line.gameId(), GameBuzz.PASSED, p.category(), passedText(p)));
    }

    /** 예: "한국인 통산 홈런 이대호(14개) 넘음" */
    static String passedText(RecordsResponse.Passed p) {
        String unit = switch (p.category()) {
            case "games" -> "경기";
            case "wins" -> "승";
            default -> "개";
        };
        return "한국인 통산 " + p.label() + " " + p.passedNameKo() + "(" + p.passedValue() + unit + ") 넘음";
    }

    private Optional<GameBuzz> fromSeason(GameLine line, Player player, int season,
                                          Map<Long, List<Appearance>> seasonGames) {
        List<GameBuzzRules.Game> games = new ArrayList<>();
        for (Appearance a : seasonGames.getOrDefault(line.playerId(), List.of())) {
            games.add(game(a));
            if (a.getGame().getGamePk() == line.gameId()) {
                break;
            }
        }
        if (games.isEmpty() || games.getLast().gameId() != line.gameId()) {
            return Optional.empty();
        }
        boolean pitcher = line.role() == Role.PITCHER;
        return GameBuzzRules.find(line.playerId(), games, pitcher, debutSeason(player, season));
    }

    /**
     * 이번이 메이저 첫 시즌인가. 시즌별 기록 줄이 있는데 지난 시즌 줄에 경기가 하나도 없을 때만 참이다
     * (기록을 아직 못 받았으면 모르니까 '메이저 첫'이라고 말하지 않는다).
     */
    private boolean debutSeason(Player player, int season) {
        if (player.getMlbId() == null) {
            return false;
        }
        List<KoreanMlbSeason> rows = mlbSeasons.findByMlbIdAndStatGroup(player.getMlbId(),
                PlayerSeasonMetric.Group.of(player.role()));
        return !rows.isEmpty() && rows.stream()
                .noneMatch(row -> row.getSeason() < season && row.counts().games() != null && row.counts().games() > 0);
    }

    private static GameBuzzRules.Game game(Appearance a) {
        Batting b = a.getBatting();
        Pitching p = a.getPitching();
        return new GameBuzzRules.Game(a.getGame().getGamePk(), b != null, orZero(b == null ? null : b.atBats()),
                orZero(b == null ? null : b.hits()), orZero(b == null ? null : b.homeRuns()),
                orZero(b == null ? null : b.rbi()), orZero(b == null ? null : b.walks()),
                orZero(b == null ? null : b.stolenBases()), b == null ? 0 : b.totalBasesOrComputed(), p != null);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
