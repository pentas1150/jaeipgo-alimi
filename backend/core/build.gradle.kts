// 도메인 코어: 엔티티, 리포지토리, 도메인 로직, DB 마이그레이션, 공통 설정.
// 모든 app-* 이 이걸 의존한다. 반대 방향은 없다.

plugins {
    kotlin("plugin.jpa")
    `java-test-fixtures`
}

dependencies {
    api(project(":backend:contract"))

    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.kafka:spring-kafka")
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    api("org.jetbrains.kotlin:kotlin-reflect")

    // 마이그레이션은 k8s 에서 별도 Job 이 담당하지만(§10.9),
    // 로컬 개발에서는 앱이 직접 돌리는 게 편해서 의존성은 유지한다.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    // 통합 테스트용 인프라를 app-* 모듈들이 재사용할 수 있게 노출한다.
    // testFixtures 는 별도 소스셋이라 루트의 testImplementation 이 닿지 않는다. 직접 선언한다.
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
    testFixturesApi("org.testcontainers:junit-jupiter")
    testFixturesApi("org.testcontainers:mysql")
    testFixturesApi("org.testcontainers:kafka")
}

// kotlin-plugin.jpa 가 이 애노테이션들을 open + no-arg 로 처리하게 한다.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

// 라이브러리 모듈이다. 실행 가능한 fat jar 가 아니라 일반 jar 를 만든다.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { enabled = false }
tasks.named<Jar>("jar") { enabled = true }
