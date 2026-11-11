package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbBoxscore;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Level;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.support.MlbTeams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 경기와 선수 기록을 MLB 공식 Stats API에서 받는다. 메이저 경기(정규시즌·포스트시즌)만 받는다.
 * <ul>
 *   <li>시즌 백필: 선수마다 gameLog 2번(타격·투구) + 그 선수들의 지금 팀·뛴 팀의 시즌 일정 1번 + 끝난 포스트시즌 경기 박스스코어
 *       (gameLog는 정규시즌만 준다).</li>
 *   <li>경기 갱신: 우리 팀들(마이너에 있는 선수의 MLB 구단은 뺀다)의 미국 날짜 [from, to] 일정을 받고,
 *       진행 중이거나 다시 확인할 때가 된 경기만 박스스코어로 받는다.
 *       따라잡기는 보통 어제·오늘을, 갱신이 며칠 밀렸으면 밀린 날부터 오늘까지(최대 14일 전) 받는다.</li>
 *   <li>끝난 경기 재확인: 끝난 뒤 3일 동안 하루 한 번 박스스코어로 다시 받고, 그 뒤에는 settled로 두어 다시 받지 않는다.</li>
 *   <li>선발·교체: 박스스코어를 받는 경기는 그때 같이 채우고, 지난 경기는 fillLineups로 조금씩 채운다.</li>
 *   <li>앞 일정 보기(비시즌): 하루 한 번 앞으로 7일 일정만 받아 저장한다(포스트시즌 경기가 잡혔는지 보려고).</li>
 * </ul>
 * 403/429(MlbBlockedException)는 그대로 던져 그 실행을 멈추게 한다. 다른 오류는 그 건만 건너뛴다.
 */
@Component
public class GameIngest {

    /** 미국 날짜(경기 공식 날짜)를 정할 때 쓰는 시간대 */
    public static final ZoneId US_EASTERN = ZoneId.of("America/New_York");
    /** 따라잡기 경기 갱신이 밀렸을 때 거슬러 올라가는 최대 날 수 */
    static final int MAX_CATCH_UP_DAYS = 14;
    /** 비시즌에 미리 받아 보는 일정 범위(미국 날짜 오늘부터 며칠 뒤까지) */
    static final int LOOK_AHEAD_DAYS = 7;

    private static final Logger log = LoggerFactory.getLogger(GameIngest.class);

    private final MlbClient client;
    private final IngestWriter writer;
    private final PlayerRepository players;
    private final AffiliationRepository affiliations;
    private final GameRepository games;
    private final AppearanceRepository appearances;
    private final Clock clock;
    // 앞 일정을 마지막으로 받은 날(UTC 날짜). 앱이 떠 있는 동안만 기억한다.
    private volatile LocalDate lookedAheadOn;

    public GameIngest(MlbClient client, IngestWriter writer, PlayerRepository players,
                      AffiliationRepository affiliations, GameRepository games, AppearanceRepository appearances,
                      Clock clock) {
        this.client = client;
        this.writer = writer;
        this.players = players;
        this.affiliations = affiliations;
        this.games = games;
        this.appearances = appearances;
        this.clock = clock;
    }

