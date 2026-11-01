package com.korleaguer.api;

import com.korleaguer.config.KorleaguerProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** /admin/** 요청은 X-Admin-Token 헤더가 설정값과 같을 때만 통과시킨다. 토큰 설정이 없으면 전부 막는다. */
@Component
public class AdminTokenInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Admin-Token";

    private final KorleaguerProperties properties;

    public AdminTokenInterceptor(KorleaguerProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (isValid(properties.admin().token(), request.getHeader(HEADER))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"forbidden\"}");
        return false;
    }

    private static boolean isValid(String expected, String given) {
        if (expected == null || expected.isBlank() || given == null) {
            return false;
        }
        // 글자를 하나씩 비교하는 시간 차로 토큰이 새지 않게 MessageDigest.isEqual을 쓴다.
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
