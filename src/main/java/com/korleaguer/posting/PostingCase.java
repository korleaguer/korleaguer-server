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
 * @param slug        영문 짧은 이름(사진 주소에 쓴다). 없으면 null
 * @param photoSeason 사진의 시즌(사진 주소에 쓴다). 사진이 없으면 null
 * @param photoUrl    프로필 사진 원본 주소(현 소속 KBO 구단 공식 사진, 편집본의 출처). 없으면 null.
 *                    사진을 보일지도 이 값으로 정한다: 출처를 적은 사진만 호스팅한다(편집본만 있고 출처가 없으면 보이지 않는다)
 * @param result      결과가 났으면 결과, 아니면 null
 */
public record PostingCase(String nameKo, String slug, String kboTeam, String positionKo, Integer birthYear,
                          List<String> searchNames, Instant windowOpens, Instant deadline, String source,
                          Integer photoSeason, String photoUrl, PostingResult result) {

    /** 관련 기사를 모으기 시작하는 때: 협상 시작 며칠 전(공시 소식부터 잡는다) */
    static final Duration NEWS_LEAD = Duration.ofDays(3);
    /** 관련 기사를 그만 모으는 때: 협상 마감 며칠 뒤(결과 기사까지 잡는다) */
    static final Duration NEWS_TAIL = Duration.ofDays(3);

    public PostingCase {
        searchNames = List.copyOf(searchNames);
        // 사진 이름 kbo-{slug}-{photoSeason}을 만들 수 있어야 한다. yml 값은 Postings가 먼저 자세한 말로 검사하고,
        // 여기서는 어디서 만들든(테스트 포함) 사진 주소를 만들다 빈 값으로 멈추지 않게 막는다
        if (photoUrl != null && (slug == null || photoSeason == null)) {
            throw new IllegalArgumentException(nameKo + ": photoUrl needs slug and photoSeason");
        }
    }

    /** 지금 관련 기사를 모을 때인지 */
    public boolean watchNews(Instant now) {
        return !now.isBefore(windowOpens.minus(NEWS_LEAD)) && !now.isAfter(deadline.plus(NEWS_TAIL));
    }
}
