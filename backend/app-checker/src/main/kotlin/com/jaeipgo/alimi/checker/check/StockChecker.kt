package com.jaeipgo.alimi.checker.check

import com.microsoft.playwright.PlaywrightException
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 이 모듈이 밖에 노출하는 유일한 진입점. 페이지를 열고([PlaywrightSnapshotLoader])
 * 판정한다([StockVerdictResolver]).
 *
 * 4단계에서 Kafka 리스너가 이걸 부른다. 지금은 Kafka 를 붙이지 않는다.
 */
@Service
class StockChecker(
    private val loader: PlaywrightSnapshotLoader,
    private val resolver: StockVerdictResolver,
    private val properties: CheckerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val consecutiveBlocks = AtomicInteger(0)

    /**
     * @param expectedProductNo 이 URL 이 가리켜야 하는 상품번호. 페이지 상태 JSON 의 `id` 와
     *   대조해 껍데기 노드와 SPA 이동 잔재를 걸러낸다. 등록 시점에 파싱된 값이 그대로 온다.
     */
    fun check(url: String, expectedProductNo: Long): CheckResult {
        val result = try {
            resolver.resolve(loader.load(url), expectedProductNo)
        } catch (e: PlaywrightException) {
            // 타임아웃/네트워크 오류. 판정 실패는 UNKNOWN 이다 — 절대 재입고로 취급하지 않는다.
            log.warn("페이지 로딩 실패: {} ({})", url, e.message)
            CheckResult.of(CheckOutcome.UNKNOWN, "로딩 실패: ${e.message?.lineSequence()?.first()}")
        }

        trackBlocking(result.outcome)

        log.debug("체크 완료 url={} 결과={} 근거={}", url, result.outcome, result.reason)
        return result
    }

    /**
     * 차단이 이어지면 쿠키가 상했다고 보고 다음 체크에서 다시 받게 한다.
     *
     * 매번 다시 받지 않는 이유는 워밍업도 네이버로 가는 요청이기 때문이다 —
     * 차단당한 상태에서 워밍업을 반복하면 상대 입장에서는 더 두드리는 꼴이 된다.
     */
    private fun trackBlocking(outcome: CheckOutcome) {
        if (outcome != CheckOutcome.BLOCKED) {
            consecutiveBlocks.set(0)
            return
        }

        val blocks = consecutiveBlocks.incrementAndGet()
        if (blocks >= properties.rewarmAfterConsecutiveBlocks) {
            log.warn("차단 {}회 연속 — 쿠키를 다시 받는다", blocks)
            loader.invalidateWarmup()
            consecutiveBlocks.set(0)
        }
    }
}
