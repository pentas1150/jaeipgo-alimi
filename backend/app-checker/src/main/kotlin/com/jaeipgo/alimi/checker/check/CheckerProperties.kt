package com.jaeipgo.alimi.checker.check

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 체커 설정. 기본값은 여기에 두고, application.yml 에서 덮어쓴다.
 */
@ConfigurationProperties(prefix = "alimi.checker")
data class CheckerProperties(
    /**
     * 헤드리스로 띄울지.
     *
     * `false` 로 바꾸려면 컨테이너에 Xvfb 가 있어야 한다
     * (`xvfb-run -a java -jar app.jar`). Playwright 공식 이미지에는 들어있다.
     * 네이버가 헤드리스를 감지하는 것으로 확인되면 이 값을 뒤집는다.
     */
    val headless: Boolean = true,

    /**
     * 페이지 로딩 타임아웃(ms).
     *
     * ⚠️ 이 값이 `spring.lifecycle.timeout-per-shutdown-phase`(60s)보다 작아야 하고,
     * 그게 다시 k8s `terminationGracePeriodSeconds`(120s)보다 작아야 한다.
     * 어긋나면 체크 도중 SIGKILL 이 나고 오프셋이 커밋되지 않아 브라우저 작업이 통째로 낭비된다.
     */
    val navigationTimeoutMs: Double = 30_000.0,

    /**
     * `__PRELOADED_STATE__` 가 나타나기를 기다리는 시간(ms).
     *
     * HTTP 200 을 받고도 상태 객체가 아직 없는 경우를 실측했다. 로드 이벤트는
     * "필요한 게 준비됐다"는 뜻이 아니므로 필요한 것 자체를 기다린다.
     * 초과해도 실패로 보지 않는다 — 판정기가 UNKNOWN 으로 떨어뜨린다.
     */
    val stateWaitTimeoutMs: Double = 10_000.0,

    /**
     * 쿠키 워밍업에 방문할 URL. **비워두면 상품 URL 에서 스토어 홈을 유도한다** (권장).
     *
     * 네이버는 쿠키 없는 요청을 거부한다. 먼저 스토어를 한 번 들러 쿠키를 확보하고
     * 이후 체크가 그 쿠키를 물려받는다.
     *
     * ⚠️ 여기에 `https://smartstore.naver.com` 을 넣으면 안 된다 — 판매자 센터로
     * 리다이렉트되어 로그인을 요구하고, 그 쿠키로는 상품 페이지가 전부 로그인 페이지가 된다.
     */
    val warmupUrl: String = "",

    /** 워밍업 쿠키를 다시 받아올 조건. BLOCKED 가 이 횟수만큼 연속되면 재워밍업한다. */
    val rewarmAfterConsecutiveBlocks: Int = 3,

    /** 실제 Chrome 과 같은 UA. 기본값은 컨테이너의 Chromium 버전에 맞춰 갱신할 것. */
    val userAgent: String =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
)
