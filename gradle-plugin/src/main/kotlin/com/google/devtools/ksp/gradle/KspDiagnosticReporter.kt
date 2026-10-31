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

import java.io.StringReader
import java.util.Properties
import javax.tools.DiagnosticListener
import javax.tools.JavaFileObject
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.tooling.core.KotlinToolingVersion

internal interface KspDiagnosticReporter : DiagnosticListener<JavaFileObject> {
    fun flush()
}

internal data class BuildToolsApiSetting(val property: String, val defaultValue: Boolean)

internal fun buildToolsApiSetting(
    platform: KotlinPlatformType,
    kgpVersion: String,
): BuildToolsApiSetting? =
    when (platform) {
        KotlinPlatformType.jvm,
        KotlinPlatformType.androidJvm ->
            BuildToolsApiSetting("kotlin.compiler.runViaBuildToolsApi", true)
        KotlinPlatformType.js,
        KotlinPlatformType.wasm,
        KotlinPlatformType.common -> {
            if (KotlinToolingVersion(kgpVersion) < KotlinToolingVersion("2.4.20")) {
                null
            } else {
                val target =
                    when (platform) {
                        KotlinPlatformType.js -> "js"
                        KotlinPlatformType.wasm -> "wasm"
                        else -> "metadata"
                    }
                BuildToolsApiSetting("kotlin.$target.runViaBuildToolsApi", false)
            }
        }
        KotlinPlatformType.native -> null
    }

internal fun buildToolsApiEnabled(
    setting: BuildToolsApiSetting,
    extraValue: Provider<String>,
    gradleProperty: Provider<String>,
    localProperties: Provider<String>,
): Provider<Boolean> =
    extraValue
        .orElse(gradleProperty)
        .orElse(
            localProperties.map { text ->
                Properties().apply { load(StringReader(text)) }.getProperty(setting.property)
            }
        )
        .map { it.toBoolean() }
        .orElse(setting.defaultValue)

internal fun compilerVersionForDiagnostics(
    useBuildToolsApi: Provider<Boolean>,
    compilerVersion: Provider<String>,
    kgpVersion: String,
    providers: ProviderFactory,
): Provider<String> = useBuildToolsApi.flatMap { enabled ->
    if (enabled) compilerVersion.orElse(kgpVersion) else providers.provider { kgpVersion }
}

internal object KspDiagnosticReporterFactory {
    private val minimumCompiler = KotlinToolingVersion("2.4.0")
    private val minimumGradle = GradleVersion.version("8.13")

    fun isSupported(
        compilerVersion: String,
        useBuildToolsApi: Boolean,
        gradleVersion: GradleVersion,
        classLoader: ClassLoader,
    ): Boolean {
        if (
            !useBuildToolsApi ||
                KotlinToolingVersion(compilerVersion) < minimumCompiler ||
                gradleVersion.baseVersion < minimumGradle
        )
            return false
        return try {
            listOf(
                    "org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer",
                    "org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer\$SourceLocation",
                    "org.gradle.api.problems.Problems",
                    "org.gradle.api.problems.ProblemReporter",
                )
                .forEach { Class.forName(it, false, classLoader) }
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    fun usesProblemsConsole(
        gradleVersion: GradleVersion,
        warningModeAll: Boolean,
        warnEnabled: Boolean,
    ): Boolean =
        warningModeAll && warnEnabled && gradleVersion.baseVersion >= GradleVersion.version("9.3")

    fun create(
        compilerVersion: String,
        useBuildToolsApi: Boolean,
        objects: ObjectFactory,
        logger: Logger,
        warningModeAll: Boolean,
        taskPath: String,
    ): KspDiagnosticReporter? {
        val classLoader = javaClass.classLoader
        val gradleVersion = GradleVersion.current()
        if (!isSupported(compilerVersion, useBuildToolsApi, gradleVersion, classLoader)) return null
        // Keep optional BTA and Problems types out of classes loaded on the legacy path.
        val implementation =
            classLoader
                .loadClass("com.google.devtools.ksp.gradle.BtaKspDiagnosticReporter")
                .asSubclass(KspDiagnosticReporter::class.java)
        return objects.newInstance(
            implementation,
            logger,
            usesProblemsConsole(gradleVersion, warningModeAll, logger.isWarnEnabled),
            taskPath,
        )
    }
}
