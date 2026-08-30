package com.jaeipgo.alimi.core.product

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 신뢰 경계의 파서라 거부 케이스가 수용 케이스만큼 중요하다.
 */
class SmartStoreUrlTest {

    private val canonical = "https://smartstore.naver.com/ufodripper/products/13112687319"

    @Nested
    @DisplayName("정규화")
    inner class Normalization {

        @Test
        fun `표준 URL 을 그대로 파싱한다`() {
            val parsed = SmartStoreUrl.parse(canonical)

            assertThat(parsed.platform).isEqualTo(Platform.NAVER_SMARTSTORE)
            assertThat(parsed.storeId).isEqualTo("ufodripper")
            assertThat(parsed.externalProductNo).isEqualTo("13112687319")
            assertThat(parsed.normalizedUrl).isEqualTo(canonical)
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                // 유입 추적 파라미터. 붙었다고 다른 상품이 되면 안 된다.
                "https://smartstore.naver.com/ufodripper/products/13112687319?NaPm=ct%3Dabc%7Cci%3D123",
                "https://smartstore.naver.com/ufodripper/products/13112687319?n_media=27758&n_query=x",
                "https://smartstore.naver.com/ufodripper/products/13112687319?utm_source=blog&utm_medium=cpc",
                // 프래그먼트
                "https://smartstore.naver.com/ufodripper/products/13112687319#reviews",
                // 모바일 — 핸드폰에서 복사하는 가장 흔한 경로다
                "https://m.smartstore.naver.com/ufodripper/products/13112687319",
                // 스킴/대소문자/공백/슬래시
                "http://smartstore.naver.com/ufodripper/products/13112687319",
                "https://SmartStore.Naver.COM/ufodripper/products/13112687319",
                "  https://smartstore.naver.com/ufodripper/products/13112687319  ",
                "https://smartstore.naver.com/ufodripper/products/13112687319/",
                // 스킴 없이 붙여넣은 경우
                "smartstore.naver.com/ufodripper/products/13112687319",
            ],
        )
        fun `여러 형태가 같은 표준 URL 로 모인다`(raw: String) {
            assertThat(SmartStoreUrl.parse(raw).normalizedUrl).isEqualTo(canonical)
        }

        @Test
        fun `상품번호의 앞자리 0 을 없앤다`() {
            // /products/007 과 /products/7 이 서로 다른 행이 되면
            // 같은 상품에 알림이 두 번 나간다.
            val parsed = SmartStoreUrl.parse("https://smartstore.naver.com/store1/products/007")

            assertThat(parsed.externalProductNo).isEqualTo("7")
            assertThat(parsed.normalizedUrl).isEqualTo("https://smartstore.naver.com/store1/products/7")
        }
    }

    @Nested
    @DisplayName("지원하지 않는 곳은 이유를 알려준다")
    inner class KnownButUnsupported {

        @Test
        fun `브랜드스토어`() {
            // 같은 상품번호 체계지만 페이지 구조가 검증되지 않았다.
            // 구조가 다르면 판정이 전부 UNKNOWN 으로 떨어져 조용히 동작하지 않는다.
            assertThatThrownBy { SmartStoreUrl.parse("https://brand.naver.com/nike/products/123") }
                .hasMessageContaining("브랜드스토어")
        }

        @Test
        fun `공유 단축 링크`() {
            assertThatThrownBy { SmartStoreUrl.parse("https://naver.me/xAbCdEf") }
                .hasMessageContaining("주소창")
        }

        @Test
        fun `가격비교 페이지`() {
            assertThatThrownBy { SmartStoreUrl.parse("https://shopping.naver.com/catalog/12345") }
                .hasMessageContaining("상품 페이지")
        }
    }

    @Nested
    @DisplayName("거부")
    inner class Rejection {

        @ParameterizedTest
        @ValueSource(
            strings = [
                // 남의 호스트. 체커의 느슨한 정규식 `/products/(\d+)` 은 이걸 통과시킨다 —
                // 그래서 신뢰 경계에는 그쪽을 쓰면 안 된다.
                "https://evil.com/ufodripper/products/13112687319",
                "https://smartstore.naver.com.evil.com/x/products/1",
                // 상품 페이지가 아님
                "https://smartstore.naver.com/ufodripper",
                "https://smartstore.naver.com/ufodripper/products/",
                "https://smartstore.naver.com/ufodripper/products/abc",
                "https://smartstore.naver.com/products/13112687319",
                // 경로가 더 붙은 경우
                "https://smartstore.naver.com/ufodripper/products/13112687319/detail",
                // 스킴
                "javascript:alert(1)",
                "file:///etc/passwd",
                "data:text/html,<script>alert(1)</script>",
                // 사용자 정보를 낀 형태 — 정상적인 붙여넣기에서는 나올 수 없다
                "https://evil.com@smartstore.naver.com/ufodripper/products/1",
                // 빈 값
                "",
                "   ",
            ],
        )
        fun `지원하지 않는 입력은 거부한다`(raw: String) {
            assertThat(SmartStoreUrl.parseOrNull(raw)).isNull()
        }

        @Test
        fun `null 도 거부한다`() {
            assertThat(SmartStoreUrl.parseOrNull(null)).isNull()
        }

        @Test
        fun `상품번호가 Long 범위를 넘으면 거부한다`() {
            // 20자리. 숫자 표준형으로 만들 때 오버플로하면 엉뚱한 번호가 된다.
            assertThat(SmartStoreUrl.parseOrNull("https://smartstore.naver.com/s/products/12345678901234567890"))
                .isNull()
        }

        @Test
        fun `거부 메시지에 올바른 형식을 담는다`() {
            assertThatThrownBy { SmartStoreUrl.parse("https://evil.com/x/products/1") }
                .hasMessageContaining("smartstore.naver.com/{스토어}/products/{번호}")
        }
    }
}
