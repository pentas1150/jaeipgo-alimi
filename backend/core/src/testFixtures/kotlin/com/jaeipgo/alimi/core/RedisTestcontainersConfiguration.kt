package com.jaeipgo.alimi.core

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Redis 를 쓰는 모듈(현재 `app-api`)의 테스트에서만 import 한다.
 *
 * [TestcontainersConfiguration] 에 합치지 않은 이유: 그걸 import 하는 모든 모듈이
 * 세션 저장소와 무관한데도 Redis 컨테이너를 띄우게 된다.
 *
 * `@ServiceConnection(name = "redis")` 로 이미지 이름을 알려주면 스프링 부트가
 * `spring.data.redis.*` 를 자동으로 이 컨테이너로 돌린다 (전용 컨테이너 클래스가 필요 없다).
 */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestcontainersConfiguration {

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT)

    private companion object {
        const val REDIS_PORT = 6379
    }
}
