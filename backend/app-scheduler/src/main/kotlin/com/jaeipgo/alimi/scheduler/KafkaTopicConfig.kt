package com.jaeipgo.alimi.scheduler

import com.jaeipgo.alimi.contract.Topics
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * 기동 시 토픽을 생성한다.
 *
 * scheduler 모듈에 둔 이유: 이 역할만 replicas=1 이 보장되므로
 * 여러 파드가 동시에 토픽을 만들려 경쟁하지 않는다.
 *
 * ⚠️ 브로커가 아직 안 떠 있으면 KafkaAdmin 이 ERROR 만 남기고 앱은 정상 기동한다.
 * 그러면 토픽이 하나도 안 생긴다. `spring.kafka.admin.fail-fast: true` 로 막고 있다.
 * (실제로 겪은 문제다 — docs/DESIGN.md §10.6 ④)
 *
 * 운영에서는 토픽을 인프라(Strimzi KafkaTopic CR 등)로 선언적으로 관리하는 게 정석이다.
 */
@Configuration
class KafkaTopicConfig(
    @Value("\${alimi.kafka.partitions.stock-check-requested}") private val checkPartitions: Int,
    @Value("\${alimi.kafka.partitions.stock-restocked}") private val restockedPartitions: Int,
    @Value("\${alimi.kafka.partitions.notification-dispatch}") private val dispatchPartitions: Int,
    @Value("\${alimi.kafka.replicas:1}") private val replicas: Int,
) {

    @Bean
    fun stockCheckRequestedTopic(): NewTopic = topic(Topics.STOCK_CHECK_REQUESTED, checkPartitions)

    @Bean
    fun stockRestockedTopic(): NewTopic = topic(Topics.STOCK_RESTOCKED, restockedPartitions)

    @Bean
    fun notificationDispatchTopic(): NewTopic = topic(Topics.NOTIFICATION_DISPATCH, dispatchPartitions)

    // DLT 는 순서 보장이 필요 없고 양도 적으므로 파티션 1개로 충분하다.
    @Bean
    fun stockCheckRequestedDlt(): NewTopic = topic(Topics.dlt(Topics.STOCK_CHECK_REQUESTED), 1)

    @Bean
    fun stockRestockedDlt(): NewTopic = topic(Topics.dlt(Topics.STOCK_RESTOCKED), 1)

    @Bean
    fun notificationDispatchDlt(): NewTopic = topic(Topics.dlt(Topics.NOTIFICATION_DISPATCH), 1)

    /** 배선 확인용 샘플 토픽. 실제 도메인 구현 시 제거. */
    @Bean
    fun notificationEventsTopic(): NewTopic = topic(Topics.NOTIFICATION_EVENTS, 3)

    private fun topic(name: String, partitions: Int): NewTopic =
        TopicBuilder.name(name)
            .partitions(partitions)
            .replicas(replicas)
            .build()
}
