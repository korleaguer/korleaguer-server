package com.korleaguer.domain;

import java.time.Instant;

/**
 * 기사 한 건에서 거론된 구단 한 번(NewsDestinationRepository.findMentions). 홈이 구단별로 센다.
 *
 * @param articleId   그 기사 번호(같은 글을 실은 여러 언론사 기사를 한 번만 세려고)
 * @param team        MLB 대표 약어
 * @param publishedAt 그 기사의 발행 시각
 */
public record DestinationMention(Long articleId, String team, NewsDestination.Stance stance, Instant publishedAt) {
}
