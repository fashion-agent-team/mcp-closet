package com.fashionagent.closet;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 테스트용 PostgreSQL + pgvector(Testcontainers, Docker 필요)와 고정 시계(서울 2026-11-04 14:00). */
@TestConfiguration(proxyBeanMethods = false)
public class TestDb {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    @Primary
    Clock testClock() {
        return Clock.fixed(Instant.parse("2026-11-04T05:00:00Z"), ZoneId.of("UTC"));
    }
}
