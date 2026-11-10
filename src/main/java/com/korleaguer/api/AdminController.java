package com.korleaguer.api;

import com.korleaguer.ingest.IngestService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 관리용 수집 엔드포인트(MLB 공식 Stats API). X-Admin-Token 검사는 AdminTokenInterceptor가 한다.
 * MLB 요청은 1초에 1회라 시즌 다시 받기는 끝날 때까지 몇십 초 걸릴 수 있다.
 * 수집이 이미 도는 중이면 409 {"error":"ingest already running"}을 돌려준다(ApiExceptionHandler).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final IngestService ingestService;

    public AdminController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    /** 명단 선수의 프로필·지금 팀·단계·이동 기록을 지금 다시 받는다. */
    @PostMapping("/ingest/players")
    public IngestLogResponse ingestPlayers() {
        return IngestLogResponse.from(ingestService.syncPlayers());
    }

    /** 명단 선수 전원의 한 시즌(gameLog + 팀 일정 + 포스트시즌 박스스코어)을 다시 받는다. */
    @PostMapping("/ingest/season")
    public IngestLogResponse ingestSeason(@RequestParam @Min(2000) @Max(2100) int season) {
        return IngestLogResponse.from(ingestService.ingestSeason(season));
    }

    /** 미국 날짜 date와 그 전날의 우리 팀 경기(일정 + 박스스코어)를 다시 받는다. */
    @PostMapping("/ingest/games")
    public IngestLogResponse ingestGames(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return IngestLogResponse.from(ingestService.ingestGames(date));
    }

    /** 스케줄이 하는 따라잡기를 지금 한 번 돈다(경기 갱신 포함). */
    @PostMapping("/ingest/catch-up")
    public IngestLogResponse catchUp() {
        return IngestLogResponse.from(ingestService.catchUp(true));
    }
}
