package com.korleaguer.api.web;

import com.korleaguer.api.card.MyPlayersResponse;
import com.korleaguer.api.card.MyPlayersService;
import com.korleaguer.api.game.GameDetailService;
import com.korleaguer.api.player.PlayerSummary;
import com.korleaguer.api.screen.BaseballQueryService;
import com.korleaguer.api.screen.CompareResponse;
import com.korleaguer.api.screen.CompareService;
import com.korleaguer.api.screen.HomeResponse;
import com.korleaguer.api.screen.PlayerPageResponse;
import com.korleaguer.api.screen.PlayerTimelineResponse;
import com.korleaguer.api.screen.PlayerTimelineService;
import com.korleaguer.api.screen.SameGamesResponse;
import com.korleaguer.api.screen.SameGamesService;

import com.korleaguer.domain.GameDetail;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class BaseballController {

    private final BaseballQueryService queryService;
    private final MyPlayersService myPlayers;
    private final GameDetailService gameDetails;
    private final PlayerTimelineService timelines;
    private final SameGamesService sameGames;
    private final CompareService compare;

    public BaseballController(BaseballQueryService queryService, MyPlayersService myPlayers,
                              GameDetailService gameDetails,
                              PlayerTimelineService timelines, SameGamesService sameGames,
                              CompareService compare) {
        this.queryService = queryService;
        this.myPlayers = myPlayers;
        this.gameDetails = gameDetails;
        this.timelines = timelines;
        this.sameGames = sameGames;
        this.compare = compare;
    }

    @GetMapping("/home")
    public HomeResponse home() {
        return queryService.home();
    }

    @GetMapping("/players")
    public List<PlayerSummary> players() {
        return queryService.players();
    }

    /**
     * 선수별 시즌 이동 연표(웹 선수 탭): 등록 선수 전원의 띠 구간·이동·시즌 숫자를 한 번에 준다. season이 없으면 홈과 같은
     * 지금 시즌이고, 숫자가 아니면 400이다. 없는 시즌은 기록이 빈 채로 200이다. DB만 읽는다(MLB를 부르지 않는다).
     * 경로의 {id}보다 이 글자 그대로의 경로가 먼저 잡힌다
     */
    @GetMapping("/players/timeline")
    public PlayerTimelineResponse timeline(@RequestParam(required = false) Integer season) {
        return timelines.timeline(timelines.resolveSeason(season));
    }

    /** 선수 화면. DB만 읽는다(그 선수 시즌을 아직 받지 않았으면 있는 것만 준다. 스케줄 수집이 받는다) */
    @GetMapping("/players/{id}")
    public PlayerPageResponse player(@PathVariable long id) {
        return queryService.player(id);
    }

    /**
     * 선수 화면이 있는지만 답한다(있으면 200, 없으면 404, 본문 없음). 웹이 선수 화면을 열 때마다 먼저 물어 없는 주소에 404를 준다.
     * 이 처리가 없으면 Spring이 HEAD에도 위 GET을 불러 선수 화면 계산을 다 하고 본문만 버린다
     */
    @RequestMapping(value = "/players/{id}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> playerExists(@PathVariable long id) {
        return queryService.playerExists(id) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * 같은 경기에 나온 우리 선수(이슈 #217): 그 시즌 메이저 경기 중 이 선수와 다른 우리 선수가 모두 뛴 경기를 상대 선수별로
     * 묶어 준다(경기 수·이 선수 팀 전적·경기마다 두 사람 한 줄). 없으면 빈 목록, 없는 선수면 404. DB만 읽는다
     */
    @GetMapping("/players/{id}/same-games")
    public SameGamesResponse sameGames(@PathVariable long id) {
        return sameGames.find(id);
    }

    /**
     * 두 선수 비교에 쓸 타자 시즌 성적 묶음(이슈 #221): 이 선수(me)와 견줄 수 있는 다른 우리 타자들(others)의 그 시즌 메이저 성적과
     * wRC+. 투수거나 메이저 타석이 없으면 me가 null, 없는 선수면 404. DB만 읽는다
     */
    @GetMapping("/players/{id}/compare")
    public CompareResponse compare(@PathVariable long id) {
        return compare.find(id);
    }

    /**
     * 선수 한 명의 경기 한 개 상세(타석별 기록·그 선수 하이라이트 영상). 수집이 미리 받아 둔 것만 읽는다(GameDetailService).
     * 아직 없거나 그 선수 기록이 없는 경기면 available=false, 우리가 모르는 경기·선수면 404
     */
    @GetMapping("/players/{id}/games/{gamePk}")
    public GameDetail gameDetail(@PathVariable long id, @PathVariable long gamePk) {
        return gameDetails.detail(id, gamePk);
    }

    /** 그 경기 상세에 나온 공 하나의 영상 주소(Baseball Savant mp4). 없으면 404 */
    // 영상 주소를 못 찾으면 본문 없는 404다(응답 모양 명세에 그렇게 적는다)
    @ApiResponse(responseCode = "404", description = "Not Found", content = @Content)
    @GetMapping("/players/{id}/games/{gamePk}/clips/{playId}")
    public ResponseEntity<ClipResponse> clip(@PathVariable long id, @PathVariable long gamePk,
                                             @PathVariable String playId) {
        return gameDetails.clip(id, gamePk, playId)
                .map(url -> ResponseEntity.ok(new ClipResponse(url)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 공 하나 영상 주소(MLB 서버 mp4) */
    public record ClipResponse(String mp4Url) {
    }

    /** 내 선수. ids는 쉼표로 이은 선수 id(1~20개), days는 7·14·30 중 하나(기본 7). 규칙에 안 맞으면 400 */
    @GetMapping("/my")
    public MyPlayersResponse my(@RequestParam String ids, @RequestParam(defaultValue = "7") int days) {
        return myPlayers.find(ids, days);
    }
}
