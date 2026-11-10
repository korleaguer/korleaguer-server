package com.korleaguer.api;

import com.korleaguer.ingest.OnDemandSeason;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class BaseballController {

    private final BaseballQueryService queryService;
    private final MyPlayersService myPlayers;
    private final OnDemandSeason onDemandSeason;

    public BaseballController(BaseballQueryService queryService, MyPlayersService myPlayers,
                              OnDemandSeason onDemandSeason) {
        this.queryService = queryService;
        this.myPlayers = myPlayers;
        this.onDemandSeason = onDemandSeason;
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

    /** 내 선수. ids는 쉼표로 이은 선수 id(1~20개), days는 7·14·30 중 하나(기본 7). 규칙에 안 맞으면 400 */
    @GetMapping("/my")
    public MyPlayersResponse my(@RequestParam String ids, @RequestParam(defaultValue = "7") int days) {
        return myPlayers.find(ids, days);
    }
}