    /**
     * 선수들의 한 시즌을 받는다. gameLog를 받지 못한 선수는 건너뛰고(다음에 다시), 받은 선수만 "받았음" 표시를 한다.
     *
     * @throws MlbBlockedException 403/429. 이번 실행에서는 아무것도 저장하지 않는다.
     */
    public String backfillSeason(List<Player> targets, int season) {
        if (targets.isEmpty()) {
            return "";
        }
        Instant now = clock.instant();
        List<IngestWriter.PlayerLogs> logs = new ArrayList<>();
        int failed = 0;
        for (Player player : targets) {
            try {
                logs.add(new IngestWriter.PlayerLogs(player.getId(),
                        client.getGameLog(player.getMlbId(), MlbClient.Group.HITTING, season),
                        client.getGameLog(player.getMlbId(), MlbClient.Group.PITCHING, season)));
            } catch (MlbBlockedException e) {
                throw e;
            } catch (MlbApiException e) {
                log.warn("[mlb] {} {} gameLog failed, will retry next ingest ({})", player.getNameKo(), season,
                        e.getMessage());
                failed++;
            }
        }
        if (logs.isEmpty()) {
            return "시즌 " + season + " 받기 실패 " + failed + "명";
        }

        // 지금 팀과 이번 시즌에 뛴 팀(이적 전 팀 포함)의 일정을 한 번에 받는다.
        Set<Long> teamIds = new LinkedHashSet<>(currentTeamIds(targets));
        logs.stream()
                .flatMap(l -> Stream.concat(l.hitting().stream(), l.pitching().stream()))
                .map(MlbGameLogSplit::team)
                .filter(team -> team != null && MlbTeams.isClub(team.id()))
                .forEach(team -> teamIds.add(team.id()));
        List<MlbScheduleGame> schedule = client.getSeasonSchedule(teamIds, season);
        IngestWriter.SeasonSaveResult saved = writer.saveSeason(season, schedule, logs, now);

        // 포스트시즌 기록은 gameLog에 없으므로 끝난 포스트시즌 경기만 박스스코어로 받는다.
        int boxscores = boxscores(games.findTeamSeasonGames(teamIds, season, "postseason").stream()
                .filter(g -> g.needsBoxscore(now))
                .toList(), now);

        StringBuilder message = new StringBuilder("시즌 ").append(season).append(" ").append(logs.size()).append("명")
                .append("(경기 ").append(saved.savedGames()).append("개, 선수 기록 ").append(saved.savedLines()).append("개");
        if (boxscores > 0) {
            message.append(", 포스트시즌 박스스코어 ").append(boxscores).append("개");
        }
        if (saved.skippedLines() > 0) {
            message.append(", 건너뛴 기록 ").append(saved.skippedLines()).append("개");
        }
        message.append(")");
        if (failed > 0) {
            message.append(", 실패 ").append(failed).append("명");
        }
        return message.toString();
    }

    /**
     * 우리 팀들(선수들의 지금 소속)의 미국 날짜 [from, to] 경기를 일정으로 갱신하고, 박스스코어가 필요한 경기만 받는다.
     * forceFinals면 그 기간의 끝난 경기를 settled여도 다시 받는다(관리용).
     */
    public String refreshGames(LocalDate from, LocalDate to, boolean forceFinals) {
        return refreshGames(trackedTeamIds(), from, to, forceFinals);
    }

    /**
     * 따라잡기용 경기 갱신. 보통은 미국 날짜 어제·오늘을 받는다.
     * 서버가 멈췄거나 MLB가 막아(403/429) 며칠 갱신을 못 했으면 밀린 날부터 오늘까지를 일정 한 번으로 받는다.
     * 밀린 날은 아래 중 이른 날이다(최대 MAX_CATCH_UP_DAYS일 전까지).
     * <ul>
     *   <li>시작했는데 아직 끝남·연기·취소가 아닌 우리 팀 경기가 있는 날(결과를 못 받은 경기)</li>
     *   <li>우리 팀의 마지막으로 끝난 경기가 있는 날(그 뒤 날짜의 일정을 못 받았을 수 있다.
     *       포스트시즌 경기는 그날 일정을 받을 때에야 DB에 들어온다)</li>
     * </ul>
     */
    public String refreshRecent() {
        List<Long> teamIds = trackedTeamIds();
        if (teamIds.isEmpty()) {
            return "";
        }
        Instant now = clock.instant();
        LocalDate today = usDate(now);
        LocalDate from = catchUpFrom(teamIds, today, now);
        String message = refreshGames(teamIds, from, today, false);
        return from.isBefore(today.minusDays(1)) ? from + "부터 " + message : message;
    }

