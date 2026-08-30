package com.jaeipgo.alimi.checker

import com.jaeipgo.alimi.contract.Topics
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
 * ── 왜 재시도가 얼마 없는가 ────────────────────────────────────
 * 이 리스너에서 **판정 실패는 예외가 아니다.** 차단도 타임아웃도 `CheckResult` 로
 * 돌아와 상품 행의 백오프로 처리된다. 여기까지 예외가 올라온다는 건 DB 오류처럼
 * 진짜 이상한 일이라는 뜻이다.
 *
 * 그래서 재시도 횟수를 notifier(3회)보다 낮게 잡았다. 체크 1건이 수십 초 걸리므로
 * 재시도가 길면 **뒤에 쌓인 정상 요청이 그만큼 밀린다.** `max-poll-records: 1` +
 * `max.poll.interval.ms: 300000` 안에서 끝나야 리밸런스도 안 돈다.
 *
 * 상품이 이미 지워졌으면 재시도해도 같은 결과라 즉시 DLT 로 보낸다.
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

        val backOff = ExponentialBackOff(2_000L, 2.0).apply {
            maxInterval = 10_000L
            maxAttempts = 2
        }

        return DefaultErrorHandler(recoverer, backOff).apply {
            // 상품 행이 사라진 뒤 도착한 메시지. 재시도해도 계속 없다.
            addNotRetryableExceptions(NoSuchElementException::class.java)
            setLogLevel(org.springframework.kafka.KafkaException.Level.WARN)
        }
    }
}
