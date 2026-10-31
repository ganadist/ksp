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
package com.google.devtools.ksp.processing

import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.Location
import java.io.File
import java.util.Locale
import javax.tools.Diagnostic
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject

/**
 * Adapts a KSP warning or error to the JDK [Diagnostic] contract.
 *
 * The isolated engine delivers this object through [javax.tools.DiagnosticListener], so Gradle and BTA
 * classes stay outside the engine's classloader. The receiver renders the message with its severity
 * and source location.
 *
 * KSP runs on the JVM for all compilation targets, including common, JS, Wasm, and Native sources.
 * [JavaFileObject] exposes the source URI for either a Kotlin or Java file.
 *
 * [FileLocation] supplies the source URI and 1-based line number when known. Other locations have
 * no source. Unknown lines, columns, and all character offsets use [Diagnostic.NOPOS]. Source
 * contents are not read.
 */
internal class KspDiagnostic(
    private val diagnosticKind: Diagnostic.Kind,
    private val message: String,
    location: Location?
) : Diagnostic<JavaFileObject> {
    private val fileLocation = location as? FileLocation
    private val sourceFile = fileLocation?.let {
        object : SimpleJavaFileObject(File(it.filePath).toURI(), JavaFileObject.Kind.SOURCE) {}
    }

    override fun getKind(): Diagnostic.Kind = diagnosticKind
    override fun getMessage(locale: Locale?): String = message
    override fun getSource(): JavaFileObject? = sourceFile
    override fun getLineNumber(): Long = fileLocation?.lineNumber?.toLong() ?: Diagnostic.NOPOS
    override fun getColumnNumber(): Long = Diagnostic.NOPOS
    override fun getPosition(): Long = Diagnostic.NOPOS
    override fun getStartPosition(): Long = Diagnostic.NOPOS
    override fun getEndPosition(): Long = Diagnostic.NOPOS
    override fun getCode(): String = "ksp"
}
