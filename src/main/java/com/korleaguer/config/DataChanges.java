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
 * </ul>
 * Hibernate가 직접 만들므로(빈이 아니다) 값은 정적 필드에 둔다.
 */
public class DataChanges implements StatementInspector {

    private static final AtomicLong VERSION = new AtomicLong();
    private static final Pattern WRITE = Pattern.compile(
            "^\\s*(?:insert\\s+into|update|delete\\s+from|merge\\s+into)\\s+[`\"]?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> IGNORED_TABLES = Set.of("ingest_log", "photo", "flyway_schema_history");

    /** 지금까지 센 쓰기 횟수. 두 번 읽은 값이 다르면 그 사이 응답에 나오는 데이터가 바뀌었을 수 있다 */
    public static long version() {
        return VERSION.get();
    }

    @Override
    public String inspect(String sql) {
        if (countsAsChange(sql)) {
            VERSION.incrementAndGet();
        }
        return sql;
    }

    static boolean countsAsChange(String sql) {
        Matcher matcher = WRITE.matcher(sql);
        return matcher.find() && !IGNORED_TABLES.contains(matcher.group(1).toLowerCase(Locale.ROOT));
    }
}
