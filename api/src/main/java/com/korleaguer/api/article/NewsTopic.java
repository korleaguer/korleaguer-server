package com.korleaguer.api.article;

/**
 * 많이 나온 말 하나.
 *
 * @param word  검색어로 그대로 쓰는 말(예: "FA", "다저스")
 * @param count 이 말로 검색하면 나오는 줄 수(지금 범위의 기사 전체, 같은 기사는 한 줄). 말은 최근 기사에서 고른다(이슈 #167).
 *              그 검색 첫 쪽의 queryCount와 같은 셈이다
 */
public record NewsTopic(String word, int count) {
}
