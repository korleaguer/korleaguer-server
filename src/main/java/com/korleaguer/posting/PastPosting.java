package com.korleaguer.posting;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 지금 제도의 지난 포스팅 한 건(posting.yml의 history를 읽어 검사한 값).
 *
 * @param season      포스팅된 겨울이 시작한 해
 * @param deadline    협상 마감 미국 날짜
 * @param result      계약했으면 SIGNED(구단·합의일·보장 계약), 아니면 NO_DEAL
 * @param kboId       KBO 선수 번호(없으면 null). MLB 사진이 없을 때 season 시즌의 KBO 공식 등록 사진을 찾는 데 쓴다
 * @param mlbId       MLB 선수 번호(photoUrl이 있을 때만)
 * @param photoSeason photoUrl 사진의 시즌(계약한 MLB 구단에서의 마지막 시즌, photoUrl이 있을 때만)
 * @param photoUrl    계약한 MLB 구단 시절 MLB 공식 사진 원본 주소(없으면 null). 화면에는 이 사진의 편집본
 *                    (src/main/resources/photos/posting-{mlbId}-{photoSeason}.jpg)을 보인다
 */
public record PastPosting(String nameKo, int season, LocalDate deadline, PostingResult result, String source,
                          Long kboId, Long mlbId, Integer photoSeason, String photoUrl) {

    /** 마감 며칠 전에 합의했는지(미국 날짜). 마감 당일 합의면 0, 결렬이면 null */
    public Integer daysBeforeDeadline() {
        if (result.outcome() != PostingResult.Outcome.SIGNED) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(result.agreedOn(), deadline);
    }
}
