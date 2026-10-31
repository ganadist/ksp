/*
 * Copyright 2026 Google LLC
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.devtools.ksp.gradle

import com.google.common.truth.Truth.assertThat
import com.google.devtools.ksp.gradle.testing.TestConfig
import groovy.json.JsonSlurper
import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BtaDiagnosticsIntegrationTest {
    @Rule @JvmField val tmpDir = TemporaryFolder()

    private val config = TestConfig.read()

    @Test
    fun compiler24ReportsStructuredWarningsAndCompilesGeneratedSources() {
        setup("2.4.0", "2.4.0")
        val result = runner().build()
        assertCompilerVersion(result, "2.4.0")
        assertThat(result.task(":app:compileKotlin")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(tmpDir.root.resolve("app/build/classes/kotlin/main/test/Generated.class").isFile)
            .isTrue()
        assertDiagnostics(result, "warning", "WARNING")
    }

    @Test
    fun diagnosticProvidersAndWorkerSupportConfigurationCacheReuse() {
        setup("2.4.0", "2.4.0")
        val cached =
            runner()
                .withArguments(
                    ":app:classes",
                    "--warning-mode=all",
                    "--console=plain",
                    "--configuration-cache",
                    "--configuration-cache-problems=fail",
                    "--rerun-tasks",
                )
        val allArguments = cached.arguments.toList()
        val first = cached.build()
        assertThat(first.output).contains("FIXTURE_CONFIGURED")
        assertDiagnostics(first, "warning", "WARNING")
        val reused = cached.build()
        assertThat(reused.output).contains("Reusing configuration cache.")
        assertCompilerVersion(reused, "2.4.0")
        assertDiagnostics(reused, "warning", "WARNING")
        assertThat(reused.output).doesNotContain("FIXTURE_CONFIGURED")
        val noneArguments = allArguments.map {
            if (it == "--warning-mode=all") "--warning-mode=none" else it
        }
        val defaultArguments = allArguments.filterNot { it.startsWith("--warning-mode=") }
        for (arguments in listOf(noneArguments, defaultArguments)) {
            val warnings = cached.withArguments(arguments).build()
            assertThat(warnings.output).doesNotContain("FIXTURE_CONFIGURED")
            assertDiagnostics(warnings, "warning", "WARNING")
        }
        write("app/src/main/java/test/ErrorTrigger.java", "package test; class ErrorTrigger {}")
        for (arguments in listOf(noneArguments, defaultArguments)) {
            val errors = cached.withArguments(arguments).buildAndFail()
            assertThat(errors.output).doesNotContain("FIXTURE_CONFIGURED")
            assertDiagnostics(errors, "warning", "WARNING")
            assertThat(errors.output.lineSequence().count { "[ksp] quiet-cache error" in it })
                .isEqualTo(1)
            assertThat(
                    problems()
                        .single { it["contextualLabel"] == "[ksp] quiet-cache error" }["severity"]
                )
                .isEqualTo("ERROR")
        }
        val quiet = cached.withArguments(allArguments + "--quiet").buildAndFail()
        assertThat(quiet.output).doesNotContain("FIXTURE_CONFIGURED")
        assertThat(quiet.output.lineSequence().count { "[ksp] quiet-cache error" in it })
            .isEqualTo(1)
        assertThat(
                problems().single { it["contextualLabel"] == "[ksp] quiet-cache error" }["severity"]
            )
            .isEqualTo("ERROR")
    }

    @Test
    fun compiler24ReportsErrorsBeforeFailingTheWorker() {
        setup("2.4.0", "2.4.0", errors = true)
        val result = runner().buildAndFail()
        assertThat(result.task(":app:kspKotlin")!!.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("KSP failed with exit code: PROCESSING_ERROR")
        assertDiagnostics(result, "error", "ERROR")
    }

    @Test
    fun repeatedWarningsAndErrorsRetainEveryLocationBeyondGradleSummaryLimit() {
        setup("2.4.0", "2.4.0")
        val source = tmpDir.root.resolve("app/src/main/kotlin/test/Symbols.kt").canonicalFile
        source.writeText("package test\n" + (1..20).joinToString("\n") { "class Many$it" })
        tmpDir.root.resolve("app/src/main/java/test/Symbols.java").delete()
        val result = runner().buildAndFail()
        assertThat(result.task(":app:kspKotlin")!!.outcome).isEqualTo(TaskOutcome.FAILED)
        val repeated = problems().filter { it["contextualLabel"] == "[ksp] repeated diagnostic" }
        for (severity in listOf("WARNING", "ERROR")) {
            val diagnostics = repeated.filter { it["severity"] == severity }
            assertThat(diagnostics).hasSize(20)
            val locations = diagnostics.map { diagnostic ->
                @Suppress("UNCHECKED_CAST")
                val positions = diagnostic["locations"] as List<Map<String, Any?>>
                assertThat(positions.any { it["taskPath"] == ":app:kspKotlin" }).isTrue()
                positions.single { it["path"] == source.path }
            }
            assertThat(locations.map { (it["line"] as Number).toInt() })
                .containsExactlyElementsIn(2..21)
            locations.forEach {
                assertThat((it["column"] as? Number)?.toInt() ?: -1).isAtMost(0)
            }
        }
        assertThat(result.output.lineSequence().count { "[ksp] repeated diagnostic" in it })
            .isEqualTo(40)
        val consoleReports = result.output.split("Problem found: KSP processor ").drop(1)
        for (severity in listOf("warning", "error")) {
            assertThat(
                    consoleReports.count {
                        it.startsWith("$severity ") && "[ksp] repeated diagnostic" in it
                    }
                )
                .isEqualTo(20)
        }
    }

    @Test
    fun errorsRemainVisibleWhenQuietLoggingFiltersTheProblemsConsole() {
        setup("2.4.0", "2.4.0", errors = true)
        val result =
            runner()
                .withArguments(":app:classes", "--warning-mode=all", "--console=plain", "--quiet")
                .buildAndFail()
        assertDiagnostics(result, "error", "ERROR")
    }

    @Test
    fun warningsAsErrorsStillFailAndFlushWarnings() {
        setup("2.4.0", "2.4.0")
        tmpDir.root
            .resolve("app/build.gradle.kts")
            .appendText("\nksp { allWarningsAsErrors = true }\n")
        val result = runner().buildAndFail()
        assertThat(result.output).contains("KSP failed with exit code: PROCESSING_ERROR")
        assertDiagnostics(result, "warning", "WARNING")
    }

    @Test
    fun workerStartupErrorsAreStructuredAndFlushed() {
        setup("2.4.0", "2.4.0")
        tmpDir.root
            .resolve(
                "processor/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"
            )
            .writeText("")
        val result = runner().buildAndFail()
        val message = "[ksp] No providers found in processor classpath."
        assertThat(result.output.lineSequence().count { message in it }).isEqualTo(1)
        assertThat(problems().single { it["contextualLabel"] == message }["severity"])
            .isEqualTo("ERROR")
    }

    @Test
    fun compilerOverride23RetainsLegacyOutput() {
        setup("2.4.0", "2.3.20")
        val result = runner().build()
        assertCompilerVersion(result, "2.3.20")
        assertLegacyDiagnostics(result)
    }

    @Test
    fun baselineKgpRetainsLegacyOutput() {
        setup("2.3.20", "2.3.20")
        val result = runner().build()
        assertCompilerVersion(result, "2.3.20")
        assertLegacyDiagnostics(result)
    }

    @Test
    fun kspDiagnosticsOptOutRetainsLegacyOutputAndCompilerSelection() {
        setup("2.4.0", "2.4.0")
        tmpDir.root
            .resolve("gradle.properties")
            .appendText("\nksp.diagnostics.useBuildToolsApi=false\n")
        val result = runner().build()
        assertLegacyDiagnostics(result)
        assertCompilerVersion(result, "2.4.0")
        assertThat(result.output).contains("COMPILATION_USES_BTA=true")
    }

    @Test
    fun kspDiagnosticsOptOutSupportsConfigurationCacheAndCommandLineOverride() {
        setup("2.4.0", "2.4.0")
        tmpDir.root
            .resolve("gradle.properties")
            .appendText("\nksp.diagnostics.useBuildToolsApi=false\n")
        val cached =
            runner()
                .withArguments(
                    ":app:classes",
                    "--warning-mode=all",
                    "--console=plain",
                    "--configuration-cache",
                    "--configuration-cache-problems=fail",
                    "--rerun-tasks",
                    "-Pksp.diagnostics.useBuildToolsApi=true",
                )
        assertDiagnostics(cached.build(), "warning", "WARNING")
        val enabledReuse = cached.build()
        assertThat(enabledReuse.output).contains("Reusing configuration cache.")
        assertDiagnostics(enabledReuse, "warning", "WARNING")

        tmpDir.root.resolve("build/reports/problems/problems-report.html").delete()
        val disabledArguments =
            cached.arguments.filterNot { it.startsWith("-Pksp.diagnostics.useBuildToolsApi=") }
        val disabled = cached.withArguments(disabledArguments).build()
        assertLegacyDiagnostics(disabled)
        assertCompilerVersion(disabled, "2.4.0")
        assertThat(disabled.output).contains("COMPILATION_USES_BTA=true")
        val disabledReuse = cached.build()
        assertThat(disabledReuse.output).contains("Reusing configuration cache.")
        assertLegacyDiagnostics(disabledReuse)

        write("app/src/main/java/test/ErrorTrigger.java", "package test; class ErrorTrigger {}")
        val failed = cached.buildAndFail()
        assertThat(failed.output).contains("Reusing configuration cache.")
        assertThat(failed.task(":app:kspKotlin")!!.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(failed.output.lineSequence().count { "quiet-cache error" in it }).isEqualTo(1)
        assertThat(problems().filter { it["contextualLabel"].toString().contains("[ksp]") })
            .isEmpty()
    }

    @Test
    fun kspDiagnosticsOptInDoesNotOverrideCompilerBtaOptOut() {
        setup("2.4.0", "2.4.0")
        tmpDir.root
            .resolve("gradle.properties")
            .appendText(
                "\nkotlin.compiler.runViaBuildToolsApi=false\nksp.diagnostics.useBuildToolsApi=true\n"
            )
        val result = runner().build()
        assertLegacyDiagnostics(result)
        assertThat(result.output).contains("COMPILATION_USES_BTA=false")
    }

    @Test
    fun jvmExplicitBtaOptOutRetainsLegacyOutput() {
        setup("2.4.0", "2.4.0")
        tmpDir.root
            .resolve("gradle.properties")
            .appendText("\nkotlin.compiler.runViaBuildToolsApi=false\n")
        assertLegacyDiagnostics(runner().build())
    }

    @Test
    fun jvmLocalPropertiesBtaOptOutRetainsLegacyOutput() {
        setup("2.4.0", "2.4.0")
        tmpDir.root
            .resolve("local.properties")
            .writeText("kotlin.compiler.runViaBuildToolsApi=false\n")
        assertLegacyDiagnostics(runner().build())
    }

    @Test
    fun jvmExtraPropertyBtaOptOutOverridesGradleAndLocalProperties() {
        setup("2.4.0", "2.4.0")
        tmpDir.root
            .resolve("gradle.properties")
            .appendText("\nkotlin.compiler.runViaBuildToolsApi=true\n")
        tmpDir.root
            .resolve("local.properties")
            .writeText("kotlin.compiler.runViaBuildToolsApi=true\n")
        tmpDir.root
            .resolve("app/build.gradle.kts")
            .appendText("\nextra[\"kotlin.compiler.runViaBuildToolsApi\"] = false\n")
        assertLegacyDiagnostics(runner().build())
    }

    private fun assertCompilerVersion(result: BuildResult, version: String) {
        assertThat(result.output).contains("DIAGNOSTICS_COMPILER=$version")
        assertThat(result.output).contains("RESOLVED_COMPILER=$version")
    }

    private fun assertLegacyDiagnostics(result: BuildResult) {
        for (name in names) {
            assertThat(result.output)
                .contains("[ksp] ${sourceFile(name).absolutePath}:2: warning-$name")
        }
        assertThat(problems().filter { it["contextualLabel"].toString().contains("[ksp]") })
            .isEmpty()
        assertThat(result.task(":app:compileKotlin")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    private fun assertDiagnostics(result: BuildResult, prefix: String, severity: String) {
        val diagnostics = problems()
        for (name in names) {
            val message = "[ksp] $prefix-$name"
            assertThat(result.output.lineSequence().count { message in it }).isEqualTo(1)
            val diagnostic =
                diagnostics.singleOrNull { it["contextualLabel"] == message }
                    ?: error("Missing diagnostic for $message: $diagnostics")
            assertThat(diagnostic["severity"]).isEqualTo(severity)
            @Suppress("UNCHECKED_CAST")
            val locations = diagnostic["locations"] as List<Map<String, Any?>>
            val source =
                locations.singleOrNull { it["path"] == sourceFile(name).absolutePath }
                    ?: error("Missing location for ${sourceFile(name).absolutePath}: $locations")
            assertThat((source["line"] as Number).toInt()).isEqualTo(2)
            assertThat((source["column"] as? Number)?.toInt() ?: -1).isAtMost(0)
            assertThat(locations.any { it["taskPath"] == ":app:kspKotlin" }).isTrue()
        }
        val sourceFree = diagnostics.single { it["contextualLabel"] == "[ksp] source-free warning" }
        @Suppress("UNCHECKED_CAST")
        assertThat(
                (sourceFree["locations"] as? List<Map<String, Any?>>).orEmpty().any {
                    it.containsKey("path")
                }
            )
            .isFalse()
    }

    private fun problems(): List<Map<String, Any?>> {
        val report = tmpDir.root.resolve("build/reports/problems/problems-report.html")
        if (!report.isFile) return emptyList()
        val json =
            report
                .readText()
                .substringAfter("// begin-report-data\n")
                .substringBefore("\n// end-report-data")
        @Suppress("UNCHECKED_CAST")
        return (JsonSlurper().parseText(json) as Map<String, Any?>)["diagnostics"]
            as List<Map<String, Any?>>
    }

    private fun sourceFile(name: String) =
        tmpDir.root
            .resolve(
                if (name.startsWith("Kotlin")) "app/src/main/kotlin/test/Symbols.kt"
                else "app/src/main/java/test/Symbols.java"
            )
            .canonicalFile

    private fun runner() =
        GradleRunner.create()
            .withProjectDir(tmpDir.root)
            .withArguments(":app:classes", "--warning-mode=all", "--console=plain", "--stacktrace")

    private fun setup(kgpVersion: String, compilerVersion: String, errors: Boolean = false) {
        write(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    maven("${config.mavenRepoPath}")
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = "bta-diagnostics"
            include(":app", ":processor")
        """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm") version "$kgpVersion" apply false
                id("com.google.devtools.ksp") version "${config.kspVersion}" apply false
            }
            println("FIXTURE_CONFIGURED")
            subprojects {
                repositories {
                    maven("${config.mavenRepoPath}")
                    mavenCentral()
                }
            }
        """,
        )
        write(
            "gradle.properties",
            """
            kotlin.jvm.target.validation.mode=warning
            org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m
        """,
        )
        write(
            "processor/build.gradle.kts",
            """
            plugins { kotlin("jvm") }
            dependencies {
                implementation("com.google.devtools.ksp:symbol-processing-api:${config.kspVersion}")
            }
        """,
        )
        write(
            "processor/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider",
            "test.DiagnosticProcessorProvider",
        )
        write(
            "processor/src/main/kotlin/test/DiagnosticProcessorProvider.kt",
            """
            package test
            import com.google.devtools.ksp.processing.*
            import com.google.devtools.ksp.symbol.*

            @OptIn(com.google.devtools.ksp.KspExperimental::class)
            class DiagnosticProcessorProvider : SymbolProcessorProvider {
                override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
                    private var processed = false
                    override fun process(resolver: Resolver): List<KSAnnotated> {
                        if (processed) return emptyList()
                        processed = true
                        for (declaration in resolver.getDeclarationsFromPackage("test")) {
                            val name = declaration.simpleName.asString()
                            if (name.startsWith("Many")) {
                                environment.logger.warn("repeated diagnostic", declaration)
                                environment.logger.error("repeated diagnostic", declaration)
                            }
                            if (name == "ErrorTrigger") {
                                environment.logger.error("quiet-cache error", declaration)
                            }
                            if (name in setOf("KotlinOne", "KotlinTwo", "JavaOne", "JavaTwo")) {
                                environment.logger.warn("warning-" + name, declaration)
                                if (environment.options["errors"] == "true") {
                                    environment.logger.error("error-" + name, declaration)
                                }
                            }
                        }
                        environment.logger.warn("source-free warning")
                        environment.codeGenerator.createNewFile(Dependencies.ALL_FILES, "test", "Generated")
                            .bufferedWriter().use { it.write("package test; class Generated") }
                        return emptyList()
                    }
                }
            }
        """,
        )
        write(
            "app/build.gradle.kts",
            """
            @file:OptIn(
                org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class,
                org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class,
            )
            import com.google.devtools.ksp.gradle.KspAATask
            plugins {
                kotlin("jvm")
                id("com.google.devtools.ksp")
            }
            dependencies { add("ksp", project(":processor")) }
            ksp { arg("errors", "$errors") }
            kotlin.compilerVersion.set("$compilerVersion")
            configurations.matching { it.name.startsWith("kotlinCompilerPluginClasspath") }.configureEach {
                resolutionStrategy.eachDependency {
                    if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-scripting-")) {
                        useVersion("$compilerVersion")
                    }
                }
            }
            val compilerArtifacts = configurations.named("kotlinBuildToolsApiClasspath")
                .get().incoming.artifacts.resolvedArtifacts
            tasks.withType<KspAATask>().configureEach {
                inputs.property("resolvedCompilerVersion", compilerArtifacts.map { artifacts ->
                    artifacts.map { it.id.componentIdentifier }
                        .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
                        .single { it.group == "org.jetbrains.kotlin" && it.module == "kotlin-compiler-embeddable" }
                        .version
                })
                doFirst {
                    println("DIAGNOSTICS_COMPILER=" + (this as KspAATask).compilerVersionForDiagnostics.get())
                    println("COMPILATION_USES_BTA=" + (this as KspAATask).useBuildToolsApi.get())
                    println("RESOLVED_COMPILER=" + inputs.properties["resolvedCompilerVersion"])
                }
            }
        """,
        )
        write(
            "app/src/main/kotlin/test/Symbols.kt",
            """
            package test
            class KotlinOne; class KotlinTwo
            class UsesGenerated { val value = Generated() }
        """,
        )
        write(
            "app/src/main/java/test/Symbols.java",
            """
            package test;
            class JavaOne {} class JavaTwo {}
        """,
        )
    }

    private fun write(path: String, content: String) {
        File(tmpDir.root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    companion object {
        private val names = listOf("KotlinOne", "KotlinTwo", "JavaOne", "JavaTwo")
    }
}
