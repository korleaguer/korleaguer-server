package com.korleaguer;

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
	void contextLoads() {
	}

	@Test
	void cacheIsCaffeine() {
		assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
		assertThat(cacheManager.getCacheNames()).contains("home", "player", "my", "card");
	}

}
