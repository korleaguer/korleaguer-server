package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameContent;
import com.korleaguer.client.MlbPlayByPlay;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameDetail;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerGameDetail;
import com.korleaguer.domain.PlayerGameDetailRepository;
import com.korleaguer.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 경기 상세(선수 한 명 · 경기 한 개의 타석별 기록·하이라이트)를 MLB에서 받아 player_game_detail에 둔다(이슈 #229).
 * 화면용 API(GameDetailService)는 여기서 저장한 것만 읽는다.
 * <ul>
 *   <li>받는 대상: 우리 선수가 뛴 경기. 메이저는 기록(appearance)이 있는 (선수, 경기), 마이너는 minor_game_log 줄이 있는 (선수, 경기).</li>
 *   <li>MLB 요청은 경기당 한 번씩: playByPlay 1번, 끝난 메이저 경기면 content(영상 목록) 1번 더. 마이너 경기에는 MLB 하이라이트가 없어 묻지 않는다.
 *       진행 중 경기는 수집 때마다 받으므로 playByPlay만 받고(요청을 아낀다), 하이라이트는 끝난 뒤에 받는다.
 *       같은 경기에 우리 선수가 둘이면 한 번 받은 것으로 둘 다 만든다.</li>
 *   <li>MLB에 타석별 기록이 없거나(404) 저장 칸보다 긴 경기는, 굳힐 때가 됐으면 '없음'(available=false)으로 굳힌다.
 *       그러지 않으면 그 경기가 채우기 줄 맨 앞에 남아 실행마다 다시 묻고 뒤 경기를 막는다.</li>
 *   <li>굳힘(complete): 끝난 경기를 시작 COMPLETE_AFTER(하루) 뒤에 받았으면 굳힌다(하이라이트가 다 올라온 뒤).
 *       시작 시각을 모르는 끝난 경기는 기다릴 기준이 없어 바로 굳힌다. 굳은 것은 다시 받지 않는다.</li>
 *   <li>받는 때: (1) 박스스코어를 받는 메이저 경기에서 같이(refresh. 진행 중 경기는 수집 때마다 갱신된다),
 *       (2) 빠졌거나 굳힐 때가 된 것을 최근 경기부터 조금씩(fill).</li>
 * </ul>
 * 403/429(MlbBlockedException)는 그대로 던져 그 실행을 멈추게 한다. 다른 오류는 그 경기만 건너뛴다.
 * 채우기에서 실패한 경기는 '받아 봤음'(굳지 않은 available=false)을 남기고 RETRY_AFTER 뒤에 다시 받는다.
 * 그러지 않으면 계속 실패하는 경기가 채우기 줄 맨 앞에 남아 실행마다 다시 묻고, 상한만큼 쌓이면 뒤 경기를 막는다.
 */
@Component
public class GameDetailIngest {

    /** 끝난 경기는 시작 뒤 이만큼 지나 받았으면 굳힌다(하이라이트가 다 올라온 뒤) */
    static final Duration COMPLETE_AFTER = Duration.ofDays(1);
    /** 채우기가 굳지 않은 상세(받다 실패한 경기 포함)를 다시 받기까지. 계속 실패하는 경기를 실행마다 다시 묻지 않게 한다 */
    static final Duration RETRY_AFTER = Duration.ofHours(6);

    private static final Logger log = LoggerFactory.getLogger(GameDetailIngest.class);

    private final MlbClient client;
    private final AppearanceRepository appearances;
    private final MinorGameLogRepository minorGames;
    private final PlayerGameDetailRepository details;
    private final JsonMapper json;
    private final Clock clock;

    public GameDetailIngest(MlbClient client, AppearanceRepository appearances, MinorGameLogRepository minorGames,
                            PlayerGameDetailRepository details, JsonMapper json, Clock clock) {
        this.client = client;
        this.appearances = appearances;
        this.minorGames = minorGames;
        this.details = details;
        this.json = json;
        this.clock = clock;
    }

    /**
     * 박스스코어를 방금 받은 메이저 경기의 상세를 받는다(진행 중·방금 끝남·재확인). 그 경기에 기록이 있는 우리 선수 중
     * 상세가 아직 굳지 않은 선수만 만든다. 받을 선수가 없으면 MLB를 부르지 않는다.
     *
     * @return 저장한 상세 수(선수 수)
     * @throws MlbBlockedException 403/429
     * @throws MlbApiException     그 밖의 MLB 오류(부르는 쪽이 이 경기만 건너뛴다)
     */
    public int refresh(Game game, Instant now) {
        List<Player> targets = appearances.findWithPlayerByGame(game.getGamePk()).stream()
                .map(Appearance::getPlayer)
                .filter(p -> p.getMlbId() != null)
                .filter(p -> notComplete(p, game.getGamePk()))
                .toList();
        if (targets.isEmpty()) {
            return 0;
        }
        return fetch(game.getGamePk(), true, game.isFinal(), game.getStartTimeUtc(), targets, now);
    }

    /**
     * 끝난 경기 중 상세가 없거나, 아직 굳지 않았는데 굳힐 때가 된 (선수, 경기)를 최근 경기부터 limit경기까지 받는다(메이저·마이너 모두).
     * 경기 하나가 요청 1~2번이므로 한 번 실행에 MLB 요청은 많아야 limit × 2번이다. 실패한 경기도 limit에 센다.
     */
    public String fill(int limit) {
        Instant now = clock.instant();
        int asked = 0;
        int games = 0;
        int saved = 0;
        for (Pending game : pending()) {
            List<Player> targets = game.players().stream()
                    .filter(p -> missingOrDue(p, game.gamePk(), game.start(), now))
                    .toList();
            if (targets.isEmpty()) {
                continue;
            }
            if (asked == limit) {
                break;
            }
            asked++;
            try {
                int fetched = fetch(game.gamePk(), game.major(), true, game.start(), targets, now);
                if (fetched > 0) {
                    games++;
                    saved += fetched;
                }
            } catch (MlbBlockedException e) {
                throw e;
            } catch (MlbApiException e) {
                log.warn("[mlb] game detail {} failed, will retry in {} ({})", game.gamePk(), RETRY_AFTER,
                        e.getMessage());
                targets.forEach(p -> markTried(p, game.gamePk(), now));
            }
        }
        return saved == 0 ? "" : "경기 상세 " + saved + "개(경기 " + games + "개)";
    }

    // 굳은 상세가 없는 끝난 경기와 그 경기의 우리 선수들, 최근 경기부터(시작 시각을 모르는 경기는 맨 뒤)
    private List<Pending> pending() {
        Map<Long, Pending> byGame = new LinkedHashMap<>();
        for (Appearance a : appearances.findFinalsWithoutCompleteDetail()) {
            Game game = a.getGame();
            byGame.computeIfAbsent(game.getGamePk(),
                    pk -> new Pending(pk, true, game.getStartTimeUtc(), new ArrayList<>())).players().add(a.getPlayer());
        }
        for (MinorGameLog minor : minorGames.findWithoutCompleteDetail()) {
            // 같은 번호가 메이저 일정에 있으면 메이저 경기로 본다(한 경기를 두 번 받지 않게)
            Pending game = byGame.computeIfAbsent(minor.getGamePk(),
                    pk -> new Pending(pk, false, minorStart(minor), new ArrayList<>()));
            if (!game.major()) {
                game.players().add(minor.getPlayer());
            }
        }
        return byGame.values().stream()
                .sorted(Comparator.comparing(Pending::start, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // 마이너 기록은 끝난 경기만 온다. 시작 시각을 모르면 공식 날짜(미국) 하루 끝으로 본다
    private static Instant minorStart(MinorGameLog minor) {
        return minor.getSchedule() != null && minor.getSchedule().startTimeUtc() != null
                ? minor.getSchedule().startTimeUtc()
                : minor.getGameDate().atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC);
    }

    // 박스스코어를 받을 때: 굳지 않았으면 매번 갱신한다(진행 중 경기의 새 타석, 방금 끝난 경기의 늦게 올라온 하이라이트)
    private boolean notComplete(Player player, long gamePk) {
        return details.findByPlayerIdAndGamePk(player.getId(), gamePk).map(d -> !d.isComplete()).orElse(true);
    }

    // 뒤에서 채울 때: 상세가 없거나, 굳지 않았는데 지금 받으면 굳는 경기만. 굳기 전 상세를 실행마다 다시 받지 않는다
    private boolean missingOrDue(Player player, long gamePk, Instant start, Instant now) {
        Optional<PlayerGameDetail> saved = details.findByPlayerIdAndGamePk(player.getId(), gamePk);
        return saved.isEmpty() || (!saved.get().isComplete() && completesAt(start, now)
                && !now.isBefore(saved.get().getFetchedAt().plus(RETRY_AFTER)));
    }

    // 받다 실패한 경기: 저장된 상세가 있으면 받은 시각만 지금으로, 없으면 굳지 않은 '없음'을 남긴다
    private void markTried(Player player, long gamePk, Instant now) {
        Optional<PlayerGameDetail> saved = details.findByPlayerIdAndGamePk(player.getId(), gamePk);
        if (saved.isPresent()) {
            saved.get().update(saved.get().getPayload(), false, now);
            details.save(saved.get());
        } else {
            store(GameDetail.unavailable(player.getId(), gamePk, player.role().name()), false, now);
        }
    }

    private int fetch(long gamePk, boolean major, boolean isFinal, Instant start, List<Player> targets, Instant now) {
        MlbPlayByPlay pbp = client.getPlayByPlay(gamePk);
        boolean complete = isFinal && completesAt(start, now);
        if (pbp == null) {
            if (complete) {
                targets.forEach(p -> store(GameDetail.unavailable(p.getId(), gamePk, p.role().name()), true, now));
            }
            return 0;
        }
        // 진행 중에는 영상 목록을 묻지 않는다(수집 때마다 받으므로 경기당 요청이 두 배가 된다. 하이라이트는 끝난 뒤에 받는다)
        MlbGameContent content = major && isFinal ? client.getGameContent(gamePk) : null;
        int saved = 0;
        for (Player player : targets) {
            Role role = player.role();
            GameDetail detail = new GameDetail(player.getId(), gamePk, role.name(), true, major,
                    GameDetails.plays(pbp, player.getMlbId(), role), GameDetails.highlights(content, player.getMlbId()));
            if (save(detail, complete, now)) {
                saved++;
            }
        }
        return saved;
    }

    private static boolean completesAt(Instant start, Instant now) {
        return start == null || !now.isBefore(start.plus(COMPLETE_AFTER));
    }

    private boolean save(GameDetail detail, boolean complete, Instant now) {
        String payload = fitted(detail);
        if (payload == null) {
            log.warn("game detail {} for player {} is too long to keep", detail.gamePk(), detail.playerId());
            if (complete) {
                store(GameDetail.unavailable(detail.playerId(), detail.gamePk(), detail.role()), true, now);
            }
            return false;
        }
        store(detail.playerId(), detail.gamePk(), payload, complete, now);
        return true;
    }

    // '없음'처럼 짧은 상세를 그대로 저장한다
    private void store(GameDetail detail, boolean complete, Instant now) {
        store(detail.playerId(), detail.gamePk(), json.writeValueAsString(detail), complete, now);
    }

    private void store(long playerId, long gamePk, String payload, boolean complete, Instant now) {
        PlayerGameDetail row = details.findByPlayerIdAndGamePk(playerId, gamePk)
                .orElseGet(() -> new PlayerGameDetail(playerId, gamePk));
        row.update(payload, complete, now);
        details.save(row);
    }

    /** 저장할 JSON. 칸보다 길면 뒤 하이라이트부터 덜어 낸다. 그래도 길면 null(저장하지 않음) */
    private String fitted(GameDetail detail) {
        List<GameDetail.GameHighlight> highlights = new ArrayList<>(detail.highlights());
        while (true) {
            GameDetail candidate = new GameDetail(detail.playerId(), detail.gamePk(), detail.role(),
                    detail.available(), detail.clips(), detail.plays(), List.copyOf(highlights));
            String payload = json.writeValueAsString(candidate);
            if (payload.length() <= PlayerGameDetail.MAX_PAYLOAD_LENGTH) {
                return payload;
            }
            if (highlights.isEmpty()) {
                return null;
            }
            highlights.removeLast();
        }
    }

    /** 상세를 받을 끝난 경기 하나와 그 경기의 우리 선수들 */
    private record Pending(long gamePk, boolean major, Instant start, List<Player> players) {
    }
}
