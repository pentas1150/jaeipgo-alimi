package com.jaeipgo.alimi.scheduler

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * 배치 스케줄러. 반드시 단일 인스턴스로 뜬다 (k8s/app/scheduler.yaml).
 *
 * scanBasePackages 로 com.jaeipgo.alimi 전체를 훑는다 —
 * core 의 CoreJpaConfig / 서비스 빈을 주워오기 위해서다.
 * 이 모듈에 없는 코드는 클래스패스에도 없으므로 과하게 스캔될 위험이 없다.
 */
@SpringBootApplication(scanBasePackages = ["com.jaeipgo.alimi"])
class SchedulerApplication

fun main(args: Array<String>) {
    runApplication<SchedulerApplication>(*args)
}
