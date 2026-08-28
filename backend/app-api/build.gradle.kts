// REST API 서버. 상품/구독 등록.
dependencies {
    implementation(project(":backend:core"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation(testFixtures(project(":backend:core")))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
