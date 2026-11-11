package com.korleaguer.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class NewsController {

    private final NewsPageFinder finder;

    public NewsController(NewsPageFinder finder) {
        this.finder = finder;
    }

    /** 우리 기사 페이지 한 건(제목·대표 사진·요약·출처). 없는 번호이거나 지금 받지 않는 언론사의 기사면 404. DB만 읽는다 */
    @GetMapping("/news/{id}")
    public NewsPageResponse news(@PathVariable long id) {
        return finder.find(id);
    }
}
