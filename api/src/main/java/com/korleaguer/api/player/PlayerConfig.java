package com.korleaguer.api.player;

import com.korleaguer.config.MinorTeamProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 선수 기본 주제의 값 빈. 마이너 팀 이름표는 뜰 때 한 번 만드는 값이라 여기서 등록한다 */
@Configuration
public class PlayerConfig {

    @Bean
    public MinorTeamNames minorTeamNames(MinorTeamProperties properties) {
        return new MinorTeamNames(properties);
    }
}
