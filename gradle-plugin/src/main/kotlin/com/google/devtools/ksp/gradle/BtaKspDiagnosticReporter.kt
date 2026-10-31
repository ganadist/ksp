/*
 * Copyright 2023 Google LLC
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
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

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.tools.Diagnostic
import javax.tools.JavaFileObject
import org.gradle.api.logging.Logger
import org.gradle.api.problems.ProblemGroup
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.Problems
import org.gradle.api.problems.Severity
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer.SourceLocation
import org.jetbrains.kotlin.buildtools.api.KotlinLogger

internal open class BtaKspDiagnosticReporter
@Inject
constructor(
    private val problems: Problems,
    gradleLogger: Logger,
    private val suppressConsole: Boolean,
    private val taskPath: String,
) : KspDiagnosticReporter {
    private data class Pending(
        val severity: CompilerMessageRenderer.Severity,
        val message: String,
        val location: SourceLocation?,
    )

    private val pending = ConcurrentLinkedQueue<Pending>()
    private val logger: KotlinLogger = GradleKotlinLogger(gradleLogger)
    private val renderer =
        object : CompilerMessageRenderer {
            override fun render(
                severity: CompilerMessageRenderer.Severity,
                message: String,
                location: SourceLocation?,
            ): String {
                pending.add(Pending(severity, message, location))
                val position =
                    location
                        ?.let {
                            buildString {
                                append(File(it.path).toURI())
                                if (it.line > 0) append(":${it.line}")
                                if (it.column > 0) append(":${it.column}")
                                append(" ")
                            }
                        }
                        .orEmpty()
                val prefix = if (severity == CompilerMessageRenderer.Severity.ERROR) "e" else "w"
                return "$prefix: $position$message"
            }
        }

    override fun report(diagnostic: Diagnostic<out JavaFileObject>) {
        val severity =
            when (diagnostic.kind) {
                Diagnostic.Kind.ERROR -> CompilerMessageRenderer.Severity.ERROR
                Diagnostic.Kind.WARNING,
                Diagnostic.Kind.MANDATORY_WARNING -> CompilerMessageRenderer.Severity.WARNING
                else -> return
            }
        val rendered =
            renderer.render(severity, diagnostic.getMessage(null), diagnostic.toBtaSourceLocation())
        if (!suppressConsole) {
            when (severity) {
                CompilerMessageRenderer.Severity.ERROR -> logger.error(rendered)
                CompilerMessageRenderer.Severity.WARNING -> logger.warn(rendered)
                else -> Unit
            }
        }
    }

    override fun flush() {
        // BTA callbacks may arrive on compiler threads. Problems need the worker's build operation
        // context.
        while (true) {
            val diagnostic = pending.poll() ?: return
            val isError = diagnostic.severity == CompilerMessageRenderer.Severity.ERROR
            val location = diagnostic.location
            val identity =
                listOf(
                        taskPath,
                        diagnostic.severity.name,
                        diagnostic.message,
                        location?.path.orEmpty(),
                        location?.line?.toString().orEmpty(),
                        location?.column?.toString().orEmpty(),
                    )
                    .joinToString("") { "${it.length}:$it" }
            val fingerprint =
                MessageDigest.getInstance("SHA-256")
                    .digest(identity.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            val id =
                ProblemId.create(
                    "processor-${if (isError) "error" else "warning"}-$fingerprint",
                    if (isError) "KSP processor error" else "KSP processor warning",
                    ProblemGroup.create("ksp", "Kotlin Symbol Processing"),
                )
            problems.reporter.report(id) { spec ->
                spec.contextualLabel(diagnostic.message)
                spec.severity(if (isError) Severity.ERROR else Severity.WARNING)
                diagnostic.location?.let { location ->
                    when {
                        location.line > 0 && location.column > 0 ->
                            spec.lineInFileLocation(location.path, location.line, location.column)
                        location.line > 0 -> spec.lineInFileLocation(location.path, location.line)
                        else -> spec.fileLocation(location.path)
                    }
                }
            }
        }
    }
}

internal fun Diagnostic<out JavaFileObject>.toBtaSourceLocation(): SourceLocation? = source?.let {
    SourceLocation(
        path = File(it.toUri()).path,
        line = lineNumber.toInt(),
        column = columnNumber.toInt(),
        lineEnd = -1,
        columnEnd = -1,
        lineContent = null,
    )
}

private class GradleKotlinLogger(private val logger: Logger) : KotlinLogger {
    override val isDebugEnabled: Boolean
        get() = logger.isDebugEnabled

    override fun error(msg: String, throwable: Throwable?) = logger.error(msg, throwable)

    override fun warn(msg: String, throwable: Throwable?) = logger.warn(msg, throwable)

    override fun info(msg: String) = logger.info(msg)

    override fun debug(msg: String) = logger.debug(msg)

    override fun lifecycle(msg: String) = logger.lifecycle(msg)
}
