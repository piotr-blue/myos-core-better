plugins {
    application
    jacoco
}
group = "blue.myos"
version = "0.1.0-experiment"
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
val javaFormatter = configurations.create("javaFormatter")
dependencies {
    add(javaFormatter.name, "com.google.googlejavaformat:google-java-format:1.30.0:all-deps") { isTransitive = false }
    implementation("blue.language:blue-java-better:0.1.0-SNAPSHOT")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("org.apache.commons:commons-fileupload2-core:2.0.0-M5")
    implementation("net.coobird:thumbnailator:0.4.21")
    implementation(platform("software.amazon.awssdk:bom:2.46.17"))
    implementation("software.amazon.awssdk:dynamodb") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:apache5-client")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.6.5")
    implementation("software.amazon.awssdk:cognitoidentityprovider")
    implementation("software.amazon.awssdk:secretsmanager")
    implementation("software.amazon.awssdk:sesv2")
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
application { mainClass.set("myos.better.Main") }
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Native integration fixtures retain the complete durable history in memory.
    maxHeapSize = "1g"
    testLogging { events("failed", "skipped") }
}
// Instrument a separate test task so ordinary tests and timing probes use the plain runtime.
jacoco { toolVersion = "0.8.16" }
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
}
val coverageTest = tasks.register<Test>("coverageTest") {
    group = "verification"
    description = "Run the ordinary JUnit suite with JaCoCo instrumentation."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    extensions.configure<JacocoTaskExtension> { isEnabled = true }
}
val suiteCoverage = coverageTest.map { it.extensions.getByType<JacocoTaskExtension>().destinationFile }
tasks.jacocoTestReport {
    dependsOn(coverageTest)
    executionData.setFrom(suiteCoverage)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    executionData.setFrom(suiteCoverage)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.86".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.73".toBigDecimal()
            }
        }
    }
}

dependencyLocking { lockAllConfigurations() }

// Equal inputs produce equal deployable archives, independent of checkout timestamps.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register("resolveDependencies") {
    notCompatibleWithConfigurationCache("Explicit dependency resolution for lockfile maintenance")
    doLast { configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
}

val javaSources = fileTree("src") { include("**/*.java") }
for (taskName in listOf("format", "checkFormat")) {
    tasks.register<JavaExec>(taskName) {
        group = if (taskName == "format") "formatting" else "verification"
        classpath = javaFormatter
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        mainClass.set("com.google.googlejavaformat.java.Main")
        jvmArgs(listOf("api", "file", "parser", "tree", "util", "code").map {
            "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED"
        })
        args(if (taskName == "format") listOf("--replace") else listOf("--dry-run", "--set-exit-if-changed"))
        args(javaSources.files.sorted().map { it.absolutePath })
    }
}
tasks.named("checkFormat") { mustRunAfter("format") }
tasks.named("check") { dependsOn("checkFormat") }
tasks.withType<JavaCompile>().configureEach { mustRunAfter("format") }

distributions {
    main {
        contents {
            from("examples") { into("examples") }
            from("core-ui/dist") { into("web") }
            from(listOf("scripts/demo.py", "scripts/demo_hello.py", "scripts/demo_reads.py", "scripts/throughput.py", "scripts/reproduce_canonical.py")) { into("scripts") }
        }
    }
}

sourceSets.test { resources.srcDir("examples") }

