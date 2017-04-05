/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.backend.konan.llvm

import kotlinx.cinterop.*
import llvm.*
import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanConfigKeys
import org.jetbrains.kotlin.backend.konan.KonanPhase
import org.jetbrains.kotlin.backend.konan.PhaseManager
import org.jetbrains.kotlin.backend.konan.descriptors.*
import org.jetbrains.kotlin.backend.konan.ir.IrInlineFunctionBody
import org.jetbrains.kotlin.backend.konan.ir.ir2string
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltinOperatorDescriptorBase
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.isNothing
import org.jetbrains.kotlin.types.typeUtil.isPrimitiveNumberType
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter
import org.jetbrains.kotlin.types.typeUtil.isUnit


internal fun emitLLVM(context: Context) {

        val irModule = context.irModule!!
        // Note that we don't set module target explicitly.
        // It is determined by the target of runtime.bc
        // (see Llvm class in ContextUtils)
        // Which in turn is determined by the clang flags
        // used to compile runtime.bc.
        val llvmModule = LLVMModuleCreateWithName("out")!! // TODO: dispose
        context.llvmModule = llvmModule

        context.llvmDeclarations = createLlvmDeclarations(context)

        val phaser = PhaseManager(context)

        phaser.phase(KonanPhase.RTTI) {
            irModule.acceptVoid(RTTIGeneratorVisitor(context))
        }

        phaser.phase(KonanPhase.CODEGEN) {
            irModule.acceptVoid(CodeGeneratorVisitor(context))
        }

        phaser.phase(KonanPhase.METADATOR) {
            irModule.acceptVoid(MetadatorVisitor(context))
        }

        phaser.phase(KonanPhase.BITCODE_LINKER) {
            for (library in context.config.nativeLibraries) {
                val libraryModule = parseBitcodeFile(library)
                val failed = LLVMLinkModules2(llvmModule, libraryModule)
                if (failed != 0) {
                    throw Error("failed to link $library") // TODO: retrieve error message from LLVM.
                }
            }
        }

        val outFile = context.config.configuration.get(KonanConfigKeys.BITCODE_FILE)!!
        LLVMWriteBitcodeToFile(llvmModule, outFile)
}

internal fun verifyModule(llvmModule: LLVMModuleRef, current: String = "") {
    memScoped {
        val errorRef = allocPointerTo<ByteVar>()
        // TODO: use LLVMDisposeMessage() on errorRef, once possible in interop.
        if (LLVMVerifyModule(
                llvmModule, LLVMVerifierFailureAction.LLVMPrintMessageAction, errorRef.ptr) == 1) {
            if (current.isNotEmpty())
                println("Error in ${current}")
            LLVMDumpModule(llvmModule)
            throw Error("Invalid module");
        }
    }
}

internal class RTTIGeneratorVisitor(context: Context) : IrElementVisitorVoid {
    val generator = RTTIGenerator(context)

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitClass(declaration: IrClass) {
        super.visitClass(declaration)

        if (declaration.descriptor.isIntrinsic) {
            // do not generate any code for intrinsic classes as they require special handling
            return
        }

        generator.generate(declaration.descriptor)
    }

}

//-------------------------------------------------------------------------//

internal class MetadatorVisitor(val context: Context) : IrElementVisitorVoid {

    val metadator = MetadataGenerator(context)

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitModuleFragment(module: IrModuleFragment) {
        module.acceptChildrenVoid(this)
        metadator.endModule(module)
    }
}

/**
 * Defines how to generate context-dependent operations.
 */
internal interface CodeContext {

    /**
     * Generates `return` [value] operation.
     *
     * @param value may be null iff target type is `Unit`.
     */
    fun genReturn(target: CallableDescriptor, value: LLVMValueRef?)

    fun genBreak(destination: IrBreak)

    fun genContinue(destination: IrContinue)

    fun genCall(function: LLVMValueRef, args: List<LLVMValueRef>, resultLifetime: Lifetime): LLVMValueRef

    fun genThrow(exception: LLVMValueRef)

    /**
     * Declares the variable.
     * @return index of declared variable.
     */
    fun genDeclareVariable(descriptor: VariableDescriptor, value: LLVMValueRef?): Int

    /**
     * @return index of variable declared before, or -1 if no such variable has been declared yet.
     */
    fun getDeclaredVariable(descriptor: VariableDescriptor): Int

    /**
     * Generates the code to obtain a value available in this context.
     *
     * @return the requested value
     */
    fun genGetValue(descriptor: ValueDescriptor): LLVMValueRef

    /**
     * Returns owning function scope.
     *
     * @return the requested value
     */
    fun functionScope(): CodeContext

}

//-------------------------------------------------------------------------//

internal class CodeGeneratorVisitor(val context: Context) : IrElementVisitorVoid {

    val codegen = CodeGenerator(context)
    val resultLifetimes = mutableMapOf<IrElement, Lifetime>()

    //-------------------------------------------------------------------------//

    // TODO: consider eliminating mutable state
    private var currentCodeContext: CodeContext = TopLevelCodeContext


    /**
     * Fake [CodeContext] that doesn't support any operation.
     *
     * During function code generation [FunctionScope] should be set up.
     */
    private object TopLevelCodeContext : CodeContext {
        private fun unsupported(any: Any? = null): Nothing = throw UnsupportedOperationException(any?.toString() ?: "")

        override fun genReturn(target: CallableDescriptor, value: LLVMValueRef?) = unsupported(target)

        override fun genBreak(destination: IrBreak) = unsupported()

        override fun genContinue(destination: IrContinue) = unsupported()

        override fun genCall(function: LLVMValueRef, args: List<LLVMValueRef>, resultLifetime: Lifetime) = unsupported(function)

        override fun genThrow(exception: LLVMValueRef) = unsupported()

        override fun genDeclareVariable(descriptor: VariableDescriptor, value: LLVMValueRef?) = unsupported(descriptor)

        override fun getDeclaredVariable(descriptor: VariableDescriptor) = -1

        override fun genGetValue(descriptor: ValueDescriptor) = unsupported(descriptor)

        override fun functionScope(): CodeContext = unsupported()
    }

    /**
     * The [CodeContext] which can define some operations and delegate other ones to [outerContext]
     */
    private abstract class InnerScope(val outerContext: CodeContext) : CodeContext by outerContext

    /**
     * Convenient [InnerScope] implementation that is bound to the [currentCodeContext].
     */
    private abstract inner class InnerScopeImpl : InnerScope(currentCodeContext)
    /**
     * Executes [block] with [codeContext] substituted as [currentCodeContext].
     */
    private inline fun <R> using(codeContext: CodeContext?, block: () -> R): R {
        val oldCodeContext = currentCodeContext
        if (codeContext != null) {
            currentCodeContext = codeContext
        }
        try {
            return block()
        } finally {
            currentCodeContext = oldCodeContext
        }
    }

    //-------------------------------------------------------------------------//

    override fun visitElement(element: IrElement) {
        TODO(ir2string(element))
    }

    //-------------------------------------------------------------------------//
    override fun visitModuleFragment(module: IrModuleFragment) {
        context.log("visitModule                    : ${ir2string(module)}")

        // computeLifetimes(module, this.codegen, resultLifetimes)

        module.acceptChildrenVoid(this)
        appendLlvmUsed(context.llvm.usedFunctions)
        appendStaticInitializers(context.llvm.staticInitializers)
    }

    //-------------------------------------------------------------------------//

    val kVoidFuncType = LLVMFunctionType(LLVMVoidType(), null, 0, 0)
    val kNodeInitType = LLVMGetTypeByName(context.llvmModule, "struct.InitNode")!!
    //-------------------------------------------------------------------------//

    fun createInitBody(initName: String): LLVMValueRef {
        val initFunction = LLVMAddFunction(context.llvmModule, initName, kVoidFuncType)!!    // create LLVM function
        codegen.prologue(initFunction, voidType)
        using(FunctionScope(initFunction)) {
            context.llvm.fileInitializers.forEach {
                val irField = it as IrField
                val descriptor = irField.descriptor
                val initialization = evaluateExpression(irField.initializer!!.expression)
                val globalPtr = context.llvmDeclarations.forStaticField(descriptor).storage
                codegen.storeAnyGlobal(initialization, globalPtr)
            }
            codegen.ret(null)
        }
        codegen.epilogue()

        return initFunction
    }

    //-------------------------------------------------------------------------//
    // Creates static struct InitNode $nodeName = {$initName, NULL};

    fun createInitNode(initFunction: LLVMValueRef, nodeName: String): LLVMValueRef {
        val nextInitNode = LLVMConstNull(pointerType(kNodeInitType))                    // Set InitNode.next = NULL.
        val argList      = cValuesOf(initFunction, nextInitNode)                        // Construct array of args.
        val initNode     = LLVMConstNamedStruct(kNodeInitType, argList, 2)!!            // Create static object of class InitNode.
        return context.llvm.staticData.placeGlobal(nodeName, constPointer(initNode)).llvmGlobal     // Put the object in global var with name "nodeName".
    }

    //-------------------------------------------------------------------------//

