package com.korleaguer.api.article;

import java.time.Instant;
import java.util.List;

/**
 * 뉴스 탭 한 쪽(GET /api/news). NewsListFinder가 만든다.
 *
 * @param players 선수 고르기 목록: 기사가 있는 선수만, 이번 겨울 포스팅 선수 먼저, 그다음 명단 순
 * @param player  이 쪽을 거른 선수(한글 이름). 전체면 null, 모르는 선수를 달라고 했어도 null(그때 items는 빈 목록)
 * @param items   기사(같은 기사 묶음마다 한 줄, 늦은 순, 최대 NewsListFinder.PAGE줄)
 * @param next    다음 쪽 커서(before로 그대로 넘긴다). 더 없으면 null
 * @param query   이 쪽을 거른 검색어(앞뒤 공백을 뗀 것). 없으면 null
 * @param topics  지금 범위(전체·선수)의 최근 기사에 많이 나온 말(뉴스 탭 맨 위 알약 줄, 이슈 #158·#165). 첫 쪽에만(검색어가
 *                있어도 검색어와 상관없이 센다), 아니면 빈 목록. 선수를 고르면 그 선수의 지금 구단 이름은 뺀다
 * @param queryCount 이 검색으로 나오는 줄 수(끝까지 넘겨 본 줄 수, 같은 기사는 한 줄, 이슈 #183). 알약 숫자(Topic.count)와
 *                   같은 셈이라 알약 'FA 21'을 눌러 연 검색(q=FA)도 21이다. query가 있는 첫 쪽에만 있고, 검색어가 없거나
 *                   둘째 쪽부터는 null(웹은 첫 쪽 값을 그대로 둔다)
 */
public record NewsListResponse(List<Subject> players, String player, List<Item> items, String next, String query,
                               List<Topic> topics, Integer queryCount) {

    public NewsListResponse {
        players = List.copyOf(players);
        items = List.copyOf(items);
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    NewsListResponse(List<Subject> players, String player, List<Item> items, String next) {
        this(players, player, items, next, null, List.of(), null);
    }

    /**
     * 많이 나온 말 하나.
     *
     * @param word  검색어로 그대로 쓰는 말(예: "FA", "다저스")
     * @param count 이 말로 검색하면 나오는 줄 수(지금 범위의 기사 전체, 같은 기사는 한 줄). 말은 최근 기사에서 고른다(이슈 #167).
     *              그 검색 첫 쪽의 queryCount와 같은 셈이다
     */
    public record Topic(String word, int count) {
    }

    /**
     * 선수 고르기 한 칸.
     *
     * @param photoUrl 우리가 호스팅하는 선수 사진 주소. 없으면 null
     * @param playerId 명단 선수의 번호(선수 화면 /players/{id}). 포스팅 선수처럼 명단에 없으면 null
     * @param posting  이번 겨울 포스팅 선수인가(아직 명단에 없는 KBO 선수)
     * @param latestAt 이 선수의 보일 기사 중 가장 최근 발행 시각. 웹이 '마지막으로 본 뒤 새 기사가 있나'를 볼 때 쓴다(이슈 #155).
     *                 같은 기사 묶음은 가장 늦게 실린 사본까지 본다
     */
    public record Subject(String nameKo, String photoUrl, Long playerId, boolean posting, Instant latestAt) {

        Subject withLatestAt(Instant at) {
            return new Subject(nameKo, photoUrl, playerId, posting, at);
        }
    }

    /**
     * 기사 한 줄. 홈·선수 화면의 기사 줄(PostingSummary.Headline)과 같은 값에 누구 기사인지(who)를 더했다.
     * 목록에서는 요약을 보이지 않아(누르면 기사 화면에서 본다) 요약·첫머리는 싣지 않는다.
     *
     * @param alsoBy 같은 글을 실은 다른 언론사(낸 순)
     * @param who    누구 기사인지(한글 이름). 같은 기사가 여러 선수에 걸렸으면 모두, 대표 기사의 선수가 먼저
     */
    public record Item(Long id, String title, String url, String publisher, Instant publishedAt, String titleKo,
                       String imageUrl, List<String> alsoBy, List<String> who) {

        public Item {
            alsoBy = alsoBy == null ? List.of() : List.copyOf(alsoBy);
            who = who == null ? List.of() : List.copyOf(who);
        }
    }
}
