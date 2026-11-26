package com.korleaguer.ingest;

import com.korleaguer.client.MlbBoxscore;
import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbStat;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Lineup;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeason;
import com.korleaguer.domain.PlayerSeasonRepository;
import com.korleaguer.domain.SeasonType;
import com.korleaguer.domain.Team;
import com.korleaguer.domain.TeamRepository;
import com.korleaguer.support.GameStatuses;
import com.korleaguer.support.KoreanTime;
import com.korleaguer.support.MlbTeams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.korleaguer.ingest.MlbValues.gameState;
import static com.korleaguer.ingest.MlbValues.toBatting;
import static com.korleaguer.ingest.MlbValues.toPitching;

/**
 * MLB 경기와 그 경기의 우리 선수 기록(일정, 시즌 gameLog, 박스스코어, 선발·교체)을 DB에 upsert한다.
 * HTTP 호출은 느리므로 수집기(GameIngest)가 먼저 다 받아 오고, 저장만 여기서 트랜잭션으로 묶는다.
 * 값이 이상한 건(번호·날짜가 없음, DB에 없는 경기)은 그 건만 건너뛰고 센다.
 */
@Component
@Transactional
public class GameWriter {

    private static final Logger log = LoggerFactory.getLogger(GameWriter.class);

    private final GameRepository games;
    private final AppearanceRepository appearances;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final PlayerSeasonRepository seasons;
    private final PlayerWriter playerWriter;

    public GameWriter(GameRepository games, AppearanceRepository appearances, TeamRepository teams,
                      PlayerRepository players, PlayerSeasonRepository seasons, PlayerWriter playerWriter) {
        this.games = games;
        this.appearances = appearances;
        this.teams = teams;
        this.players = players;
        this.seasons = seasons;
        this.playerWriter = playerWriter;
    }

    /**
     * 일정의 경기들을 upsert한다. 정규시즌·포스트시즌 경기만 저장한다(시범경기·올스타 등은 건너뛴다).
     * 같은 gamePk가 두 번 오면(연기된 뒤 다시 잡힘) 연기가 아닌 쪽, 그중 늦게 시작하는 쪽을 쓴다.
     * 끝난 경기는 처음 본 시각을 남긴다.
     *
     * @return 저장한 경기 번호들
     */
    public List<Long> saveGames(List<MlbScheduleGame> sources, Instant now) {
        List<Long> saved = new ArrayList<>();
        for (MlbScheduleGame source : distinctByGamePk(sources)) {
            Game game = saveGame(source, now);
            if (game != null) {
                saved.add(game.getGamePk());
            }
        }
        return saved;
    }

    /**
     * 시즌 백필 한 번을 한 트랜잭션으로 저장한다: 일정(경기), 선수들의 gameLog(타격·투구), 그리고 선수·시즌 "받았음" 표시.
     * gameLog가 이미 기록을 주므로 정규시즌의 끝난 지 오래된 경기는 바로 settled로 둔다(박스스코어를 받지 않는다).
     * 포스트시즌 경기는 gameLog에 없으므로 그대로 두어 박스스코어로 받게 한다.
     */
    public SeasonSaveResult saveSeason(int season, List<MlbScheduleGame> schedule, List<PlayerLogs> logs, Instant now) {
        List<Long> savedGames = saveGames(schedule, now);
        for (Game game : games.findAllById(savedGames)) {
            if (SeasonType.of(game.getSeasonType()) == SeasonType.REGULAR) {
                game.settleIfDue(now);
            }
        }
        int savedLines = 0;
        int skippedLines = 0;
        for (PlayerLogs playerLogs : logs) {
            Player player = players.findById(playerLogs.playerId()).orElseThrow();
            LineCount count = saveGameLog(player, playerLogs.hitting(), playerLogs.pitching());
            savedLines += count.saved();
            skippedLines += count.skipped();
            seasons.find(player.getId(), season).ifPresentOrElse(
                    marker -> marker.refetched(now),
                    () -> seasons.save(new PlayerSeason(player, season, now)));
            // 처음 받을 때 이미 MLB 구단 소속이 없던 선수(FA 등)는 선수 정보 단계에서 남길 팀이 없었다.
            // 방금 저장한 기록의 마지막 팀을 끝난 소속으로 남겨 홈 시즌 성적에 보이게 한다.
            if (player.getLevelCheckedAt() != null) {
                playerWriter.keepLastPlayedTeamIfUnaffiliated(player, LocalDate.ofInstant(now, ZoneOffset.UTC));
            }
        }
        if (skippedLines > 0) {
            log.warn("skipped {} gameLog lines (game not in schedule or missing values)", skippedLines);
        }
        return new SeasonSaveResult(savedGames.size(), savedLines, skippedLines);
    }

