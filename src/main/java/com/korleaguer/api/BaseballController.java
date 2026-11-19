package com.korleaguer.api;

import com.korleaguer.ingest.OnDemandSeason;
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
    private final OnDemandSeason onDemandSeason;
    private final GameDetailService gameDetails;

    public BaseballController(BaseballQueryService queryService, MyPlayersService myPlayers,
                              OnDemandSeason onDemandSeason, GameDetailService gameDetails) {
        this.queryService = queryService;
        this.myPlayers = myPlayers;
        this.onDemandSeason = onDemandSeason;
        this.gameDetails = gameDetails;
    }

    @GetMapping("/home")
    public HomeResponse home() {
        return queryService.home();
    }

    @GetMapping("/players")
    public List<PlayerSummary> players() {
        return queryService.players();
    }

    /** 그 선수의 이번 시즌을 아직 받지 않았으면 먼저 짧게 받아 본다(OnDemandSeason). 그 밖에는 DB만 읽는다. */
    @GetMapping("/players/{id}")
    public PlayerPageResponse player(@PathVariable long id) {
        onDemandSeason.ensureSeason(id);
        return queryService.player(id);
    }

    /**
     * 선수 화면이 있는지만 답한다(있으면 200, 없으면 404, 본문 없음). 웹이 선수 화면을 열 때마다 먼저 물어 없는 주소에 404를 준다.
     * 이 처리가 없으면 Spring이 HEAD에도 위 GET을 불러 시즌 받기·선수 화면 계산을 다 하고 본문만 버린다
     */
    @RequestMapping(value = "/players/{id}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> playerExists(@PathVariable long id) {
        return queryService.playerExists(id) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * 선수 한 명의 경기 한 개 상세(타석별 기록·그 선수 하이라이트 영상). 처음 열 때 MLB에서 한 번 받아 저장한다(GameDetailService).
     * 그 선수의 경기가 아니면 404
     */
    @GetMapping("/players/{id}/games/{gamePk}")
    public GameDetailResponse gameDetail(@PathVariable long id, @PathVariable long gamePk) {
        return gameDetails.detail(id, gamePk);
    }

    /** 그 경기 상세에 나온 공 하나의 영상 주소(Baseball Savant mp4). 없으면 404 */
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
