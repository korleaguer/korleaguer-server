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
 * 관리용 수집 엔드포인트. X-Admin-Token 검사는 AdminTokenInterceptor가 한다.
 * 실제 모드에서는 요청 수 제한(분당 5회) 때문에 끝날 때까지 몇 분 걸릴 수 있다.
 * 수집이 이미 도는 중이면 409 {"error":"ingest already running"}을 돌려준다(ApiExceptionHandler).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final IngestService ingestService;

    public AdminController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/resolve-players")
    public IngestLogResponse resolvePlayers() {
        return IngestLogResponse.from(ingestService.resolvePlayers());
    }

    @PostMapping("/ingest/season")
    public IngestLogResponse ingestSeason(@RequestParam @Min(2000) @Max(2100) int season) {
        return IngestLogResponse.from(ingestService.ingestSeason(season));
    }

    @PostMapping("/ingest/daily")
    public IngestLogResponse ingestDaily(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return IngestLogResponse.from(ingestService.ingestDaily(date));
    }
}
