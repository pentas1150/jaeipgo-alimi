package com.jaeipgo.alimi.checker.check

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 상품 페이지를 열어 [PageSnapshot] 을 만든다. **이 클래스만 IO 를 한다.**
 *
 * 판정은 여기 없다 — [StockVerdictResolver] 가 한다. 그래야 판정을 네트워크 없이 테스트한다.
 */
@Component
class PlaywrightSnapshotLoader(
    private val browser: Browser,
    private val properties: CheckerProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 워밍업으로 확보한 쿠키. 체크마다 새 컨텍스트에 주입한다.
     *
     * `BrowserContext` 를 매번 닫아야 하므로(누수 방지) 쿠키도 같이 사라진다.
     * 그렇다고 체크마다 워밍업을 하면 네이버로 가는 트래픽이 2배가 된다.
     * `storageState` 로 쿠키만 떠서 물려주면 둘 다 지킨다.
     */
    @Volatile
    private var storageState: String? = null

    fun load(url: String): PageSnapshot {
        ensureWarmedUp(url)

        return newContext().use { context ->
            val page = context.newPage()
            page.setDefaultTimeout(properties.navigationTimeoutMs)

            // networkidle 을 기다리지 않는다 — 스마트스토어는 추천/리뷰 위젯이 계속 요청을 날려
            // idle 이 오지 않거나 아주 늦게 온다. 대기는 아래에서 상태 객체를 직접 기다린다.
            val response = page.navigate(
                url,
                Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(properties.navigationTimeoutMs),
            )

            // 로드 이벤트가 아니라 **필요한 것 자체를 기다린다.**
            // HTTP 200 을 받고도 상태 객체가 아직 없는 경우를 관측했다.
            // 끝내 안 나타나면 그냥 진행한다 — 판정기가 UNKNOWN 을 내면 되고,
            // 차단 페이지였다면 title 로 BLOCKED 를 잡는다.
            runCatching {
                page.waitForFunction(
                    "() => window.${SmartStoreFields.STATE_VARIABLE} !== undefined",
                    null,
                    Page.WaitForFunctionOptions().setTimeout(properties.stateWaitTimeoutMs),
                )
            }

            @Suppress("UNCHECKED_CAST")
            val extracted = page.evaluate(EXTRACT_SCRIPT) as Map<String, Any?>

            PageSnapshot(
                url = page.url(),
                httpStatus = response?.status(),
                title = extracted["title"] as? String,
                preloadedState = extracted["state"]?.let { objectMapper.valueToTree(it) },
                buttons = readButtons(extracted["buttons"]),
            )
        }
    }

    /** 차단이 이어지면 쿠키가 상했다고 보고 다시 받는다. */
    fun invalidateWarmup() {
        storageState = null
    }

    private fun newContext(): BrowserContext {
        val options = Browser.NewContextOptions()
            .setLocale("ko-KR")
            .setTimezoneId("Asia/Seoul")
            .setUserAgent(properties.userAgent)
            .setViewportSize(1440, 900)

        storageState?.let { options.setStorageState(it) }
        return browser.newContext(options)
    }

    /**
     * 스토어 홈에 한 번 들러 쿠키를 받는다.
     *
     * 쿠키 없는 요청은 네이버가 거부하는 것으로 관측됐다 —
     * curl 도, 새 프로필 헤드리스 Chrome 도 전부 차단됐고
     * 쿠키를 가진 평소 브라우저만 통과했다. (docs/DESIGN.md §7)
     */
    private fun ensureWarmedUp(productUrl: String) {
        if (storageState != null) return

        // 설정으로 못 박지 않고 **지금 보려는 상품의 스토어 홈**에서 받는다.
        // 루트(smartstore.naver.com)는 판매자 센터로 리다이렉트되어 로그인을 요구한다.
        val warmupUrl = properties.warmupUrl.ifBlank { null }
            ?: SmartStoreFields.storeHomeFrom(productUrl)
            ?: return

        synchronized(this) {
            if (storageState != null) return

            runCatching {
                browser.newContext(
                    Browser.NewContextOptions()
                        .setLocale("ko-KR")
                        .setTimezoneId("Asia/Seoul")
                        .setUserAgent(properties.userAgent),
                ).use { context ->
                    context.newPage().navigate(
                        warmupUrl,
                        Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs),
                    )
                    storageState = context.storageState()
                }
                log.info("쿠키 워밍업 완료: {}", warmupUrl)
            }.onFailure {
                // 워밍업 실패가 체크 실패는 아니다. 쿠키 없이 시도해보고 판정기가 BLOCKED 를 내면 된다.
                log.warn("쿠키 워밍업 실패 — 쿠키 없이 진행한다: {}", it.message)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readButtons(raw: Any?): List<PageSnapshot.ButtonState> =
        (raw as? List<Map<String, Any?>>).orEmpty().mapNotNull { item ->
            val text = item["text"] as? String ?: return@mapNotNull null
            PageSnapshot.ButtonState(text, item["disabled"] as? Boolean ?: false)
        }

    private companion object {
        /**
         * 상태 객체에서 **판정에 쓰는 필드만** 브라우저 안에서 추려 온다.
         *
         * 통째로 가져오지 않는 이유가 두 가지다:
         *  1. 원본에는 판매자 실명 등 개인정보가 들어있다. 아예 우리 프로세스로 들이지 않는다.
         *  2. 수십 KB 를 매 체크마다 직렬화해 넘길 이유가 없다.
         *
         * ⚠️ `product` 노드는 일부러 읽지 않는다 — 하이드레이션 전 껍데기이며
         * `soldout` 이 false 로 초기화돼 있어 품절을 "품절 아님"으로 읽게 만든다.
         */
        const val EXTRACT_SCRIPT = """
            () => {
              const st = window.__PRELOADED_STATE__;
              let state = null;
              if (st) {
                const p = st.simpleProductForDetailPage;
                state = { simpleProductForDetailPage: p ? {
                  id: p.id ?? null,
                  productStatusType: p.productStatusType ?? null,
                  stockQuantity: p.stockQuantity ?? null,
                  name: p.name ?? null
                } : {} };
              }
              const buttons = [];
              document.querySelectorAll('button, a[role="button"]').forEach(el => {
                if (buttons.length >= 30) return;
                const text = (el.innerText || '').trim();
                if (!text || text.length > 20) return;
                if (!el.getClientRects().length) return;
                buttons.push({
                  text,
                  disabled: el.disabled === true || el.getAttribute('aria-disabled') === 'true'
                });
              });
              return { state, buttons, title: document.title };
            }
        """
    }
}
