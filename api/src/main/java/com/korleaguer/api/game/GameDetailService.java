package com.korleaguer.api.game;

import com.korleaguer.api.player.PlayerNotFoundException;

import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.GameDetail;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.PlayClip;
import com.korleaguer.domain.PlayClipRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerGameDetail;
import com.korleaguer.domain.PlayerGameDetailRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.video.PlayVideoFinder;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 경기 달력에서 날짜를 누르면 뜨는 상세(선수 한 명 · 경기 한 개). DB만 읽는다(이슈 #229, ARCHITECTURE.md §3).
 * <ul>
 *   <li>상세는 수집(ingest.GameDetailIngest)이 미리 받아 player_game_detail에 둔다. 여기서는 MLB를 부르지 않는다.
 *       진행 중 경기의 상세는 수집 주기(12분)로 갱신된다.</li>
 *   <li>그 선수의 경기(메이저 기록 appearance, 또는 마이너 기록 minor_game_log가 있음)이고 저장된 상세가 있으면 그것을 준다.
 *       아직 없으면 available=false를 돌려준다(창이 '잠시 뒤 다시'를 보인다).</li>
 *   <li>우리 메이저 일정에 있는 경기인데 그 선수 기록이 없으면(진행 중인데 아직 안 나옴, 결장) 그것도 available=false다.
 *       웹은 진행 중 경기를 선수가 나오기 전에도 묻는다. 메이저 일정에도 그 선수 마이너 기록에도 없는 경기, 없는 선수는 404.</li>
 *   <li>영상(korleaguer.videos.enabled=false, 환경 변수 KORLEAGUER_VIDEOS)을 끄면 하이라이트를 비우고 공 영상을 묻지 않는다.
 *       MLB 영상은 MLB 저작물이라 공개 전에 허락을 받거나 끈다(선수 사진 스위치와 같은 뜻).</li>
 * </ul>
 * 요청 중에 밖을 부르는 것은 공 하나 영상 주소(clip → PlayVideoFinder, 구현은 client.SavantClips)뿐이다. 하이라이트 한국어 제목도 수집이 미리 옮겨 둔 것만 붙인다(이슈 #237).
 */
@Service
public class GameDetailService {

    private static final Logger log = LoggerFactory.getLogger(GameDetailService.class);

    /** 공 영상이 없었던 공은 이만큼 지나면 다시 묻는다(경기 직후에는 아직 안 올라왔을 수 있다) */
    static final Duration CLIP_RETRY_AFTER = Duration.ofHours(6);

    private final PlayerRepository players;
    private final GameRepository games;
    private final AppearanceRepository appearances;
    private final MinorGameLogRepository minorGames;
    private final PlayerGameDetailRepository details;
    private final PlayClipRepository clips;
    private final PlayVideoFinder savant;
    private final JsonMapper json;
    private final Clock clock;
    private final HighlightTitles highlightTitles;
    private final boolean videos;

    public GameDetailService(PlayerRepository players, GameRepository games, AppearanceRepository appearances,
                             MinorGameLogRepository minorGames, PlayerGameDetailRepository details,
                             PlayClipRepository clips, PlayVideoFinder savant, JsonMapper json, Clock clock,
                             HighlightTitles highlightTitles,
                             @Value("${korleaguer.videos.enabled:true}") boolean videos) {
        this.players = players;
        this.games = games;
        this.appearances = appearances;
        this.minorGames = minorGames;
        this.details = details;
        this.clips = clips;
        this.savant = savant;
        this.json = json;
        this.clock = clock;
        this.highlightTitles = highlightTitles;
        this.videos = videos;
    }

    public GameDetail detail(long playerId, long gamePk) {
        return withKoreanTitles(load(playerId, gamePk));
    }

    private GameDetail load(long playerId, long gamePk) {
        Player player = players.findById(playerId).orElseThrow(() -> new PlayerNotFoundException(playerId));
        GameDetail unavailable = GameDetail.unavailable(playerId, gamePk, player.role().name());
        if (!playedIn(playerId, gamePk)) {
            return unavailable;
        }
        return details.findByPlayerIdAndGamePk(playerId, gamePk).flatMap(this::read).orElse(unavailable);
    }

    /**
     * 그 선수가 뛴 경기인지: 메이저 기록(appearance)이나 마이너 기록(minor_game_log)이 있다.
     * 우리 메이저 일정에 있는 경기인데 그 선수 기록이 없으면 false(아직 안 나왔거나 결장), 어디에도 없는 경기면 404
     */
    private boolean playedIn(long playerId, long gamePk) {
        if (appearances.existsByPlayer_IdAndGame_GamePk(playerId, gamePk)
                || minorGames.findFirstByPlayer_IdAndGamePk(playerId, gamePk).isPresent()) {
            return true;
        }
        if (games.existsById(gamePk)) {
            return false;
        }
        throw new GameNotFoundException(playerId, gamePk);
    }

    /**
     * 하이라이트에 한국어 제목을 붙인다(이슈 #199). 저장된 상세 JSON에는 넣지 않고 응답마다 번역 표(highlight_title)에서
     * 붙이므로, 이미 굳은 상세에도 번역이 생기면 바로 보인다. 번역은 수집이 미리 해 두고(이슈 #237) 아직 없는 제목은 null이다
     */
    private GameDetail withKoreanTitles(GameDetail response) {
        if (response.highlights().isEmpty()) {
            return response;
        }
        Map<String, String> korean = highlightTitles.koreanOf(
                response.highlights().stream().map(GameDetail.GameHighlight::title).toList());
        List<GameDetail.GameHighlight> highlights = response.highlights().stream()
                .map(h -> h.withTitleKo(korean.get(h.title()))).toList();
        return new GameDetail(response.playerId(), response.gamePk(), response.role(), response.available(),
                response.clips(), response.plays(), highlights);
    }

    /**
     * 공 하나 영상 주소. 그 선수 그 경기 상세(저장된 것)에 나온 공 번호만 묻는다(아무 번호로 Savant를 부르게 두지 않는다).
     * 없거나 영상이 꺼져 있으면 빈 값
     */
    public Optional<String> clip(long playerId, long gamePk, String playId) {
        if (!videos || playId == null || !PlayVideoFinder.PLAY_ID.matcher(playId).matches()) {
            return Optional.empty();
        }
        boolean known = details.findByPlayerIdAndGamePk(playerId, gamePk)
                .flatMap(this::read)
                .filter(GameDetail::clips)
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
        PlayVideoFinder.Lookup lookup = savant.find(playId);
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

    private Optional<GameDetail> read(PlayerGameDetail saved) {
        try {
            return Optional.of(withVideoSwitch(json.readValue(saved.getPayload(), GameDetail.class)));
        } catch (JacksonException e) {
            log.warn("game detail {} for player {}: stored JSON is unreadable", saved.getGamePk(),
                    saved.getPlayerId(), e);
            return Optional.empty();
        }
    }

    /** 영상을 끄면 하이라이트를 비우고 공 영상도 묻지 않게 한다(저장된 것에도 읽을 때 적용) */
    private GameDetail withVideoSwitch(GameDetail response) {
        if (videos) {
            return response;
        }
        return new GameDetail(response.playerId(), response.gamePk(), response.role(), response.available(),
                false, response.plays(), List.of());
    }
}
