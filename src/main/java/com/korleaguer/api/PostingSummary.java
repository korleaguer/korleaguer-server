package com.korleaguer.api;

import com.korleaguer.domain.NewsArticle;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 홈의 포스팅 묶음(GET /api/home의 posting). 비시즌일 때만 있다(PostingFinder).
 *
 * @param players 이번 겨울 포스팅된 선수(협상 마감이 이른 순). 마감 60일 뒤에는 빠진다
 * @param history 포스팅으로 MLB 구단과 계약한 지난 기록(2012~, posting.yml에 적은 순서)
 */
public record PostingSummary(List<Player> players, List<Past> history) {

    /**
     * 포스팅 선수 한 명.
     *
     * @param photoUrl      프로필 사진(현 소속 KBO 구단 공식 사진). 없거나 공식 사진을 껐으면 null
     * @param status        UPCOMING(협상 시작 전) · NEGOTIATING(협상 중) · SIGNED(계약) · NO_DEAL(결렬) ·
     *                      CLOSED(마감이 지났는데 결과를 아직 적지 않음)
     * @param windowOpensAt 30일 협상 시작 시각
     * @param deadlineAt    협상 마감 시각
     * @param daysLeft      한국 날짜로 마감일까지 남은 날(마감 당일 0). 협상 시작 전·협상 중일 때만, 그 밖에는 null
     * @param elapsedRatio  협상 기간 중 지난 비율(0~1, 소수 셋째 자리)
     * @param deal          계약했으면 계약 내용, 아니면 null
     * @param news          관련 기사 제목(발행 시각이 늦은 순, 최대 12건)
     * @param linkedTeams   최근 며칠 기사에서 이 선수의 행선지로 거론된 MLB 구단(이슈 #115)
     */
    public record Player(String nameKo, String kboTeam, String positionKo, Integer birthYear, String photoUrl,
                         String status,
                         Instant windowOpensAt, Instant deadlineAt, Integer daysLeft, double elapsedRatio,
                         Deal deal, List<Headline> news, LinkedTeams linkedTeams) {
    }

    /**
     * 최근 days일(한국 날짜, 오늘 포함) 기사에서 이 선수의 행선지로 거론된 MLB 구단. 기사마다 AI로 고른 것을 센다
     * (news.NewsTagger). 같은 기사를 여러 언론사가 실으면 따로 센다.
     *
     * @param teams 거론(linked)이 많은 순 → 오늘 거론된 구단 → 최근에 거론된 순. 반대 전망만 있는 구단도 넣는다.
     *              기사가 없거나 아직 고르지 않았으면 빈 목록
     */
    public record LinkedTeams(int days, List<LinkedTeam> teams) {
    }

    /**
     * 거론된 구단 하나.
     *
     * @param abbreviation MLB 대표 약어(NYM). 웹이 로고·팀 색을 찾는다
     * @param nameKo       한글 이름(뉴욕 메츠)
     * @param linked       갈 수 있다·관심 있다는 거론이 나온 기사 수
     * @param doubted      필요 없다·안 갈 것이라는 전망이 나온 기사 수
     * @param today        오늘(한국 날짜) 나온 기사에서 거론(linked)됐나
     */
    public record LinkedTeam(String abbreviation, String nameKo, int linked, int doubted, boolean today) {
    }

    /**
     * 계약 내용.
     *
     * @param agreedOn      합의가 처음 보도된 미국 날짜
     * @param guaranteedUsd 보장액(달러). 옵션·인센티브는 넣지 않는다
     * @param krwPerUsd     합의일 원/달러 환율(1달러당 원, ECB 기준 환율로 계산). 화면이 보장액을 원화로 어림한다.
     *                      적지 않았으면 null
     */
    public record Deal(Long mlbTeamId, String mlbTeamKo, LocalDate agreedOn, Integer years, Long guaranteedUsd,
                       Double krwPerUsd) {
    }

    /**
     * 지난 포스팅 한 건. 결렬이면 deal과 daysBeforeDeadline이 null이다.
     *
     * @param season             포스팅된 겨울이 시작한 해
     * @param deadline           협상 마감 미국 날짜
     * @param daysBeforeDeadline 마감 며칠 전에 합의했는지(미국 날짜, 마감 당일이면 0)
     * @param photoUrl           계약 구단 시절 MLB 공식 사진의 편집본(흰 바탕 정사각형, /photos/posting-673490-2024).
     *                           없으면 포스팅 당시 KBO 공식 등록 사진(/photos/kbo-77829-2019). 둘 다 없거나 공식 사진을 껐으면 null
     */
    public record Past(String nameKo, int season, LocalDate deadline, Integer daysBeforeDeadline, Deal deal,
                       String photoUrl) {
    }

    /**
     * 관련 기사 한 건. url은 원문(언론사) 주소다.
     *
     * @param id       우리 기사 번호. 웹은 제목을 누르면 우리 기사 페이지(/news/{id}, GET /api/news/{id})를 연다
     * @param titleKo  영어 기사 제목을 Claude가 한국어로 옮긴 것(화면은 이것을 제목으로). 없으면 null
     * @param lead     기사 첫머리(피드가 준 글자 200자 안팎). 없으면 null
     * @param summary  Claude가 쓴 한국어 요약(줄마다 한 문장). 없으면 null
     * @param imageUrl 기사 대표 사진의 원본 주소(언론사 서버, https). 없으면 null
     */
    public record Headline(Long id, String title, String url, String publisher, Instant publishedAt, String titleKo,
                           String lead, String summary, String imageUrl) {

        public Headline(String title, String url, String publisher, Instant publishedAt) {
            this(null, title, url, publisher, publishedAt, null, null, null, null);
        }

        static Headline of(NewsArticle a) {
            return new Headline(a.getId(), a.getTitle(), a.getUrl(), a.getPublisher(), a.getPublishedAt(),
                    a.getTitleKo(), a.getLeadText(), a.getSummary(), a.getImageUrl());
        }
    }
}
