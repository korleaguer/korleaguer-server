package com.korleaguer.api.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // 화면(web)은 서버에서 api를 부르므로 CORS가 꼭 필요하진 않다. 개발 편의로 localhost:3000만 연다.
    // HEAD(선수·기사가 있는지만 묻기)는 GET과 같은 주소라 함께 연다. 빼면 브라우저에서 부른 HEAD는 403이 된다
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("GET", "HEAD");
    }
}
