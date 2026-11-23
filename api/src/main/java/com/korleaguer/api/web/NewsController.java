package com.korleaguer.api.web;

import com.korleaguer.api.article.NewsListFinder;
import com.korleaguer.api.article.NewsListResponse;
import com.korleaguer.api.article.NewsPageFinder;
import com.korleaguer.api.article.NewsPageResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class NewsController {

    private final NewsPageFinder finder;
    private final NewsListFinder list;

    public NewsController(NewsPageFinder finder, NewsListFinder list) {
        this.finder = finder;
        this.list = list;
    }

    /**
     * 뉴스 탭 한 쪽: 선수 고르기 목록과 기사 20줄(늦은 순, 같은 기사는 한 줄) + 다음 쪽 커서(이슈 #153). DB만 읽는다.
     * player(한글 이름)를 주면 그 선수 기사만, 모르는 선수면 빈 목록. before는 앞 쪽의 next 그대로이고 모양이 틀리면 400.
     * q를 주면 제목에 그 말이 있는 기사만(이슈 #158·#161). 첫 쪽이면 많이 나온 말(topics)도 준다(q와 상관없이 센다, 이슈 #165).
     * q가 있는 첫 쪽이면 그 검색의 줄 수(queryCount)도 준다(알약 숫자와 같은 셈, 이슈 #183)
     */
    @GetMapping("/news")
    public NewsListResponse list(@RequestParam(required = false) String player,
                                 @RequestParam(required = false) String before,
                                 @RequestParam(required = false) String q) {
        return list.find(player, before, q);
    }

    /**
     * 우리 기사 페이지 한 건(제목·대표 사진·요약·출처, 같은 글을 실은 다른 언론사 alsoBy). 없는 번호이거나 지금 받지 않는
     * 언론사의 기사면 404. DB만 읽는다
     */
    @GetMapping("/news/{id}")
    public NewsPageResponse news(@PathVariable long id) {
        return finder.find(id);
    }

    /**
     * 기사 페이지가 있는지만 답한다(GET과 같은 기준으로 200·404, 본문 없음). 웹이 기사 화면을 열 때마다 먼저 묻는다.
     * 이 처리가 없으면 Spring이 HEAD에도 위 GET을 불러 같은 기사 묶음·다른 기사까지 다 찾고 본문만 버린다
     */
    @RequestMapping(value = "/news/{id}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> newsExists(@PathVariable long id) {
        return finder.exists(id) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
}
