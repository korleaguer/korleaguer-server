package com.korleaguer.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 저장이 실제로 되는지 보는 테스트. 테스트를 트랜잭션으로 묶어 되돌리지 않는다: 묶으면 저장(save)을 부르지 않아도 바뀐 값이
 * 저절로 DB에 반영되어, 트랜잭션 없이 도는 운영의 저장 누락을 못 잡는다(이슈 #261).
 * <p>
 * 다른 테스트와 섞이지 않는 DB 하나를 이 애너테이션을 단 클래스끼리 함께 쓴다(스프링도 한 번만 뜬다). 되돌리지 않으므로
 * 클래스마다 쓰는 표를 {@code @BeforeEach}에서 비운다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:committed-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
public @interface CommittedDbTest {
}
