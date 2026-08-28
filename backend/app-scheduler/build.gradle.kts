// 배치 스케줄러. 검사할 때가 된 상품을 찾아 체크 요청을 발행한다.
// 반드시 단일 인스턴스로 뜬다 (k8s/app/scheduler.yaml).
dependencies {
    implementation(project(":backend:core"))
    // actuator 프로브용으로만 web 이 필요하다. 컨트롤러는 없다.
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation(testFixtures(project(":backend:core")))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