    // 어제, 결과를 못 받은 경기의 날, 마지막으로 끝난 경기의 날 중 가장 이른 날. 단 MAX_CATCH_UP_DAYS일 전보다 앞으로는 가지 않는다.
    private LocalDate catchUpFrom(List<Long> teamIds, LocalDate today, Instant now) {
        Optional<Instant> unfinished = games.findFirstUnfinishedStartTime(teamIds,
                now.minus(Duration.ofDays(MAX_CATCH_UP_DAYS)), now);
        Optional<Instant> lastFinal = games.findLastFinalStartTime(teamIds, now);
        LocalDate from = Stream.of(unfinished, lastFinal)
                .flatMap(Optional::stream)
                .map(this::usDate)
                .reduce(today.minusDays(1), (a, b) -> a.isBefore(b) ? a : b);
        LocalDate oldest = today.minusDays(MAX_CATCH_UP_DAYS);
        return from.isBefore(oldest) ? oldest : from;
    }

    private String refreshGames(List<Long> teamIds, LocalDate from, LocalDate to, boolean forceFinals) {
        if (teamIds.isEmpty()) {
            return "";
        }
        Instant now = clock.instant();
        List<Long> gamePks = writer.saveGames(client.getSchedule(teamIds, from, to), now);
        if (gamePks.isEmpty()) {
            return "경기 없음";
        }
        Predicate<Game> due = forceFinals ? g -> g.isFinal() || g.needsBoxscore(now) : g -> g.needsBoxscore(now);
        List<Game> targets = games.findAllById(gamePks).stream().filter(due).toList();
        int boxscores = boxscores(targets, now);
        return "경기 " + gamePks.size() + "개" + (boxscores > 0 ? ", 박스스코어 " + boxscores + "개" : "");
    }

    /**
     * 선발·교체를 아직 모르는 지난 경기(굳은 경기)를 최근 것부터 limit개까지 박스스코어로 채운다.
     * 한 경기는 한 번만 받는다(못 정한 선수가 있어도 다시 받지 않는다). 기록은 건드리지 않는다.
     * 단 그 뒤 시즌 백필로 그 경기에 새 기록이 생기면 다시 채울 경기가 된다(IngestWriter.saveGameLog).
     */
    public String fillLineups(int limit) {
        List<Game> targets = appearances.findGamesMissingLineups(PageRequest.of(0, limit));
        if (targets.isEmpty()) {
            return "";
        }
        Map<Long, Long> ours = ourPlayers();
        int games = 0;
        int filled = 0;
        for (Game game : targets) {
            MlbBoxscore box;
            try {
                box = client.getBoxscore(game.getGamePk());
            } catch (MlbBlockedException e) {
                throw e;
            } catch (MlbApiException e) {
                log.warn("[mlb] boxscore {} for lineups failed, will retry next ingest ({})", game.getGamePk(),
                        e.getMessage());
                continue;
            }
            filled += writer.saveLineups(game.getGamePk(), box, ours);
            games++;
        }
        return "선발·교체 " + filled + "개(경기 " + games + "개)";
    }

    /** 끝났지만 아직 굳지 않은 경기 중 오늘 아직 다시 확인하지 않은 경기를 박스스코어로 받는다. */
    public String recheckFinals() {
        Instant now = clock.instant();
        List<Game> due = games.findUnsettledFinals().stream().filter(g -> g.needsBoxscore(now)).toList();
        int boxscores = boxscores(due, now);
        return boxscores == 0 ? "" : "끝난 경기 재확인 " + boxscores + "개";
    }

