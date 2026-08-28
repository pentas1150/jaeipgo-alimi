package com.jaeipgo.alimi.scheduler

import com.jaeipgo.alimi.core.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * 이 모듈이 단독으로 기동되는지 확인한다.
 * 모듈을 쪼갠 뒤로는 이게 "의존성이 실제로 격리됐는가"를 검증하는 역할도 한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SchedulerApplicationTests {

    @Test
    fun contextLoads() {
    }
}
