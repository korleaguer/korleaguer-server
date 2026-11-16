package com.korleaguer.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 한국인 메이저리거 역대 기록(기록 탭, 이슈 #177) */
@RestController
@RequestMapping("/api")
public class RecordsController {

    private final RecordsService records;

    public RecordsController(RecordsService records) {
        this.records = records;
    }

    @GetMapping("/records")
    public RecordsResponse records() {
        return records.records();
    }
}
