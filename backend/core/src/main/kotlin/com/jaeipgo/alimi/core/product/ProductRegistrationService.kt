package com.jaeipgo.alimi.core.product

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * 상품을 등록하거나, 이미 있으면 그 행을 재사용한다.
 *
 * **분산 락을 쓰지 않는다.** `uk_product_external` 이 중복을 물리적으로 막고 있어서,
 * 동시 등록이면 하나는 `DataIntegrityViolationException` 을 맞고 기존 행을 읽으면 된다.
 *
 * Redis 락이 필요한 곳은 등록이 아니라 **크롤링 중복 방지**다 — 상품 행이 하나여도
 * 체크 요청은 등록 인원수만큼 나가기 때문이다. 그건 커밋 후 `CheckRequestGate` 가 맡는다.
 *
 * ── ⚠️ 왜 `@Transactional` 하나로 감싸지 않는가 ────────────────────────
 * 제약 위반이 난 **뒤에는 그 트랜잭션을 계속 쓸 수 없다.** Hibernate 세션이 오염돼
 * 다음 flush 에서 터진다:
 *
 * ```
 * org.hibernate.AssertionFailure: Entry for instance of 'Product' has a null identifier
 * (this can happen if the session is flushed after an exception occurs)
 * ```
 *
 * JPA 명세상으로도 `PersistenceException` 이 난 트랜잭션은 롤백되어야 한다.
 * 그래서 "INSERT 시도" 와 "충돌 후 재조회" 를 **서로 다른 트랜잭션**으로 갈랐다.
 * 메서드를 나누는 것만으로는 안 된다 — 같은 빈 안의 호출은 프록시를 타지 않아
 * `@Transactional` 이 걸리지 않는다. 그래서 [TransactionTemplate] 을 명시적으로 쓴다.
 *
 * (동시 등록 테스트가 없었으면 이 문제는 운영에서 처음 드러났을 것이다)
 */
@Service
class ProductRegistrationService(
    private val productRepository: ProductRepository,
    private val events: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun register(parsed: ParsedProductUrl, now: Instant = Instant.now()): ProductRegistration {
        reuseExisting(parsed, now)?.let { return it }

        return try {
            createNew(parsed, now)
        } catch (e: DataIntegrityViolationException) {
            // 다른 요청이 같은 순간에 같은 상품을 만들었다. 위 트랜잭션은 이미 롤백됐으므로
            // **새 트랜잭션**에서 다시 읽는다.
            log.debug("동시 등록 감지, 기존 행을 사용한다: {}", parsed.externalProductNo)
            reuseExisting(parsed, now) ?: throw e
        }
    }

    /**
     * **상품번호로 먼저 찾는다.** `uk_product_external` 에는 `store_id` 가 들어 있어서
     * 판매자가 스토어 슬러그를 바꾸면 같은 상품이 두 행이 되고, 구독이 갈라져
     * **알림이 두 번 나간다.** 상품번호는 네이버 전역 시퀀스로 보이므로 이쪽이 더 안정적이다.
     */
    private fun reuseExisting(parsed: ParsedProductUrl, now: Instant): ProductRegistration? =
        transactionTemplate.execute {
            val product = productRepository
                .findByPlatformAndExternalProductNo(parsed.platform, parsed.externalProductNo)
                ?: return@execute null

            if (product.storeId != parsed.storeId) {
                log.info(
                    "스토어 슬러그 변경을 흡수한다: productNo={} {} → {}",
                    product.externalProductNo, product.storeId, parsed.storeId,
                )
                product.relocate(parsed.storeId, parsed.normalizedUrl)
            }

            finish(product, created = false, now = now)
        }

    private fun createNew(parsed: ParsedProductUrl, now: Instant): ProductRegistration =
        transactionTemplate.execute {
            val product = productRepository.saveAndFlush(
                Product.register(
                    platform = parsed.platform,
                    storeId = parsed.storeId,
                    externalProductNo = parsed.externalProductNo,
                    productUrl = parsed.normalizedUrl,
                    now = now,
                ),
            )
            finish(product, created = true, now = now)
        }!!

    private fun finish(product: Product, created: Boolean, now: Instant): ProductRegistration {
        val checkRequested = product.needsFreshCheck(now)
        if (checkRequested) {
            // 커밋 후에 Kafka 로 옮겨진다 (규칙 7). 여기서 직접 보내면 롤백돼도 메시지는 남는다.
            events.publishEvent(
                StockCheckRequired(
                    productId = product.id!!,
                    externalProductNo = product.externalProductNo,
                    productUrl = product.productUrl,
                ),
            )
        }
        return ProductRegistration(product = product, created = created, checkRequested = checkRequested)
    }
}

/**
 * @param created 이번 호출이 상품 행을 만들었는지. 이미 있던 상품이면 `false`.
 * @param checkRequested 재고 확인을 요청했는지. 최근 관측이 아직 쓸 만하면 `false` 이고,
 *   그때는 구독을 곧바로 판정할 수 있다 (체크 왕복이 필요 없다).
 */
data class ProductRegistration(
    val product: Product,
    val created: Boolean,
    val checkRequested: Boolean,
)
