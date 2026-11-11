package com.korleaguer.posting;

import java.time.LocalDate;

/**
 * 포스팅 결과. 결렬(NO_DEAL)이면 구단·날짜·금액이 모두 null이다.
 *
 * @param mlbTeamId     계약한 구단의 MLB 팀 번호
 * @param agreedOn      합의가 처음 보도된 미국 날짜
 * @param years         보장 연수
 * @param guaranteedUsd 보장액(달러)
 */
public record PostingResult(Outcome outcome, Long mlbTeamId, LocalDate agreedOn, Integer years,
                            Long guaranteedUsd) {

    public enum Outcome {
        /** MLB 구단과 계약 */
        SIGNED,
        /** 마감까지 계약하지 못해 원 소속팀으로 돌아감 */
        NO_DEAL
    }

    public static PostingResult noDeal() {
        return new PostingResult(Outcome.NO_DEAL, null, null, null, null);
    }
}
