package com.jaeipgo.alimi.core

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter
import org.springframework.kafka.support.converter.RecordMessageConverter

/**
 * 컨슈머의 역직렬화 타입을 **@KafkaListener 메서드의 파라미터 타입**으로 결정한다.
 *
 * 왜 이렇게 하는가:
 * `JsonDeserializer` + `spring.json.value.default.type` 조합은 타입을 하나로 고정한다.
 * 이벤트가 하나뿐일 때는 괜찮지만, 토픽마다 타입이 다른 지금은
 * 어떤 토픽을 듣든 그 하나로 역직렬화되어 깨진다.
 * (실제로 겪은 문제다 — NotificationDispatch 를 보냈는데
 *  NotificationCreatedEvent 로 파싱하려다 MissingKotlinParameterException)
 *
 * Boot 이 이 빈을 리스너 팩토리에 자동으로 연결해준다.
 */
@Configuration
class CoreKafkaConfig {

    @Bean
    fun kafkaRecordMessageConverter(objectMapper: ObjectMapper): RecordMessageConverter =
        ByteArrayJsonMessageConverter(objectMapper)
}
