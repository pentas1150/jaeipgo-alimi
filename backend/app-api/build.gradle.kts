// REST API 서버. 상품/구독 등록.
dependencies {
    implementation(project(":backend:core"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    // 구글 OAuth 로그인.
    // ⚠️ spring-boot-starter-security 가 아니라 oauth2-client 다. starter-security 를 직접
    //    넣을 이유가 없고, oauth2-client 가 필요한 security 모듈을 전이로 끌고 온다.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    // 세션 저장소. api 는 파이에서 replicas=1 이고 maxUnavailable:0 + maxSurge:1 로 롤링하므로,
    // 인메모리 세션이면 **배포할 때마다 전원 로그아웃**된다. Redis 는 5단계의 크롤링 중복
    // 방지(SETNX)에서도 같은 인스턴스를 쓴다.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.session:spring-session-data-redis")

    testImplementation(testFixtures(project(":backend:core")))
    // oauth2Login() RequestPostProcessor 로 구글 왕복 없이 인증된 요청을 만든다.
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
