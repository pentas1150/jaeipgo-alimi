package com.jaeipgo.alimi.checker.check

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 판정 규칙 단위 테스트. **네트워크도 브라우저도 쓰지 않는다.**
 *
 * 픽스처는 실제 스마트스토어 페이지에서 관측한 값이다(개인정보 필드는 제외).
 * 네이버는 브라우저가 아닌 클라이언트를 전부 차단하므로 CI 가 실제 페이지를 열 수 없다 —
 * 그래서 판정을 순수 함수로 떼어놓았다.
 */
class StockVerdictResolverTest {

    private val resolver = StockVerdictResolver()
    private val mapper = ObjectMapper()

    private val soldOutUrl = "https://smartstore.naver.com/ufodripper/products/13112687319"
    private val inStockUrl = "https://smartstore.naver.com/ufodripper/products/13614118308"

    private fun fixture(name: String) =
        mapper.readTree(javaClass.getResourceAsStream("/fixtures/$name.json")!!)

    private fun snapshot(
        url: String,
        fixture: String? = null,
        httpStatus: Int? = 200,
        title: String? = "상품 페이지",
        buttons: List<PageSnapshot.ButtonState> = emptyList(),
    ) = PageSnapshot(url, httpStatus, title, fixture?.let(::fixture), buttons)

    @Nested
    @DisplayName("정상 판정")
    inner class Normal {

        @Test
        fun `재고가 있으면 IN_STOCK`() {
            val result = resolver.resolve(snapshot(inStockUrl, "in-stock"))

            assertThat(result.outcome).isEqualTo(CheckOutcome.IN_STOCK)
            assertThat(result.productName).isEqualTo("재고 있는 상품")
        }

        @Test
        fun `품절이면 OUT_OF_STOCK`() {
            val result = resolver.resolve(snapshot(soldOutUrl, "out-of-stock"))

            assertThat(result.outcome).isEqualTo(CheckOutcome.OUT_OF_STOCK)
            assertThat(result.stockQuantity).isZero()
        }

        @Test
        fun `쿼리스트링이 붙어도 상품번호를 읽는다`() {
            val url = "$inStockUrl?NaPm=ct%3Dmtdv67xk%7Cci%3Dshopn"

            assertThat(resolver.resolve(snapshot(url, "in-stock")).outcome)
                .isEqualTo(CheckOutcome.IN_STOCK)
        }
    }

    @Nested
    @DisplayName("fail-closed — 판정이 서면 안 되는 경우")
    inner class FailClosed {

        /**
         * 이 테스트가 이 파일에서 가장 중요하다.
         *
         * 하이드레이션 전 `product` 노드는 전부 null 인데 `soldout` 만 false 다.
         * 그쪽을 읽는 구현이라면 여기서 IN_STOCK 이 나오고, 실제로는 품절인 상품에
         * 재입고 알림이 나간다.
         */
        @Test
        fun `껍데기 노드만 있으면 UNKNOWN — soldout=false 에 속지 않는다`() {
            val result = resolver.resolve(snapshot(soldOutUrl, "shell-only"))

            assertThat(result.outcome).isEqualTo(CheckOutcome.UNKNOWN)
            assertThat(result.outcome).isNotEqualTo(CheckOutcome.IN_STOCK)
        }

        @Test
        fun `다른 상품의 데이터가 남아있으면 UNKNOWN`() {
            val result = resolver.resolve(snapshot(soldOutUrl, "other-product"))

            assertThat(result.outcome).isEqualTo(CheckOutcome.UNKNOWN)
            assertThat(result.reason).contains("13112687319")
        }

        /** 화이트리스트가 진짜 화이트리스트인지 본다. `!= OUTOFSTOCK` 구현이면 여기서 IN_STOCK 이 난다. */
        @Test
        fun `판매중지는 IN_STOCK 이 아니라 UNKNOWN`() {
            val result = resolver.resolve(snapshot(soldOutUrl, "suspended"))

            assertThat(result.outcome).isEqualTo(CheckOutcome.UNKNOWN)
            assertThat(result.reason).contains("SUSPENSION")
        }

        @Test
        fun `상태 객체가 없으면 UNKNOWN`() {
            assertThat(resolver.resolve(snapshot(soldOutUrl, fixture = null)).outcome)
                .isEqualTo(CheckOutcome.UNKNOWN)
        }

        @Test
        fun `상품 URL 이 아니면 UNKNOWN`() {
            val result = resolver.resolve(snapshot("https://smartstore.naver.com/ufodripper", "in-stock"))

            assertThat(result.outcome).isEqualTo(CheckOutcome.UNKNOWN)
        }
    }

