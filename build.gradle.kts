import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// 루트는 아무것도 빌드하지 않는다. 공통 설정만 내려준다.
plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("plugin.spring") version "2.1.21" apply false
    kotlin("plugin.jpa") version "2.1.21" apply false
    id("org.springframework.boot") version "3.5.4" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

// Dockerfile 의 PLAYWRIGHT_VERSION build arg 와 동일해야 한다.
val playwrightVersion by extra("1.62.0")

allprojects {
    group = "com.jaeipgo"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    // `:backend` 는 실제 모듈이 아니라 디렉터리를 묶는 컨테이너 프로젝트다.
    // 여기에 boot 플러그인이 붙으면 main 클래스가 없다며 bootJar 가 실패한다.
    if (childProjects.isNotEmpty()) return@subprojects

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")

    // 라이브러리 모듈(contract/core)은 각자 bootJar 를 끄고 jar 를 켠다.

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict")
            jvmTarget = JvmTarget.JVM_21
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    dependencies {
        // kotlin-reflect 는 여기 두지 않는다 — contract 를 kotlin-stdlib 만으로 유지하기 위해서다.
        // 필요한 모듈(core)이 직접 선언한다.
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
