package com.jaeipgo.alimi.checker

import com.jaeipgo.alimi.checker.check.CheckOutcome
import com.jaeipgo.alimi.checker.check.StockChecker
import com.jaeipgo.alimi.contract.StockCheckRequested
import com.jaeipgo.alimi.contract.Topics
import com.jaeipgo.alimi.core.product.StockObservationService
import com.jaeipgo.alimi.core.product.StockStatus
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `stock.check.requested.v1` 을 소비해 판정하고 결과를 상품 행에 반영한다.
 *
 * ⚠️ **`concurrency` 를 올리면 안 된다.** Playwright Java 는 스레드 안전하지 않고
 * 브라우저는 파드당 하나다 (`PlaywrightConfig`). 동시성은 KEDA 가 **파드 수**로 올린다 —
 * 파티션 12 / maxReplicaCount 12 가 그러라고 있는 구조다.
 *
 * 결과 반영을 다시 Kafka 로 돌리지 않고 `core` 서비스를 직접 부르는 이유는 §10.1 이다 —
 * 이 시스템은 마이크로서비스가 아니라 **같은 MySQL 을 보는 모듈러 모놀리스**다.
 * 프로세스 경계를 넘겨야 하는 것은 **재입고 사실**뿐이고, 그건
 * `@TransactionalEventListener(AFTER_COMMIT)` 를 거쳐 `stock.restocked.v1` 로 나간다.
 */
@Component
class StockCheckListener(
    private val stockChecker: StockChecker,
    private val observations: StockObservationService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [Topics.STOCK_CHECK_REQUESTED],
        groupId = "\${spring.kafka.consumer.group-id}",
    )
    fun on(request: StockCheckRequested) {
        val expectedProductNo = request.externalProductNo.toLongOrNull()
        if (expectedProductNo == null) {
            // 등록 시점 파서가 숫자만 통과시키므로 정상 경로에서는 올 수 없다.
            // 재시도해도 같은 결과라 실패로 세지 않고 그냥 버린다.
            log.error("상품번호가 숫자가 아니다. 메시지를 버린다: {}", request)
            return
        }

        val result = stockChecker.check(request.productUrl, expectedProductNo)

        log.debug(
            "판정 결과 productId={} outcome={} 근거={}",
            request.productId, result.outcome, result.reason,
        )

        when (result.outcome) {
            CheckOutcome.IN_STOCK ->
                observations.recordObservation(
                    productId = request.productId,
                    observed = StockStatus.IN_STOCK,
                    name = result.productName,
                )

            CheckOutcome.OUT_OF_STOCK ->
                observations.recordObservation(
                    productId = request.productId,
                    observed = StockStatus.OUT_OF_STOCK,
                    name = result.productName,
                )

            // 판정 실패. fail-closed — 절대 재입고로 취급하지 않는다.
            // last_status 는 건드리지 않으므로 다음 관측이 재입고면 정상적으로 잡힌다 (§4.1).
            CheckOutcome.UNKNOWN -> observations.recordFailure(request.productId)

            // 상대 쪽 사정이다. 실패로 세지 않고 물러나기만 한다 —
            // 차단은 전 상품에 동시에 걸리므로 세면 감시 목록 전체가 SUSPENDED 로 내려간다.
            CheckOutcome.BLOCKED -> observations.recordBlocked(request.productId)

            CheckOutcome.NOT_FOUND -> observations.recordNotFound(request.productId)
        }
    }
}
