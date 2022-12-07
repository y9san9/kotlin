/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.cexport

import kotlinx.cinterop.cValuesOf
import llvm.*
import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.backend.konan.lower.getObjectClassInstanceFunction
import org.jetbrains.kotlin.builtins.UnsignedType
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.isOverridable
import org.jetbrains.kotlin.ir.util.referenceFunction
import org.jetbrains.kotlin.konan.target.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.isChildOf
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.annotations.*
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.*
import java.io.PrintWriter

private enum class ScopeKind {
    TOP,
    CLASS,
    PACKAGE
}

private enum class ElementKind {
    FUNCTION,
    PROPERTY,
    TYPE
}

private enum class DefinitionKind {
    C_HEADER_DECLARATION,
    C_HEADER_STRUCT,
    C_SOURCE_DECLARATION,
    C_SOURCE_STRUCT
}

private enum class Direction {
    KOTLIN_TO_C,
    C_TO_KOTLIN
}

private operator fun String.times(count: Int): String {
    val builder = StringBuilder()
    repeat(count, { builder.append(this) })
    return builder.toString()
}

private val KotlinType.shortNameForPredefinedType
    get() = this.toString().split('.').last()


private val KotlinType.createNullableNameForPredefinedType
        get() = "createNullable${this.shortNameForPredefinedType}"

private val KotlinType.createGetNonNullValueOfPredefinedType
    get() = "getNonNullValueOf${this.shortNameForPredefinedType}"

private fun isExportedFunction(descriptor: FunctionDescriptor): Boolean {
    if (!descriptor.isEffectivelyPublicApi || !descriptor.kind.isReal || descriptor.isExpect)
        return false
    if (descriptor.isSuspend)
        return false
    return !descriptor.typeParameters.any()
}

private fun isExportedClass(descriptor: ClassDescriptor): Boolean {
    if (!descriptor.isEffectivelyPublicApi) return false
    // No sense to export annotations.
    if (DescriptorUtils.isAnnotationClass(descriptor)) return false
    // Do not export expect classes.
    if (descriptor.isExpect) return false
    // Do not export types with type parameters.
    // TODO: is it correct?
    if (!descriptor.declaredTypeParameters.isEmpty()) return false
    // Do not export inline classes for now. TODO: add proper support.
    if (descriptor.isInlined()) return false

    return true
}

internal fun AnnotationDescriptor.properValue(key: String) =
        this.argumentValue(key)?.toString()?.removeSurrounding("\"")

private fun functionImplName(descriptor: DeclarationDescriptor, default: String, shortName: Boolean): String {
    assert(descriptor is FunctionDescriptor)
    val annotation = descriptor.annotations.findAnnotation(RuntimeNames.cnameAnnotation) ?: return default
    val key = if (shortName) "shortName" else "externName"
    val value = annotation.properValue(key)
    return value.takeIf { value != null && value.isNotEmpty() } ?: default
}

internal data class SignatureElement(val name: String, val type: KotlinType)

private class ExportedElementScope(val kind: ScopeKind, val name: String) {
    val elements = mutableListOf<ExportedElement>()
    val scopes = mutableListOf<ExportedElementScope>()
    private val scopeNames = mutableSetOf<String>()
    private val scopeNamesMap = mutableMapOf<Pair<DeclarationDescriptor, Boolean>, String>()

    override fun toString(): String {
        return "$kind: $name ${elements.joinToString(", ")} ${scopes.joinToString("\n")}"
    }

    fun generateCAdapters(builder: (ExportedElement) -> Unit) {
        elements.forEach { builder(it) }
        scopes.forEach { it.generateCAdapters(builder) }
    }

    // collects names of inner scopes to make sure function<->scope name clashes would be detected, and functions would be mangled with "_" suffix
    fun collectInnerScopeName(innerScope: ExportedElementScope) {
        scopeNames += innerScope.name
    }

    fun scopeUniqueName(descriptor: DeclarationDescriptor, shortName: Boolean): String {
        scopeNamesMap[descriptor to shortName]?.apply { return this }
        var computedName = when (descriptor) {
            is ConstructorDescriptor -> "${descriptor.constructedClass.fqNameSafe.shortName().asString()}"
            is PropertyGetterDescriptor -> "get_${descriptor.correspondingProperty.name.asString()}"
            is PropertySetterDescriptor -> "set_${descriptor.correspondingProperty.name.asString()}"
            is FunctionDescriptor -> functionImplName(descriptor, descriptor.fqNameSafe.shortName().asString(), shortName)
            else -> descriptor.fqNameSafe.shortName().asString()
        }
        while (scopeNames.contains(computedName) || cKeywords.contains(computedName)) {
            computedName += "_"
        }
        scopeNames += computedName
        scopeNamesMap[descriptor to shortName] = computedName
        return computedName
    }
}

