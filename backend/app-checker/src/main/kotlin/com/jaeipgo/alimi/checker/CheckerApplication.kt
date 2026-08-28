package com.jaeipgo.alimi.checker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Playwright 재고 판정 워커. KEDA 로 스케일된다.
 *
 * scanBasePackages 로 com.jaeipgo.alimi 전체를 훑는다 —
 * core 의 CoreJpaConfig / 서비스 빈을 주워오기 위해서다.
 * 이 모듈에 없는 코드는 클래스패스에도 없으므로 과하게 스캔될 위험이 없다.
 */
@SpringBootApplication(scanBasePackages = ["com.jaeipgo.alimi"])
class CheckerApplication

fun main(args: Array<String>) {
    runApplication<CheckerApplication>(*args)
}
