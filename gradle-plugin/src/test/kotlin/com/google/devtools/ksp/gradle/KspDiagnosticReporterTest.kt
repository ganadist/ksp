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
import java.io.File
import java.lang.reflect.Proxy
import java.net.URI
import javax.tools.Diagnostic
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import org.gradle.api.Action
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.ProblemReporter
import org.gradle.api.problems.ProblemSpec
import org.gradle.api.problems.Problems
import org.gradle.api.problems.Severity
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.junit.Test

class KspDiagnosticReporterTest {
    @Test
    fun effectiveCompilerAndCapabilitiesSelectTheAdapter() {
        assertThat(supported("2.3.20")).isFalse()
        assertThat(supported("2.4.0")).isTrue()
        assertThat(supported("2.4.20")).isTrue()
        assertThat(supported("2.4.0", useBta = false)).isFalse()
        assertThat(supported("2.4.0", gradleVersion = "8.12")).isFalse()
        assertThat(supported("2.4.0", gradleVersion = "8.13")).isTrue()
        val missingApi =
            object : ClassLoader(javaClass.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (
                        name.startsWith(
                            "org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer"
                        )
                    ) {
                        throw ClassNotFoundException(name)
                    }
                    return super.loadClass(name, resolve)
                }
            }
        assertThat(
                KspDiagnosticReporterFactory.isSupported(
                    "2.4.0",
                    true,
                    GradleVersion.current(),
                    missingApi,
                )
            )
            .isFalse()
    }

    @Test
    fun factoryCreatesAdapterThroughPublicGradleServices() {
        val project = ProjectBuilder.builder().build()
        val reporter =
            KspDiagnosticReporterFactory.create(
                "2.4.0",
                true,
                project.objects,
                project.logger,
                false,
                ":app:kspKotlin",
            )
        assertThat(reporter).isNotNull()
    }

    @Test
    fun targetSpecificActivationMatchesKgp() {
        for (platform in listOf(KotlinPlatformType.jvm, KotlinPlatformType.androidJvm)) {
            assertThat(usesBta(platform, "2.4.0")).isTrue()
            assertThat(usesBta(platform, "2.4.20", "false")).isFalse()
        }
        for (platform in
            listOf(KotlinPlatformType.js, KotlinPlatformType.wasm, KotlinPlatformType.common)) {
            assertThat(usesBta(platform, "2.4.0", "true")).isFalse()
            assertThat(usesBta(platform, "2.4.10", "true")).isFalse()
            assertThat(usesBta(platform, "2.4.20")).isFalse()
            assertThat(usesBta(platform, "2.4.20", "true")).isTrue()
            assertThat(usesBta(platform, "2.4.20", "false")).isFalse()
        }
        assertThat(usesBta(KotlinPlatformType.native, "2.4.20", "true")).isFalse()
        assertThat(buildToolsApiSetting(KotlinPlatformType.js, "2.4.20")!!.property)
            .isEqualTo("kotlin.js.runViaBuildToolsApi")
        assertThat(buildToolsApiSetting(KotlinPlatformType.wasm, "2.4.20")!!.property)
            .isEqualTo("kotlin.wasm.runViaBuildToolsApi")
        assertThat(buildToolsApiSetting(KotlinPlatformType.common, "2.4.20")!!.property)
            .isEqualTo("kotlin.metadata.runViaBuildToolsApi")
    }

    @Test
    fun activationUsesExtraThenGradleThenRootLocalProperties() {
        val project = ProjectBuilder.builder().build()
        val setting = BuildToolsApiSetting("kotlin.compiler.runViaBuildToolsApi", true)
        fun enabled(extra: String?, gradle: String?, local: String?) =
            buildToolsApiEnabled(
                    setting,
                    project.providers.provider { extra },
                    project.providers.provider { gradle },
                    project.providers.provider { local?.let { "${setting.property}=$it" } },
                )
                .get()
        assertThat(enabled(null, null, null)).isTrue()
        assertThat(enabled(null, null, "false")).isFalse()
        assertThat(enabled(null, "false", "true")).isFalse()
        assertThat(enabled("false", "true", "true")).isFalse()
        assertThat(enabled("true", "false", "false")).isTrue()
        assertThat(enabled(null, "TRUE", null)).isTrue()
        assertThat(enabled(null, "invalid", "false")).isFalse()
    }

    @Test
    fun problemsConsoleEmitterStartsAtGradle93() {
        for (version in listOf("8.13", "9.0", "9.2")) {
            assertThat(
                    KspDiagnosticReporterFactory.usesProblemsConsole(
                        GradleVersion.version(version),
                        true,
                        true,
                    )
                )
                .isFalse()
        }
        assertThat(
                KspDiagnosticReporterFactory.usesProblemsConsole(
                    GradleVersion.version("9.3"),
                    true,
                    true,
                )
            )
            .isTrue()
        assertThat(
                KspDiagnosticReporterFactory.usesProblemsConsole(
                    GradleVersion.version("9.5"),
                    false,
                    true,
                )
            )
            .isFalse()
    }

    @Test
    fun errorOnlyLoggingRetainsExplicitErrorsWhenProblemsConsoleCannotPrint() {
        val suppress =
            KspDiagnosticReporterFactory.usesProblemsConsole(
                GradleVersion.version("9.5"),
                true,
                false,
            )
        assertThat(suppress).isFalse()
        val capture = Capture()
        capture.adapter(LogLevel.ERROR, suppress).apply {
            report(diagnostic(Diagnostic.Kind.ERROR))
            flush()
        }
        assertThat(capture.console.map { it.first }).containsExactly("error")
    }

    @Test
    fun sourceConversionPreservesPositionsWithoutInventingRangesOrContents() {
        val source =
            diagnostic(Diagnostic.Kind.WARNING, File("/tmp/Ksp source.kt").toURI(), 3, 11)
                .toBtaSourceLocation()!!
        assertThat(source.path).isEqualTo("/tmp/Ksp source.kt")
        assertThat(source.line).isEqualTo(3)
        assertThat(source.column).isEqualTo(11)
        assertThat(source.lineEnd).isEqualTo(-1)
        assertThat(source.columnEnd).isEqualTo(-1)
        assertThat(source.lineContent).isNull()
        assertThat(diagnostic(Diagnostic.Kind.ERROR).toBtaSourceLocation()).isNull()
        assertThat(
                diagnostic(Diagnostic.Kind.ERROR, File("/tmp/Old.java").toURI(), 2)
                    .toBtaSourceLocation()!!
                    .column
            )
            .isEqualTo(-1)
    }

    @Test
    fun warningsAndErrorsAreBufferedUntilFlushOnTheWorkerThread() {
        val capture = Capture()
        val adapter = capture.adapter()
        val callback = Thread {
            adapter.report(diagnostic(Diagnostic.Kind.WARNING, File("/tmp/Foo.kt").toURI(), 3, 11))
            adapter.report(diagnostic(Diagnostic.Kind.ERROR, File("/tmp/Foo.java").toURI(), 5, 21))
            adapter.report(diagnostic(Diagnostic.Kind.ERROR))
        }
        callback.start()
        callback.join()
        assertThat(capture.problems).isEmpty()
        adapter.flush()
        assertThat(capture.problems.map { it["severity"] })
            .containsExactly(Severity.WARNING, Severity.ERROR, Severity.ERROR)
            .inOrder()
        assertThat(capture.problems[0]["lineInFileLocation"])
            .isEqualTo(listOf("/tmp/Foo.kt", 3, 11))
        assertThat(capture.problems[1]["lineInFileLocation"])
            .isEqualTo(listOf("/tmp/Foo.java", 5, 21))
        assertThat(capture.problems[2]).doesNotContainKey("fileLocation")
        assertThat(capture.problems[2]).doesNotContainKey("lineInFileLocation")
        assertThat(capture.problemThreads)
            .containsExactly(Thread.currentThread(), Thread.currentThread(), Thread.currentThread())
        assertThat(capture.console.map { it.first })
            .containsExactly("warn", "error", "error")
            .inOrder()
        adapter.flush()
        assertThat(capture.problems).hasSize(3)
    }

    @Test
    fun problemIdsAreStableAndDistinguishDiagnosticFieldsAndTasks() {
        val source = File("/tmp/Foo.kt").toURI()
        val base = diagnostic(Diagnostic.Kind.WARNING, source, 3, 11)
        val capture = Capture()
        val adapter = capture.adapter()
        listOf(
                base,
                base,
                diagnostic(Diagnostic.Kind.ERROR, source, 3, 11),
                diagnostic(Diagnostic.Kind.WARNING, source, 3, 11, "[ksp] another message"),
                diagnostic(Diagnostic.Kind.WARNING, File("/tmp/Other.kt").toURI(), 3, 11),
                diagnostic(Diagnostic.Kind.WARNING, source, 4, 11),
                diagnostic(Diagnostic.Kind.WARNING, source, 3, 12),
                diagnostic(Diagnostic.Kind.WARNING),
            )
            .forEach(adapter::report)
        adapter.flush()
        val ids = capture.problems.map { it["id"] }
        assertThat(ids[0]).isEqualTo(ids[1])
        assertThat(ids.drop(1).toSet()).hasSize(7)
        val anotherRun = Capture()
        anotherRun.adapter().apply {
            report(base)
            flush()
        }
        assertThat(anotherRun.problems.single()["id"]).isEqualTo(ids[0])
        val anotherTask = Capture()
        anotherTask.adapter(taskPath = ":other:kspKotlin").apply {
            report(base)
            flush()
        }
        assertThat(anotherTask.problems.single()["id"]).isNotEqualTo(ids[0])
    }

    @Test
    fun structuredWarningsSurviveErrorOnlyVerbosityAndProblemsConsoleAvoidsDuplicates() {
        val capture = Capture()
        val adapter = capture.adapter(LogLevel.ERROR)
        adapter.report(diagnostic(Diagnostic.Kind.WARNING))
        adapter.report(diagnostic(Diagnostic.Kind.ERROR))
        adapter.flush()
        assertThat(capture.problems).hasSize(2)
        assertThat(capture.console.map { it.first }).containsExactly("error")
        val problemsConsole = Capture()
        problemsConsole.adapter(suppressConsole = true).apply {
            report(diagnostic(Diagnostic.Kind.WARNING))
            report(diagnostic(Diagnostic.Kind.ERROR))
            flush()
        }
        assertThat(problemsConsole.problems).hasSize(2)
        assertThat(problemsConsole.console).isEmpty()
    }

    private fun supported(version: String, useBta: Boolean = true, gradleVersion: String = "9.5") =
        KspDiagnosticReporterFactory.isSupported(
            version,
            useBta,
            GradleVersion.version(gradleVersion),
            javaClass.classLoader,
        )

    private fun usesBta(
        platform: KotlinPlatformType,
        kgp: String,
        property: String? = null,
    ): Boolean {
        val setting = buildToolsApiSetting(platform, kgp) ?: return false
        return property?.toBoolean() ?: setting.defaultValue
    }

    private class Capture {
        val problems = mutableListOf<Map<String, Any?>>()
        val problemThreads = mutableListOf<Thread>()
        val console = mutableListOf<Pair<String, String>>()
        private val reporter =
            proxy<ProblemReporter> { name, args ->
                if (name == "report") {
                    val values = mutableMapOf<String, Any?>("id" to (args[0] as ProblemId).name)
                    val spec =
                        proxy<ProblemSpec> { method, parameters ->
                            values[method] =
                                if (parameters.size == 1) parameters[0] else parameters.toList()
                            null
                        }
                    @Suppress("UNCHECKED_CAST") (args[1] as Action<ProblemSpec>).execute(spec)
                    problems.add(values)
                    problemThreads.add(Thread.currentThread())
                }
                null
            }
        private val services = proxy<Problems> { _, _ -> reporter }
        private var level = LogLevel.WARN
        private val logger =
            proxy<Logger> { method, args ->
                when (method) {
                    "warn",
                    "error" -> {
                        val messageLevel = if (method == "warn") LogLevel.WARN else LogLevel.ERROR
                        if (level <= messageLevel) console.add(method to args[0].toString())
                        null
                    }
                    "isDebugEnabled" -> false
                    "isWarnEnabled" -> level <= LogLevel.WARN
                    else -> null
                }
            }

        fun adapter(
            level: LogLevel = LogLevel.WARN,
            suppressConsole: Boolean = false,
            taskPath: String = ":app:kspKotlin",
        ): BtaKspDiagnosticReporter {
            this.level = level
            return BtaKspDiagnosticReporter(services, logger, suppressConsole, taskPath)
        }
    }

    companion object {
        private inline fun <reified T> proxy(
            crossinline invoke: (String, Array<out Any?>) -> Any?
        ): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
                instance,
                method,
                args ->
                val result = invoke(method.name, args ?: emptyArray())
                if (result == null && method.returnType.isInstance(instance)) instance else result
            } as T

        private fun diagnostic(
            kind: Diagnostic.Kind,
            uri: URI? = null,
            line: Long = Diagnostic.NOPOS,
            column: Long = Diagnostic.NOPOS,
            message: String = "[ksp] diagnostic message",
        ): Diagnostic<JavaFileObject> =
            object : Diagnostic<JavaFileObject> {
                override fun getKind() = kind

                override fun getSource(): JavaFileObject? = uri?.let {
                    object : SimpleJavaFileObject(it, JavaFileObject.Kind.SOURCE) {}
                }

                override fun getLineNumber() = line

                override fun getColumnNumber() = column

                override fun getPosition() = Diagnostic.NOPOS

                override fun getStartPosition() = Diagnostic.NOPOS

                override fun getEndPosition() = Diagnostic.NOPOS

                override fun getCode(): String? = null

                override fun getMessage(locale: java.util.Locale?) = message
            }
    }
}
