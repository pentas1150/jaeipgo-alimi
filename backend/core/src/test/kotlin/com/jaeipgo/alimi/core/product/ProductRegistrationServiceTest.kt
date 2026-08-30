package com.jaeipgo.alimi.core.product

import com.jaeipgo.alimi.core.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ProductRegistrationServiceTest {

    @Autowired private lateinit var service: ProductRegistrationService
    @Autowired private lateinit var productRepository: ProductRepository

    private val t0: Instant = Instant.parse("2026-08-30T00:00:00Z")

    @BeforeEach
    fun clean() = productRepository.deleteAll()

    private fun parsed(storeId: String = "ufodripper", productNo: String = "13112687319") =
        SmartStoreUrl.parse("https://smartstore.naver.com/$storeId/products/$productNo")

    @Nested
    @DisplayName("등록")
    inner class Register {

        @Test
        fun `처음 등록하면 상품 행이 생기고 체크를 요청한다`() {
            val result = service.register(parsed(), t0)

            assertThat(result.created).isTrue()
            assertThat(result.checkRequested).isTrue()
            assertThat(result.product.lastStatus).isEqualTo(StockStatus.UNKNOWN)
            assertThat(productRepository.count()).isEqualTo(1)
        }

        @Test
        fun `이미 있는 상품이면 행을 재사용한다`() {
            val first = service.register(parsed(), t0)

            val second = service.register(parsed(), t0)

            assertThat(second.created).isFalse()
            assertThat(second.product.id).isEqualTo(first.product.id)
            assertThat(productRepository.count()).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("최근 관측이 있으면 다시 긁지 않는다")
    inner class ReuseRecentObservation {

        @Test
        fun `방금 관측한 상품은 체크를 요청하지 않는다`() {
            // 페이지 요청이 이 서비스에서 가장 귀한 자원이다 (§7.1 차단).
            // 인기 상품일수록 여러 명이 몰려 등록하는데, 그때마다 긁으면
            // 가장 나쁜 순간에 가장 많이 긁게 된다.
            val registered = service.register(parsed(), t0)
            registered.product.applyObservation(StockStatus.OUT_OF_STOCK, t0)
            productRepository.saveAndFlush(registered.product)

            val again = service.register(parsed(), t0.plusSeconds(60))

            assertThat(again.checkRequested).isFalse()
        }

        @Test
        fun `관측이 낡았으면 다시 요청한다`() {
            val registered = service.register(parsed(), t0)
            registered.product.applyObservation(StockStatus.OUT_OF_STOCK, t0)
            productRepository.saveAndFlush(registered.product)

            val again = service.register(parsed(), t0.plusSeconds(301))

            assertThat(again.checkRequested).isTrue()
        }
    }

    @Nested
    @DisplayName("스토어 슬러그 변경")
    inner class StoreRelocation {

        @Test
        fun `같은 상품번호면 새 행을 만들지 않고 흡수한다`() {
            // uk_product_external 에 store_id 가 들어 있어 그대로 INSERT 하면
            // 같은 상품이 두 행이 되고 구독이 갈라져 알림이 두 번 나간다.
            val first = service.register(parsed(storeId = "ufodripper"), t0)

            val moved = service.register(parsed(storeId = "ufodripper2"), t0)

            assertThat(moved.product.id).isEqualTo(first.product.id)
            assertThat(moved.created).isFalse()
            assertThat(productRepository.count()).isEqualTo(1)
            assertThat(productRepository.findById(first.product.id!!).orElseThrow().storeId)
                .isEqualTo("ufodripper2")
        }
    }

    @Nested
    @DisplayName("동시 등록")
    inner class Concurrency {

        @Test
        fun `여러 명이 같은 상품을 동시에 등록해도 행은 하나다`() {
            val threads = 8
            val barrier = CyclicBarrier(threads)
            val pool = Executors.newFixedThreadPool(threads)

            val tasks = (1..threads).map {
                Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    service.register(parsed(), t0).product.id!!
                }
            }

            val ids = try {
                pool.invokeAll(tasks).map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            // 분산 락 없이 UNIQUE 제약만으로 막힌다.
            assertThat(ids.distinct()).hasSize(1)
            assertThat(productRepository.count()).isEqualTo(1)
        }
    }
}
