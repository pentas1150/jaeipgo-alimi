package com.jaeipgo.alimi.api.check

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 같은 상품에 대한 체크 요청이 짧은 시간에 여러 건 나가는 것을 막는다.
 *
 * ── 왜 필요한가 ──────────────────────────────────────────────────
 * **등록 중복은 이미 DB 가 막는다.** `uk_product_external` 덕분에 10명이 동시에 같은
 * 상품을 등록해도 `product` 행은 하나다. 그런데 체크 요청은 그렇지 않다:
 *
 * ```
 * 10명이 동시에 같은 상품 등록
 *   → product 행은 1개              (UNIQUE 가 보장)
 *   → 그러나 stock.check.requested.v1 이 10번 발행됨
 *   → 체커가 같은 페이지를 10번 긁음 → 차단 자초 (§7.1)
 * ```
 *
 * Kafka 키(`productId`)는 파티션을 몰아줄 뿐 **중복을 제거하지 않는다.**
 * 그래서 발행 직전에 `SETNX` 로 한 건만 통과시킨다.
 *
 * 뒤따라온 요청들은 각자 구독을 `PENDING` 으로 만들어두고, **하나의 체크 결과가
 * 그 상품의 모든 `PENDING` 을 한꺼번에 승격**시킨다. 아무도 손해 보지 않는다.
 *
 * ── 왜 TTL 만으로 푸는가 ────────────────────────────────────────
 * 체크가 끝날 때 키를 지우면 더 정확하겠지만, 그러려면 `app-checker` 가 Redis 를 알아야 한다.
 * 체커에 Redis 를 들이면 actuator 헬스가 Redis 에 묶여서 **redis 가 흔들릴 때 멀쩡한
 * 체커 파드가 죽는다.** 60초는 등록이 몰리는 순간(사람이 링크를 공유한 직후)을 덮기에
 * 충분하고, 그 뒤의 재시도는 배치가 `next_check_at` 으로 알아서 한다.
 *
 * ── 이 게이트가 막지 못하는 것 ──────────────────────────────────
 * 배치(스케줄러)가 같은 상품을 집어가는 것과는 겹칠 수 있다. 다만 배치는 `next_check_at`
 * 으로 자연히 중복이 제거되므로 최악이 2건이지 10건이 아니다. 그 정도는 받아들인다.
 */
@Component
class CheckRequestGate(
    private val redis: StringRedisTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이 상품에 대한 체크 요청을 지금 발행해도 되는지.
     *
     * @return 통과했으면 `true`. 이미 진행 중이면 `false` — 발행하지 않는다.
     */
    fun tryAcquire(productId: Long): Boolean {
        val acquired = try {
            redis.opsForValue().setIfAbsent(key(productId), MARKER, TTL) == true
        } catch (e: Exception) {
            // ⚠️ Redis 가 흔들린다고 등록을 막지 않는다.
            // 게이트는 크롤링을 아끼는 최적화이지 정확성 장치가 아니다 —
            // 통과시켜서 생기는 최악은 중복 크롤링 몇 건이고, 막아서 생기는 최악은
            // 사용자가 등록을 못 하는 것이다. 후자가 훨씬 나쁘다.
            log.warn("체크 요청 게이트를 건너뛴다 (Redis 오류): productId={} {}", productId, e.message)
            return true
        }

        if (!acquired) {
            log.debug("체크 요청이 이미 진행 중이라 발행하지 않는다: productId={}", productId)
        }
        return acquired
    }

    /**
     * 발행에 실패했을 때 되돌린다.
     *
     * 이게 없으면 Kafka 전송이 실패한 뒤 TTL(60초) 동안 재시도가 통째로 막힌다 —
     * 아무도 체크를 요청하지 못하는 창이 생긴다.
     */
    fun release(productId: Long) {
        try {
            redis.delete(key(productId))
        } catch (e: Exception) {
            log.warn("게이트 해제 실패 (TTL 로 자연 만료된다): productId={} {}", productId, e.message)
        }
    }

    private fun key(productId: Long) = "$KEY_PREFIX$productId"

    private companion object {
        const val KEY_PREFIX = "check:inflight:"

        /** 값 자체는 쓰지 않는다. 키의 존재 여부만 의미가 있다. */
        const val MARKER = "1"

        val TTL: Duration = Duration.ofSeconds(60)
    }
}
