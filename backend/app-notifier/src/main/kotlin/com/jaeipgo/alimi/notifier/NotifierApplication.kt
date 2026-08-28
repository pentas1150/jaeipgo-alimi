package com.jaeipgo.alimi.notifier

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * 팬아웃 + 알림 발송 워커. KEDA 로 스케일된다.
 *
 * scanBasePackages 로 com.jaeipgo.alimi 전체를 훑는다 —
 * core 의 CoreJpaConfig / 서비스 빈을 주워오기 위해서다.
 * 이 모듈에 없는 코드는 클래스패스에도 없으므로 과하게 스캔될 위험이 없다.
 */
@SpringBootApplication(scanBasePackages = ["com.jaeipgo.alimi"])
class NotifierApplication

fun main(args: Array<String>) {
    runApplication<NotifierApplication>(*args)
}