    fun createInitCtor(ctorName: String, initNodePtr: LLVMValueRef) {
        val ctorFunction = LLVMAddFunction(context.llvmModule, ctorName, kVoidFuncType)!!   // Create constructor function.
        codegen.prologue(ctorFunction, voidType)
        codegen.call(context.llvm.appendToInitalizersTail, listOf(initNodePtr))             // Add node to the tail of initializers list.
        codegen.ret(null)
        codegen.epilogue()

        context.llvm.staticInitializers.add(ctorFunction)                                   // Push newly created constructor in staticInitializers list.
    }

    //-------------------------------------------------------------------------//

    override fun visitFile(declaration: IrFile) {

        context.llvm.fileInitializers.clear()

        declaration.acceptChildrenVoid(this)

        if (context.llvm.fileInitializers.isEmpty())
            return

        // Create global initialization records.
        val fileName = declaration.name.takeLastWhile { it != '/' }.dropLastWhile { it != '.' }.dropLast(1)
        val initName = "${fileName}_init_${context.llvm.globalInitIndex}"
        val nodeName = "${fileName}_node_${context.llvm.globalInitIndex}"
        val ctorName = "${fileName}_ctor_${context.llvm.globalInitIndex++}"

        val initFunction = createInitBody(initName)
        val initNode = createInitNode(initFunction, nodeName)
        createInitCtor(ctorName, initNode)
    }

    //-------------------------------------------------------------------------//

    private inner class LoopScope(val loop: IrLoop) : InnerScopeImpl() {
        val loopExit  = codegen.basicBlock("loop_exit")
        val loopCheck = codegen.basicBlock("loop_check")

        override fun genBreak(destination: IrBreak) {
            if (destination.loop == loop)
                codegen.br(loopExit)
            else
                super.genBreak(destination)
        }

        override fun genContinue(destination: IrContinue) {
            if (destination.loop == loop)
                codegen.br(loopCheck)
            else
                super.genContinue(destination)
        }
    }

    //-------------------------------------------------------------------------//

    fun evaluateBreak(destination: IrBreak): LLVMValueRef {
        currentCodeContext.genBreak(destination)
        return codegen.kNothingFakeValue
    }

    //-------------------------------------------------------------------------//

    fun evaluateContinue(destination: IrContinue): LLVMValueRef {
        currentCodeContext.genContinue(destination)
        return codegen.kNothingFakeValue
    }

    //-------------------------------------------------------------------------//

    override fun visitConstructor(constructorDeclaration: IrConstructor) {
        context.log("visitConstructor               : ${ir2string(constructorDeclaration)}")
        if (constructorDeclaration.descriptor.containingDeclaration.isIntrinsic) {
            // Do not generate any ctors for intrinsic classes.
            return
        }

        val constructorDescriptor = constructorDeclaration.descriptor
        val classDescriptor = constructorDescriptor.constructedClass
        if (constructorDescriptor.isPrimary) {
            if (DescriptorUtils.isObject(classDescriptor)) {
                if (!classDescriptor.isUnit()) {
                    val objectPtr = objectPtrByName(classDescriptor)

                    LLVMSetInitializer(objectPtr, codegen.kNullObjHeaderPtr)
                }
            }
        }

        visitFunction(constructorDeclaration)
    }

    //-------------------------------------------------------------------------//

