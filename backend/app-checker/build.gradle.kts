// 재고 판정 워커. **Playwright 는 오직 이 모듈에만 있다.**
// 멀티모듈로 쪼갠 가장 실질적인 이유가 이것이다 —
// 단일 모듈일 때는 브라우저 의존성이 api/scheduler/notifier 클래스패스에도 올라갔다.
val playwrightVersion: String by rootProject.extra

dependencies {
    implementation(project(":backend:core"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Dockerfile 의 mcr.microsoft.com/playwright/java 태그와 반드시 같은 버전.
    implementation("com.microsoft.playwright:playwright:$playwrightVersion")

    testImplementation(testFixtures(project(":backend:core")))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
