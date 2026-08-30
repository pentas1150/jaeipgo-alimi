package com.jaeipgo.alimi.api.validation

import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.constraints.NotBlank
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 애노테이션과 검증기의 배선을 확인한다.
 * 파싱 규칙 자체는 `SmartStoreUrlTest`(core)가 본다.
 */
class SmartStoreUrlValidatorTest {

    private val validator: Validator =
        Validation.buildDefaultValidatorFactory().validator

    private data class Request(
        @field:NotBlank
        @field:SmartStoreUrl
        val productUrl: String?,
    )

    @Test
    fun `올바른 URL 은 위반이 없다`() {
        val violations = validator.validate(
            Request("https://smartstore.naver.com/ufodripper/products/13112687319"),
        )

        assertThat(violations).isEmpty()
    }

    @Test
    fun `형식이 아니면 안내 문구가 담긴 위반이 생긴다`() {
        val violations = validator.validate(Request("https://evil.com/x/products/1"))

        assertThat(violations).hasSize(1)
        assertThat(violations.first().message).contains("smartstore.naver.com/{스토어}/products/{번호}")
    }

    @Test
    fun `null 은 NotBlank 만 잡는다`() {
        // 한 애노테이션이 두 가지를 검사하면 어느 쪽이 실패했는지 메시지로 구분할 수 없다.
        // 필수 여부는 @NotBlank 의 책임으로 남긴다.
        val violations = validator.validate(Request(null))

        assertThat(violations).hasSize(1)
        assertThat(violations.first().messageTemplate).contains("NotBlank")
    }
}
