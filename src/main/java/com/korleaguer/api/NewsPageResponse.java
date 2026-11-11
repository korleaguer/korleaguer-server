package com.korleaguer.api;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/news/{id}: 우리 기사 페이지 한 건. 원문은 url(언론사)에 있고, 우리는 제목·대표 사진·요약과 출처만 보여 준다.
 *
 * @param player         이 기사의 선수(한글 이름)
 * @param playerPhotoUrl 그 선수 사진의 우리 주소(기사 사진이 없을 때 쓴다). 이번 겨울 포스팅 선수가 아니거나 사진을 껐으면 null
 * @param title          원문 제목
 * @param titleKo        영어 기사 제목을 Claude가 한국어로 옮긴 것(화면은 이것을 제목으로). 한국어 기사면 null
 * @param url            원문 주소(언론사)
 * @param imageUrl       기사 대표 사진의 원본 주소(언론사 서버, https). 없으면 null
 * @param summary        Claude가 쓴 한국어 요약(줄마다 한 문장). 아직 없으면 null
 * @param lead           기사 첫머리(피드가 준 글자 200자 안팎). 요약이 없을 때 쓴다. 없으면 null
 * @param more           같은 선수의 다른 기사(늦은 순 NewsPageFinder.MORE건, 이 기사는 빼고)
 */
public record NewsPageResponse(long id, String player, String playerPhotoUrl, String title, String titleKo, String url,
                               String publisher, Instant publishedAt, String imageUrl, String summary, String lead,
                               List<PostingSummary.Headline> more) {
}
