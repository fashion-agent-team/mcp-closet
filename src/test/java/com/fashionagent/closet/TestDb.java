package com.fashionagent.closet;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 PostgreSQL + pgvector와 고정 시계(서울 2026-11-04 14:00).
 * 기본은 Testcontainers(Docker 필요). Docker가 없으면 TEST_DB_URL(·TEST_DB_USER·TEST_DB_PASSWORD)로 pgvector가 깔린 DB에 붙고,
 * 둘 다 없으면 통합 테스트를 건너뜁니다({@link #available()}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDb {

    private static final String URL = System.getenv("TEST_DB_URL");

    public static boolean available() {
        return (URL != null && !URL.isBlank()) || DockerClientFactory.instance().isDockerAvailable();
    }

    /** TEST_DB_URL이 있으면 그 DB로, 없으면 아래 컨테이너(@ServiceConnection)로 붙습니다. */
    public static void register(DynamicPropertyRegistry r) {
        if (URL != null && !URL.isBlank()) {
            r.add("test.db.external", () -> "true");
            r.add("spring.datasource.url", () -> URL);
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "postgres"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "postgres"));
        }
    }

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "test.db.external", havingValue = "false", matchIfMissing = true)
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    @Primary
    Clock testClock() {
        return Clock.fixed(Instant.parse("2026-11-04T05:00:00Z"), ZoneId.of("UTC"));
    }
}
