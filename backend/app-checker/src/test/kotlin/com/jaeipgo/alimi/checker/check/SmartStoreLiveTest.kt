package com.jaeipgo.alimi.checker.check

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * **실제 스마트스토어에 접속한다.** 기본 `test` 태스크에서는 제외된다.
 *
 * ```
 * ./gradlew :backend:app-checker:test -Pexternal
 * ```
 *
 * CI 에서 돌리면 안 된다 — 네이버는 데이터센터 IP 와 쿠키 없는 클라이언트를 차단한다.
 * 개발 PC 에서만 의미가 있다.
 *
 * 이 테스트는 두 가지를 확인한다:
 *  1. 판정이 실제로 맞는가 (품절 URL → OUT_OF_STOCK, 재고 URL → IN_STOCK)
 *  2. **헤드리스로 접근이 되는가** — 이게 이 프로젝트의 최대 미해결 과제다.
 *     BLOCKED 가 나오면 워밍업이 안 먹은 것이고, 다음 수는 Xvfb + headless=false 다.
 */
@Tag("external")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("실제 스마트스토어 접속")
class SmartStoreLiveTest {

    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    private lateinit var loader: PlaywrightSnapshotLoader
    private lateinit var checker: StockChecker

    private val soldOutUrl = "https://smartstore.naver.com/ufodripper/products/13112687319"
    private val inStockUrl = "https://smartstore.naver.com/ufodripper/products/13614118308"

    // 운영에서는 `StockCheckRequested.externalProductNo` 로 온다 — 등록 시점에
    // `core/product/SmartStoreUrl` 이 한 번 파싱한 값이다.
    private val soldOutProductNo = 13112687319L
    private val inStockProductNo = 13614118308L

    @BeforeAll
    fun setUp() {
        val properties = CheckerProperties(
            headless = System.getProperty("checker.headless", "true").toBoolean(),
        )
        playwright = Playwright.create()
        browser = playwright.chromium().launch(
            BrowserType.LaunchOptions()
                .setHeadless(properties.headless)
                .setArgs(listOf("--disable-dev-shm-usage")),
        )
        loader = PlaywrightSnapshotLoader(browser, properties, ObjectMapper())
        checker = StockChecker(loader, StockVerdictResolver(), properties)
    }

    @AfterAll
    fun tearDown() {
        runCatching { browser.close() }
        runCatching { playwright.close() }
    }

    @Test
    fun `품절 상품을 OUT_OF_STOCK 으로 판정한다`() {
        val result = checker.check(soldOutUrl, soldOutProductNo)

        assertThat(result.outcome)
            .describedAs("근거: %s", result.reason)
            .isEqualTo(CheckOutcome.OUT_OF_STOCK)
    }

    @Test
    fun `재고 있는 상품을 IN_STOCK 으로 판정한다`() {
        val result = checker.check(inStockUrl, inStockProductNo)

        assertThat(result.outcome)
            .describedAs("근거: %s", result.reason)
            .isEqualTo(CheckOutcome.IN_STOCK)
    }

    /**
     * 픽스처 갱신용. 네이버가 마크업을 바꿔 단위 테스트가 현실과 어긋날 때 이걸 돌린다.
     * 판정에 쓰는 필드만 저장되므로 개인정보가 섞이지 않는다.
     */
    @Test
    fun `픽스처를 갱신한다`() {
        val target = Path.of("src/test/resources/fixtures/captured")
        Files.createDirectories(target)

        listOf("out-of-stock" to soldOutUrl, "in-stock" to inStockUrl).forEach { (name, url) ->
            val snapshot = loader.load(url)

            // 스냅샷 전체를 저장한다. preloadedState 만 저장했더니 그게 null 일 때
            // "우리가 대체 어떤 페이지를 받았는가"를 알 길이 없었다.
            val json = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(snapshot)
            Files.writeString(target.resolve("$name.json"), json)

            println("캡처: $name ← $url")
            println("   HTTP ${snapshot.httpStatus}  title=${snapshot.title}")
            println("   최종 URL: ${snapshot.url}")
            println("   상태객체: ${if (snapshot.preloadedState == null) "없음" else "있음"}")
            println("   버튼(${snapshot.buttons.size}): ${snapshot.buttons.joinToString { "${it.text}${if (it.disabled) "[disabled]" else ""}" }}")
        }
    }
}
