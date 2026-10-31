/*
 * Copyright 2026 Google LLC
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
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

package com.google.devtools.ksp.test

import com.google.devtools.ksp.impl.KSPLoader
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSDeclarationContainer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.JavaFileObject

class DiagnosticLocationTest {
    @TempDir
    lateinit var root: File

    @Test
    fun processorDiagnosticsPreserveKotlinAndJavaSourceLines() {
        val kotlin = File(root, "Names.kt").apply {
            writeText("annotation class Mark\n@Mark\n    class Name\nclass First; class Second\n")
        }
        val java = File(root, "JavaNames.java").apply {
            writeText("class JavaNames { int first; int second; }\n")
        }
        val diagnostics = runProcessing { environment ->
            object : SymbolProcessor {
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    fun report(container: KSDeclarationContainer) {
                        container.declarations.forEach {
                            environment.logger.warn(it.simpleName.asString(), it)
                            if (it is KSDeclarationContainer) report(it)
                        }
                    }
                    resolver.getAllFiles().forEach(::report)
                    return emptyList()
                }
            }
        }
        fun assertPosition(name: String, file: File, line: Long) {
            val diagnostic = diagnostics.single { it.getMessage(null) == "[ksp] $name" }
            assertEquals(Diagnostic.Kind.WARNING, diagnostic.kind)
            assertEquals(file.toURI(), diagnostic.source.toUri())
            assertEquals(line, diagnostic.lineNumber)
            assertEquals(Diagnostic.NOPOS, diagnostic.columnNumber)
        }
        assertPosition("Name", kotlin, 3)
        assertPosition("First", kotlin, 4)
        assertPosition("Second", kotlin, 4)
        assertPosition("first", java, 1)
        assertPosition("second", java, 1)
    }

    @Test
    fun checkerDiagnosticsPreserveSourceLinesAndRepeatedJavaErrors() {
        val kotlin = File(root, "Broken.kt").apply { writeText("val broken: String = 1\n") }
        val java = File(root, "BrokenJava.java").apply {
            writeText("class BrokenJava { Missing first; Missing second; }\n")
        }
        val diagnostics = runProcessing { environment ->
            object : SymbolProcessor {
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    environment.logger.error("trigger type checking")
                    return emptyList()
                }
            }
        }
        val kotlinDiagnostics = diagnostics.filter { it.source?.toUri() == kotlin.toURI() }
        assertTrue(kotlinDiagnostics.isNotEmpty())
        assertTrue(kotlinDiagnostics.all { it.kind == Diagnostic.Kind.ERROR })
        assertTrue(kotlinDiagnostics.all { it.lineNumber == 1L })
        assertTrue(kotlinDiagnostics.all { it.columnNumber == Diagnostic.NOPOS })
        val javaDiagnostics = diagnostics.filter { it.source?.toUri() == java.toURI() }
        assertEquals(listOf(1L, 1L), javaDiagnostics.map { it.lineNumber })
        assertTrue(javaDiagnostics.all { it.columnNumber == Diagnostic.NOPOS })
        assertTrue(javaDiagnostics.all { it.getMessage(null) == "[ksp] Unresolved reference 'Missing'" })
    }

    private fun runProcessing(create: (SymbolProcessorEnvironment) -> SymbolProcessor): List<Diagnostic<out JavaFileObject>> {
        val config = KSPJvmConfig.Builder().apply {
            moduleName = "diagnosticTest"
            sourceRoots = listOf(root)
            javaSourceRoots = listOf(root)
            jdkHome = File(System.getProperty("java.home"))
            jvmTarget = "1.8"
            languageVersion = "2.3"
            apiVersion = "2.3"
            libraries = listOf(File("dist/kotlinc/lib/kotlin-stdlib.jar"))
            projectBaseDir = root
            outputBaseDir = File(root, "output")
            cachesDir = File(root, "caches")
            classOutputDir = File(outputBaseDir, "classes")
            javaOutputDir = File(outputBaseDir, "java")
            kotlinOutputDir = File(outputBaseDir, "kotlin")
            resourceOutputDir = File(outputBaseDir, "resources")
        }.build()
        val stream = ByteArrayOutputStream().apply { ObjectOutputStream(this).use { it.writeObject(config) } }
        val diagnostics = mutableListOf<Diagnostic<out JavaFileObject>>()
        val provider = object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = create(environment)
        }
        val exitCode = KSPLoader.loadAndRunKSP(
            stream.toByteArray(), listOf(provider), 0, DiagnosticListener { diagnostics.add(it) }
        )
        assertEquals(if (diagnostics.any { it.kind == Diagnostic.Kind.ERROR }) 1 else 0, exitCode)
        return diagnostics
    }
}
