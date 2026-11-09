package com.korleaguer.api;

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

    public BaseballController(BaseballQueryService queryService, MyPlayersService myPlayers) {
        this.queryService = queryService;
        this.myPlayers = myPlayers;
    }

    @GetMapping("/home")
    public HomeResponse home() {
        return queryService.home();
    }

    @GetMapping("/players")
    public List<PlayerSummary> players() {
        return queryService.players();
    }

    @GetMapping("/players/{id}")
    public PlayerPageResponse player(@PathVariable long id) {
        return queryService.player(id);
    }

    /** 내 선수. ids는 쉼표로 이은 선수 id(1~20개), days는 7·14·30 중 하나(기본 7). 규칙에 안 맞으면 400 */
    @GetMapping("/my")
    public MyPlayersResponse my(@RequestParam String ids, @RequestParam(defaultValue = "7") int days) {
        return myPlayers.find(ids, days);
    }
}
