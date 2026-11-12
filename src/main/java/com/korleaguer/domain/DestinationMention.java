package com.korleaguer.domain;

import java.time.Instant;

/**
 * 기사 한 건에서 거론된 구단 한 번(NewsDestinationRepository.findMentions). 홈이 구단별로 센다.
 *
 * @param team        MLB 대표 약어
 * @param publishedAt 그 기사의 발행 시각
 */
public record DestinationMention(String team, NewsDestination.Stance stance, Instant publishedAt) {
}
