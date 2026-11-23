package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * MLB 시즌 한 개의 날짜(/api/v1/seasons/{year}?sportId=1). 날짜는 미국 날짜 "yyyy-MM-dd" 문자열 그대로다.
 * 시범경기 시작일(springStartDate) 등 다른 칸도 오지만 쓰는 칸만 받는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbSeason(String seasonId, String regularSeasonStartDate, String regularSeasonEndDate,
                        String postSeasonEndDate) {
}
