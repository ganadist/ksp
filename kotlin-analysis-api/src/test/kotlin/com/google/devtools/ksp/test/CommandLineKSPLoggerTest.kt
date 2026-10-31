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

import com.google.devtools.ksp.impl.CommandLineKSPLogger
import com.google.devtools.ksp.impl.DiagnosticNode
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.NonExistLocation
import com.google.devtools.ksp.symbol.Origin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class CommandLineKSPLoggerTest {
    @Test
    fun checkerLocationsKeepLegacyFormatAndProcessorErrorsStayUndecorated() {
        val previous = System.err
        val output = ByteArrayOutputStream()
        try {
            System.setErr(PrintStream(output))
            val logger = CommandLineKSPLogger()
            val checkerNode = DiagnosticNode(FileLocation("/src/Broken.kt", 3), Origin.KOTLIN)
            logger.error("checker error", checkerNode)
            logger.error("processor error", object : KSNode by checkerNode {})
            logger.error("no source", DiagnosticNode(NonExistLocation, Origin.KOTLIN))
        } finally {
            System.setErr(previous)
        }
        assertEquals(
            "Broken.kt:3: checker error\nprocessor error\nno source\n",
            output.toString().replace("\r\n", "\n")
        )
    }
}
