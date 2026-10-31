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

package com.google.devtools.ksp

import com.google.devtools.ksp.processing.KspGradleLogger
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSVisitor
import com.google.devtools.ksp.symbol.Location
import com.google.devtools.ksp.symbol.NonExistLocation
import com.google.devtools.ksp.symbol.Origin
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.JavaFileObject

class KspGradleLoggerTest {
    @Test
    fun warningPreservesSourceAndLineWithoutColumn() {
        val diagnostics = mutableListOf<Diagnostic<out JavaFileObject>>()
        val logger = KspGradleLogger(0, DiagnosticListener { diagnostics.add(it) })
        logger.warn("warning", node(FileLocation("/src/Names.kt", 3)))

        val diagnostic = diagnostics.single()
        assertEquals(Diagnostic.Kind.WARNING, diagnostic.kind)
        assertEquals("[ksp] warning", diagnostic.getMessage(null))
        assertEquals(File("/src/Names.kt").toURI(), diagnostic.source.toUri())
        assertEquals(3L, diagnostic.lineNumber)
        assertEquals(Diagnostic.NOPOS, diagnostic.columnNumber)
        assertEquals(Diagnostic.NOPOS, diagnostic.position)
        assertEquals(Diagnostic.NOPOS, diagnostic.startPosition)
        assertEquals(Diagnostic.NOPOS, diagnostic.endPosition)
    }

    @Test
    fun errorsWithoutSourceKeepUnknownPositions() {
        val diagnostics = mutableListOf<Diagnostic<out JavaFileObject>>()
        val logger = KspGradleLogger(0, DiagnosticListener { diagnostics.add(it) })
        logger.error("error")
        logger.error("unknown", node(NonExistLocation))
        logger.exception(IllegalStateException("failure"))

        diagnostics.forEach {
            assertEquals(Diagnostic.Kind.ERROR, it.kind)
            assertNull(it.source)
            assertEquals(Diagnostic.NOPOS, it.lineNumber)
            assertEquals(Diagnostic.NOPOS, it.columnNumber)
        }
        assertEquals(3, diagnostics.size)
        assertEquals("[ksp] java.lang.IllegalStateException: failure", diagnostics.last().getMessage(null))
    }

    @Test
    fun listenerReplacesWarningAndErrorStdoutButKeepsInfoAndLogging() {
        val diagnostics = mutableListOf<Diagnostic<out JavaFileObject>>()
        val output = captureOutput {
            val logger = KspGradleLogger(0, DiagnosticListener { diagnostics.add(it) })
            logger.logging("logging")
            logger.info("info")
            logger.warn("warning")
            logger.error("error")
        }
        assertEquals("v: [ksp] logging\ni: [ksp] info\n", output)
        assertEquals(listOf(Diagnostic.Kind.WARNING, Diagnostic.Kind.ERROR), diagnostics.map { it.kind })
    }

    @Test
    fun legacyStdoutAndLogLevelRemainUnchanged() {
        val output = captureOutput {
            val logger = KspGradleLogger(0)
            logger.warn("warning", node(FileLocation("/src/Names.kt", 3)))
            logger.error("error")
            logger.exception(IllegalStateException("failure"))
            val diagnostics = mutableListOf<Diagnostic<out JavaFileObject>>()
            val filtered = KspGradleLogger(5, DiagnosticListener { diagnostics.add(it) })
            filtered.warn("hidden")
            assertEquals(Diagnostic.Kind.WARNING, diagnostics.single().kind)
            filtered.error("visible")
            assertEquals(Diagnostic.Kind.ERROR, diagnostics.last().kind)
            KspGradleLogger(5).warn("hidden")
        }
        assertEquals(
            "w: [ksp] /src/Names.kt:3: warning\ne: [ksp] error\ne: [ksp] java.lang.IllegalStateException: failure\n",
            output
        )
    }

    private fun node(location: Location): KSNode = object : KSNode {
        override val origin = Origin.KOTLIN
        override val location = location
        override val parent: KSNode? = null
        override fun <D, R> accept(visitor: KSVisitor<D, R>, data: D): R = visitor.visitNode(this, data)
    }

    private fun captureOutput(block: () -> Unit): String {
        val previous = System.out
        val output = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(output))
            block()
        } finally {
            System.setOut(previous)
        }
        return output.toString().replace("\r\n", "\n")
    }
}
