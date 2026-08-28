package com.jaeipgo.alimi.core

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * 엔티티와 리포지토리가 app-* 모듈의 패키지 바깥(여기)에 있으므로,
 * Spring Boot 의 기본 스캔 범위(= @SpringBootApplication 이 있는 패키지)로는 안 잡힌다.
 * 각 앱은 컴포넌트 스캔으로 이 설정을 주워가고, 그러면 JPA 배선이 끝난다.
 */
@Configuration
@EnableJpaAuditing
@EntityScan(basePackages = ["com.jaeipgo.alimi.core"])
@EnableJpaRepositories(basePackages = ["com.jaeipgo.alimi.core"])
class CoreJpaConfig
