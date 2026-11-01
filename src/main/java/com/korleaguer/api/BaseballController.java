package com.korleaguer.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class BaseballController {

    private final BaseballQueryService queryService;

    public BaseballController(BaseballQueryService queryService) {
        this.queryService = queryService;
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
}
