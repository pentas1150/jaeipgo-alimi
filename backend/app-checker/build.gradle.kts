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

// CI 에서 브라우저 캐시를 채우는 용도. 로컬에서도 브라우저가 없을 때 쓴다.
//
// Playwright 는 첫 사용 시 브라우저를 자동으로 내려받지만, 그러면 테스트 실행 중에
// 150MB 다운로드가 끼어들어 무엇 때문에 느린지 알기 어려워진다. 단계를 분리한다.
//
// ⚠️ `sourceSets` 접근자를 쓸 수 없다 — 이 프로젝트는 플러그인을 루트에서
// `apply(plugin = ...)` 로 붙이므로 타입 안전 접근자가 생성되지 않는다.
// (위 bootJar 설정이 정규화된 타입명을 쓰는 것과 같은 이유)
tasks.register<JavaExec>("installChromium") {
    group = "playwright"
    description = "Chromium 과 시스템 의존성을 설치한다"

    classpath = project.extensions.getByType<SourceSetContainer>()
        .getByName("main").runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    // --with-deps 가 리눅스 시스템 패키지까지 깔아준다. macOS 에서는 무시된다.
    args("install", "--with-deps", "chromium")
}
