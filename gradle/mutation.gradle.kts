import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.toolchain.JavaToolchainService

// Keep the mutation engine on its own classpath. It is never packaged with the runtime.
val mutationTools = configurations.create("mutationTools")
dependencies {
    add(mutationTools.name, "org.pitest:pitest-command-line:1.30.2")
    add(mutationTools.name, "org.pitest:pitest-junit5-plugin:1.2.3")
}
val testRuntime = extensions.getByType<SourceSetContainer>().named("test").get().runtimeClasspath
val jdk = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}
val selectedClasses = providers.gradleProperty("mutationClasses").orElse("myos.better.OwnerTimelineSources*,myos.better.CoreInputStatus,myos.better.CoreTimelineApi")
val selectedTests = providers.gradleProperty("mutationTests").orElse("myos.better.OwnerTimelineSourcesTest,myos.better.CoreDocumentsTest,myos.better.CoreTimelineApiTest,myos.better.CoreDashboardHttpTest")
val mutationReport = layout.buildDirectory.dir("reports/mutation")

tasks.register<JavaExec>("mutationTest") {
    group = "verification"
    description = "Measure which deliberate changes the selected tests detect with PIT."
    dependsOn("testClasses")
    classpath(mutationTools, testRuntime)
    javaLauncher.set(jdk)
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
    args(
        "--reportDir", mutationReport.get().asFile,
        "--sourceDirs", file("src/main/java"),
        "--targetClasses", selectedClasses.get(),
        "--targetTests", selectedTests.get(),
        "--excludedClasses", "*Test,*Test\$*,*Fixture,*Fixture\$*",
        "--outputFormats", "HTML,XML",
        "--threads", "2",
        "--jvmArgs", "-Xmx320m",
        "--timestampedReports", "false",
    )
}
