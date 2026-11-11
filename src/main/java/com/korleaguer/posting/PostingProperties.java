package com.korleaguer.posting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * resources/roster/posting.yml 의 포스팅 자료(사람이 적은 값). 날짜·시각은 글자 그대로 받고 Postings가 읽어 검사한다.
 * 형식이 틀리면 앱이 뜰 때 바로 실패하게 하려고 여기서는 변환하지 않는다.
 */
@ConfigurationProperties("posting")
public record PostingProperties(List<Current> current, List<Past> history) {

    public PostingProperties {
        current = current == null ? List.of() : List.copyOf(current);
        history = history == null ? List.of() : List.copyOf(history);
    }

    /**
     * 이번 겨울 포스팅된 선수.
     *
     * @param windowOpens 협상 시작 시각(오프셋 포함 ISO, 예: 2026-11-02T08:00:00-05:00)
     * @param deadline    협상 마감 시각(같은 형식)
     * @param slug        영문 짧은 이름(주소에 쓴다, 예: si-hwan-roh). 사진이 있으면 꼭 적는다
     * @param photoUrl    프로필 사진 원본 주소(선택, 현 소속 KBO 구단 사진). 우리 서버가 한 번 받아 호스팅한다
     * @param result      결과가 났으면 적는다(없으면 null)
     */
    public record Current(String nameKo, String slug, String kboTeam, String positionKo, Integer birthYear,
                          List<String> searchNames, String windowOpens, String deadline, String source,
                          String photoUrl, Result result) {

        public Current {
            searchNames = searchNames == null ? List.of() : List.copyOf(searchNames);
        }
    }

    /**
     * 포스팅 결과.
     *
     * @param outcome       SIGNED(계약) 또는 NO_DEAL(결렬)
     * @param mlbTeam       계약한 구단 약어(예: SD). SIGNED일 때만
     * @param agreedOn      합의가 처음 보도된 미국 날짜(yyyy-MM-dd). SIGNED일 때만
     * @param years         보장 연수. SIGNED일 때만
     * @param guaranteedUsd 보장액(달러). SIGNED일 때만
     * @param krwPerUsd     합의일 원/달러 환율(1달러당 원, 선택). 화면이 보장액을 원화로 어림해 보인다. SIGNED일 때만
     */
    public record Result(String outcome, String mlbTeam, String agreedOn, Integer years, Long guaranteedUsd,
                         Double krwPerUsd) {
    }

    /**
     * 지난 포스팅 한 건(2012~). agreedOn이 없으면 결렬이다.
     *
     * @param season   포스팅된 겨울이 시작한 해
     * @param deadline 협상 마감 미국 날짜(yyyy-MM-dd)
     * @param kboId       KBO 선수 번호(선택). MLB 사진이 없으면 season 시즌의 KBO 공식 등록 사진을 쓴다
     * @param mlbId       MLB 선수 번호(선택, photo-url과 함께)
     * @param photoSeason photo-url 사진의 시즌(계약한 MLB 구단에서의 마지막 시즌)
     * @param photoUrl    계약한 MLB 구단 시절 MLB 공식 사진 원본 주소(선택). 화면에는 그 편집본을 보인다
     * @param krwPerUsd   합의일 원/달러 환율(1달러당 원, 선택). 화면이 보장액을 원화로 어림해 보인다
     */
    public record Past(String nameKo, Integer season, String mlbTeam, String deadline, String agreedOn,
                       Integer years, Long guaranteedUsd, String source, Long kboId,
                       Long mlbId, Integer photoSeason, String photoUrl, Double krwPerUsd) {
    }
}