    /**
     * 박스스코어에서 우리 선수(그 경기에 뛴 선수만)의 기록과 선발·교체를 저장하고, 경기 점수와 확인 시각을 고친다.
     *
     * @param playerIdByMlbId 우리 선수의 MLB 번호 → 선수 id
     * @return 저장한 선수 기록 수
     */
    public int saveBoxscore(long gamePk, MlbBoxscore box, Map<Long, Long> playerIdByMlbId, Instant now) {
        Optional<Game> found = games.findById(gamePk);
        if (found.isEmpty()) {
            log.debug("skip boxscore of unknown game {}", gamePk);
            return 0;
        }
        Game game = found.get();
        int saved = 0;
        if (box != null) {
            for (MlbBoxscore.Side side : box.sides()) {
                if (side.team() == null || side.team().id() == null) {
                    continue;
                }
                Team team = saveTeam(side.team());
                for (Map.Entry<Long, Long> ours : playerIdByMlbId.entrySet()) {
                    MlbBoxscore.Player line = side.player(ours.getKey());
                    if (line == null || (line.batting() == null && line.pitching() == null)) {
                        continue;
                    }
                    Player player = players.findById(ours.getValue()).orElseThrow();
                    Appearance appearance = appearances.findByPlayerAndGame(player, game)
                            .orElseGet(() -> new Appearance(player, game));
                    appearance.update(team, toBatting(line.batting()), toPitching(line.pitching()));
                    appearance.updateLineup(LineupRule.of(box, ours.getKey()));
                    appearances.save(appearance);
                    saved++;
                }
            }
            MlbBoxscore.Teams sides = box.teams();
            if (sides != null && sides.home() != null && sides.away() != null
                    && sides.home().runs() != null && sides.away().runs() != null) {
                game.updateScore(sides.home().runs(), sides.away().runs());
            }
        }
        game.markChecked(now);
        if (box != null) {
            game.markLineupsChecked();
        }
        return saved;
    }

    /**
     * 지난 경기의 선발·교체만 채운다(기록은 건드리지 않는다). 그 경기의 우리 선수 기록 중 선발·교체를 모르는 것만 고친다.
     * 박스스코어에 없는 선수는 null로 두고, 경기는 "채워 봤음"으로 표시해 다시 받지 않는다.
     *
     * @return 선발·교체를 정한 기록 수
     */
    public int saveLineups(long gamePk, MlbBoxscore box, Map<Long, Long> playerIdByMlbId) {
        Game game = games.findById(gamePk).orElse(null);
        if (game == null) {
            return 0;
        }
        Map<Long, Long> mlbIdByPlayerId = playerIdByMlbId.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        int filled = 0;
        for (Appearance appearance : appearances.findByPlayersAndGames(mlbIdByPlayerId.keySet(), List.of(gamePk))) {
            Long mlbId = mlbIdByPlayerId.get(appearance.getPlayer().getId());
            if (appearance.getLineup() != null || mlbId == null) {
                continue;
            }
            Lineup lineup = LineupRule.of(box, mlbId);
            if (lineup != null) {
                appearance.updateLineup(lineup);
                filled++;
            }
        }
        game.markLineupsChecked();
        return filled;
    }

    private LineCount saveGameLog(Player player, List<MlbGameLogSplit> hitting, List<MlbGameLogSplit> pitching) {
        // 같은 경기의 타격·투구 줄을 합친다(투타겸업).
        Map<Long, MlbGameLogSplit> hitByGame = byGamePk(hitting);
        Map<Long, MlbGameLogSplit> pitchByGame = byGamePk(pitching);
        List<Long> gamePks = Stream.concat(hitByGame.keySet().stream(), pitchByGame.keySet().stream())
                .distinct()
                .toList();
        Map<Long, Game> gameById = games.findAllById(gamePks).stream()
                .collect(Collectors.toMap(Game::getGamePk, Function.identity()));
        int saved = 0;
        int skipped = hitting.size() + pitching.size() - hitByGame.size() - pitchByGame.size();
        for (Long gamePk : gamePks) {
            MlbGameLogSplit hit = hitByGame.get(gamePk);
            MlbGameLogSplit pitch = pitchByGame.get(gamePk);
            MlbTeam team = hit != null ? hit.team() : pitch.team();
            Game game = gameById.get(gamePk);
            if (game == null || team == null || team.id() == null) {
                skipped++;
                continue;
            }
            Optional<Appearance> existing = appearances.findByPlayerAndGame(player, game);
            Appearance appearance = existing.orElseGet(() -> new Appearance(player, game));
            if (existing.isEmpty()) {
                // 새 기록은 선발·교체를 모른다. 이미 채워 본 경기여도(다른 우리 선수 때문에) 다시 채우게 한다
                // (나중에 명단에 들어온 선수의 시즌 백필 등). 안 그러면 fillLineups가 이 경기를 다시 보지 않는다.
                game.markLineupsUnchecked();
            }
            appearance.update(saveTeam(team), hit == null ? null : toBatting(hit.stat()),
                    pitch == null ? null : toPitching(pitch.stat()));
            appearances.save(appearance);
            saved++;
        }
        return new LineCount(saved, skipped);
    }

