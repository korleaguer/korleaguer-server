package com.korleaguer.config;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 화면용 응답에 나오는 데이터를 DB에 쓴 횟수(INSERT·UPDATE·DELETE)를 센다. 응답 캐시를 실제로 바뀔 때만 지우려고 쓴다
 * (ResponseCaches). Hibernate가 SQL을 보내기 직전에 부른다(hibernate.session_factory.statement_inspector).
 * <ul>
 *   <li>Hibernate는 값이 바뀐 엔티티만 UPDATE를 보낸다. 같은 일정·기록을 다시 받아도 내용이 같으면 세지 않는다.</li>
 *   <li>우리 코드는 DB에 Hibernate로만 쓴다(JdbcTemplate 없음). 일괄 삭제(@Modifying JPQL)도 여기를 지난다.</li>
 *   <li>응답에 나오지 않는 표는 세지 않는다: 수집 기록(ingest_log), 사진(photo, 응답의 사진 주소는 저장 여부와 무관), Flyway 기록.</li>
 *   <li>홈에만 나오는 표(기사 제목 news_article, 행선지로 거론된 구단 news_destination)는 따로 센다(homeVersion).
 *       바뀌면 홈 캐시만 비운다.</li>
 * </ul>
 * Hibernate가 직접 만들므로(빈이 아니다) 값은 정적 필드에 둔다.
 */
public class DataChanges implements StatementInspector {

    private static final AtomicLong VERSION = new AtomicLong();
    private static final AtomicLong HOME_VERSION = new AtomicLong();
    private static final Pattern WRITE = Pattern.compile(
            "^\\s*(?:insert\\s+into|update|delete\\s+from|merge\\s+into)\\s+[`\"]?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> IGNORED_TABLES = Set.of("ingest_log", "photo", "flyway_schema_history", "news_blocked_host");
    private static final Set<String> HOME_ONLY_TABLES = Set.of("news_article", "news_destination");

    /** 쓴 SQL이 어느 응답을 바꿀 수 있나 */
    enum Scope { NONE, HOME, ALL }

    /** 홈·선수·내 선수 응답 모두에 나오는 데이터를 쓴 횟수. 두 번 읽은 값이 다르면 그 사이 바뀌었을 수 있다 */
    public static long version() {
        return VERSION.get();
    }

    /** 홈에만 나오는 데이터(기사 제목)를 쓴 횟수 */
    public static long homeVersion() {
        return HOME_VERSION.get();
    }

    @Override
    public String inspect(String sql) {
        switch (scopeOf(sql)) {
            case ALL -> VERSION.incrementAndGet();
            case HOME -> HOME_VERSION.incrementAndGet();
            case NONE -> {
            }
        }
        return sql;
    }

    static Scope scopeOf(String sql) {
        Matcher matcher = WRITE.matcher(sql);
        if (!matcher.find()) {
            return Scope.NONE;
        }
        String table = matcher.group(1).toLowerCase(Locale.ROOT);
        if (IGNORED_TABLES.contains(table)) {
            return Scope.NONE;
        }
        return HOME_ONLY_TABLES.contains(table) ? Scope.HOME : Scope.ALL;
    }
}
