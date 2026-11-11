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
     * @param result      결과가 났으면 적는다(없으면 null)
     */
    public record Current(String nameKo, String kboTeam, String positionKo, Integer birthYear,
                          List<String> searchNames, String windowOpens, String deadline, String source,
                          Result result) {

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
     */
    public record Result(String outcome, String mlbTeam, String agreedOn, Integer years, Long guaranteedUsd) {
    }

    /**
     * 지금 제도의 지난 포스팅 한 건. agreedOn이 없으면 결렬이다.
     *
     * @param season   포스팅된 겨울이 시작한 해
     * @param deadline 협상 마감 미국 날짜(yyyy-MM-dd)
     */
    public record Past(String nameKo, Integer season, String mlbTeam, String deadline, String agreedOn,
                       Integer years, Long guaranteedUsd, String source) {
    }
}
