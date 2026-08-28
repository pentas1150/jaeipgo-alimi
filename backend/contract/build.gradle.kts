// 프로세스 간 계약: Kafka 이벤트 페이로드와 토픽 이름.
//
// ⚠️ 이 모듈에는 의존성을 추가하지 말 것.
// Spring 도, JPA 도, Jackson 도 들어오면 안 된다.
// 나중에 한 모듈을 별도 서비스로 떼낼 때 이것만 들고 갈 수 있어야 하기 때문이다.
// 여기에 뭔가 넣고 싶어지면, 그건 대개 core 에 들어가야 하는 것이다.

dependencies {
    // 비어 있는 것이 정상이다.
}

// 라이브러리 모듈이다. 실행 가능한 fat jar 가 아니라 일반 jar 를 만든다.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { enabled = false }
tasks.named<Jar>("jar") { enabled = true }
