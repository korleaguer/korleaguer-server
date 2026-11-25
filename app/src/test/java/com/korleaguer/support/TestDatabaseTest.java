package com.korleaguer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** CI에서 MySQL로 돌리기로 한 테스트 DB가 조용히 H2로 돌지 않게 한다(KORLEAGUER_TEST_DB=mysql일 때만). */
@EnabledIfEnvironmentVariable(named = "KORLEAGUER_TEST_DB", matches = "mysql")
class TestDatabaseTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class SharedDb {

        @Test
        @DisplayName("CI에서 함께 쓰는 DB는 MySQL이다")
        void isMySql(@Autowired DataSource dataSource) throws SQLException {
            assertThat(product(dataSource)).isEqualTo("MySQL");
        }
    }

    @Nested
    @CommittedDbTest
    class CommittedDb {

        @Test
        @DisplayName("CI에서 저장을 확인하는 테스트의 DB는 MySQL이다")
        void isMySql(@Autowired DataSource dataSource) throws SQLException {
            assertThat(product(dataSource)).isEqualTo("MySQL");
        }
    }

    private static String product(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        }
    }
}