private class ExportedElement(
        val kind: ElementKind,
        val scope: ExportedElementScope,
        val declaration: DeclarationDescriptor,
        val owner: CAdapterGenerator,
        val typeTranslator: CAdapterTypeTranslator,
) {
    init {
        scope.elements.add(this)
    }

    val name: String
        get() = declaration.fqNameSafe.shortName().asString()

    lateinit var cname: String

    override fun toString(): String {
        return "$kind: $name (aliased to ${if (::cname.isInitialized) cname.toString() else "<unknown>"})"
    }

    fun uniqueName(descriptor: DeclarationDescriptor, shortName: Boolean) =
            scope.scopeUniqueName(descriptor, shortName)

    val isFunction = declaration is FunctionDescriptor
    val isTopLevelFunction: Boolean
        get() {
            if (declaration !is FunctionDescriptor ||
                    !declaration.annotations.hasAnnotation(RuntimeNames.cnameAnnotation))
                return false
            val annotation = declaration.annotations.findAnnotation(RuntimeNames.cnameAnnotation)!!
            val externName = annotation.properValue("externName")
            return externName != null && externName.isNotEmpty()
        }
    val isClass = declaration is ClassDescriptor && declaration.kind != ClassKind.ENUM_ENTRY
    val isEnumEntry = declaration is ClassDescriptor && declaration.kind == ClassKind.ENUM_ENTRY
    val isSingletonObject = declaration is ClassDescriptor && DescriptorUtils.isObject(declaration)

    val irSymbol = when {
        isFunction -> owner.symbolTable.referenceFunction(declaration as FunctionDescriptor)
        isClass -> owner.symbolTable.referenceClass(declaration as ClassDescriptor)
        isEnumEntry -> owner.symbolTable.referenceEnumEntry(declaration as ClassDescriptor)
        else -> error("unexpected $kind element: $declaration")
    }

    fun KotlinType.includeToSignature() = !this.isUnit()

    fun makeCFunctionSignature(shortName: Boolean): List<SignatureElement> {
        if (!isFunction) {
            throw Error("only for functions")
        }
        val descriptor = declaration
        val original = descriptor.original as FunctionDescriptor
        val returned = when {
            original is ConstructorDescriptor ->
                SignatureElement(uniqueName(original, shortName), original.constructedClass.defaultType)
            else ->
                SignatureElement(uniqueName(original, shortName), original.returnType!!)
        }
        val uniqueNames = owner.paramsToUniqueNames(original.explicitParameters)
        val params = ArrayList(original.explicitParameters
                .filter { it.type.includeToSignature() }
                .map { SignatureElement(uniqueNames[it]!!, it.type) })
        return listOf(returned) + params
    }

    fun makeBridgeSignature(): List<String> {
        if (!isFunction) {
            throw Error("only for functions")
        }
        val descriptor = declaration
        val original = descriptor.original as FunctionDescriptor
        val returnedType = when {
            original is ConstructorDescriptor -> owner.context.builtIns.unitType
            else -> original.returnType!!
        }
        val params = ArrayList(original.allParameters
                .filter { it.type.includeToSignature() }
                .map {
                    typeTranslator.translateTypeBridge(it.type)
                })
        if (typeTranslator.isMappedToReference(returnedType) || typeTranslator.isMappedToString(returnedType)) {
            params += "KObjHeader**"
        }
        return listOf(typeTranslator.translateTypeBridge(returnedType)) + params
    }


    fun makeFunctionPointerString(): String {
        val signature = makeCFunctionSignature(true)
        return "${typeTranslator.translateType(signature[0])} (*${signature[0].name})(${signature.drop(1).map { "${typeTranslator.translateType(it)} ${it.name}" }.joinToString(", ")});"
    }

    fun makeTopLevelFunctionString(): Pair<String, String> {
        val signature = makeCFunctionSignature(false)
        val name = signature[0].name
        return (name to
                "extern ${typeTranslator.translateType(signature[0])} $name(${signature.drop(1).map { "${typeTranslator.translateType(it)} ${it.name}" }.joinToString(", ")});")
    }

    fun makeFunctionDeclaration(): String {
        assert(isFunction)
        val bridge = makeBridgeSignature()

        val builder = StringBuilder()
        builder.append("extern \"C\" ${bridge[0]} $cname")
        builder.append("(${bridge.drop(1).joinToString(", ")});\n")

        // Now the C function body.
        builder.append(translateBody(makeCFunctionSignature(false)))
        return builder.toString()
    }

    fun makeClassDeclaration(): String {
        assert(isClass)
        val typeGetter = "extern \"C\" ${owner.prefix}_KType* ${cname}_type(void);"
        val instanceGetter = if (isSingletonObject) {
            val objectClassC = typeTranslator.translateType((declaration as ClassDescriptor).defaultType)
            """
            |
            |extern "C" KObjHeader* ${cname}_instance(KObjHeader**);
            |static $objectClassC ${cname}_instance_impl(void) {
            |  Kotlin_initRuntimeIfNeeded();
            |  ScopedRunnableState stateGuard;
            |  KObjHolder result_holder;
            |  KObjHeader* result = ${cname}_instance(result_holder.slot());
            |  return $objectClassC { .pinned = CreateStablePointer(result)};
            |}
            """.trimMargin()
        } else ""
        return "$typeGetter$instanceGetter"
    }

    fun makeEnumEntryDeclaration(): String {
        assert(isEnumEntry)
        val enumClass = declaration.containingDeclaration as ClassDescriptor
        val enumClassC = typeTranslator.translateType(enumClass.defaultType)

        return """
              |extern "C" KObjHeader* $cname(KObjHeader**);
              |static $enumClassC ${cname}_impl(void) {
              |  Kotlin_initRuntimeIfNeeded();
              |  ScopedRunnableState stateGuard;
              |  KObjHolder result_holder;
              |  KObjHeader* result = $cname(result_holder.slot());
              |  return $enumClassC { .pinned = CreateStablePointer(result)};
              |}
              """.trimMargin()
    }

    private fun translateArgument(name: String, signatureElement: SignatureElement,
                                  direction: Direction, builder: StringBuilder): String {
        return when {
            typeTranslator.isMappedToString(signatureElement.type) ->
                if (direction == Direction.C_TO_KOTLIN) {
                    builder.append("  KObjHolder ${name}_holder;\n")
                    "CreateStringFromCString($name, ${name}_holder.slot())"
                } else {
                    "CreateCStringFromString($name)"
                }
            typeTranslator.isMappedToReference(signatureElement.type) ->
                if (direction == Direction.C_TO_KOTLIN) {
                    builder.append("  KObjHolder ${name}_holder2;\n")
                    "DerefStablePointer(${name}.pinned, ${name}_holder2.slot())"
                } else {
                    "((${typeTranslator.translateType(signatureElement.type)}){ .pinned = CreateStablePointer(${name})})"
                }
            else -> {
                assert(!signatureElement.type.binaryTypeIsReference()) {
                    println(signatureElement.toString())
                }
                name
            }
        }
    }

    val cnameImpl: String
        get() = if (isTopLevelFunction)
            functionImplName(declaration, "******" /* Default value must never be used. */, false)
        else
            "${cname}_impl"

    private fun translateBody(cfunction: List<SignatureElement>): String {
        val visibility = if (isTopLevelFunction) "RUNTIME_USED extern \"C\"" else "static"
        val builder = StringBuilder()
        builder.append("$visibility ${typeTranslator.translateType(cfunction[0])} ${cnameImpl}(${cfunction.drop(1).
                mapIndexed { index, it -> "${typeTranslator.translateType(it)} arg${index}" }.joinToString(", ")}) {\n")
        // TODO: do we really need that in every function?
        builder.append("  Kotlin_initRuntimeIfNeeded();\n")
        builder.append("  ScopedRunnableState stateGuard;\n")
        builder.append("  FrameOverlay* frame = getCurrentFrame();")
        val args = ArrayList(cfunction.drop(1).mapIndexed { index, pair ->
            translateArgument("arg$index", pair, Direction.C_TO_KOTLIN, builder)
        })
        val isVoidReturned = typeTranslator.isMappedToVoid(cfunction[0].type)
        val isConstructor = declaration is ConstructorDescriptor
        val isObjectReturned = !isConstructor && typeTranslator.isMappedToReference(cfunction[0].type)
        val isStringReturned = typeTranslator.isMappedToString(cfunction[0].type)
        builder.append("   try {\n")
        if (isObjectReturned || isStringReturned) {
            builder.append("  KObjHolder result_holder;\n")
            args += "result_holder.slot()"
        }
        if (isConstructor) {
            builder.append("  KObjHolder result_holder;\n")
            val clazz = scope.elements[0]
            assert(clazz.kind == ElementKind.TYPE)
            builder.append("  KObjHeader* result = AllocInstance((const KTypeInfo*)${clazz.cname}_type(), result_holder.slot());\n")
            args.add(0, "result")
        }
        if (!isVoidReturned && !isConstructor) {
            builder.append("  auto result = ")
        }
        builder.append("  $cname(")
        builder.append(args.joinToString(", "))
        builder.append(");\n")

        if (!isVoidReturned) {
            val result = translateArgument(
                    "result", cfunction[0], Direction.KOTLIN_TO_C, builder)
            builder.append("  return $result;\n")
        }
        builder.append("   } catch (...) {")
        builder.append("       SetCurrentFrame(reinterpret_cast<KObjHeader**>(frame));\n")
        builder.append("       HandleCurrentExceptionWhenLeavingKotlinCode();\n")
        builder.append("   } \n")

        builder.append("}\n")

        return builder.toString()
    }

    private fun addUsedType(type: KotlinType, set: MutableSet<KotlinType>) {
        if (type.constructor.declarationDescriptor is TypeParameterDescriptor) return
        set.add(type)
    }

    fun addUsedTypes(set: MutableSet<KotlinType>) {
        val descriptor = declaration
        when (descriptor) {
            is FunctionDescriptor -> {
                val original = descriptor.original
                original.allParameters.forEach { addUsedType(it.type, set) }
                original.returnType?.let { addUsedType(it, set) }
            }
            is PropertyAccessorDescriptor -> {
                val original = descriptor.original
                addUsedType(original.correspondingProperty.type, set)
            }
            is ClassDescriptor -> {
                set += descriptor.defaultType
            }
        }
    }
}

