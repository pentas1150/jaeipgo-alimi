package com.jaeipgo.alimi.core.product

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 체크 결과를 상품 행에 반영한다. **체커가 판정을 끝낸 뒤 호출하는 진입점이다.**
 *
 * 체커가 이 서비스를 직접 부르는 것이 이상해 보일 수 있지만, 이 시스템은
 * 마이크로서비스가 아니라 **같은 MySQL 을 보는 모듈러 모놀리스**다 (§10.1).
 * 상태 반영을 다시 Kafka 한 바퀴 돌리면 왕복만 늘고 얻는 게 없다.
 * 프로세스 경계를 넘겨야 하는 것은 **재입고 사실**뿐이고, 그건 이벤트로 나간다.
 *
 * 메서드가 판정 결과 enum 하나를 받지 않고 셋으로 갈린 이유:
 * 체커의 `CheckOutcome`(IN_STOCK/OUT_OF_STOCK/UNKNOWN/BLOCKED/NOT_FOUND)을 core 가
 * 알 필요가 없다. 도메인이 구분해야 하는 것은 **관측했다 / 못 했다 / 사라졌다** 셋뿐이고,
 * BLOCKED 와 UNKNOWN 을 하나로 접는 판단은 호출하는 쪽(체커)에 남는다.
 */
@Service
class StockObservationService(
    private val productRepository: ProductRepository,
    private val events: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 재고 상태를 관측했다.
     *
     * `OUT_OF_STOCK → IN_STOCK` 이면 [ProductRestocked] 를 발행한다. 그 전이 하나뿐이다 (규칙 1).
     *
     * @return 재입고였는지
     */
    @Transactional
    fun recordObservation(
        productId: Long,
        observed: StockStatus,
        name: String? = null,
        thumbnailUrl: String? = null,
        now: Instant = Instant.now(),
    ): Boolean {
        val product = load(productId)
        val previous = product.lastStatus

        product.describe(name, thumbnailUrl)
        val restocked = product.applyObservation(observed, now)

        if (restocked) {
            log.info("재입고 관측: productId={} {} → {}", productId, previous, observed)
            // 커밋 후에 stock.restocked.v1 로 나간다 (규칙 7).
            events.publishEvent(
                ProductRestocked(productId = productId, previousStatus = previous, detectedAt = now),
            )
        }
        return restocked
    }

    /**
     * 판정에 실패했다 (차단, 타임아웃, 알 수 없는 상태값 등).
     *
     * ⚠️ `last_status` 는 건드리지 않는다. 실패는 새로운 사실을 알려주지 않으므로
     * 마지막으로 관측된 사실을 지울 이유가 없고, 지우면 실패 한 번이 곧바로
     * 재입고 누락이 된다 (§4.1).
     *
     * @return 연속 실패가 임계값에 이르러 감시를 중단했는지
     */
    @Transactional
    fun recordFailure(productId: Long, now: Instant = Instant.now()): Boolean {
        val product = load(productId)
        val suspended = product.recordCheckFailure(now)
        if (suspended) {
            log.warn(
                "연속 판정 실패로 감시를 중단한다: productId={} failures={}",
                productId, product.consecutiveFailures,
            )
        }
        return suspended
    }

    /**
     * 네이버가 요청을 거부했다. **실패로 세지 않고 물러나기만 한다.**
     *
     * 차단은 전 상품에 동시에 걸리므로 실패로 세면 감시 목록 전체가 `SUSPENDED` 로
     * 내려가고, 정작 차단이 풀렸을 때 아무도 감시하고 있지 않게 된다.
     */
    @Transactional
    fun recordBlocked(productId: Long, now: Instant = Instant.now()) {
        val product = load(productId)
        product.recordBlocked(now)
        log.info("차단당해 물러난다: productId={} 다음 체크={}", productId, product.nextCheckAt)
    }

    /** 상품 페이지가 사라졌다. 되살아나지 않으므로 감시를 영구히 끝낸다. */
    @Transactional
    fun recordNotFound(productId: Long, now: Instant = Instant.now()) {
        val product = load(productId)
        product.delist(now)
        log.info("상품 페이지 없음, 감시를 종료한다: productId={}", productId)
    }

    private fun load(productId: Long): Product =
        productRepository.findById(productId).orElseThrow {
            NoSuchElementException("product not found: $productId")
        }
}
