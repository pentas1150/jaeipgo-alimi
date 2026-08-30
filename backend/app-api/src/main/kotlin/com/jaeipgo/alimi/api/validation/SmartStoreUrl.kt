package com.jaeipgo.alimi.api.validation

import com.jaeipgo.alimi.core.product.SmartStoreUrl as SmartStoreUrlParser
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * 네이버 스마트스토어 상품 URL 인지 검사한다.
 *
 * ```kotlin
 * data class RegisterWatchRequest(@field:SmartStoreUrl val productUrl: String)
 * ```
 *
 * 서비스에서 파싱 실패를 예외로 던지는 대신 **빈 검증**으로 두는 이유:
 * 코드베이스가 이미 `@field:NotBlank` 스타일을 쓰고 있어서, 이러면 400 응답이 다른 필드
 * 검증과 **같은 형태**(`GlobalExceptionHandler` 의 `MethodArgumentNotValidException` →
 * ProblemDetail + `errors`)로 나간다. 프론트가 응답을 두 가지로 나눠 다룰 필요가 없다.
 *
 * 검증기와 서비스가 각각 파싱해 정규식이 두 번 돌지만, 정규식 한 번이라 무시할 만한 비용이다.
 *
 * ⚠️ 이 애노테이션은 **형식만** 본다. 상품이 실제로 존재하는지는 확인하지 않는다 —
 * 그건 체크 왕복이 필요하고 4단계의 `PENDING` 게이트가 맡는다.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [SmartStoreUrlValidator::class])
annotation class SmartStoreUrl(
    val message: String = "네이버 스마트스토어 상품 URL 을 입력해주세요 " +
        "(https://smartstore.naver.com/{스토어}/products/{번호})",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class SmartStoreUrlValidator : ConstraintValidator<SmartStoreUrl, String> {

    /**
     * `null` 은 통과시킨다 — 필수 여부는 `@NotBlank` 의 책임이다.
     * 한 애노테이션이 두 가지를 검사하면 어느 쪽이 실패했는지 메시지로 구분할 수 없다.
     */
    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean =
        value == null || SmartStoreUrlParser.parseOrNull(value) != null
}
