plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":bc:shared"))
    implementation(project(":bc:settlement"))
    implementation(project(":support:common"))
    implementation(project(":support:logging"))
    implementation(project(":support:security"))
    implementation(project(":support:web"))
    implementation(project(":support:jpa"))
    implementation(libs.dotenv)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.flyway.core)
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    runtimeOnly(libs.h2)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.flyway.postgresql)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
