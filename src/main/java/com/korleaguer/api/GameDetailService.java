package com.korleaguer.api;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameContent;
import com.korleaguer.client.MlbPlayByPlay;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.PlayClip;
import com.korleaguer.domain.PlayClipRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerGameDetail;
import com.korleaguer.domain.PlayerGameDetailRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 경기 달력에서 날짜를 누르면 뜨는 상세(선수 한 명 · 경기 한 개).
 * <ul>
 *   <li>DB(player_game_detail)에 있으면 그것을 쓴다. 끝난 경기를 시작 하루 뒤에 받은 것(complete)은 다시 받지 않는다.
 *       그 전(진행 중, 또는 하이라이트가 아직 덜 올라왔을 때)은 REUSE_FOR 동안만 다시 쓰고 지나면 다시 받는다.</li>
 *   <li>없으면 그 자리에서 MLB(playByPlay, 메이저 경기면 content까지)를 한 번 받아 만들고 저장한다.
 *       선수 시즌을 처음 열 때 받는 것(OnDemandSeason)과 같은 '필요할 때 한 번' 규칙이다.</li>
 *   <li>MLB가 실패하면 저장해 둔 것이 있으면 그것을, 없으면 available=false를 돌려준다(창이 '잠시 뒤 다시'를 보인다).</li>
 *   <li>영상(korleaguer.videos.enabled=false, 환경 변수 KORLEAGUER_VIDEOS)을 끄면 하이라이트를 비우고 공 영상을 묻지 않는다.
 *       MLB 영상은 MLB 저작물이라 공개 전에 허락을 받거나 끈다(선수 사진 스위치와 같은 뜻).</li>
 * </ul>
 */
@Service
public class GameDetailService {

    private static final Logger log = LoggerFactory.getLogger(GameDetailService.class);

    /** 아직 굳지 않은 상세를 다시 쓰는 시간 */
    static final Duration REUSE_FOR = Duration.ofMinutes(5);
    /** 끝난 경기는 시작 뒤 이만큼 지나 받았으면 굳힌다(하이라이트가 다 올라온 뒤) */
    static final Duration COMPLETE_AFTER = Duration.ofDays(1);
    /** 공 영상이 없었던 공은 이만큼 지나면 다시 묻는다(경기 직후에는 아직 안 올라왔을 수 있다) */
    static final Duration CLIP_RETRY_AFTER = Duration.ofHours(6);

    private final PlayerRepository players;
    private final GameRepository games;
    private final MinorGameLogRepository minorGames;
    private final PlayerGameDetailRepository details;
    private final PlayClipRepository clips;
    private final MlbClient mlb;
    private final SavantClips savant;
    private final JsonMapper json;
    private final Clock clock;
    private final boolean videos;

    public GameDetailService(PlayerRepository players, GameRepository games, MinorGameLogRepository minorGames,
                             PlayerGameDetailRepository details, PlayClipRepository clips, MlbClient mlb,
                             SavantClips savant, JsonMapper json, Clock clock,
                             @Value("${korleaguer.videos.enabled:true}") boolean videos) {
        this.players = players;
        this.games = games;
        this.minorGames = minorGames;
        this.details = details;
        this.clips = clips;
        this.mlb = mlb;
        this.savant = savant;
        this.json = json;
        this.clock = clock;
        this.videos = videos;
    }

    public GameDetailResponse detail(long playerId, long gamePk) {
        Player player = players.findById(playerId).orElseThrow(() -> new PlayerNotFoundException(playerId));
        Role role = player.role();
        GameFacts facts = facts(playerId, gamePk);
        Instant now = clock.instant();
        Optional<PlayerGameDetail> saved = details.findByPlayerIdAndGamePk(playerId, gamePk);
        Optional<GameDetailResponse> savedResponse = saved.flatMap(this::read);
        if (saved.isPresent() && savedResponse.isPresent() && fresh(saved.get(), now)) {
            return savedResponse.get();
        }
        if (player.getMlbId() == null) {
            return GameDetailResponse.unavailable(playerId, gamePk, role.name());
        }
        long mlbId = player.getMlbId();
        try {
            MlbPlayByPlay pbp = mlb.getPlayByPlay(gamePk);
            if (pbp == null) {
                return savedResponse.orElse(GameDetailResponse.unavailable(playerId, gamePk, role.name()));
            }
            // 마이너 경기에는 MLB 하이라이트가 없어 묻지 않는다. MLB가 막거나(MlbBlockedException) 실패하면 아래 catch
            MlbGameContent content = facts.major() ? mlb.getGameContent(gamePk) : null;
            GameDetailResponse response = new GameDetailResponse(playerId, gamePk, role.name(), true, facts.major(),
                    GameDetails.plays(pbp, mlbId, role), GameDetails.highlights(content, mlbId));
            // 시작 시각을 모르는 끝난 경기는 기다릴 기준이 없어 바로 굳힌다(5분마다 다시 받지 않게)
            boolean complete = facts.isFinal()
                    && (facts.start() == null || !now.isBefore(facts.start().plus(COMPLETE_AFTER)));
            save(saved.orElse(null), playerId, gamePk, response, complete, now);
            return withVideoSwitch(response);
        } catch (MlbApiException e) {
            log.warn("game detail {} for player {}: MLB failed ({})", gamePk, playerId, e.getMessage());
            return savedResponse.orElse(GameDetailResponse.unavailable(playerId, gamePk, role.name()));
        }
    }

