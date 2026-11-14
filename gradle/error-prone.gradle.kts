import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile

// The processor and dataflow are compiler tools. Neither is on a consumer classpath.
val checker = configurations.create("errorProneCompiler") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    add("compileOnly", "org.jspecify:jspecify:1.0.1")
    add(checker.name, "com.uber.nullaway:nullaway:0.14.3")
    add(checker.name, "org.checkerframework:dataflow-nullaway:4.3.1")
    add(checker.name, "org.jspecify:jspecify:1.0.1")
    add(checker.name, "com.google.errorprone:error_prone_core:2.52.0:with-dependencies")
    add(checker.name, "io.github.eisop:dataflow-errorprone:3.41.0-eisop1")
}
val mainSources = extensions.getByType<JavaPluginExtension>().sourceSets.named("main")
val reportWarnings = providers.gradleProperty("errorProneWarnings").map(String::toBoolean).orElse(false)

tasks.register<JavaCompile>("errorProne") {
    group = "verification"
    description = "Compile production sources with Error Prone on JDK 25."
    source(mainSources.get().allJava)
    classpath = mainSources.get().compileClasspath
    destinationDirectory.set(layout.buildDirectory.dir("analysis/error-prone/classes"))
    options.release.set(25)
    options.annotationProcessorPath = checker
    options.isFork = true
    // Error Prone reads the compiler trees after attribution. Give only this compiler access.
    options.forkOptions.jvmArgs = listOf("api", "file", "main", "model", "parser", "processing", "tree", "util").map {
        "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED"
    } + listOf("code", "comp").map { "--add-opens=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" }
    options.compilerArgs.remove("-Werror")
    val warnings = if (reportWarnings.get()) "" else " -XepDisableAllWarnings"
    options.compilerArgs.addAll(listOf("-XDcompilePolicy=simple", "--should-stop=ifError=FLOW",
        "-Xplugin:ErrorProne$warnings -Xep:EqualsUnsafeCast:ERROR -Xep:MutablePublicArray:ERROR -Xep:MissingCasesInEnumSwitch:ERROR -Xep:LockNotBeforeTry:ERROR -Xep:NullAway:ERROR -XepOpt:NullAway:OnlyNullMarked=true"))
}
