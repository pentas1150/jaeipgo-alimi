package com.jaeipgo.alimi.checker.check

import com.fasterxml.jackson.databind.JsonNode
import com.jaeipgo.alimi.checker.check.SmartStoreFields as F
import org.springframework.stereotype.Component

/**
 * 페이지 스냅샷 하나를 보고 재고 상태를 판정한다. **순수 함수다** — IO 도 상태도 없다.
 *
 * 판정 순서는 docs/DESIGN.md §6.2 참고. 요지는 **의심스러우면 UNKNOWN** 이다.
 * 놓친 재입고는 사용자가 아쉬워하고 끝이지만, 가짜 재입고 알림은 신뢰를 즉시 잃는다.
 */
@Component
class StockVerdictResolver {

    fun resolve(snapshot: PageSnapshot): CheckResult {
        // 1. 상품이 사라졌다. 재시도해도 의미가 없다.
        if (snapshot.httpStatus == 404) {
            return CheckResult.of(CheckOutcome.NOT_FOUND, "HTTP 404")
        }

        // 2. 차단. 상태 코드와 본문 표식을 둘 다 본다 —
        //    프록시를 타면 코드가 바뀌어도 본문은 그대로인 경우가 있다.
        blockedReason(snapshot)?.let {
            return CheckResult.of(CheckOutcome.BLOCKED, it)
        }

        // 3. 상태 객체 자체가 없다. 렌더가 덜 됐거나 페이지 구조가 바뀌었다.
        val state = snapshot.preloadedState
            ?: return CheckResult.of(CheckOutcome.UNKNOWN, "${F.STATE_VARIABLE} 없음")

        // 4. 진짜 상품 노드가 없다.
        val product = state.get(F.PRODUCT_NODE)
            ?: return CheckResult.of(CheckOutcome.UNKNOWN, "${F.PRODUCT_NODE} 노드 없음")

        // 5. 껍데기 가드. 이게 이 판정기에서 가장 중요한 줄이다.
        //
        //    같은 상태 객체의 `product` 노드는 하이드레이션 전 초기값이라 전부 null 인데
        //    `soldout` 만 false 다. 그쪽을 읽으면 품절이 "품절 아님"이 된다.
        //    `simpleProductForDetailPage` 도 SPA 이동 후에는 비어 있을 수 있다.
        //
        //    id 가 지금 보는 상품의 것인지 대조해야 둘 다 걸러진다.
        val expected = F.productNoFrom(snapshot.url)
            ?: return CheckResult.of(CheckOutcome.UNKNOWN, "URL 에서 상품번호를 못 읽음: ${snapshot.url}")

        val actual = product.get(F.FIELD_ID)?.takeIf { it.isNumber }?.asLong()
        if (actual != expected) {
            return CheckResult.of(
                CheckOutcome.UNKNOWN,
                "상품 노드가 비었거나 다른 상품이다 (기대 $expected, 실제 ${actual ?: "없음"})",
            )
        }

        val name = product.get(F.FIELD_NAME)?.takeIf { it.isTextual }?.asText()
        val stock = product.get(F.FIELD_STOCK_QUANTITY)?.takeIf { it.isNumber }?.asInt()

        // 6. 화이트리스트 판정.
        //    `!= OUTOFSTOCK` 로 짜면 SUSPENSION(판매중지) / CLOSE(판매종료) 가
        //    전부 "재고 있음"이 되어 가짜 알림이 나간다. 아는 값만 인정한다.
        val statusType = product.get(F.FIELD_STATUS_TYPE)?.takeIf { it.isTextual }?.asText()
        val outcome = when (statusType) {
            F.STATUS_ON_SALE -> CheckOutcome.IN_STOCK
            F.STATUS_OUT_OF_STOCK -> CheckOutcome.OUT_OF_STOCK
            else -> return CheckResult(
                CheckOutcome.UNKNOWN,
                "모르는 ${F.FIELD_STATUS_TYPE}: ${statusType ?: "없음"}",
                name,
                stock,
            )
        }

        // 7. 화면과 대조한다. JSON 과 버튼이 정반대면 둘 중 하나가 틀린 것이므로 판정을 포기한다.
        contradiction(outcome, snapshot.buttons)?.let {
            return CheckResult(CheckOutcome.UNKNOWN, it, name, stock)
        }

        return CheckResult(outcome, "${F.FIELD_STATUS_TYPE}=$statusType", name, stock)
    }

    private fun blockedReason(snapshot: PageSnapshot): String? {
        if (snapshot.httpStatus == 429) return "HTTP 429"
        val title = snapshot.title ?: return null
        val marker = F.BLOCK_PAGE_MARKERS.firstOrNull { title.contains(it) } ?: return null
        return "차단 페이지 표식: $marker"
    }

    /**
     * JSON 판정과 화면이 어긋나는지 본다.
     *
     * 버튼을 못 긁었으면(빈 목록) 대조하지 않는다 — 정보가 없는 것과 반대 신호는 다르다.
     * 여기서 반대 신호를 "없음"으로 오해하면 교차검증이 오히려 판정을 망친다.
     */
    private fun contradiction(outcome: CheckOutcome, buttons: List<PageSnapshot.ButtonState>): String? {
        if (buttons.isEmpty()) return null
        val texts = buttons.map { it.text }
        val saysSoldOut = texts.any { t -> F.SOLD_OUT_BUTTON_TEXTS.any { t.contains(it) } }
        val saysBuyable = buttons.any { b ->
            !b.disabled && F.BUY_BUTTON_TEXTS.any { b.text.contains(it) }
        }

        return when {
            outcome == CheckOutcome.IN_STOCK && saysSoldOut && !saysBuyable ->
                "JSON 은 판매중인데 화면은 품절이다: $texts"
            outcome == CheckOutcome.OUT_OF_STOCK && saysBuyable && !saysSoldOut ->
                "JSON 은 품절인데 화면은 구매 가능하다: $texts"
            else -> null
        }
    }
}