private fun getPackagesFqNames(module: ModuleDescriptor): Set<FqName> {
    val result = mutableSetOf<FqName>()

    fun getSubPackages(fqName: FqName) {
        result.add(fqName)
        module.getSubPackagesOf(fqName) { true }.forEach { getSubPackages(it) }
    }

    getSubPackages(FqName.ROOT)
    return result
}

private fun ModuleDescriptor.getPackageFragments(): List<PackageFragmentDescriptor> =
        getPackagesFqNames(this).flatMap {
            getPackage(it).fragments.filter { it.module == this }
        }

internal class CAdapterGenerator(
        val context: Context,
        private val typeTranslator: CAdapterTypeTranslator,
) : DeclarationDescriptorVisitor<Boolean, Void?> {
    private val builtIns = context.builtIns

    private val scopes = mutableListOf<ExportedElementScope>()
    internal val prefix = context.config.fullExportedNamePrefix.replace("-|\\.".toRegex(), "_")
    private val paramNamesRecorded = mutableMapOf<String, Int>()

    private var symbolTableOrNull: SymbolTable? = null
    internal val symbolTable get() = symbolTableOrNull!!

    internal fun paramsToUniqueNames(params: List<ParameterDescriptor>): Map<ParameterDescriptor, String> {
        paramNamesRecorded.clear()
        return params.associate {
            val name = translateName(it.name)
            val count = paramNamesRecorded.getOrDefault(name, 0)
            paramNamesRecorded[name] = count + 1
            if (count == 0) {
                it to name
            } else {
                it to "$name${count.toString()}"
            }
        }
    }

    private fun visitChildren(descriptors: Collection<DeclarationDescriptor>) {
        for (descriptor in descriptors) {
            descriptor.accept(this, null)
        }
    }

    private fun visitChildren(descriptor: DeclarationDescriptor) {
        descriptor.accept(this, null)
    }

    override fun visitConstructorDescriptor(descriptor: ConstructorDescriptor, ignored: Void?): Boolean {
        if (!isExportedFunction(descriptor)) return true
        ExportedElement(ElementKind.FUNCTION, scopes.last(), descriptor, this, typeTranslator)
        return true
    }

    override fun visitFunctionDescriptor(descriptor: FunctionDescriptor, ignored: Void?): Boolean {
        if (!isExportedFunction(descriptor)) return true
        ExportedElement(ElementKind.FUNCTION, scopes.last(), descriptor, this, typeTranslator)
        return true
    }

    override fun visitClassDescriptor(descriptor: ClassDescriptor, ignored: Void?): Boolean {
        if (!isExportedClass(descriptor)) return true
        // TODO: fix me!
        val shortName = descriptor.fqNameSafe.shortName()
        if (shortName.isSpecial || shortName.asString().contains("<anonymous>"))
            return true
        val classScope = ExportedElementScope(ScopeKind.CLASS, shortName.asString())
        scopes.last().scopes += classScope
        scopes.push(classScope)
        // Add type getter.
        ExportedElement(ElementKind.TYPE, scopes.last(), descriptor, this, typeTranslator)
        visitChildren(descriptor.getConstructors())
        visitChildren(DescriptorUtils.getAllDescriptors(descriptor.getDefaultType().memberScope))
        scopes.pop()
        return true
    }

    override fun visitPropertyDescriptor(descriptor: PropertyDescriptor, ignored: Void?): Boolean {
        if (descriptor.isExpect) return true
        descriptor.getter?.let { visitChildren(it) }
        descriptor.setter?.let { visitChildren(it) }
        return true
    }

    override fun visitPropertyGetterDescriptor(descriptor: PropertyGetterDescriptor, ignored: Void?): Boolean {
        if (!isExportedFunction(descriptor)) return true
        ExportedElement(ElementKind.FUNCTION, scopes.last(), descriptor, this, typeTranslator)
        return true
    }

    override fun visitPropertySetterDescriptor(descriptor: PropertySetterDescriptor, ignored: Void?): Boolean {
        if (!isExportedFunction(descriptor)) return true
        ExportedElement(ElementKind.FUNCTION, scopes.last(), descriptor, this, typeTranslator)
        return true
    }

    override fun visitScriptDescriptor(descriptor: ScriptDescriptor, ignored: Void?) = true

    override fun visitPackageViewDescriptor(descriptor: PackageViewDescriptor, ignored: Void?): Boolean {
        if (descriptor.module !in moduleDescriptors) return true
        val fragments = descriptor.module.getPackage(FqName.ROOT).fragments.filter {
            it.module in moduleDescriptors }
        visitChildren(fragments)
        return true
    }

    override fun visitValueParameterDescriptor(descriptor: ValueParameterDescriptor, ignored: Void?): Boolean {
        TODO("visitValueParameterDescriptor() shall not be seen")
    }

    override fun visitReceiverParameterDescriptor(descriptor: ReceiverParameterDescriptor?, ignored: Void?): Boolean {
        TODO("visitReceiverParameterDescriptor() shall not be seen")
    }

    override fun visitVariableDescriptor(descriptor: VariableDescriptor, ignored: Void?) = true

    override fun visitTypeParameterDescriptor(descriptor: TypeParameterDescriptor, ignored: Void?) = true

    private val seenPackageFragments = mutableSetOf<PackageFragmentDescriptor>()
    private var currentPackageFragments: List<PackageFragmentDescriptor> = emptyList()
    private val packageScopes = mutableMapOf<FqName, ExportedElementScope>()

    override fun visitModuleDeclaration(descriptor: ModuleDescriptor, ignored: Void?): Boolean {
        TODO("Shall not be called directly")
    }

    override fun visitTypeAliasDescriptor(descriptor: TypeAliasDescriptor, ignored: Void?) = true

    override fun visitPackageFragmentDescriptor(descriptor: PackageFragmentDescriptor, ignored: Void?): Boolean {
        val fqName = descriptor.fqName
        val packageScope = packageScopes.getOrPut(fqName) {
            val name = if (fqName.isRoot) "root" else translateName(fqName.shortName())
            val scope = ExportedElementScope(ScopeKind.PACKAGE, name)
            scopes.last().scopes += scope
            scope
        }
        scopes.push(packageScope)
        visitChildren(DescriptorUtils.getAllDescriptors(descriptor.getMemberScope()))
        for (currentPackageFragment in currentPackageFragments) {
            if (!seenPackageFragments.contains(currentPackageFragment) &&
                    currentPackageFragment.fqName.isChildOf(descriptor.fqName)) {
                seenPackageFragments += currentPackageFragment
                visitChildren(currentPackageFragment)
            }
        }
        scopes.pop()
        return true
    }


    private val moduleDescriptors = mutableSetOf<ModuleDescriptor>()

    fun buildExports(symbolTable: SymbolTable) {
        this.symbolTableOrNull = symbolTable
        try {
            buildExports()
        } finally {
            this.symbolTableOrNull = null
        }
    }

    private fun buildExports() {
        scopes.push(ExportedElementScope(ScopeKind.TOP, "kotlin"))
        moduleDescriptors += context.moduleDescriptor
        moduleDescriptors += context.getExportedDependencies()

        currentPackageFragments = moduleDescriptors.flatMap { it.getPackageFragments() }.toSet().sortedWith(
                Comparator { o1, o2 ->
                    o1.fqName.toString().compareTo(o2.fqName.toString())
                })

        context.moduleDescriptor.getPackage(FqName.ROOT).accept(this, null)
    }

    private val simpleNameMapping = mapOf(
            "<this>" to "thiz",
            "<set-?>" to "set"
    )

    fun translateName(name: Name): String {
        val nameString = name.asString()
        return when {
            simpleNameMapping.contains(nameString) -> simpleNameMapping[nameString]!!
            cKeywords.contains(nameString) -> "${nameString}_"
            name.isSpecial -> nameString.replace("[<> ]".toRegex(), "_")
            else -> nameString
        }
    }

    private var functionIndex = 0
    fun nextFunctionIndex() = functionIndex++

    fun generateBindings(codegen: CodeGenerator) = BindingsBuilder(codegen).build()

    inner class BindingsBuilder(val codegen: CodeGenerator) : ContextUtils {
        override val generationState = codegen.generationState

        internal val prefix = context.config.fullExportedNamePrefix.replace("-|\\.".toRegex(), "_")
        private lateinit var outputStreamWriter: PrintWriter

        // Primitive built-ins and unsigned types
        private val predefinedTypes = listOf(
                builtIns.byteType, builtIns.shortType,
                builtIns.intType, builtIns.longType,
                builtIns.floatType, builtIns.doubleType,
                builtIns.charType, builtIns.booleanType,
                builtIns.unitType
        ) + UnsignedType.values().map {
            // Unfortunately, `context.ir` and `context.irBuiltins` are not initialized, so `context.ir.symbols.ubyte`, etc, are unreachable.
            builtIns.builtInsModule.findClassAcrossModuleDependencies(it.classId)!!.defaultType
        }

        fun build() {
            val top = scopes.pop()
            assert(scopes.isEmpty() && top.kind == ScopeKind.TOP)

            // Now, let's generate C world adapters for all functions.
            top.generateCAdapters(::buildCAdapter)

            // Then generate data structure, describing generated adapters.
            makeGlobalStruct(top)
        }

        private fun buildCAdapter(exportedElement: ExportedElement): Unit = with(exportedElement) {
            when {
                isFunction -> {
                    val function = declaration as FunctionDescriptor
                    val irFunction = irSymbol.owner as IrFunction
                    cname = "_konan_function_${owner.nextFunctionIndex()}"
                    val llvmCallable = codegen.llvmFunction(irFunction)
                    // If function is virtual, we need to resolve receiver properly.
                    val bridge = generateFunction(codegen, llvmCallable.functionType, cname) {
                        val callee = if (!DescriptorUtils.isTopLevelDeclaration(function) &&
                                irFunction.isOverridable) {
                            val receiver = param(0)
                            lookupVirtualImpl(receiver, irFunction)
                        } else {
                            // KT-45468: Alias insertion may not be handled by LLVM properly, in case callee is in the cache.
                            // Hence, insert not an alias but a wrapper, hoping it will be optimized out later.
                            llvmCallable
                        }

                        val numParams = LLVMCountParams(llvmCallable.llvmValue)
                        val args = (0 until numParams).map { index -> param(index) }
                        callee.attributeProvider.addFunctionAttributes(this.function)
                        val result = call(callee, args, exceptionHandler = ExceptionHandler.Caller, verbatim = true)
                        ret(result)
                    }
                    LLVMSetLinkage(bridge, LLVMLinkage.LLVMExternalLinkage)
                }
                isClass -> {
                    val irClass = irSymbol.owner as IrClass
                    cname = "_konan_function_${owner.nextFunctionIndex()}"
                    // Produce type getter.
                    val getTypeFunction = addLlvmFunctionWithDefaultAttributes(
                            context,
                            llvm.module,
                            "${cname}_type",
                            kGetTypeFuncType
                    )
                    val builder = LLVMCreateBuilderInContext(llvm.llvmContext)!!
                    val bb = LLVMAppendBasicBlockInContext(llvm.llvmContext, getTypeFunction, "")!!
                    LLVMPositionBuilderAtEnd(builder, bb)
                    LLVMBuildRet(builder, irClass.typeInfoPtr.llvm)
                    LLVMDisposeBuilder(builder)
                    // Produce instance getter if needed.
                    if (isSingletonObject) {
                        generateFunction(codegen, kGetObjectFuncType, "${cname}_instance") {
                            val value = call(
                                    codegen.llvmFunction(context.getObjectClassInstanceFunction(irClass)),
                                    emptyList(),
                                    Lifetime.GLOBAL,
                                    ExceptionHandler.Caller,
                                    false,
                                    returnSlot)
                            ret(value)
                        }
                    }
                }
                isEnumEntry -> {
                    // Produce entry getter.
                    cname = "_konan_function_${owner.nextFunctionIndex()}"
                    generateFunction(codegen, kGetObjectFuncType, cname) {
                        val irEnumEntry = irSymbol.owner as IrEnumEntry
                        val value = getEnumEntry(irEnumEntry, ExceptionHandler.Caller)
                        ret(value)
                    }
                }
            }
        }

        private val kGetTypeFuncType = LLVMFunctionType(codegen.kTypeInfoPtr, null, 0, 0)!!

        // Abstraction leak for slot :(.
        private val kGetObjectFuncType = LLVMFunctionType(codegen.kObjHeaderPtr, cValuesOf(codegen.kObjHeaderPtrPtr), 1, 0)!!

        private fun output(string: String, indent: Int = 0) {
            if (indent != 0) outputStreamWriter.print("  " * indent)
            outputStreamWriter.println(string)
        }

        private fun makeElementDefinition(element: ExportedElement, kind: DefinitionKind, indent: Int) {
            when (kind) {
                DefinitionKind.C_HEADER_DECLARATION -> {
                    when {
                        element.isTopLevelFunction -> {
                            val (name, declaration) = element.makeTopLevelFunctionString()
                            exportedSymbols += name
                            output(declaration, 0)
                        }
                    }
                }

                DefinitionKind.C_HEADER_STRUCT -> {
                    when {
                        element.isFunction ->
                            output(element.makeFunctionPointerString(), indent)
                        element.isClass -> {
                            output("${prefix}_KType* (*_type)(void);", indent)
                            if (element.isSingletonObject) {
                                output("${typeTranslator.translateType((element.declaration as ClassDescriptor).defaultType)} (*_instance)();", indent)
                            }
                        }
                        element.isEnumEntry -> {
                            val enumClass = element.declaration.containingDeclaration as ClassDescriptor
                            output("${typeTranslator.translateType(enumClass.defaultType)} (*get)(); /* enum entry for ${element.name}. */", indent)
                        }
                        // TODO: handle properties.
                    }
                }

                DefinitionKind.C_SOURCE_DECLARATION -> {
                    when {
                        element.isFunction ->
                            output(element.makeFunctionDeclaration(), 0)
                        element.isClass ->
                            output(element.makeClassDeclaration(), 0)
                        element.isEnumEntry ->
                            output(element.makeEnumEntryDeclaration(), 0)
                        // TODO: handle properties.
                    }
                }

                DefinitionKind.C_SOURCE_STRUCT -> {
                    when {
                        element.isFunction ->
                            output("/* ${element.name} = */ ${element.cnameImpl}, ", indent)
                        element.isClass -> {
                            output("/* Type for ${element.name} = */  ${element.cname}_type, ", indent)
                            if (element.isSingletonObject)
                                output("/* Instance for ${element.name} = */ ${element.cname}_instance_impl, ", indent)
                        }
                        element.isEnumEntry ->
                            output("/* enum entry getter ${element.name} = */  ${element.cname}_impl,", indent)
                        // TODO: handle properties.
                    }
                }
            }
        }

        private fun ExportedElementScope.hasNonEmptySubScopes(): Boolean = elements.isNotEmpty() || scopes.any { it.hasNonEmptySubScopes() }

        private fun makeScopeDefinitions(scope: ExportedElementScope, kind: DefinitionKind, indent: Int) {
            if (!scope.hasNonEmptySubScopes())
                return
            if (kind == DefinitionKind.C_HEADER_STRUCT) output("struct {", indent)
            if (kind == DefinitionKind.C_SOURCE_STRUCT) output(".${scope.name} = {", indent)
            scope.scopes.forEach {
                scope.collectInnerScopeName(it)
                makeScopeDefinitions(it, kind, indent + 1)
            }
            scope.elements.forEach { makeElementDefinition(it, kind, indent + 1) }
            if (kind == DefinitionKind.C_HEADER_STRUCT) output("} ${scope.name};", indent)
            if (kind == DefinitionKind.C_SOURCE_STRUCT) output("},", indent)
        }

        private fun defineUsedTypesImpl(scope: ExportedElementScope, set: MutableSet<KotlinType>) {
            scope.elements.forEach {
                it.addUsedTypes(set)
            }
            scope.scopes.forEach {
                defineUsedTypesImpl(it, set)
            }
        }

        private fun defineUsedTypes(scope: ExportedElementScope, indent: Int) {
            val usedTypes = mutableSetOf<KotlinType>()
            defineUsedTypesImpl(scope, usedTypes)
            val usedReferenceTypes = usedTypes.filter { typeTranslator.isMappedToReference(it) }
            // Add nullable primitives, which are used in prototypes of "(*createNullable<PRIMITIVE_TYPE_NAME>)"
            val predefinedNullableTypes: List<KotlinType> = predefinedTypes.map { it.makeNullable() }

            (predefinedNullableTypes + usedReferenceTypes)
                    .map { typeTranslator.translateType(it) }
                    .toSet()
                    .forEach {
                        output("typedef struct {", indent)
                        output("${prefix}_KNativePtr pinned;", indent + 1)
                        output("} $it;", indent)
                    }
        }

        val exportedSymbols = mutableListOf<String>()

        private fun makeGlobalStruct(top: ExportedElementScope) {
            val headerFile = generationState.outputFiles.cAdapterHeader
            outputStreamWriter = headerFile.printWriter()

            val exportedSymbol = "${prefix}_symbols"
            exportedSymbols += exportedSymbol

            output("#ifndef KONAN_${prefix.uppercase()}_H")
            output("#define KONAN_${prefix.uppercase()}_H")
            // TODO: use namespace for C++ case?
            output("""
        #ifdef __cplusplus
        extern "C" {
        #endif""".trimIndent())
            output("""
        #ifdef __cplusplus
        typedef bool            ${prefix}_KBoolean;
        #else
        typedef _Bool           ${prefix}_KBoolean;
        #endif
        """.trimIndent())
            output("typedef unsigned short     ${prefix}_KChar;")
            output("typedef signed char        ${prefix}_KByte;")
            output("typedef short              ${prefix}_KShort;")
            output("typedef int                ${prefix}_KInt;")
            output("typedef long long          ${prefix}_KLong;")
            output("typedef unsigned char      ${prefix}_KUByte;")
            output("typedef unsigned short     ${prefix}_KUShort;")
            output("typedef unsigned int       ${prefix}_KUInt;")
            output("typedef unsigned long long ${prefix}_KULong;")
            output("typedef float              ${prefix}_KFloat;")
            output("typedef double             ${prefix}_KDouble;")

            val typedef_KVector128 = "typedef float __attribute__ ((__vector_size__ (16))) ${prefix}_KVector128;"
            if (context.config.target.family == Family.MINGW) {
                // Separate `output` for each line to ensure Windows EOL (LFCR), otherwise generated file will have inconsistent line ending.
                output("#ifndef _MSC_VER")
                output(typedef_KVector128)
                output("#else")
                output("#include <xmmintrin.h>")
                output("typedef __m128 ${prefix}_KVector128;")
                output("#endif")
            } else {
                output(typedef_KVector128)
            }

            output("typedef void*              ${prefix}_KNativePtr;")
            output("struct ${prefix}_KType;")
            output("typedef struct ${prefix}_KType ${prefix}_KType;")

            output("")
            defineUsedTypes(top, 0)

            output("")
            makeScopeDefinitions(top, DefinitionKind.C_HEADER_DECLARATION, 0)

            output("")
            output("typedef struct {")
            output("/* Service functions. */", 1)
            output("void (*DisposeStablePointer)(${prefix}_KNativePtr ptr);", 1)
            output("void (*DisposeString)(const char* string);", 1)
            output("${prefix}_KBoolean (*IsInstance)(${prefix}_KNativePtr ref, const ${prefix}_KType* type);", 1)
            predefinedTypes.forEach {
                val nullableIt = it.makeNullable()
                val argument = if (!it.isUnit()) typeTranslator.translateType(it) else "void"
                output("${typeTranslator.translateType(nullableIt)} (*${it.createNullableNameForPredefinedType})($argument);", 1)
                if(!it.isUnit())
                    output("$argument (*${it.createGetNonNullValueOfPredefinedType})(${typeTranslator.translateType(nullableIt)});", 1)
            }

            output("")
            output("/* User functions. */", 1)
            makeScopeDefinitions(top, DefinitionKind.C_HEADER_STRUCT, 1)
            output("} ${prefix}_ExportedSymbols;")

            output("extern ${prefix}_ExportedSymbols* $exportedSymbol(void);")
            output("""
        #ifdef __cplusplus
        }  /* extern "C" */
        #endif""".trimIndent())

            output("#endif  /* KONAN_${prefix.uppercase()}_H */")

            outputStreamWriter.close()
            println("Produced library API in ${prefix}_api.h")

            outputStreamWriter = generationState.tempFiles
                    .cAdapterCpp
                    .printWriter()

            // Include header into C++ source.
            headerFile.forEachLine { it -> output(it) }

            output("#include <exception>")

            output("""
        |struct KObjHeader;
        |typedef struct KObjHeader KObjHeader;
        |struct KTypeInfo;
        |typedef struct KTypeInfo KTypeInfo;
        |
        |struct FrameOverlay;
        |typedef struct FrameOverlay FrameOverlay;
        |
        |#define RUNTIME_NOTHROW __attribute__((nothrow))
        |#define RUNTIME_USED __attribute__((used))
        |#define RUNTIME_NORETURN __attribute__((noreturn))
        |
        |extern "C" {
        |void UpdateStackRef(KObjHeader**, const KObjHeader*) RUNTIME_NOTHROW;
        |KObjHeader* AllocInstance(const KTypeInfo*, KObjHeader**) RUNTIME_NOTHROW;
        |KObjHeader* DerefStablePointer(void*, KObjHeader**) RUNTIME_NOTHROW;
        |void* CreateStablePointer(KObjHeader*) RUNTIME_NOTHROW;
        |void DisposeStablePointer(void*) RUNTIME_NOTHROW;
        |${prefix}_KBoolean IsInstance(const KObjHeader*, const KTypeInfo*) RUNTIME_NOTHROW;
        |void EnterFrame(KObjHeader** start, int parameters, int count) RUNTIME_NOTHROW;
        |void LeaveFrame(KObjHeader** start, int parameters, int count) RUNTIME_NOTHROW;
        |void SetCurrentFrame(KObjHeader** start) RUNTIME_NOTHROW;
        |FrameOverlay* getCurrentFrame() RUNTIME_NOTHROW;
        |void Kotlin_initRuntimeIfNeeded();
        |void Kotlin_mm_switchThreadStateRunnable() RUNTIME_NOTHROW;
        |void Kotlin_mm_switchThreadStateNative() RUNTIME_NOTHROW;
        |void HandleCurrentExceptionWhenLeavingKotlinCode();
        |
        |KObjHeader* CreateStringFromCString(const char*, KObjHeader**);
        |char* CreateCStringFromString(const KObjHeader*);
        |void DisposeCString(char* cstring);
        |}  // extern "C"
        |
        |struct ${prefix}_FrameOverlay {
        |  void* arena;
        |  ${prefix}_FrameOverlay* previous;
        |  ${prefix}_KInt parameters;
        |  ${prefix}_KInt count;
        |};
        |
        |class KObjHolder {
        |public:
        |  KObjHolder() : obj_(nullptr) {
        |    EnterFrame(frame(), 0, sizeof(*this)/sizeof(void*));
        |  }
        |  explicit KObjHolder(const KObjHeader* obj) : obj_(nullptr) {
        |    EnterFrame(frame(), 0, sizeof(*this)/sizeof(void*));
        |    UpdateStackRef(&obj_, obj);
        |  }
        |  ~KObjHolder() {
        |    LeaveFrame(frame(), 0, sizeof(*this)/sizeof(void*));
        |  }
        |  KObjHeader* obj() { return obj_; }
        |  KObjHeader** slot() { return &obj_; }
        | private:
        |  ${prefix}_FrameOverlay frame_;
        |  KObjHeader* obj_;
        |
        |  KObjHeader** frame() { return reinterpret_cast<KObjHeader**>(&frame_); }
        |};
        |
        |class ScopedRunnableState {
        |public:
        |   ScopedRunnableState() noexcept { Kotlin_mm_switchThreadStateRunnable(); }
        |   ~ScopedRunnableState() { Kotlin_mm_switchThreadStateNative(); }
        |   ScopedRunnableState(const ScopedRunnableState&) = delete;
        |   ScopedRunnableState(ScopedRunnableState&&) = delete;
        |   ScopedRunnableState& operator=(const ScopedRunnableState&) = delete;
        |   ScopedRunnableState& operator=(ScopedRunnableState&&) = delete;
        |};
        |
        |static void DisposeStablePointerImpl(${prefix}_KNativePtr ptr) {
        |  Kotlin_initRuntimeIfNeeded();
        |  ScopedRunnableState stateGuard;
        |  DisposeStablePointer(ptr);
        |}
        |static void DisposeStringImpl(const char* ptr) {
        |  DisposeCString((char*)ptr);
        |}
        |static ${prefix}_KBoolean IsInstanceImpl(${prefix}_KNativePtr ref, const ${prefix}_KType* type) {
        |  Kotlin_initRuntimeIfNeeded();
        |  ScopedRunnableState stateGuard;
        |  KObjHolder holder;
        |  return IsInstance(DerefStablePointer(ref, holder.slot()), (const KTypeInfo*)type);
        |}
        """.trimMargin())
            predefinedTypes.forEach {
                assert(!it.isNothing())
                val nullableIt = it.makeNullable()
                val needArgument = !it.isUnit()
                val (parameter, maybeComma) = if (needArgument)
                    ("${typeTranslator.translateType(it)} value" to ",") else ("" to "")
                val argument = if (needArgument) "value, " else ""
                output("extern \"C\" KObjHeader* Kotlin_box${it.shortNameForPredefinedType}($parameter$maybeComma KObjHeader**);")
                output("static ${typeTranslator.translateType(nullableIt)} ${it.createNullableNameForPredefinedType}Impl($parameter) {")
                output("Kotlin_initRuntimeIfNeeded();", 1)
                output("ScopedRunnableState stateGuard;", 1)
                output("KObjHolder result_holder;", 1)
                output("KObjHeader* result = Kotlin_box${it.shortNameForPredefinedType}($argument result_holder.slot());", 1)
                output("return ${typeTranslator.translateType(nullableIt)} { .pinned = CreateStablePointer(result) };", 1)
                output("}")

                if (!it.isUnit()) {
                    output("extern \"C\" ${typeTranslator.translateType(it)} Kotlin_unbox${it.shortNameForPredefinedType}(KObjHeader*);")
                    output("static ${typeTranslator.translateType(it)} ${it.createGetNonNullValueOfPredefinedType}Impl(${typeTranslator.translateType(nullableIt)} value) {")
                    output("Kotlin_initRuntimeIfNeeded();", 1)
                    output("ScopedRunnableState stateGuard;", 1)
                    output("KObjHolder value_holder;", 1)
                    output("return Kotlin_unbox${it.shortNameForPredefinedType}(DerefStablePointer(value.pinned, value_holder.slot()));", 1)
                    output("}")
                }
            }
            makeScopeDefinitions(top, DefinitionKind.C_SOURCE_DECLARATION, 0)
            output("static ${prefix}_ExportedSymbols __konan_symbols = {")
            output(".DisposeStablePointer = DisposeStablePointerImpl,", 1)
            output(".DisposeString = DisposeStringImpl,", 1)
            output(".IsInstance = IsInstanceImpl,", 1)
            predefinedTypes.forEach {
                output(".${it.createNullableNameForPredefinedType} = ${it.createNullableNameForPredefinedType}Impl,", 1)
                if (!it.isUnit()) {
                    output(".${it.createGetNonNullValueOfPredefinedType} = ${it.createGetNonNullValueOfPredefinedType}Impl,", 1)
                }
            }

            makeScopeDefinitions(top, DefinitionKind.C_SOURCE_STRUCT, 1)
            output("};")
            output("RUNTIME_USED ${prefix}_ExportedSymbols* $exportedSymbol(void) { return &__konan_symbols;}")
            outputStreamWriter.close()

            if (context.config.target.family == Family.MINGW) {
                outputStreamWriter = generationState.outputFiles
                        .cAdapterDef
                        .printWriter()
                output("EXPORTS")
                exportedSymbols.forEach { output(it) }
                outputStreamWriter.close()
            }
        }
    }
}