    /**
     * 비시즌(DB에 앞뒤 며칠 우리 팀 경기가 없음)에 부른다. 하루(UTC 날짜)에 한 번, 우리 팀들의 오늘부터 LOOK_AHEAD_DAYS일 뒤까지
     * 일정을 받아(1번) 저장한다. 포스트시즌 경기는 그날 일정을 받을 때에야 DB에 들어오므로, 라운드 사이 쉬는 날이 길면
     * (디비전 시리즈 전, 월드시리즈 전) DB만 봐서는 시즌 중으로 돌아오지 못한다. 여기서 받은 경기가 앞뒤 며칠 안에 들면
     * 다음 실행부터 시즌 중으로 본다.
     */
    public String lookAhead() {
        Instant now = clock.instant();
        LocalDate utcToday = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (utcToday.equals(lookedAheadOn)) {
            return "";
        }
        List<Long> teamIds = trackedTeamIds();
        if (teamIds.isEmpty()) {
            return "";
        }
        LocalDate today = usDate(now);
        List<Long> saved = writer.saveGames(client.getSchedule(teamIds, today, today.plusDays(LOOK_AHEAD_DAYS)), now);
        lookedAheadOn = utcToday;
        return "앞으로 " + LOOK_AHEAD_DAYS + "일 경기 " + (saved.isEmpty() ? "없음" : saved.size() + "개");
    }

    /** 우리 팀들의 경기가 now 앞뒤 days일 안에 있는지(시즌 중인지) */
    public boolean hasGamesAround(Instant now, int days) {
        List<Long> teamIds = trackedTeamIds();
        return !teamIds.isEmpty() && games.existsTeamGameBetween(teamIds,
                now.minus(Duration.ofDays(days)), now.plus(Duration.ofDays(days)));
    }

    /**
     * 지금부터 거슬러 days일 안에 시작한 우리 팀들의 경기가 있는지(DB만 본다). 홈이 비시즌인지 정할 때 쓴다(OffSeasonFinder).
     * 앞으로 잡힌 경기는 보지 않는다: 개막 경기가 시작되기 전까지는 비시즌 화면(개막까지 남은 날)을 둔다.
     */
    public boolean hasRecentGames(Instant now, int days) {
        List<Long> teamIds = trackedTeamIds();
        return !teamIds.isEmpty() && games.existsTeamGameBetween(teamIds, now.minus(Duration.ofDays(days)), now);
    }

    /** 미국 날짜(동부 시간) */
    public LocalDate usDate(Instant instant) {
        return LocalDate.ofInstant(instant, US_EASTERN);
    }

    // 경기마다 박스스코어 한 번. 실패한 경기는 건너뛰고 다음에 다시 받는다.
    private int boxscores(Collection<Game> targets, Instant now) {
        if (targets.isEmpty()) {
            return 0;
        }
        Map<Long, Long> ours = ourPlayers();
        int fetched = 0;
        for (Game game : targets) {
            MlbBoxscore box;
            try {
                box = client.getBoxscore(game.getGamePk());
            } catch (MlbBlockedException e) {
                throw e;
            } catch (MlbApiException e) {
                log.warn("[mlb] boxscore {} failed, will retry next ingest ({})", game.getGamePk(), e.getMessage());
                continue;
            }
            writer.saveBoxscore(game.getGamePk(), box, ours, now);
            fetched++;
        }
        return fetched;
    }

    // 우리 선수의 MLB 번호 → 선수 id
    private Map<Long, Long> ourPlayers() {
        return players.findByMlbIdIsNotNull().stream()
                .collect(Collectors.toMap(Player::getMlbId, Player::getId));
    }

    // 지금 메이저에 있는(또는 단계를 모르는) 선수들의 소속 팀. 마이너 선수의 MLB 구단 경기는 갱신하지 않는다.
    // 소속이 끝난 선수(FA 등)의 마지막 구단도 findAllCurrent에서 빠지므로 갱신하지 않는다.
    private List<Long> trackedTeamIds() {
        return affiliations.findAllCurrent().stream()
                .filter(a -> a.getPlayer().getLevel() != Level.MINORS)
                .map(a -> a.getTeam().getMlbTeamId())
                .distinct()
                .toList();
    }

    private List<Long> currentTeamIds(List<Player> targets) {
        Set<Long> playerIds = targets.stream().map(Player::getId).collect(Collectors.toSet());
        return affiliations.findAllCurrent().stream()
                .filter(a -> playerIds.contains(a.getPlayer().getId()))
                .map(Affiliation::getTeam)
                .map(team -> team.getMlbTeamId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
