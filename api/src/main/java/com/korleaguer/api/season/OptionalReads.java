package com.korleaguer.api.season;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 실패해도 되는 조회(시즌 성적표의 재료·MLB 안 위치·겨울 기사·거론 구단 등)를 새 읽기 전용 트랜잭션에서 따로 돌리고,
 * 실패하면 경고만 남기고 대신할 값(fallback)을 준다.
 * <p>
 * 왜 따로 돌리나(이슈 #179 리뷰): 홈·선수 화면 조회(BaseballQueryService)는 바깥 @Transactional(readOnly = true) 안에서
 * 저장소를 부른다. 같은 트랜잭션 안에서 조회가 실패하면 Hibernate(JPA 예외)나 저장소의 트랜잭션 처리(참여 중 실패)가 바깥
 * 트랜잭션을 rollback-only로 표시한다. 그러면 예외를 catch로 삼켜도 바깥이 끝날 때 커밋 대신 UnexpectedRollbackException이
 * 나서 응답 전체가 500이 된다. 새 트랜잭션(REQUIRES_NEW)에서 돌리면 표시는 새 트랜잭션에만 남고(되돌리고 끝난다) 바깥은
 * 그대로 커밋된다. 트랜잭션 관리자 전체 설정(globalRollbackOnParticipationFailure)은 바꾸지 않는다: Hibernate가 하는 표시는
 * 막지 못하고, 쓰기 트랜잭션(수집 등)에서 안쪽 실패를 삼킨 경우까지 그대로 커밋하게 된다.
 * <p>
 * 연결 풀: 새 트랜잭션은 바깥 트랜잭션이 쥔 DB 연결과 따로 연결을 하나 더 빌린다(끝나면 돌려준다). 그래서 query 안에서 이것을
 * 다시 부르지 않는다(겹치면 한 요청이 연결을 셋 쥔다). 동시에 도는 요청이 연결 풀(HikariCP 기본 10개)을 바깥 연결로 다 채우고
 * 저마다 하나 더 기다리면, 연결 대기 한도(기본 30초)가 지나 이 조회들이 fallback으로 끝날 때까지 멈춘다. 홈·선수 응답은
 * 캐시에서 답하고 캐시는 CacheWarmer가 하나씩 채우므로 지금 규모에서는 드물다.
 * <p>
 * 새 트랜잭션의 EntityManager는 query가 끝나면 닫힌다. 지연 로딩이 필요한 값은 query 안에서 꺼내 돌려준다.
 */
@Component
class OptionalReads {

    private static final Logger log = LoggerFactory.getLogger(OptionalReads.class);

    private final TransactionTemplate separate;

    OptionalReads(PlatformTransactionManager transactions) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        this.separate = template;
    }

    /** query를 새 읽기 전용 트랜잭션에서 돌린 값. 조회·연결·커밋 가운데 무엇이 실패하든 경고만 남기고 fallback */
    <T> T read(String what, Supplier<T> query, T fallback) {
        try {
            return separate.execute(status -> query.get());
        } catch (RuntimeException e) {
            log.warn("{} failed, left out: {}", what, e.toString());
            return fallback;
        }
    }
}
