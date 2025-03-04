// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.debugger.core.filter

import com.intellij.debugger.engine.SyntheticTypeComponentProvider
import com.sun.jdi.*
import com.intellij.debugger.impl.DexDebugFacility
import org.jetbrains.kotlin.idea.debugger.base.util.KotlinDebuggerConstants.SUSPEND_IMPL_NAME_SUFFIX
import org.jetbrains.kotlin.idea.debugger.base.util.safeAllLineLocations
import org.jetbrains.kotlin.idea.debugger.core.DexBytecodeInspector
import org.jetbrains.kotlin.idea.debugger.core.isInKotlinSources
import org.jetbrains.kotlin.idea.debugger.core.stepping.isSyntheticMethodForDefaultParameters
import org.jetbrains.kotlin.idea.debugger.isGeneratedErasedLambdaMethod
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.org.objectweb.asm.Opcodes
import kotlin.jvm.internal.FunctionReference
import kotlin.jvm.internal.PropertyReference

class KotlinSyntheticTypeComponentProvider : SyntheticTypeComponentProvider {
    override fun isSynthetic(typeComponent: TypeComponent?): Boolean {
        if (typeComponent !is Method) return false

        if (typeComponent.isGeneratedErasedLambdaMethod()) return true

        val containingType = typeComponent.declaringType()
        val typeName = containingType.name()
        if (!FqNameUnsafe.isValid(typeName)) return false

        // TODO: this is most likely not necessary since KT-28453 is fixed, but still can be useful when debugging old compiled code
        if (containingType.isCallableReferenceSyntheticClass()) {
            return true
        }

        try {
            if (typeComponent.isDelegateToDefaultInterfaceImpl()) return true

            if (typeComponent.location()?.lineNumber() != 1) return false

            if (typeComponent.allLineLocations().any { it.lineNumber() != 1 }) {
                return false
            }

            return !typeComponent.declaringType().safeAllLineLocations().any { it.lineNumber() != 1 }
        } catch (e: AbsentInformationException) {
            return false
        } catch (e: UnsupportedOperationException) {
            return false
        }
    }

    override fun isNotSynthetic(typeComponent: TypeComponent?): Boolean {
        if (typeComponent is Method) {
            val name = typeComponent.name()

            if (name.endsWith(SUSPEND_IMPL_NAME_SUFFIX)) {
                if (typeComponent.location()?.isInKotlinSources() == true) {
                    val containingClass = typeComponent.declaringType()
                    if (typeComponent.argumentTypeNames().firstOrNull() == containingClass.name()) {
                        // Suspend wrapper for open method
                        return true
                    }
                }
            } else if (name.endsWith(JvmAbi.DEFAULT_PARAMS_IMPL_SUFFIX)) {
                val originalName = name.dropLast(JvmAbi.DEFAULT_PARAMS_IMPL_SUFFIX.length)
                return typeComponent.declaringType().methodsByName(originalName).isNotEmpty()
            } else if (typeComponent.isSyntheticMethodForDefaultParameters()) {
                return true
            }
        }

        return super.isNotSynthetic(typeComponent)
    }

    private tailrec fun ReferenceType?.isCallableReferenceSyntheticClass(): Boolean {
        if (this !is ClassType) return false
        val superClass = this.superclass() ?: return false
        val superClassName = superClass.name()
        if (superClassName == PropertyReference::class.java.name || superClassName == FunctionReference::class.java.name) {
            return true
        }

        // The direct supertype may be FunctionReferenceImpl, PropertyReference0Impl, MutablePropertyReference0, etc.
        return if (superClassName.startsWith("kotlin.jvm.internal."))
            superClass.isCallableReferenceSyntheticClass()
        else
            false
    }

    private fun Method.isDelegateToDefaultInterfaceImpl(): Boolean {
        if (safeAllLineLocations().size != 1) return false
        if (!virtualMachine().canGetBytecodes()) return false

        if (!hasOnlyInvokeStatic(this)) return false

        return hasInterfaceWithImplementation(this)
    }

    private fun hasOnlyInvokeStatic(m: Method): Boolean {
        if (DexDebugFacility.isDex(m.virtualMachine())) {
            return DexBytecodeInspector.EP.extensionList.firstOrNull()?.hasOnlyInvokeStatic(m) == true
        }
        return hasOnlyInvokeStaticJVM(m)
    }

    // Check that method contains only load and invokeStatic instructions. Note that if after load goes ldc instruction it could be checkParametersNotNull method invocation
    private fun hasOnlyInvokeStaticJVM(m: Method): Boolean {
        val instructions = m.bytecodes()
        var i = 0
        var isALoad0BeforeStaticCall = false
        while (i < instructions.size) {
            when (val instr = instructions[i]) {
                42.toByte() /* ALOAD_0 */ -> {
                    i += 1
                    isALoad0BeforeStaticCall = true
                }
                in LOAD_INSTRUCTIONS_WITH_INDEX, in LOAD_INSTRUCTIONS -> {
                    i += 1
                    if (instr in LOAD_INSTRUCTIONS_WITH_INDEX) i += 1
                    val nextInstr = instructions[i]
                    if (nextInstr == Opcodes.LDC.toByte()) {
                        i += 2
                        isALoad0BeforeStaticCall = false
                    }
                }
                Opcodes.INVOKESTATIC.toByte() -> {
                    i += 3
                    if (isALoad0BeforeStaticCall && i == (instructions.size - 1)) {
                        val nextInstr = instructions[i]
                        return nextInstr in RETURN_INSTRUCTIONS
                    }
                }
                Opcodes.CHECKCAST.toByte() -> {
                    if (instructions[++i] != Opcodes.NOP.toByte()) return false
                    if (instructions[++i] !in ICONST_INSTRUCTIONS) return false
                    ++i
                }
                else -> return false
            }
        }
        return false
    }

    // TODO: class DefaultImpl can be not loaded
    private fun hasInterfaceWithImplementation(method: Method): Boolean {
        val declaringType = method.declaringType() as? ClassType ?: return false
        val interfaces = declaringType.allInterfaces()
        val vm = declaringType.virtualMachine()
        val traitImpls = interfaces.flatMap { vm.classesByName(it.name() + JvmAbi.DEFAULT_IMPLS_SUFFIX) }
        return traitImpls.any { it.methodsByName(method.name()).isNotEmpty() }
    }
}

private val LOAD_INSTRUCTIONS_WITH_INDEX = Opcodes.ILOAD.toByte()..Opcodes.ALOAD.toByte()
private val LOAD_INSTRUCTIONS = (Opcodes.ALOAD + 1).toByte()..(Opcodes.IALOAD - 1).toByte()
private val RETURN_INSTRUCTIONS = Opcodes.IRETURN.toByte()..Opcodes.RETURN.toByte()
private val ICONST_INSTRUCTIONS = Opcodes.ICONST_M1..Opcodes.ICONST_5
