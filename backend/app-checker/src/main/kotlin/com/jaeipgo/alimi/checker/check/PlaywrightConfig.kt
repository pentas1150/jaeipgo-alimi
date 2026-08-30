package com.jaeipgo.alimi.checker.check

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 브라우저 수명 관리. **파드 하나당 브라우저 하나다.**
 *
 * ⚠️ Playwright Java 는 스레드 안전하지 않다. 공식 문서:
 * *"all its methods as well as methods on all objects created by it are expected to be called
 * on the same thread where the Playwright object was created"*.
 *
 * 그래서 `@KafkaListener(concurrency = N)` 로 동시성을 올리면 **안 된다.**
 * 여러 리스너 스레드가 같은 `Browser` 를 만지는 순간 깨진다.
 * 동시성은 KEDA 가 **파드 수**로 올린다 — 파티션 12 / maxReplicaCount 12 가 그러라고 있는 구조다.
 *
 * `ThreadLocal<Playwright>` 로 파드당 여러 개를 띄우는 길도 있지만,
 * Chromium 이 파드당 N개가 되어 메모리 리밋(1536Mi)을 다시 잡아야 하고
 * 리밸런스로 스레드가 죽을 때 정리가 까다롭다.
 */
@Configuration
@EnableConfigurationProperties(CheckerProperties::class)
class PlaywrightConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 종료는 **Spring 에 맡긴다.** `@PreDestroy` 로 직접 닫지 않는다.
     *
     * `Playwright` 와 `Browser` 는 둘 다 `AutoCloseable` 이라 Spring 이 `close()` 를 소멸 메서드로
     * 잡아내고, 의존 역순(browser → playwright)으로 닫는다. 이 순서가 정확히 우리가 원하는 것이다.
     *
     * 직접 `@PreDestroy` 를 달면 Spring 이 이미 `Playwright` 를 닫은 뒤에 호출될 수 있고,
     * 그러면 `browser.close()` 가 죽은 드라이버 연결에 메시지를 보내다 예외를 던진다.
     * (실제로 그렇게 짰다가 종료할 때마다 스택트레이스가 찍혔다)
     */
    @Bean
    fun playwright(): Playwright = Playwright.create().also {
        log.info("Playwright 기동")
    }

    @Bean
    fun browser(playwright: Playwright, properties: CheckerProperties): Browser {
        val options = BrowserType.LaunchOptions()
            .setHeadless(properties.headless)
            // /dev/shm 이 작은 컨테이너에서 Chromium 이 랜덤하게 죽는 걸 막는다.
            // k8s 에는 emptyDir(medium: Memory)를 마운트해뒀지만, 그게 없는 환경에서도 살아남게 한다.
            .setArgs(listOf("--disable-dev-shm-usage"))

        return playwright.chromium().launch(options).also {
            log.info("Chromium 기동 (headless={})", properties.headless)
        }
    }
}
