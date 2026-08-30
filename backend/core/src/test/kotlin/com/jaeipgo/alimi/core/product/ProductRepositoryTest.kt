package com.jaeipgo.alimi.core.product

import com.jaeipgo.alimi.core.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant

/**
 * 실제 MySQL 위에서 제약과 매핑을 검증한다.
 * `ddl-auto=validate` 라 이 테스트가 도는 것만으로 V4 와 엔티티의 정합도 함께 확인된다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ProductRepositoryTest {

    @Autowired private lateinit var productRepository: ProductRepository

    private val t0: Instant = Instant.parse("2026-08-30T00:00:00Z")

    @BeforeEach
    fun clean() = productRepository.deleteAll()

    private fun product(
        storeId: String = "ufodripper",
        productNo: String = "13112687319",
    ) = Product.register(
        platform = Platform.NAVER_SMARTSTORE,
        storeId = storeId,
        externalProductNo = productNo,
        productUrl = "https://smartstore.naver.com/$storeId/products/$productNo",
        now = t0,
    )

    @Nested
    @DisplayName("중복 등록")
    inner class Duplicates {

        @Test
        fun `같은 상품은 두 번 저장되지 않는다`() {
            // 분산 락이 필요 없는 이유가 이것이다. 동시 등록이면 하나는 여기서 걸리고,
            // 진 쪽은 기존 행을 읽으면 된다.
            productRepository.saveAndFlush(product())

            assertThatThrownBy { productRepository.saveAndFlush(product()) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
        }

        @Test
        fun `같은 스토어의 다른 상품은 별개 행이다`() {
            productRepository.saveAndFlush(product(productNo = "13112687319"))
            productRepository.saveAndFlush(product(productNo = "13614118308"))

            assertThat(productRepository.count()).isEqualTo(2)
        }

        @Test
        fun `스토어가 바뀌면 UNIQUE 만으로는 못 막는다`() {
            // uk_product_external 에 store_id 가 들어 있어서 새 행이 만들어진다.
            // 그래서 등록할 때 상품번호로 먼저 조회하는 방어가 필요하다.
            productRepository.saveAndFlush(product(storeId = "ufodripper"))
            productRepository.saveAndFlush(product(storeId = "ufodripper2"))

            assertThat(productRepository.count()).isEqualTo(2)
        }
    }

    @Nested
    @DisplayName("조회")
    inner class Lookup {

        @Test
        fun `상품번호만으로 찾을 수 있다`() {
            // 스토어 슬러그 변경을 흡수하려면 이 조회가 있어야 한다.
            productRepository.saveAndFlush(product(storeId = "ufodripper"))

            val found = productRepository.findByPlatformAndExternalProductNo(
                Platform.NAVER_SMARTSTORE,
                "13112687319",
            )

            assertThat(found).isNotNull()
            assertThat(found!!.storeId).isEqualTo("ufodripper")
        }

        @Test
        fun `UNIQUE 키 그대로도 찾을 수 있다`() {
            productRepository.saveAndFlush(product())

            val found = productRepository.findByPlatformAndStoreIdAndExternalProductNo(
                Platform.NAVER_SMARTSTORE,
                "ufodripper",
                "13112687319",
            )

            assertThat(found).isNotNull()
        }
    }

    @Nested
    @DisplayName("영속화")
    inner class Persistence {

        @Test
        fun `두 상태 축이 각각 저장된다`() {
            val saved = productRepository.saveAndFlush(product())
            saved.applyObservation(StockStatus.OUT_OF_STOCK, t0)
            repeat(Product.SUSPEND_AFTER_FAILURES) { saved.recordCheckFailure(t0) }
            // 테스트에 @Transactional 이 없어 saveAndFlush 뒤의 엔티티는 detached 다.
            // 변경 감지가 돌지 않으므로 명시적으로 다시 저장한다.
            productRepository.saveAndFlush(saved)

            val reloaded = productRepository.findById(saved.id!!).orElseThrow()

            assertThat(reloaded.lastStatus).isEqualTo(StockStatus.OUT_OF_STOCK)
            assertThat(reloaded.monitoringStatus).isEqualTo(MonitoringStatus.SUSPENDED)
        }

        @Test
        fun `기본값은 UNKNOWN + ACTIVE 다`() {
            val saved = productRepository.saveAndFlush(product())

            val reloaded = productRepository.findById(saved.id!!).orElseThrow()

            assertThat(reloaded.lastStatus).isEqualTo(StockStatus.UNKNOWN)
            assertThat(reloaded.monitoringStatus).isEqualTo(MonitoringStatus.ACTIVE)
            assertThat(reloaded.checkIntervalSec).isEqualTo(Product.DEFAULT_CHECK_INTERVAL_SEC)
            assertThat(reloaded.lastCheckedAt).isNull()
        }
    }
}
