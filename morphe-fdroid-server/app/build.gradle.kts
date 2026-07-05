import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_26)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_26
    targetCompatibility = JavaVersion.VERSION_26
}

application {
    mainClass.set("app.fdroidserver.MainKt")
}

dependencies {
    // morphe-patcher, the library morphe-cli itself is built on - depended on
    // directly as a normal Maven artifact (see settings.gradle.kts for the
    // GitHub Packages repo + credentials this resolves through). It's a plain
    // JVM library with no bundled GUI/Ktor/coroutines of its own to conflict
    // with our own versions (unlike the morphe-cli release jar, which bundles
    // the whole desktop app - that's why an earlier version of this project
    // vendored a trimmed copy of that jar instead; see git history/plan.md
    // §5.1 for why that approach was replaced with this one). Its transitive
    // deps (ARSCLib, apksig/apkzlib, guava, bouncycastle, smali/antlr,
    // kotlin-reflect) come along automatically as a real `implementation`
    // dependency, so it's also bundled into the shadow jar below like every
    // other dependency - no separate jar on the runtime classpath anymore.
    implementation(libs.morphe.patcher)

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.snakeyaml)
    implementation(libs.sqlite.jdbc)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.jsoup)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    useJUnitPlatform {
        // Tests tagged "network" hit real, third-party services (e.g. APKMirror)
        // and are excluded from the normal build - they're slow, flaky, and can
        // trip that site's bot detection. Run them explicitly with:
        // ./gradlew test -DincludeNetworkTests=true
        if (System.getProperty("includeNetworkTests") != "true") {
            excludeTags("network")
        }
    }
    // sqlite-jdbc loads its bundled native library via System.load(); the test
    // JVM is forked directly (not via `java -jar`), so it doesn't pick up the
    // jar manifest's Enable-Native-Access attribute below and needs this as a
    // JVM flag instead to avoid JEP 472's restricted-method warning.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.shadowJar {
    // Keep the default "-all" classifier (rather than overriding to "") - forcing
    // it to match the plain `jar` task's output name creates an ambiguous task
    // graph with the `application` plugin's distZip/distTar/startScripts tasks.
    // We don't use those distribution tasks; the Dockerfile runs this shadow jar
    // directly via `java -jar app.jar` - morphe-patcher is a normal
    // `implementation` dependency now, so its classes are merged into this
    // single fat jar along with everything else; no second jar on the
    // classpath at runtime anymore.
    manifest {
        attributes(
            "Main-Class" to "app.fdroidserver.MainKt",
            // sqlite-jdbc loads a bundled native library via System.load();
            // this grants it native access under JEP 472 so `java -jar
            // app.jar` doesn't warn (and won't hard-fail once the JDK starts
            // enforcing this). Only honored for jars launched with `-jar`,
            // which is how this is run everywhere (see supervisord.conf).
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}

tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }
tasks.named("startScripts") { enabled = false }

tasks.build {
    dependsOn(tasks.shadowJar)
}