    override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer) {
        context.log("visitAnonymousInitializer      : ${ir2string(declaration)}")
    }

    //-------------------------------------------------------------------------//

    /**
     * The scope of variable visibility.
     */
    private inner class VariableScope : InnerScopeImpl() {

        override fun genDeclareVariable(descriptor: VariableDescriptor, value: LLVMValueRef?): Int {
            return codegen.vars.createVariable(descriptor to this, value)
        }

        override fun getDeclaredVariable(descriptor: VariableDescriptor): Int {
            val index = codegen.vars.indexOf(descriptor to this)
            return if (index < 0) super.getDeclaredVariable(descriptor) else return index
        }

        override fun genGetValue(descriptor: ValueDescriptor): LLVMValueRef {
            val index = codegen.vars.indexOf(descriptor to this)
            if (index < 0) {
                return super.genGetValue(descriptor)
            } else {
                return codegen.vars.load(index)
            }
        }
    }

    /**
     * The scope of parameter visibility.
     */
    private open inner class ParameterScope(
            parameters: Map<ParameterDescriptor, LLVMValueRef>): InnerScopeImpl() {

        // Note: it is possible to generate access to a parameter without any variables,
        // however variables are named and thus the resulting bitcode can be more readable.

        init {
            parameters.map { (descriptor, value) ->
                codegen.vars.createImmutable(descriptor to this, value)
            }

        }

        override fun genGetValue(descriptor: ValueDescriptor): LLVMValueRef {
            val index = codegen.vars.indexOf(descriptor to this)
            if (index < 0) {
                return super.genGetValue(descriptor)
            } else {
                return codegen.vars.load(index)
            }
        }
    }

    /**
     * The [CodeContext] enclosing the entire function body.
     */
    private inner class FunctionScope (val declaration: IrFunction?) :
            ParameterScope(bindParameters(declaration?.descriptor)) {
        constructor(llvmFunction:LLVMValueRef):this(null) {
            this.llvmFunction = llvmFunction
        }

        var llvmFunction:LLVMValueRef? = declaration?.let{
            codegen.llvmFunction(declaration.descriptor)
        }

        override fun genReturn(target: CallableDescriptor, value: LLVMValueRef?) {
            if (declaration == null || target == declaration.descriptor) {
                if (target.returnType!!.isUnit()) {
                    assert (value == null)
                    codegen.ret(null)
                } else {
                    codegen.ret(value!!)
                }
            } else {
                super.genReturn(target, value)
            }
        }

        override fun genCall(function: LLVMValueRef, args: List<LLVMValueRef>, resultLifetime: Lifetime) =
                codegen.callAtFunctionScope(function, args, resultLifetime)

        override fun genThrow(exception: LLVMValueRef) {
            val objHeaderPtr = codegen.bitcast(codegen.kObjHeaderPtr, exception)
            val args = listOf(objHeaderPtr)

            this.genCall(context.llvm.throwExceptionFunction, args, Lifetime.IRRELEVANT)
            codegen.unreachable()
        }

        override fun functionScope(): CodeContext = this
    }

    /**
     * Binds LLVM function parameters to IR parameter descriptors.
     */
    private fun bindParameters(descriptor: FunctionDescriptor?): Map<ParameterDescriptor, LLVMValueRef> {
        if (descriptor == null) return emptyMap()
        val parameterDescriptors = descriptor.allParameters
        return parameterDescriptors.mapIndexed { i, parameterDescriptor ->
            val parameter = codegen.param(descriptor, i)
            assert(codegen.getLLVMType(parameterDescriptor.type) == parameter.type)
            parameterDescriptor to parameter
        }.toMap()
    }

    override fun visitFunction(declaration: IrFunction) {
        context.log("visitFunction                  : ${ir2string(declaration)}")
        val body = declaration.body

        if (declaration.descriptor.modality == Modality.ABSTRACT) return
        if (declaration.descriptor.isExternal)                    return
        if (body == null)                                         return

        codegen.prologue(declaration.descriptor)

        using(FunctionScope(declaration)) {
            using(VariableScope()) {
                context.log("generateBlockBody              : ${ir2string(body)}")
                when (body) {
                    is IrBlockBody -> body.statements.forEach { generateStatement(it) }
                    is IrExpressionBody -> generateStatement(body.expression)
                    is IrSyntheticBody -> throw AssertionError("Synthetic body ${body.kind} has not been lowered")
                    else -> TODO(ir2string(body))
                }
            }
        }

        if (!codegen.isAfterTerminator()) {
            if (codegen.returnType == voidType)
                codegen.ret(null)
            else
                codegen.unreachable()
        }

        codegen.epilogue()

        if (context.shouldVerifyBitCode())
            verifyModule(context.llvmModule!!,
                "${declaration.descriptor.containingDeclaration}::${ir2string(declaration)}")
    }

    //-------------------------------------------------------------------------//

    override fun visitClass(declaration: IrClass) {
        context.log("visitClass                     : ${ir2string(declaration)}")
        if (declaration.descriptor.kind == ClassKind.ANNOTATION_CLASS) {
            // do not generate any code for annotation classes as a workaround for NotImplementedError
            return
        }

        declaration.acceptChildrenVoid(this)
    }

    //-------------------------------------------------------------------------//

    override fun visitTypeAlias(declaration: IrTypeAlias) {
        // Nothing to do.
    }

    //-------------------------------------------------------------------------//

    override fun visitProperty(declaration: IrProperty) {
        declaration.acceptChildrenVoid(this)
    }

    //-------------------------------------------------------------------------//

    override fun visitField(expression: IrField) {
        context.log("visitField                     : ${ir2string(expression)}")
        val descriptor = expression.descriptor
        if (descriptor.containingDeclaration is PackageFragmentDescriptor) {
            val type = codegen.getLLVMType(descriptor.type)
            val globalProperty = context.llvmDeclarations.forStaticField(descriptor).storage
            if (expression.initializer!!.expression is IrConst<*>) {
                LLVMSetInitializer(globalProperty, evaluateExpression(expression.initializer!!.expression))
            } else {
                LLVMSetInitializer(globalProperty, LLVMConstNull(type))
                context.llvm.fileInitializers.add(expression)
            }

            LLVMSetLinkage(globalProperty, LLVMLinkage.LLVMInternalLinkage)
            // (Cannot do this before the global is initialized).

            return
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateExpression(value: IrExpression): LLVMValueRef {
        when (value) {
            is IrTypeOperatorCall    -> return evaluateTypeOperator       (value)
            is IrCall                -> return evaluateCall               (value)
            is IrDelegatingConstructorCall ->
                                        return evaluateCall(value)
            is IrInstanceInitializerCall ->
                                        return evaluateInstanceInitializerCall(value)
            is IrGetValue            -> return evaluateGetValue           (value)
            is IrSetVariable         -> return evaluateSetVariable        (value)
            is IrGetField            -> return evaluateGetField           (value)
            is IrSetField            -> return evaluateSetField           (value)
            is IrConst<*>            -> return evaluateConst              (value)
            is IrReturn              -> return evaluateReturn             (value)
            is IrWhen                -> return evaluateWhen               (value)
            is IrThrow               -> return evaluateThrow              (value)
            is IrTry                 -> return evaluateTry                (value)
            is IrInlineFunctionBody  -> return evaluateInlineFunction     (value)
            is IrContainerExpression -> return evaluateContainerExpression(value)
            is IrWhileLoop           -> return evaluateWhileLoop          (value)
            is IrDoWhileLoop         -> return evaluateDoWhileLoop        (value)
            is IrVararg              -> return evaluateVararg             (value)
            is IrBreak               -> return evaluateBreak              (value)
            is IrContinue            -> return evaluateContinue           (value)
            is IrGetObjectValue      -> return evaluateGetObjectValue     (value)
            is IrCallableReference   -> return evaluateCallableReference  (value)
            else                     -> {
                TODO("${ir2string(value)}")
            }
        }
    }

    private fun generateStatement(statement: IrStatement) {
        when (statement) {
            is IrExpression -> evaluateExpression(statement)
            is IrVariable -> generateVariable(statement)
            else -> TODO(ir2string(statement))
        }
    }

    private fun IrStatement.generate() = generateStatement(this)

    //-------------------------------------------------------------------------//

    private fun evaluateGetObjectValue(value: IrGetObjectValue): LLVMValueRef {
        if (value.descriptor.isUnit()) {
            return codegen.theUnitInstanceRef.llvm
        }

        var objectPtr = objectPtrByName(value.descriptor)
        val bbCurrent = codegen.currentBlock
        val bbInit    = codegen.basicBlock("label_init")
        val bbExit    = codegen.basicBlock("label_continue")
        val onePtr    = codegen.intToPtr(kImmInt64One, codegen.kObjHeaderPtr)
        val objectVal = codegen.loadSlot(objectPtr, false)
        val condition = codegen.ucmpGt(objectVal, onePtr)
        codegen.condBr(condition, bbExit, bbInit)

        codegen.positionAtEnd(bbInit)
        val typeInfo = codegen.typeInfoValue(value.descriptor)
        val initFunction = value.descriptor.constructors.first { it.valueParameters.size == 0 }
        val ctor = codegen.llvmFunction(initFunction)
        val args = listOf(objectPtr, typeInfo, ctor)
        val newValue = call(context.llvm.initInstanceFunction, args, Lifetime.GLOBAL)
        val bbInitResult = codegen.currentBlock
        codegen.br(bbExit)

        codegen.positionAtEnd(bbExit)
        val valuePhi = codegen.phi(codegen.getLLVMType(value.type))
        codegen.addPhiIncoming(valuePhi,
                bbCurrent to objectVal, bbInitResult to newValue)

        return valuePhi
    }


    //-------------------------------------------------------------------------//

    private fun evaluateExpressionAndJump(expression: IrExpression, destination: ContinuationBlock) {
        val result = evaluateExpression(expression)

        // It is possible to check here whether the generated code has the normal continuation path
        // and do not generate any jump if not;
        // however such optimization can lead to phi functions with zero entries, which is not allowed by LLVM;
        // TODO: find the better solution.

        jump(destination, result)
    }

    //-------------------------------------------------------------------------//

    /**
     * Represents the basic block which may expect a value:
     * when generating a [jump] to this block, one should provide the value.
     * Inside the block that value is accessible as [valuePhi].
     *
     * This class is designed to be used to generate Kotlin expressions that have a value and require branching.
     *
     * [valuePhi] may be `null`, which would mean `Unit` value is passed.
     */
    private data class ContinuationBlock(val block: LLVMBasicBlockRef, val valuePhi: LLVMValueRef?)

    private val ContinuationBlock.value: LLVMValueRef
        get() = this.valuePhi ?: codegen.theUnitInstanceRef.llvm

    /**
     * Jumps to [target] passing [value].
     */
    private fun jump(target: ContinuationBlock, value: LLVMValueRef?) {
        val entry = target.block
        codegen.br(entry)
        if (target.valuePhi != null) {
            codegen.assignPhis(target.valuePhi to value!!)
        }
    }

    /**
     * Creates new [ContinuationBlock] that receives the value of given Kotlin type
     * and generates [code] starting from its beginning.
     */
    private fun continuationBlock(type: KotlinType,
                                  code: (ContinuationBlock) -> Unit = {}): ContinuationBlock {
        val entry = codegen.basicBlock("continuation_block")

        codegen.appendingTo(entry) {
            val valuePhi = if (type.isUnit()) {
                null
            } else {
                codegen.phi(codegen.getLLVMType(type))
            }

            val result = ContinuationBlock(entry, valuePhi)
            code(result)
            return result
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateVararg(value: IrVararg): LLVMValueRef {
        val elements = value.elements.map {
            if (it is IrExpression) {
                val mapped = evaluateExpression(it)
                if (codegen.isConst(mapped)) {
                    return@map mapped
                }
            }

            throw IllegalStateException("IrVararg neither was lowered nor can be statically evaluated")
        }

        // Note: even if all elements are const, they aren't guaranteed to be statically initialized.
        // E.g. an element may be a pointer to lazy-initialized object (aka singleton).
        // However it is guaranteed that all elements are already initialized at this point.
        return codegen.staticData.createKotlinArray(value.type, elements)
    }

    //-------------------------------------------------------------------------//

    private fun evaluateThrow(expression: IrThrow): LLVMValueRef {
        val exception = evaluateExpression(expression.value)
        currentCodeContext.genThrow(exception)
        return codegen.kNothingFakeValue
    }

    //-------------------------------------------------------------------------//

    /**
     * The [CodeContext] that catches exceptions.
     */
    private inner abstract class CatchingScope : InnerScopeImpl() {

        /**
         * The LLVM `landingpad` such that if invoked function throws an exception,
         * then this exception is passed to [handler].
         */
        private val landingpad: LLVMBasicBlockRef by lazy {
            using(outerContext) {
                codegen.basicBlock("landingpad") {
                    genLandingpad()
                }
            }
        }

        /**
         * The Kotlin exception handler, i.e. the [ContinuationBlock] which gets started
         * when the exception is caught, receiving this exception as its value.
         */
        private val handler by lazy {
            using(outerContext) {
                continuationBlock(context.builtIns.throwable.defaultType) {
                    genHandler(it.value)
                }
            }
        }

        private fun jumpToHandler(exception: LLVMValueRef) {
            jump(this.handler, exception)
        }

        /**
         * Generates the LLVM `landingpad` that catches C++ exception with type `KotlinException`,
         * unwraps the Kotlin exception object and jumps to [handler].
         *
         * This method generates nearly the same code as `clang++` does for the following:
         * ```
         * catch (KotlinException& e) {
         *     KRef exception = e.exception_;
         *     return exception;
         * }
         * ```
         * except that our code doesn't check exception `typeid`.
         *
         * TODO: why does `clang++` check `typeid` even if there is only one catch clause?
         */
        private fun genLandingpad() {
            with(codegen) {
                val landingpadResult = codegen.gxxLandingpad(numClauses = 1, name = "lp")

                LLVMAddClause(landingpadResult, LLVMConstNull(kInt8Ptr))

                // FIXME: properly handle C++ exceptions: currently C++ exception can be thrown out from try-finally
                // bypassing the finally block.

                val exceptionRecord = LLVMBuildExtractValue(codegen.builder, landingpadResult, 0, "er")!!

                // __cxa_begin_catch returns pointer to C++ exception object.
                val beginCatch = context.llvm.cxaBeginCatchFunction
                val exceptionRawPtr = call(beginCatch, listOf(exceptionRecord))

                // Pointer to KotlinException instance:
                val exceptionPtrPtr = bitcast(codegen.kObjHeaderPtrPtr, exceptionRawPtr, "")

                // Pointer to Kotlin exception object:
                // We do need a slot here, as otherwise exception instance could be freed by _cxa_end_catch.
                val exceptionPtr = loadSlot(exceptionPtrPtr, true, "exception")

                // __cxa_end_catch performs some C++ cleanup, including calling `KotlinException` class destructor.
                val endCatch = context.llvm.cxaEndCatchFunction
                call(endCatch, listOf())

                jumpToHandler(exceptionPtr)
            }
        }

        // The call inside [CatchingScope] must be configured to dispatch exception to the scope's handler.
        override fun genCall(function: LLVMValueRef, args: List<LLVMValueRef>, lifetime: Lifetime): LLVMValueRef {
            val res = codegen.call(function, args, lifetime, this::landingpad)
            return res
        }

        override fun genThrow(exception: LLVMValueRef) {
            jumpToHandler(exception)
        }

        protected abstract fun genHandler(exception: LLVMValueRef)
    }

    /**
     * The [CatchingScope] that handles exceptions using Kotlin `catch` clauses.
     *
     * @param success the block to be used when the exception is successfully handled;
     * expects `catch` expression result as its value.
     */
    private inner class CatchScope(private val catches: List<IrCatch>,
                                   private val success: ContinuationBlock) : CatchingScope() {

        override fun genHandler(exception: LLVMValueRef) {
            // TODO: optimize for `Throwable` clause.
            catches.forEach {
                val isInstance = genInstanceOf(exception, it.parameter.type)
                val nextCheck = codegen.basicBlock("catchCheck")
                val body = codegen.basicBlock("catch")
                codegen.condBr(isInstance, body, nextCheck)

                codegen.appendingTo(body) {
                    using(VariableScope()) {
                        currentCodeContext.genDeclareVariable(it.parameter, exception)
                        evaluateExpressionAndJump(it.result, success)
                    }
                }

                codegen.positionAtEnd(nextCheck)
            }
            // rethrow the exception if no clause can handle it.
            outerContext.genThrow(exception)
        }
    }

    /**
     * The [InnerScope] that includes code generated by [genFinalizeImpl] when leaving it
     * with `return`, `break`, `continue` or throwing exception.
     */
    private abstract inner class FinalizingScope() : CatchingScope() {

        /**
         * Cache for [genReturn].
         *
         * `returnBlocks[func]` contains the [ContinuationBlock] to be used for `return` from `func`;
         * the block expects return value as its value.
         */
        private val returnBlocks = mutableMapOf<CallableDescriptor, ContinuationBlock>()

        // Jump to finalize-and-return instead of simply returning.
        override fun genReturn(target: CallableDescriptor, value: LLVMValueRef?) {
            val block = returnBlocks.getOrPut(target) {
                continuationBlock(target.returnType!!) {
                    genFinalize()
                    val returnValue = it.valuePhi // `null` if return type is `Unit`.
                    outerContext.genReturn(target, returnValue)
                }
            }

            jump(block, value)
        }

        /**
         * Cache for [genBreak].
         */
        private val breakBlocks = mutableMapOf<IrLoop, LLVMBasicBlockRef>()

        // Jump to finalize-and-break instead of simply breaking.
        override fun genBreak(destination: IrBreak) {
            val block = breakBlocks.getOrPut(destination.loop) {
                codegen.basicBlock("finalizeAndBreak") {
                    genFinalize()
                    outerContext.genBreak(destination)
                }
            }

            codegen.br(block)
        }

        /**
         * Cache for [genContinue].
         *
         * Note: can't merge with [breakBlocks] because the code for `break` and `continue` is different.
         */
        private val continueBlocks = mutableMapOf<IrLoop, LLVMBasicBlockRef>()

        // Jump to finalize-and-continue instead of simply continuing.
        override fun genContinue(destination: IrContinue) {
            val block = continueBlocks.getOrPut(destination.loop) {
                codegen.basicBlock("finalizeAndContinue") {
                    genFinalize()
                    outerContext.genContinue(destination)
                }
            }

            codegen.br(block)
        }

        // When an exception is caught, finalize the scope and rethrow the exception.
        override fun genHandler(exception: LLVMValueRef) {
            genFinalizeImpl()
            outerContext.genThrow(exception)
        }

        private fun genFinalize() {
            using(outerContext) {
                this.genFinalizeImpl()
            }
        }

        protected abstract fun genFinalizeImpl()
    }

    /**
     * Generates the code which gets "finalized" exactly once when completed either normally or abnormally.
     *
     * @param code generates the code to be post-dominated by cleanup.
     * It must jump to given [ContinuationBlock] with its result when completed normally.
     *
     * @param finalize generates the cleanup code that must be executed when code generated by [code] is completed.
     *
     * @param type Kotlin type of the result of generated code.
     *
     * @return the result of the generated code.
     */
    private fun genFinalizedBy(finalize: (() -> Unit)?,
                                   type: KotlinType, code: (ContinuationBlock) -> Unit): LLVMValueRef {

        val scope = if (finalize == null) null else {
            object : FinalizingScope() {
                override fun genFinalizeImpl() {
                    finalize()
                }
            }
        }

        val continuation = continuationBlock(type)

        using(scope) {
            code(continuation)
        }
        codegen.positionAtEnd(continuation.block)
        finalize?.invoke()

        // TODO: finalize is duplicated many times (just as in C++, Java or Kotlin JVM);
        // it is very important to optimize this.

        return continuation.value
    }

    /**
     * Generates code that is "finalized" by given expression.
     */
    private fun genFinalizedBy(finalize: IrExpression?, type: KotlinType,
                                   code: (ContinuationBlock) -> Unit): LLVMValueRef {

        val finalizeFun: (() -> Unit)? = if (finalize == null) {
            null
        } else {
            { evaluateExpression(finalize) }
        }

        return genFinalizedBy(finalizeFun, type, code)
    }

    private fun evaluateTry(expression: IrTry): LLVMValueRef {
        // TODO: does basic block order influence machine code order?
        // If so, consider reordering blocks to reduce exception tables size.

        return genFinalizedBy(expression.finallyExpression, expression.type) { continuation ->

            val catchScope = if (expression.catches.isEmpty()) null else CatchScope(expression.catches, continuation)

            using(catchScope) {
                evaluateExpressionAndJump(expression.tryResult, continuation)
            }
        }
    }

    //-------------------------------------------------------------------------//
    /* FIXME. Fix "when" type in frontend.
     * For the following code:
     *  fun foo(x: Int) {
     *      when (x) {
     *          0 -> 0
     *      }
     *  }
     *  we cannot determine if the result of when is assigned or not.
     */
    private fun evaluateWhen(expression: IrWhen): LLVMValueRef {
        context.log("evaluateWhen                   : ${ir2string(expression)}")
        var bbExit: LLVMBasicBlockRef? = null             // By default "when" does not have "exit".
        val isUnit                = KotlinBuiltIns.isUnit(expression.type)
        val isNothing             = KotlinBuiltIns.isNothing(expression.type)

        // We may not cover all cases if IrWhen is used as statement.
        val coverAllCases         = isUnconditional(expression.branches.last())

        // "When" has exit block if:
        //      its type is not Nothing - we must place phi in the exit block
        //      or it doesn't cover all cases - we may fall through all of them and must create exit block to continue.
        if (!isNothing || !coverAllCases)                       // If "when" has "exit".
            bbExit = codegen.basicBlock("when_exit")            // Create basic block to process "exit".

        val llvmType = codegen.getLLVMType(expression.type)
        val resultPhi = if (isUnit || isNothing || !coverAllCases) null else
            codegen.appendingTo(bbExit!!) {
                codegen.phi(llvmType)
            }

        expression.branches.forEach {                           // Iterate through "when" branches (clauses).
            var bbNext = bbExit                                 // For last clause bbNext coincides with bbExit.
            if (it != expression.branches.last())               // If it is not last clause.
                bbNext = codegen.basicBlock("when_next")        // Create new basic block for next clause.
            generateWhenCase(resultPhi, it, bbNext, bbExit)     // Generate code for current clause.
        }

        return when {
            // FIXME: remove the hacks.
            isUnit -> codegen.theUnitInstanceRef.llvm
            isNothing -> codegen.kNothingFakeValue
            !coverAllCases -> LLVMGetUndef(llvmType)!!
            else -> resultPhi!!
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateWhileLoop(loop: IrWhileLoop): LLVMValueRef {
        val loopScope = LoopScope(loop)
        using(loopScope) {
            val loopBody  = codegen.basicBlock("while_loop")
            codegen.br(loopScope.loopCheck)

            codegen.positionAtEnd(loopScope.loopCheck)
            val condition = evaluateExpression(loop.condition)
            codegen.condBr(condition, loopBody, loopScope.loopExit)

            codegen.positionAtEnd(loopBody)
            loop.body?.generate()

            codegen.br(loopScope.loopCheck)
            codegen.positionAtEnd(loopScope.loopExit)
        }


        assert(loop.type.isUnit())
        return codegen.theUnitInstanceRef.llvm
    }

    //-------------------------------------------------------------------------//

    private fun evaluateDoWhileLoop(loop: IrDoWhileLoop): LLVMValueRef {
        val loopScope = LoopScope(loop)
        using(loopScope) {
            val loopBody = codegen.basicBlock("do_while_loop")
            codegen.br(loopBody)

            codegen.positionAtEnd(loopBody)
            loop.body?.generate()
            codegen.br(loopScope.loopCheck)

            codegen.positionAtEnd(loopScope.loopCheck)
            val condition = evaluateExpression(loop.condition)
            codegen.condBr(condition, loopBody, loopScope.loopExit)

            codegen.positionAtEnd(loopScope.loopExit)
        }

        assert(loop.type.isUnit())
        return codegen.theUnitInstanceRef.llvm
    }

    //-------------------------------------------------------------------------//

    private fun evaluateGetValue(value: IrGetValue): LLVMValueRef {
        context.log("evaluateGetValue               : ${ir2string(value)}")
        return currentCodeContext.genGetValue(value.descriptor)
    }

    //-------------------------------------------------------------------------//

    private fun evaluateSetVariable(value: IrSetVariable): LLVMValueRef {
        context.log("evaluateSetVariable            : ${ir2string(value)}")
        val result = evaluateExpression(value.value)
        val variable = currentCodeContext.getDeclaredVariable(value.descriptor)
        codegen.vars.store(result, variable)

        assert(value.type.isUnit())
        return codegen.theUnitInstanceRef.llvm
    }

    //-------------------------------------------------------------------------//

    private fun generateVariable(value: IrVariable) {
        context.log("generateVariable               : ${ir2string(value)}")
        val result = value.initializer?.let { evaluateExpression(it) }
        currentCodeContext.genDeclareVariable(value.descriptor, result)
    }

    //-------------------------------------------------------------------------//

    private fun evaluateTypeOperator(value: IrTypeOperatorCall): LLVMValueRef {
        when (value.operator) {
            IrTypeOperator.CAST                      -> return evaluateCast(value)
            IrTypeOperator.IMPLICIT_INTEGER_COERCION -> return evaluateIntegerCoercion(value)
            IrTypeOperator.IMPLICIT_CAST             -> return evaluateExpression(value.argument)
            IrTypeOperator.IMPLICIT_NOTNULL          -> TODO("${ir2string(value)}")
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> {
                evaluateExpression(value.argument)
                return codegen.theUnitInstanceRef.llvm
            }
            IrTypeOperator.SAFE_CAST                 -> throw IllegalStateException("safe cast wasn't lowered")
            IrTypeOperator.INSTANCEOF                -> return evaluateInstanceOf(value)
            IrTypeOperator.NOT_INSTANCEOF            -> return evaluateNotInstanceOf(value)
        }
    }

    //-------------------------------------------------------------------------//

    private fun KotlinType.isPrimitiveInteger(): Boolean {
        return isPrimitiveNumberType() &&
               !KotlinBuiltIns.isFloat(this) &&
               !KotlinBuiltIns.isDouble(this) &&
               !KotlinBuiltIns.isChar(this)
    }

    private fun evaluateIntegerCoercion(value: IrTypeOperatorCall): LLVMValueRef {
        context.log("evaluateIntegerCoercion        : ${ir2string(value)}")
        val type = value.typeOperand
        assert(type.isPrimitiveInteger())
        val result = evaluateExpression(value.argument)
        val llvmSrcType = codegen.getLLVMType(value.argument.type)
        val llvmDstType = codegen.getLLVMType(type)
        val srcWidth = LLVMGetIntTypeWidth(llvmSrcType)
        val dstWidth = LLVMGetIntTypeWidth(llvmDstType)
        return when {
            srcWidth == dstWidth           -> result
            srcWidth > dstWidth            -> LLVMBuildTrunc(codegen.builder, result, llvmDstType, "")!!
            else /* srcWidth < dstWidth */ -> LLVMBuildSExt(codegen.builder, result, llvmDstType, "")!!
        }
    }

    //-------------------------------------------------------------------------//
    //   table of conversion with llvm for primitive types
    //   to be used in replacement fo primitive.toX() calls with
    //   translator intrinsics.
    //            | byte     short   int     long     float     double
    //------------|----------------------------------------------------
    //    byte    |   x       sext   sext    sext     sitofp    sitofp
    //    short   | trunc      x     sext    sext     sitofp    sitofp
    //    int     | trunc    trunc    x      sext     sitofp    sitofp
    //    long    | trunc    trunc   trunc     x      sitofp    sitofp
    //    float   | fptosi   fptosi  fptosi  fptosi      x      fpext
    //    double  | fptosi   fptosi  fptosi  fptosi   fptrunc      x

    private fun evaluateCast(value: IrTypeOperatorCall): LLVMValueRef {
        context.log("evaluateCast                   : ${ir2string(value)}")
        val type = value.typeOperand
        assert(!KotlinBuiltIns.isPrimitiveType(type) && !KotlinBuiltIns.isPrimitiveType(value.argument.type))
        assert(!type.isTypeParameter())
        val dstDescriptor = TypeUtils.getClassDescriptor(type)                         // Get class descriptor for dst type.
        val dstTypeInfo   = codegen.typeInfoValue(dstDescriptor!!)                     // Get TypeInfo for dst type.
        val srcArg        = evaluateExpression(value.argument)                         // Evaluate src expression.
        val srcObjInfoPtr = codegen.bitcast(codegen.kObjHeaderPtr, srcArg)             // Cast src to ObjInfoPtr.
        val args          = listOf(srcObjInfoPtr, dstTypeInfo)                         // Create arg list.
        call(context.llvm.checkInstanceFunction, args)                                 // Check if dst is subclass of src.
        return srcArg
    }

    //-------------------------------------------------------------------------//

    private fun evaluateInstanceOf(value: IrTypeOperatorCall): LLVMValueRef {
        context.log("evaluateInstanceOf             : ${ir2string(value)}")

        val type     = value.typeOperand
        val srcArg   = evaluateExpression(value.argument)     // Evaluate src expression.

        val bbExit       = codegen.basicBlock("instance_of_exit")
        val bbInstanceOf = codegen.basicBlock("instance_of_notnull")
        val bbNull       = codegen.basicBlock("instance_of_null")

        val condition = codegen.icmpEq(srcArg, codegen.kNullObjHeaderPtr)
        codegen.condBr(condition, bbNull, bbInstanceOf)

        codegen.positionAtEnd(bbNull)
        val resultNull = if (TypeUtils.isNullableType(type)) kTrue else kFalse
        codegen.br(bbExit)

        codegen.positionAtEnd(bbInstanceOf)
        val resultInstanceOf = genInstanceOf(srcArg, type)
        codegen.br(bbExit)
        val bbInstanceOfResult = codegen.currentBlock

        codegen.positionAtEnd(bbExit)
        val result = codegen.phi(kBoolean)
        codegen.addPhiIncoming(result, bbNull to resultNull, bbInstanceOfResult to resultInstanceOf)
        return result
    }

    //-------------------------------------------------------------------------//

    private fun genInstanceOf(obj: LLVMValueRef, type: KotlinType): LLVMValueRef {
        var dstDescriptor = TypeUtils.getClassDescriptor(type)                         // Get class descriptor for dst type.
        // Reified parameters are not yet supported.
        if (dstDescriptor == null) return kTrue                                        // Workaround for reified parameters

        val dstTypeInfo   = codegen.typeInfoValue(dstDescriptor!!)                     // Get TypeInfo for dst type.
        val srcObjInfoPtr = codegen.bitcast(codegen.kObjHeaderPtr, obj)                // Cast src to ObjInfoPtr.
        val args          = listOf(srcObjInfoPtr, dstTypeInfo)                         // Create arg list.

        val result = call(context.llvm.isInstanceFunction, args)                       // Check if dst is subclass of src.
        return LLVMBuildTrunc(codegen.builder, result, kInt1, "")!!                    // Truncate result to boolean
    }

    //-------------------------------------------------------------------------//

    private fun evaluateNotInstanceOf(value: IrTypeOperatorCall): LLVMValueRef {
        val instanceOfResult = evaluateInstanceOf(value)
        return LLVMBuildNot(codegen.builder, instanceOfResult, "")!!
    }

    //-------------------------------------------------------------------------//

    private fun evaluateGetField(value: IrGetField): LLVMValueRef {
        context.log("evaluateGetField               : ${ir2string(value)}")
        if (value.descriptor.dispatchReceiverParameter != null) {
            val thisPtr = evaluateExpression(value.receiver!!)
            return codegen.loadSlot(
                    fieldPtrOfClass(thisPtr, value.descriptor), value.descriptor.isVar())
        }
        else {
            assert (value.receiver == null)
            val ptr = context.llvmDeclarations.forStaticField(value.descriptor).storage
            return codegen.loadSlot(ptr, value.descriptor.isVar())
        }
    }

    //-------------------------------------------------------------------------//

    private fun objectPtrByName(descriptor: ClassDescriptor): LLVMValueRef {
        assert (!descriptor.isUnit())
        return if (codegen.isExternal(descriptor)) {
            val llvmType = codegen.getLLVMType(descriptor.defaultType)
            codegen.externalGlobal(descriptor.objectInstanceFieldSymbolName, llvmType,
                    threadLocal = true)
        } else {
            context.llvmDeclarations.forSingleton(descriptor).instanceFieldRef
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateSetField(value: IrSetField): LLVMValueRef {
        context.log("evaluateSetField               : ${ir2string(value)}")
        val valueToAssign = evaluateExpression(value.value)

        if (value.descriptor.dispatchReceiverParameter != null) {
            val thisPtr = evaluateExpression(value.receiver!!)
            codegen.storeAnyGlobal(valueToAssign, fieldPtrOfClass(thisPtr, value.descriptor))
        }
        else {
            assert (value.receiver == null)
            val globalValue = context.llvmDeclarations.forStaticField(value.descriptor).storage
            codegen.storeAnyGlobal(valueToAssign, globalValue)
        }

        assert (value.type.isUnit())
        return codegen.theUnitInstanceRef.llvm
    }

    //-------------------------------------------------------------------------//

    /*
       in C:
       struct ObjHeader *header = (struct ObjHeader *)ptr;
       struct T* obj = (T*)&header[1];
       return &obj->fieldX;

       in llvm ir:
       %struct.ObjHeader = type { i32, i32 }
       %struct.Object = type { i32, i32 }

       ; Function Attrs: nounwind ssp uwtable
       define i32 @fooField2(i8*) #0 {
         %2 = alloca i8*, align 8
         %3 = alloca %struct.ObjHeader*, align 8
         %4 = alloca %struct.Object*, align 8
         store i8* %0, i8** %2, align 8
         %5 = load i8*, i8** %2, align 8
         %6 = bitcast i8* %5 to %struct.ObjHeader*
         store %struct.ObjHeader* %6, %struct.ObjHeader** %3, align 8
         %7 = load %struct.ObjHeader*, %struct.ObjHeader** %3, align 8

         %8 = getelementptr inbounds %struct.ObjHeader, %struct.ObjHeader* %7, i64 1; <- (T*)&header[1];

         %9 = bitcast %struct.ObjHeader* %8 to %struct.Object*
         store %struct.Object* %9, %struct.Object** %4, align 8
         %10 = load %struct.Object*, %struct.Object** %4, align 8
         %11 = getelementptr inbounds %struct.Object, %struct.Object* %10, i32 0, i32 0 <-  &obj->fieldX
         %12 = load i32, i32* %11, align 4
         ret i32 %12
       }

    */
    private fun fieldPtrOfClass(thisPtr: LLVMValueRef, value: PropertyDescriptor): LLVMValueRef {
        val fieldInfo = context.llvmDeclarations.forField(value)

        val typePtr = pointerType(fieldInfo.classBodyType)
        val objectPtr = LLVMBuildGEP(codegen.builder, thisPtr, cValuesOf(kImmOne), 1, "")
        val typedObjPtr = codegen.bitcast(typePtr, objectPtr!!)
        val fieldPtr = LLVMBuildStructGEP(codegen.builder, typedObjPtr, fieldInfo.index, "")
        return fieldPtr!!
    }

    //-------------------------------------------------------------------------//

    private fun evaluateConst(value: IrConst<*>): LLVMValueRef {
        context.log("evaluateConst                  : ${ir2string(value)}")
        when (value.kind) {
            IrConstKind.Null    -> return codegen.kNullObjHeaderPtr
            IrConstKind.Boolean -> when (value.value) {
                true  -> return kTrue
                false -> return kFalse
            }
            IrConstKind.Char   -> return LLVMConstInt(LLVMInt16Type(), (value.value as Char).toLong(),  0)!!
            IrConstKind.Byte   -> return LLVMConstInt(LLVMInt8Type(),  (value.value as Byte).toLong(),  1)!!
            IrConstKind.Short  -> return LLVMConstInt(LLVMInt16Type(), (value.value as Short).toLong(), 1)!!
            IrConstKind.Int    -> return LLVMConstInt(LLVMInt32Type(), (value.value as Int).toLong(),   1)!!
            IrConstKind.Long   -> return LLVMConstInt(LLVMInt64Type(), value.value as Long,             1)!!
            IrConstKind.String -> return context.llvm.staticData.kotlinStringLiteral(
                    context.builtIns.stringType, value as IrConst<String>).llvm
            IrConstKind.Float  -> return LLVMConstRealOfString(LLVMFloatType(), (value.value as Float).toString())!!
            IrConstKind.Double -> return LLVMConstRealOfString(LLVMDoubleType(), (value.value as Double).toString())!!
        }
        TODO("${ir2string(value)}")
    }

    //-------------------------------------------------------------------------//

    private fun evaluateReturn(expression: IrReturn): LLVMValueRef {
        context.log("evaluateReturn                 : ${ir2string(expression)}")
        val value = expression.value

        val evaluated = evaluateExpression(value)

        val target = expression.returnTarget
        val ret = if (target.returnType!!.isUnit()) {
            null
        } else {
            evaluated
        }

        currentCodeContext.genReturn(target, ret)
        return codegen.kNothingFakeValue
    }

    //-------------------------------------------------------------------------//

    private inner class InlinedFunctionScope(val inlineBody: IrInlineFunctionBody) : InnerScopeImpl() {

        var bbExit : LLVMBasicBlockRef? = null
        var resultPhi : LLVMValueRef? = null

        private fun getExit(): LLVMBasicBlockRef {
            if (bbExit == null) bbExit = codegen.basicBlock("inline_body_exit")
            return bbExit!!
        }

        private fun getResult(): LLVMValueRef {
            if (resultPhi == null) {
                val bbCurrent = codegen.currentBlock
                codegen.positionAtEnd(getExit())
                resultPhi = codegen.phi(codegen.getLLVMType(inlineBody.type))
                codegen.positionAtEnd(bbCurrent)
            }
            return resultPhi!!
        }

        override fun genReturn(target: CallableDescriptor, value: LLVMValueRef?) {
            if (target != inlineBody.descriptor) {                              // It is not our "local return".
                super.genReturn(target, value)
                return
            }
                                                                                // It is local return from current function.
            codegen.br(getExit())                                               // Generate branch on exit block.

            if (!KotlinBuiltIns.isUnit(inlineBody.type)) {                      // If function returns more then "unit"
                codegen.assignPhis(getResult() to value!!)                      // Assign return value to result PHI node.
            }
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateInlineFunction(value: IrInlineFunctionBody): LLVMValueRef {
        context.log("evaluateInlineFunction         : ${value.statements.forEach { ir2string(it) }}")

        val inlinedFunctionScope = InlinedFunctionScope(value)
        using(inlinedFunctionScope) {
            using(VariableScope()) {
                value.statements.forEach {
                    generateStatement(it)
                }
            }
        }

        val bbExit = inlinedFunctionScope.bbExit
        if (bbExit != null) {
            if (!codegen.isAfterTerminator()) {                 // TODO should we solve this problem once and for all
                if (inlinedFunctionScope.resultPhi != null) {
                    codegen.unreachable()
                } else {
                    codegen.br(bbExit)
                }
            }
            codegen.positionAtEnd(bbExit)
        }

        return inlinedFunctionScope.resultPhi ?: codegen.theUnitInstanceRef.llvm
    }

    //-------------------------------------------------------------------------//

    private fun evaluateContainerExpression(value: IrContainerExpression): LLVMValueRef {
        context.log("evaluateContainerExpression    : ${value.statements.forEach { ir2string(it) }}")

        val scope = if (value is IrContainerExpression && value.isTransparentScope) {
            null
        } else {
            VariableScope()
        }

        using(scope) {
            value.statements.dropLast(1).forEach {
                generateStatement(it)
            }
            value.statements.lastOrNull()?.let {
                if (it is IrExpression) {
                    return evaluateExpression(it)
                } else {
                    generateStatement(it)
                }
            }

            assert(value.type.isUnit())
            return codegen.theUnitInstanceRef.llvm
        }
    }

    private fun evaluateInstanceInitializerCall(expression: IrInstanceInitializerCall): LLVMValueRef {
        assert (expression.type.isUnit())
        return codegen.theUnitInstanceRef.llvm
    }

    //-------------------------------------------------------------------------//

    /**
     * Tries to evaluate given expression with given (already evaluated) arguments in compile time.
     * Returns `null` on failure.
     */
    private fun compileTimeEvaluate(expression: IrMemberAccessExpression, args: List<LLVMValueRef>): LLVMValueRef? {
        if (!args.all { codegen.isConst(it) }) {
            return null
        }

        val function = expression.descriptor

        if (function.fqNameSafe.asString() == "kotlin.collections.listOf" && function.valueParameters.size == 1) {
            val varargExpression = expression.getValueArgument(0) as? IrVararg

            if (varargExpression != null) {
                // The function is kotlin.collections.listOf<T>(vararg args: T).
                // TODO: refer functions more reliably.

                val vararg = args.single()

                if (varargExpression.elements.any { it is IrSpreadElement }) {
                    return null // not supported yet, see `length` calculation below.
                }
                val length = varargExpression.elements.size
                // TODO: store length in `vararg` itself when more abstract types will be used for values.

                // `elementType` is type argument of function return type:
                val elementType = function.returnType!!.arguments.single()

                val array = constPointer(vararg)
                // Note: dirty hack here: `vararg` has type `Array<out E>`, but `createArrayList` expects `Array<E>`;
                // however `vararg` is immutable, and in current implementation it has type `Array<E>`,
                // so let's ignore this mismatch currently for simplicity.

                return context.llvm.staticData.createArrayList(elementType, array, length).llvm
            }
        }

        return null
    }

    private fun evaluateCall(value: IrMemberAccessExpression): LLVMValueRef {
        context.log("evaluateCall                   : ${ir2string(value)}")

        val args = evaluateExplicitArgs(value)

        compileTimeEvaluate(value, args)?.let { return it }

        when {
            value is IrDelegatingConstructorCall ->
                return delegatingConstructorCall(value.descriptor, args)

            value.descriptor is FunctionDescriptor -> return evaluateFunctionCall(
                    value as IrCall, args, resultLifetime(value))
            else -> {
                TODO("${ir2string(value)}")
            }
        }
    }

    /**
     * Evaluates all arguments of [expression] that are explicitly represented in the IR.
     * Returns results in the same order as LLVM function expects, assuming that all explicit arguments
     * exactly correspond to a tail of LLVM parameters.
     */
    private fun evaluateExplicitArgs(expression: IrMemberAccessExpression): List<LLVMValueRef> {
        val evaluatedArgs = expression.getArguments().map { (param, argExpr) ->
            param to evaluateExpression(argExpr)
        }.toMap()

        val allValueParameters = expression.descriptor.allParameters

        return allValueParameters.dropWhile { it !in evaluatedArgs }.map {
            evaluatedArgs[it]!!
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateCallableReference(expression: IrCallableReference): LLVMValueRef {
        // TODO: consider creating separate IR element for pointer to function.
        assert (expression.type.isUnboundCallableReference() ||
                TypeUtils.getClassDescriptor(expression.type) == context.interopBuiltIns.cPointer)

        assert (expression.getArguments().isEmpty())

        val descriptor = expression.descriptor
        assert (descriptor.dispatchReceiverParameter == null)

        val entry = codegen.functionEntryPointAddress(descriptor as FunctionDescriptor)
        return entry
    }

    //-------------------------------------------------------------------------//

    private fun evaluateFunctionCall(callee: IrCall, args: List<LLVMValueRef>,
                                     resultLifetime: Lifetime): LLVMValueRef {
        val descriptor:FunctionDescriptor = callee.descriptor as FunctionDescriptor

        if (descriptor.isFunctionInvoke) {
            return evaluateFunctionInvoke(descriptor, args, resultLifetime)
        }

        if (descriptor.isIntrinsic) {
            return evaluateIntrinsicCall(callee, args)
        }

        when (descriptor) {
            is IrBuiltinOperatorDescriptorBase -> return evaluateOperatorCall      (callee, args)
            is ConstructorDescriptor           -> return evaluateConstructorCall   (callee, args)
            else                               -> return evaluateSimpleFunctionCall(
                    descriptor, args, resultLifetime, callee.superQualifier)
        }
    }

    //-------------------------------------------------------------------------//

    private val functionImplUnboundRefGetter by lazy {
        context.builtIns.getKonanInternalClass("FunctionImpl")
                .unsubstitutedMemberScope.getContributedDescriptors()
                .filterIsInstance<PropertyDescriptor>()
                .single { it.name.asString() == "unboundRef" }
                .getter!!
    }

    private fun evaluateFunctionInvoke(descriptor: FunctionDescriptor,
                                       args: List<LLVMValueRef>, resultLifetime: Lifetime): LLVMValueRef {

        // Note: the whole function code below is written in the assumption that
        // `invoke` method receiver is passed as first argument.

        val functionImpl = args[0] // Instance of `konan.internal.FunctionImpl`.

        // `functionImpl.unboundRef` is the pointer to (static) function of type
        // `(FunctionImpl, Arg_1, ..., Arg_n): Ret`. See [CallableReferenceLowering] for details.
        // LLVM type for such function is equal to type for `FunctionImpl.invoke(Arg_1, ..., Arg_n): Ret`.
        // So we can use the latter for simplicity:
        val unboundRefType = codegen.getLlvmFunctionType(descriptor)

        // Get `functionImpl.unboundRef`:
        val unboundRef = evaluateSimpleFunctionCall(functionImplUnboundRefGetter,
                listOf(functionImpl), Lifetime.IRRELEVANT /* unboundRef isn't managed reference */)

        // Cast `functionImpl.unboundRef` to pointer to function:
        val entryPtr = codegen.bitcast(pointerType(unboundRefType), unboundRef, "entry")

        return call(descriptor, entryPtr, args, resultLifetime)
    }

    //-------------------------------------------------------------------------//

    private fun evaluateSimpleFunctionCall(
            descriptor: FunctionDescriptor, args: List<LLVMValueRef>,
            resultLifetime: Lifetime, superClass: ClassDescriptor? = null): LLVMValueRef {
        //context.log("evaluateSimpleFunctionCall : $tmpVariableName = ${ir2string(value)}")
        if (descriptor.isOverridable && superClass == null)
            return callVirtual(descriptor, args, resultLifetime)
        else
            return callDirect(descriptor, args, resultLifetime)
    }

    //-------------------------------------------------------------------------//
    private fun resultLifetime(callee: IrElement): Lifetime {
        return resultLifetimes.getOrElse(callee) { /* TODO: make IRRELEVANT */ Lifetime.GLOBAL }
    }

    private fun evaluateConstructorCall(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        context.log("evaluateConstructorCall        : ${ir2string(callee)}")
        memScoped {
            val constructedClass = (callee.descriptor as ConstructorDescriptor).constructedClass
            val thisValue = if (constructedClass.isArray) {
                assert(args.size >= 1 && args[0].type == int32Type)
                codegen.allocArray(codegen.typeInfoValue(constructedClass), args[0],
                        resultLifetime(callee))
            } else if (constructedClass == context.builtIns.string) {
                // TODO: consider returning the empty string literal instead.
                assert(args.size == 0)
                codegen.allocArray(codegen.typeInfoValue(constructedClass), count = kImmZero,
                        lifetime = resultLifetime(callee))
            } else {
                codegen.allocInstance(codegen.typeInfoValue(constructedClass), resultLifetime(callee))
            }
            evaluateSimpleFunctionCall(callee.descriptor as FunctionDescriptor,
                    listOf(thisValue) + args, Lifetime.IRRELEVANT /* constructor doesn't return anything */)
            return thisValue
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateIntrinsicCall(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val descriptor = callee.descriptor.original
        val name = descriptor.fqNameUnsafe.asString()

        when (name) {
            "konan.internal.areEqualByValue" -> {
                val arg0 = args[0]
                val arg1 = args[1]
                assert (arg0.type == arg1.type, { "Types are different: '${llvmtype2string(arg0.type)}' and '${llvmtype2string(arg1.type)}'" })

                return when (LLVMGetTypeKind(arg0.type)) {
                    LLVMTypeKind.LLVMFloatTypeKind, LLVMTypeKind.LLVMDoubleTypeKind ->
                        codegen.fcmpEq(arg0, arg1)

                    else ->
                        codegen.icmpEq(arg0, arg1)
                }
            }
        }

        val interop = context.interopBuiltIns

        return when (descriptor) {
            interop.interpretNullablePointed, interop.interpretCPointer,
            interop.nativePointedGetRawPointer, interop.cPointerGetRawValue -> args.single()

            in interop.readPrimitive -> {
                val pointerType = pointerType(codegen.getLLVMType(descriptor.returnType!!))
                val rawPointer = args.last()
                val pointer = codegen.bitcast(pointerType, rawPointer)
                codegen.load(pointer)
            }
            in interop.writePrimitive -> {
                val pointerType = pointerType(codegen.getLLVMType(descriptor.valueParameters.last().type))
                val rawPointer = args[1]
                val pointer = codegen.bitcast(pointerType, rawPointer)
                codegen.store(args[2], pointer)
                codegen.theUnitInstanceRef.llvm
            }
            interop.nativePtrPlusLong -> codegen.gep(args[0], args[1])
            interop.getNativeNullPtr -> kNullInt8Ptr
            interop.getPointerSize -> Int32(LLVMPointerSize(codegen.llvmTargetData)).llvm
            interop.nativePtrToLong -> {
                val intPtrValue = codegen.ptrToInt(args.single(), codegen.intPtrType)
                val resultType = codegen.getLLVMType(descriptor.returnType!!)

                if (resultType == intPtrValue.type) {
                    intPtrValue
                } else {
                    LLVMBuildSExt(codegen.builder, intPtrValue, resultType, "")!!
                }
            }
            else -> TODO(callee.descriptor.original.toString())
        }
    }

    //-------------------------------------------------------------------------//
    private val kImmZero     = LLVMConstInt(LLVMInt32Type(),  0, 1)!!
    private val kImmOne      = LLVMConstInt(LLVMInt32Type(),  1, 1)!!
    private val kTrue        = LLVMConstInt(LLVMInt1Type(),   1, 1)!!
    private val kFalse       = LLVMConstInt(LLVMInt1Type(),   0, 1)!!


    private fun evaluateOperatorCall(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        context.log("evaluateCall                   : origin:${ir2string(callee)}")
        val descriptor = callee.descriptor
        val ib = context.irModule!!.irBuiltins
        when (descriptor) {
            ib.eqeqeq     -> return codegen.icmpEq(args[0], args[1])
            ib.gt0        -> return codegen.icmpGt(args[0], kImmZero)
            ib.gteq0      -> return codegen.icmpGe(args[0], kImmZero)
            ib.lt0        -> return codegen.icmpLt(args[0], kImmZero)
            ib.lteq0      -> return codegen.icmpLe(args[0], kImmZero)
            ib.booleanNot -> return codegen.icmpNe(args[0], kTrue)
            else -> {
                TODO(descriptor.name.toString())
            }
        }
    }

    //-------------------------------------------------------------------------//

    private fun generateWhenCase(resultPhi: LLVMValueRef?, branch: IrBranch,
                                 bbNext: LLVMBasicBlockRef?, bbExit: LLVMBasicBlockRef?) {
        val branchResult = branch.result
        val brResult = if (isUnconditional(branch)) {                                // It is the "else" clause.
            evaluateExpression(branchResult)                                         // Generate clause body.
        } else {                                                                     // It is conditional clause.
            val bbCase = codegen.basicBlock("when_case")                             // Create block for clause body.
            val condition = evaluateExpression(branch.condition)                     // Generate cmp instruction.
            codegen.condBr(condition, bbCase, bbNext)                                // Conditional branch depending on cmp result.
            codegen.positionAtEnd(bbCase)                                            // Switch generation to block for clause body.
            evaluateExpression(branch.result)                                        // Generate clause body.
        }
        if (!codegen.isAfterTerminator()) {
            if (resultPhi != null)
                codegen.assignPhis(resultPhi to brResult)
            if (bbExit != null)
                codegen.br(bbExit)
        }
        if (bbNext != null)                                                          // Switch generation to next or exit.
            codegen.positionAtEnd(bbNext)
        else if (bbExit != null)
            codegen.positionAtEnd(bbExit)
    }

    //-------------------------------------------------------------------------//
    // Checks if the branch is unconditional

    private fun isUnconditional(branch: IrBranch): Boolean =
        branch.condition is IrConst<*>                            // If branch condition is constant.
            && (branch.condition as IrConst<*>).value as Boolean  // If condition is "true"

    //-------------------------------------------------------------------------//

    fun callDirect(descriptor: FunctionDescriptor, args: List<LLVMValueRef>,
                   resultLifetime: Lifetime): LLVMValueRef {
        val realDescriptor = descriptor.target
        val llvmFunction = codegen.functionLlvmValue(realDescriptor)
        return call(descriptor, llvmFunction, args, resultLifetime)
    }

    //-------------------------------------------------------------------------//

    fun callVirtual(descriptor: FunctionDescriptor, args: List<LLVMValueRef>,
                    resultLifetime: Lifetime): LLVMValueRef {
        assert(LLVMTypeOf(args[0]) == codegen.kObjHeaderPtr)
        val typeInfoPtrPtr  = LLVMBuildStructGEP(codegen.builder, args[0], 0 /* type_info */, "")!!
        val typeInfoPtr     = codegen.load(typeInfoPtrPtr)
        assert (typeInfoPtr.type == codegen.kTypeInfoPtr)

        val owner = descriptor.containingDeclaration as ClassDescriptor
        val llvmMethod = if (!owner.isInterface) {
            // If this is a virtual method of the class - we can call via vtable.
            val index = context.getVtableBuilder(owner).vtableIndex(descriptor)

            val vtablePlace = codegen.gep(typeInfoPtr, Int32(1).llvm) // typeInfoPtr + 1
            val vtable = codegen.bitcast(kInt8PtrPtr, vtablePlace)

            val slot = codegen.gep(vtable, Int32(index).llvm)
            codegen.load(slot)
        } else {
            // Otherwise, call by hash.
            // TODO: optimize by storing interface number in lower bits of 'this' pointer
            //       when passing object as an interface. This way we can use those bits as index
            //       for an additional per-interface vtable.
            val methodHash = codegen.functionHash(descriptor)                       // Calculate hash of the method to be invoked
            val lookupArgs = listOf(typeInfoPtr, methodHash)                        // Prepare args for lookup
            call(context.llvm.lookupOpenMethodFunction, lookupArgs)
        }
        val functionPtrType = pointerType(codegen.getLlvmFunctionType(descriptor))   // Construct type of the method to be invoked
        val function        = codegen.bitcast(functionPtrType, llvmMethod)           // Cast method address to the type
        return call(descriptor, function, args, resultLifetime)                      // Invoke the method
    }

    //-------------------------------------------------------------------------//

    // TODO: it seems to be much more reliable to get args as a mapping from parameter descriptor to LLVM value,
    // instead of a plain list.
    // In such case it would be possible to check that all args are available and in the correct order.
    // However, it currently requires some refactoring to be performed.
    private fun call(descriptor: FunctionDescriptor, function: LLVMValueRef, args: List<LLVMValueRef>,
                     resultLifetime: Lifetime): LLVMValueRef {
        val result = call(function, args, resultLifetime)
        if (descriptor.returnType?.isNothing() == true) {
            codegen.unreachable()
        }

        if (LLVMGetReturnType(getFunctionType(function)) == voidType) {
            return codegen.theUnitInstanceRef.llvm
        }

        return result
    }

    private fun call(function: LLVMValueRef, args: List<LLVMValueRef>,
                     resultLifetime: Lifetime = Lifetime.IRRELEVANT): LLVMValueRef {
        return currentCodeContext.genCall(function, args, resultLifetime)
    }

    //-------------------------------------------------------------------------//

    fun delegatingConstructorCall(descriptor: ClassConstructorDescriptor, args: List<LLVMValueRef>): LLVMValueRef {

        val constructedClass = codegen.constructedClass!!
        val thisPtr = currentCodeContext.genGetValue(constructedClass.thisAsReceiverParameter)

        val thisPtrArgType = codegen.getLLVMType(descriptor.allParameters[0].type)
        val thisPtrArg = if (thisPtr.type == thisPtrArgType) {
            thisPtr
        } else {
            // e.g. when array constructor calls super (i.e. Any) constructor.
            codegen.bitcast(thisPtrArgType, thisPtr)
        }

        return callDirect(descriptor, listOf(thisPtrArg) + args,
                Lifetime.IRRELEVANT /* no value returned */)
    }

    //-------------------------------------------------------------------------//

    fun appendLlvmUsed(args: List<LLVMValueRef>) {
        if (args.isEmpty()) return

        memScoped {
            val arrayLength = args.size
            val argsCasted = args.map { it -> constPointer(it).bitcast(int8TypePtr) }
            val llvmUsedGlobal = 
                context.llvm.staticData.placeGlobalArray("llvm.used", int8TypePtr, argsCasted)

            LLVMSetLinkage(llvmUsedGlobal.llvmGlobal, LLVMLinkage.LLVMAppendingLinkage);
            LLVMSetSection(llvmUsedGlobal.llvmGlobal, "llvm.metadata");
        }
    }

    //-------------------------------------------------------------------------//
    // Create type { i32, void ()*, i8* }

    val kCtorType = LLVMStructType(
            cValuesOf(
                    LLVMInt32Type(),
                    LLVMPointerType(kVoidFuncType, 0),
                    kInt8Ptr
            ),
            3, 0)!!

    //-------------------------------------------------------------------------//
    // Create object { i32, void ()*, i8* } { i32 1, void ()* @ctorFunction, i8* null }

    fun createGlobalCtor(ctorFunction: LLVMValueRef): ConstPointer {
        val priority = kImmInt32One
        val data     = kNullInt8Ptr
        val argList  = cValuesOf(priority, ctorFunction, data)
        val ctorItem = LLVMConstNamedStruct(kCtorType, argList, 3)!!
        return constPointer(ctorItem)
    }

    //-------------------------------------------------------------------------//
    // Append initializers of global variables in "llvm.global_ctors" array

    fun appendStaticInitializers(initializers: List<LLVMValueRef>) {
        if (initializers.isEmpty()) return

        val ctorList = initializers.map { it -> createGlobalCtor(it) }
        val globalCtors = context.llvm.staticData.placeGlobalArray("llvm.global_ctors", kCtorType, ctorList)
        LLVMSetLinkage(globalCtors.llvmGlobal, LLVMLinkage.LLVMAppendingLinkage)
    }

    //-------------------------------------------------------------------------//
    /**
     * This block is devoted to coming codegen refactoring:
     * removing [Codegenerator.currentFunction] of [FunctionDesctiptor]
     * and working with local variables with [FunctionScope] and other enhancements.
     */

    // TODO: is described refactoring still coming?

    fun CodeGenerator.basicBlock(name: String, code: () -> Unit) = basicBlock(name).apply {
        appendingTo(this) {
            code()
        }
    }
}

