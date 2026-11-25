package com.korleaguer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ApiApplicationTests {

	@Autowired
	private CacheManager cacheManager;

	@Test
	@DisplayName("테스트 설정으로 앱이 오류 없이 뜬다")
	void contextLoads() {
		// when // then
		// 앱이 뜨는 것 자체가 확인이라 본문이 없다
	}

	@Test
	@DisplayName("홈·선수·내 선수·카드 캐시를 메모리 캐시(Caffeine)에 둔다")
	void cacheIsCaffeine() {
		// when // then
		assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
		assertThat(cacheManager.getCacheNames()).contains("home", "player", "my", "card");
	}

}
