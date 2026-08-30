package com.jaeipgo.alimi.api.check

import com.jaeipgo.alimi.core.RedisTestcontainersConfiguration
import com.jaeipgo.alimi.core.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class, RedisTestcontainersConfiguration::class)
@SpringBootTest
class CheckRequestGateTest {

    @Autowired private lateinit var gate: CheckRequestGate
    @Autowired private lateinit var redis: StringRedisTemplate

    @BeforeEach
    fun clean() {
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
    }

    @Test
    fun `처음 요청만 통과시킨다`() {
        assertThat(gate.tryAcquire(1L)).isTrue()
        assertThat(gate.tryAcquire(1L)).isFalse()
    }

    @Test
    fun `상품마다 따로 센다`() {
        assertThat(gate.tryAcquire(1L)).isTrue()
        assertThat(gate.tryAcquire(2L)).isTrue()
    }

    @Test
    fun `해제하면 다시 통과할 수 있다`() {
        // 발행이 실패했을 때 이걸 안 하면 TTL(60초) 동안 아무도 체크를 요청하지 못한다.
        gate.tryAcquire(1L)

        gate.release(1L)

        assertThat(gate.tryAcquire(1L)).isTrue()
    }

    @Test
    fun `TTL 이 붙어 있어 영영 막히지 않는다`() {
        gate.tryAcquire(1L)

        val ttl = redis.getExpire("check:inflight:1")

        assertThat(ttl).isPositive()
    }

    @Test
    fun `동시에 몰려도 하나만 통과한다`() {
        // 사람이 링크를 공유한 직후가 정확히 이 상황이다.
        val threads = 16
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)

        val tasks = (1..threads).map {
            Callable {
                barrier.await(10, TimeUnit.SECONDS)
                gate.tryAcquire(42L)
            }
        }

        val results = try {
            pool.invokeAll(tasks).map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(results.count { it }).isEqualTo(1)
    }
}