    private Game saveGame(MlbScheduleGame source, Instant now) {
        // 정규시즌·포스트시즌만 저장한다(시범경기·올스타 등은 종류가 null이라 건너뛴다)
        SeasonType seasonType = SeasonType.fromGameType(source.gameType());
        MlbScheduleGame.Teams sides = source.teams();
        if (source.gamePk() == null || seasonType == null || source.gameDate() == null || sides == null
                || !hasTeam(sides.home()) || !hasTeam(sides.away())) {
            return null;
        }
        Instant start;
        int season;
        try {
            start = KoreanTime.parseUtc(source.gameDate());
            season = Integer.parseInt(source.season());
        } catch (DateTimeParseException | NumberFormatException e) {
            log.debug("skip game {} with unreadable date or season", source.gamePk());
            return null;
        }
        Game game = games.findById(source.gamePk()).orElseGet(() -> new Game(source.gamePk()));
        game.update(season, seasonType.stored(), start, saveTeam(sides.home().team()), saveTeam(sides.away().team()),
                sides.home().score(), sides.away().score(),
                gameState(source),
                source.venue() == null ? null : source.venue().name());
        game.markFinalIfNew(now);
        return games.save(game);
    }

    private Team saveTeam(MlbTeam source) {
        Team team = teams.findById(source.id()).orElseGet(() -> new Team(source.id()));
        String abbreviation = MlbTeams.abbreviation(source.id());
        team.update(abbreviation != null ? abbreviation : source.abbreviation(),
                source.name() != null ? source.name() : team.getNameEn());
        return teams.save(team);
    }

    // 같은 gamePk가 여러 번 오면 연기가 아닌 쪽, 그중 늦게 시작하는 쪽
    private static Collection<MlbScheduleGame> distinctByGamePk(List<MlbScheduleGame> sources) {
        Map<Long, MlbScheduleGame> chosen = new LinkedHashMap<>();
        for (MlbScheduleGame source : sources) {
            if (source.gamePk() == null) {
                continue;
            }
            chosen.merge(source.gamePk(), source, (kept, next) -> better(next, kept) ? next : kept);
        }
        return chosen.values();
    }

    private static boolean better(MlbScheduleGame candidate, MlbScheduleGame current) {
        boolean candidatePostponed = postponed(candidate);
        if (candidatePostponed != postponed(current)) {
            return !candidatePostponed;
        }
        return String.valueOf(candidate.gameDate()).compareTo(String.valueOf(current.gameDate())) > 0;
    }

    private static boolean postponed(MlbScheduleGame game) {
        return GameStatuses.POSTPONED.equals(gameState(game));
    }

    private static Map<Long, MlbGameLogSplit> byGamePk(List<MlbGameLogSplit> splits) {
        Map<Long, MlbGameLogSplit> byGame = new LinkedHashMap<>();
        for (MlbGameLogSplit split : splits) {
            if (split.gamePk() != null && !MlbStat.isEmpty(split.stat())) {
                byGame.putIfAbsent(split.gamePk(), split);
            }
        }
        return byGame;
    }

    private static boolean hasTeam(MlbScheduleGame.Side side) {
        return side != null && side.team() != null && side.team().id() != null;
    }

    /** 선수 한 명의 시즌 gameLog(타격·투구) */
    public record PlayerLogs(long playerId, List<MlbGameLogSplit> hitting, List<MlbGameLogSplit> pitching) {
    }

    public record SeasonSaveResult(int savedGames, int savedLines, int skippedLines) {
    }

    private record LineCount(int saved, int skipped) {
    }
}
