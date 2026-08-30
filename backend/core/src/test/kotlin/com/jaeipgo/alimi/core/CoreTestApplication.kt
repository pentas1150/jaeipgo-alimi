package com.jaeipgo.alimi.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean

/**
 * core 는 라이브러리 모듈이라 `@SpringBootApplication` 이 없다.
 * 도메인 로직을 실제 MySQL 위에서 검증하려면 `@SpringBootTest` 가 붙잡을
 * `@SpringBootConfiguration` 이 하나 필요해서 테스트 소스셋에만 둔다.
 */
@SpringBootApplication
class CoreTestApplication {

    /**
     * ⚠️ core 단독 컨텍스트에서는 `ObjectMapper` 빈이 자동으로 만들어지지 않는다.
     *
     * `JacksonAutoConfiguration` 의 ObjectMapper 부분은 `Jackson2ObjectMapperBuilder` 가
     * 있어야 켜지는데 그 클래스는 **spring-web** 에 있고, core 에는 web 스타터가 없다.
     * 그래서 `CoreKafkaConfig.kafkaRecordMessageConverter(ObjectMapper)` 가 주입에 실패한다.
     *
     * 실제 배포에서는 문제가 되지 않는다 — `app-*` 는 전부 `spring-boot-starter-web` 을 갖는다
     * (checker/notifier/scheduler 도 actuator 프로브 때문에 web 이 필요하다).
     * 그래서 프로덕션 코드를 건드리는 대신 테스트 컨텍스트에서만 채운다.
     */
    @Bean
    fun objectMapper(): ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
}
