package com.korleaguer.posting;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 이번 겨울 포스팅된 선수 한 명(posting.yml의 current를 읽어 검사한 값).
 *
 * @param searchNames 기사 제목에서 찾을 이름. 한글 이름이 맨 앞에 있다
 * @param windowOpens 30일 협상 시작 시각
 * @param deadline    협상 마감 시각
 * @param result      결과가 났으면 결과, 아니면 null
 */
public record PostingCase(String nameKo, String kboTeam, String positionKo, Integer birthYear,
                          List<String> searchNames, Instant windowOpens, Instant deadline, String source,
                          PostingResult result) {

    /** 관련 기사를 모으기 시작하는 때: 협상 시작 며칠 전(공시 소식부터 잡는다) */
    static final Duration NEWS_LEAD = Duration.ofDays(3);
    /** 관련 기사를 그만 모으는 때: 협상 마감 며칠 뒤(결과 기사까지 잡는다) */
    static final Duration NEWS_TAIL = Duration.ofDays(3);

    public PostingCase {
        searchNames = List.copyOf(searchNames);
    }

    /** 지금 관련 기사를 모을 때인지 */
    public boolean watchNews(Instant now) {
        return !now.isBefore(windowOpens.minus(NEWS_LEAD)) && !now.isAfter(deadline.plus(NEWS_TAIL));
    }
}