    /**
     * 공 하나 영상 주소. 그 선수 그 경기 상세(저장된 것)에 나온 공 번호만 묻는다(아무 번호로 Savant를 부르게 두지 않는다).
     * 없거나 영상이 꺼져 있으면 빈 값
     */
    public Optional<String> clip(long playerId, long gamePk, String playId) {
        if (!videos || playId == null || !SavantClips.PLAY_ID.matcher(playId).matches()) {
            return Optional.empty();
        }
        boolean known = details.findByPlayerIdAndGamePk(playerId, gamePk)
                .flatMap(this::read)
                .filter(GameDetailResponse::clips)
                .map(d -> d.plays().stream().anyMatch(p -> playId.equals(p.playId())))
                .orElse(false);
        if (!known) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<PlayClip> saved = clips.findById(playId);
        if (saved.isPresent() && (saved.get().getMp4Url() != null
                || now.isBefore(saved.get().getFetchedAt().plus(CLIP_RETRY_AFTER)))) {
            return Optional.ofNullable(saved.get().getMp4Url());
        }
        SavantClips.Lookup lookup = savant.find(playId);
        if (!lookup.answered()) {
            return Optional.empty();
        }
        PlayClip row = saved.orElseGet(() -> new PlayClip(playId));
        row.update(lookup.mp4Url().orElse(null), now);
        try {
            clips.save(row);
        } catch (DataIntegrityViolationException e) {
            log.debug("play clip {} saved by another request", playId);
        }
        return lookup.mp4Url();
    }

    private boolean fresh(PlayerGameDetail saved, Instant now) {
        return saved.isComplete() || now.isBefore(saved.getFetchedAt().plus(REUSE_FOR));
    }

    /** 그 선수의 경기인지, 메이저인지, 끝났는지, 언제 시작했는지. 우리 DB에 없으면 404 */
    private GameFacts facts(long playerId, long gamePk) {
        Optional<Game> game = games.findById(gamePk);
        if (game.isPresent()) {
            return new GameFacts(true, game.get().isFinal(), game.get().getStartTimeUtc());
        }
        MinorGameLog minor = minorGames.findFirstByPlayer_IdAndGamePk(playerId, gamePk)
                .orElseThrow(() -> new GameNotFoundException(playerId, gamePk));
        // 마이너 기록은 끝난 경기만 온다. 시작 시각을 모르면 공식 날짜(미국) 하루 끝으로 본다
        Instant start = minor.getSchedule() != null && minor.getSchedule().startTimeUtc() != null
                ? minor.getSchedule().startTimeUtc()
                : minor.getGameDate().atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC);
        return new GameFacts(false, true, start);
    }

    private void save(PlayerGameDetail saved, long playerId, long gamePk, GameDetailResponse response,
                      boolean complete, Instant now) {
        String payload = fitted(response);
        if (payload == null) {
            log.warn("game detail {} for player {} is too long to keep", gamePk, playerId);
            return;
        }
        PlayerGameDetail row = saved != null ? saved : new PlayerGameDetail(playerId, gamePk);
        row.update(payload, complete, now);
        try {
            details.save(row);
        } catch (DataIntegrityViolationException e) {
            // 같은 상세를 다른 요청이 먼저 저장했다(처음 여는 순간이 겹침). 다음 요청부터 그것을 쓴다
            log.debug("game detail {} for player {} saved by another request", gamePk, playerId);
        }
    }

    /** 저장할 JSON. 칸보다 길면 뒤 하이라이트부터 덜어 낸다. 그래도 길면 null(저장하지 않음) */
    private String fitted(GameDetailResponse response) {
        List<GameDetailResponse.GameHighlight> highlights = new ArrayList<>(response.highlights());
        while (true) {
            GameDetailResponse candidate = new GameDetailResponse(response.playerId(), response.gamePk(),
                    response.role(), response.available(), response.clips(), response.plays(), List.copyOf(highlights));
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

    private Optional<GameDetailResponse> read(PlayerGameDetail saved) {
        try {
            return Optional.of(withVideoSwitch(json.readValue(saved.getPayload(), GameDetailResponse.class)));
        } catch (JacksonException e) {
            log.warn("game detail {} for player {}: stored JSON is unreadable", saved.getGamePk(),
                    saved.getPlayerId(), e);
            return Optional.empty();
        }
    }

    /** 영상을 끄면 하이라이트를 비우고 공 영상도 묻지 않게 한다(저장된 것에도 읽을 때 적용) */
    private GameDetailResponse withVideoSwitch(GameDetailResponse response) {
        if (videos) {
            return response;
        }
        return new GameDetailResponse(response.playerId(), response.gamePk(), response.role(), response.available(),
                false, response.plays(), List.of());
    }

    /**
     * @param major   메이저 경기(우리 game 표에 있음)
     * @param isFinal 끝난 경기
     * @param start   시작 시각(UTC). 모르면 null
     */
    private record GameFacts(boolean major, boolean isFinal, Instant start) {
    }
}
