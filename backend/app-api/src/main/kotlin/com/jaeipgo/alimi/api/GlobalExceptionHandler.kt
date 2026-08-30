package com.jaeipgo.alimi.api

import com.jaeipgo.alimi.core.user.EmailAlreadyRegisteredException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message ?: "resource not found")

    /**
     * `@Valid` 검증 실패.
     *
     * 이 핸들러가 없으면 스프링 기본 응답이 나가는데, 그건 다른 에러(위 404, 아래 409)와
     * 형태가 달라서 프론트가 응답을 두 가지로 나눠 다뤄야 한다. ProblemDetail 로 통일한다.
     * `errors` 에는 필드별 메시지를 담아 어느 입력이 문제인지 바로 보이게 한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다")
        problem.setProperty(
            "errors",
            e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") },
        )
        return problem
    }

    /**
     * 같은 이메일이 다른 공급자로 이미 가입된 경우.
     *
     * 공급자가 구글 하나뿐인 지금은 발생하지 않는다. 카카오 등을 붙이는 순간 실제로 생기며,
     * 그때 계정 연결 정책을 정해야 한다는 신호로 409 를 남긴다.
     */
    @ExceptionHandler(EmailAlreadyRegisteredException::class)
    fun handleEmailConflict(e: EmailAlreadyRegisteredException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message ?: "already registered")
}