// Qualification executables are compiled by check but never run by an ordinary build.
// They are a separate tool artifact, not part of the service distribution.
val qualification = sourceSets.create("qualification") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
    resources.srcDir("src/test/resources")
    resources.include("coordination/coordination.bluepkg", "tls/localhost.p12")
}
configurations[qualification.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[qualification.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
// The real-clause acceptance test shares the read-only whole-root oracle used on AWS.
sourceSets.test {
    compileClasspath += qualification.output
    runtimeClasspath += qualification.output
}
tasks.register<Jar>("qualificationJar") {
    group = "verification"
    description = "Build bounded, explicit performance and recovery probes without running them."
    archiveClassifier.set("qualification")
    from(qualification.output)
}
tasks.named("check") { dependsOn(qualification.classesTaskName) }

tasks.register<JavaExec>("canonicalGraphProbe") {
    group = "verification"
    description = "Bounded inline-versus-durable canonical graph qualification."
    classpath = qualification.runtimeClasspath
    mainClass.set("myos.better.CanonicalGraphProbe")
    maxHeapSize = "1g"
}

// The supervisor must target the worker JVM itself when simulating an abrupt process death.
tasks.register("processRecoveryClasspath") {
    group = "verification"
    description = "Prepare the isolated process-recovery worker and write its runtime classpath."
    dependsOn(qualification.classesTaskName)
    val processRecoveryPath = qualification.runtimeClasspath
    inputs.files(processRecoveryPath)
    val output = layout.buildDirectory.file("process-recovery-classpath.txt")
    outputs.file(output)
    doLast { output.get().asFile.writeText(processRecoveryPath.asPath) }
}

tasks.register<JavaExec>("commandReplayProbe") {
    group = "verification"
    description = "Race repeated commands across host instances and reopen every durable result."
    classpath = qualification.runtimeClasspath
    mainClass.set("myos.better.CommandReplayProbe")
    systemProperty("myos.probe.conflictingCommands", providers.gradleProperty("conflictingCommands").getOrElse("false"))
    maxHeapSize = "384m"
    args(providers.gradleProperty("firstSeed").getOrElse("1"),
        providers.gradleProperty("seedCount").getOrElse("3"),
        providers.gradleProperty("commandsPerSeed").getOrElse("12"))
    val endpoint = providers.gradleProperty("dynamoEndpoint").orNull
    val table = providers.gradleProperty("dynamoTable").orNull
    require((endpoint == null) == (table == null)) { "Supply both dynamoEndpoint and dynamoTable" }
    if (endpoint != null && table != null) args(endpoint, table)
}

tasks.register<JavaExec>("ownerScaleProbe") {
    group = "verification"
    description = "Explicit independent-owner storage and native parity qualification."
    classpath = qualification.runtimeClasspath
    mainClass.set("myos.better.OwnerScaleProbe")
    maxHeapSize = providers.gradleProperty("ownerHeap").getOrElse("2g")
    systemProperty("blue.probe.expectedHeapBytes", providers.gradleProperty("ownerExpectedHeapBytes").getOrElse("0"))
    systemProperty("blue.probe.samples", providers.gradleProperty("ownerSamples").getOrElse("5"))
    systemProperty("blue.probe.rotateTargets", providers.gradleProperty("ownerRotate").getOrElse("false"))
    systemProperty("blue.probe.observableRoot", providers.gradleProperty("ownerObservable").getOrElse("false"))
    systemProperty("blue.probe.orders", providers.gradleProperty("ownerCounts").getOrElse("1,128"))
    args(providers.gradleProperty("ownerTable").getOrElse("memory"), providers.gradleProperty("ownerAccount").getOrElse("bench-owner-local"))
}

// Explicit larger runs reuse the published-clause regression; ordinary tests use three children.
tasks.register<Test>("clauseGrowth") {
    group = "verification"
    description = "Local signed-input growth measurements with published IncrementalDocuments clauses."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("myos.better.ClauseGrowthTest") }
    maxHeapSize = "2g"
    systemProperty("blue.growth.count", providers.gradleProperty("growthCount").getOrElse("3"))
    systemProperty("blue.growth.historyRepeats", providers.gradleProperty("historyRepeats").getOrElse("0"))
    if (providers.gradleProperty("growthBlobDirectory").isPresent())
        systemProperty("myos.test.blobDirectory", providers.gradleProperty("growthBlobDirectory").get())
    systemProperty("blue.growth.profile", providers.gradleProperty("growthProfile").getOrElse("false"))
    systemProperty("blue.growth.output", providers.gradleProperty("growthOutput").getOrElse("build/clause-growth"))
    // Explicit measurements must execute even when source and parameters are unchanged.
    outputs.upToDateWhen { false }
}

tasks.register<Test>("cycleReplay") {
    group = "verification"
    description = "Replay signed inputs through seeded cyclic owners and check every member after restart."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("myos.better.OwnerCycleTest.reopenedCyclesApplyEachSignedAmountExactlyOncePerMember") }
    systemProperty("myos.cycles.firstSeed", providers.gradleProperty("firstSeed").getOrElse("930000"))
    systemProperty("myos.cycles.seedCount", providers.gradleProperty("seedCount").getOrElse("3"))
    outputs.upToDateWhen { false }
}

// Explicit fixed-topology history-growth qualification, separate from ordinary regression sizes.
tasks.register<Test>("idleGrowth") {
    group = "verification"
    description = "Measure a first idle-child update independently of embedded document growth."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("myos.better.IdleOwnerGrowthTest") }
    maxHeapSize = "2g"
    systemProperty("blue.idle.histories", providers.gradleProperty("idleHistories").getOrElse("0,32,100,1000"))
    systemProperty("blue.idle.assertBound", providers.gradleProperty("idleAssertBound").getOrElse("true"))
    systemProperty("blue.idle.output", providers.gradleProperty("idleOutput").getOrElse("build/idle-owner"))
    if (providers.gradleProperty("growthBlobDirectory").isPresent())
        systemProperty("myos.test.blobDirectory", providers.gradleProperty("growthBlobDirectory").get())
    outputs.upToDateWhen { false }
}

apply(from = "gradle/mutation.gradle.kts")

apply(from = "gradle/error-prone.gradle.kts")