    @Nested
    @DisplayName("차단 — UNKNOWN 과 구분되어야 한다")
    inner class Blocked {

        /** 실측한 차단 페이지의 title 이다. */
        private val blockTitle = "[에러] 에러페이지 - 시스템오류"

        @Test
        fun `HTTP 429 는 BLOCKED`() {
            val result = resolver.resolve(snapshot(soldOutUrl, httpStatus = 429, title = blockTitle))

            assertThat(result.outcome).isEqualTo(CheckOutcome.BLOCKED)
        }

        /**
         * 차단을 UNKNOWN 으로 처리하면 consecutive_failures 가 올라간다.
         * 차단은 전 상품에 동시에 걸리므로 감시 목록 전체가 SUSPENDED 로 내려간다.
         */
        @Test
        fun `상태 코드가 200 이어도 차단 페이지면 BLOCKED`() {
            val result = resolver.resolve(snapshot(soldOutUrl, httpStatus = 200, title = blockTitle))

            assertThat(result.outcome).isEqualTo(CheckOutcome.BLOCKED)
            assertThat(result.outcome).isNotEqualTo(CheckOutcome.UNKNOWN)
        }

        @Test
        fun `404 는 NOT_FOUND`() {
            assertThat(resolver.resolve(snapshot(soldOutUrl, httpStatus = 404)).outcome)
                .isEqualTo(CheckOutcome.NOT_FOUND)
        }
    }

    @Nested
    @DisplayName("화면 교차검증")
    inner class CrossCheck {

        private fun buttons(vararg pairs: Pair<String, Boolean>) =
            pairs.map { PageSnapshot.ButtonState(it.first, it.second) }

        @Test
        fun `JSON 과 화면이 맞으면 그대로 간다`() {
            val result = resolver.resolve(
                snapshot(inStockUrl, "in-stock", buttons = buttons("구매하기" to false)),
            )

            assertThat(result.outcome).isEqualTo(CheckOutcome.IN_STOCK)
        }

        @Test
        fun `JSON 은 판매중인데 화면이 품절이면 UNKNOWN`() {
            val result = resolver.resolve(
                snapshot(inStockUrl, "in-stock", buttons = buttons("품절" to true)),
            )

            assertThat(result.outcome).isEqualTo(CheckOutcome.UNKNOWN)
        }

        @Test
        fun `JSON 은 품절인데 화면이 구매 가능하면 UNKNOWN`() {
            val result = resolver.resolve(
                snapshot(soldOutUrl, "out-of-stock", buttons = buttons("구매하기" to false)),
            )

            assertThat(result.outcome).isEqualTo(CheckOutcome.UNKNOWN)
        }

        /** 장바구니는 품절 상품에서도 살아있다(실측). 반대 신호로 쓰면 안 된다. */
        @Test
        fun `장바구니 버튼은 판정을 뒤집지 않는다`() {
            val result = resolver.resolve(
                snapshot(soldOutUrl, "out-of-stock", buttons = buttons("장바구니" to false)),
            )

            assertThat(result.outcome).isEqualTo(CheckOutcome.OUT_OF_STOCK)
        }

        /** 버튼을 못 긁은 것과 "품절이라고 쓰여 있는 것"은 다르다. */
        @Test
        fun `버튼을 못 긁었으면 교차검증하지 않는다`() {
            val result = resolver.resolve(snapshot(inStockUrl, "in-stock", buttons = emptyList()))

            assertThat(result.outcome).isEqualTo(CheckOutcome.IN_STOCK)
        }
    }
}
