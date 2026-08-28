package com.jaeipgo.alimi.core

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName
import org.springframework.context.annotation.Bean

/**
 * 통합 테스트용 인프라. Docker 데몬이 떠 있어야 한다.
 *
 * testFixtures 로 노출되어 있으므로 app-* 모듈에서
 * `testImplementation(testFixtures(project(":backend:core")))` 로 가져다 쓴다.
 * `@ServiceConnection` 이 datasource / kafka bootstrap 프로퍼티를 자동 연결한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer<*> =
        MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("alimi")

    /**
     * docker-compose / k8s 와 같은 브로커 이미지를 쓴다.
     *
     * `apache/kafka` 이미지용 `KafkaContainer` 는 쓰지 말 것 —
     * "advertised.listeners cannot use the nonroutable meta-address 0.0.0.0" 로 기동에 실패한다.
     */
    @Bean
    @ServiceConnection
    fun kafkaContainer(): ConfluentKafkaContainer =
        ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"))
}
