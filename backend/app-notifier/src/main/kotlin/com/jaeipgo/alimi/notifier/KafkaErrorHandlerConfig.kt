package com.jaeipgo.alimi.notifier

import com.jaeipgo.alimi.contract.Topics
import com.jaeipgo.alimi.notifier.send.PermanentSendException
import com.jaeipgo.alimi.notifier.send.UnsupportedChannelException
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff

/**
 * 재시도 정책.
 *
 * 핵심은 **영구 실패를 재시도하지 않는 것**이다.
 * 존재하지 않는 이메일 주소를 3번 재시도하는 건 낭비고, 그 사이 뒤에 쌓인
 * 정상 메시지들이 밀린다. 영구 실패는 즉시 DLT 로 보낸다.
 */
@Configuration
class KafkaErrorHandlerConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun errorHandler(kafkaTemplate: KafkaTemplate<Any, Any>): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { record, exception ->
            log.error(
                "재시도 소진 → DLT 로 보냅니다. topic={} key={}",
                record.topic(), record.key(), exception,
            )
            TopicPartition(Topics.dlt(record.topic()), record.partition())
        }

        // 1초에서 시작해 2배씩, 최대 30초. 총 3회 재시도.
        val backOff = ExponentialBackOff(1_000L, 2.0).apply {
            maxInterval = 30_000L
            maxAttempts = 3
        }

        return DefaultErrorHandler(recoverer, backOff).apply {
            // 이 예외들은 재시도 없이 즉시 DLT 로.
            addNotRetryableExceptions(
                PermanentSendException::class.java,
                UnsupportedChannelException::class.java,
            )
            setLogLevel(org.springframework.kafka.KafkaException.Level.WARN)
        }
    }
}

