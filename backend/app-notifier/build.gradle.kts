// 팬아웃 + 알림 발송 워커.
// 메일/웹훅 클라이언트는 오직 이 모듈에만 들어온다.
dependencies {
    implementation(project(":backend:core"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    // 채널별 클라이언트는 **오직 이 모듈에만** 들어온다.
    // 디스코드/텔레그램을 추가하면 여기에 HTTP 클라이언트를 넣는다.
    implementation("org.springframework.boot:spring-boot-starter-mail")

    testImplementation(testFixtures(project(":backend:core")))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
