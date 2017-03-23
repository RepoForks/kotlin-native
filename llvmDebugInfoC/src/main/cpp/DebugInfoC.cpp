#include <llvm/IR/DebugInfo.h>
#include <llvm/IR/Function.h>
#include <llvm/IR/IRBuilder.h>
#include <llvm/IR/DIBuilder.h>
#include <llvm/IR/DebugInfoMetadata.h>
#include <llvm/IR/Instruction.h>
#include <llvm/Support/Casting.h>
#include "DebugInfoC.h"


/** 
 * c++ --std=c++11 llvmDebugInfoC/src/DebugInfoC.cpp -IllvmDebugInfoC/include/ -Idependencies/all/clang+llvm-3.9.0-darwin-macos/include -Ldependencies/all/clang+llvm-3.9.0-darwin-macos/lib  -lLLVMCore -lLLVMSupport -lncurses -shared -o libLLVMDebugInfoC.dylib
 */

namespace llvm {
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(DIBuilder,        DIBuilderRef)
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(DICompileUnit,    DICompileUnitRef)
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(DIFile,           DIFileRef)
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(DIBasicType,      DIBasicTypeRef)
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(DIType,           DITypeOpaqueRef)
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(DIModule,         DIModuleRef)
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(DIScope,          DIScopeOpaqueRef)
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(DISubroutineType, DISubroutineTypeRef)
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(DISubprogram,     DISubprogramRef)
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(DILocation,       DILocationRef)

// from Module.cpp
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(Module,        LLVMModuleRef)
}

extern "C" {

  DIBuilderRef DICreateBuilder(LLVMModuleRef module) {
    return llvm::wrap(new llvm::DIBuilder(* llvm::unwrap(module)));
  }

  void DIFinalize(DIBuilderRef builder) {
    llvm::unwrap(builder)->finalize();
  }
  
  DICompileUnitRef DICreateCompilationUnit(DIBuilderRef builder, unsigned int lang,
                                           const char *file, const char* dir,
                                           const char * producer, int isOptimized,
                                           const char * flags, unsigned int rv) {
    return llvm::wrap(llvm::unwrap(builder)->createCompileUnit(lang, file, dir, producer, isOptimized, flags, rv));
  }

  DIFileRef DICreateFile(DIBuilderRef builder, const char *filename, const char *directory) {
    return llvm::wrap(llvm::unwrap(builder)->createFile(filename, directory));
  }

  DIBasicTypeRef DICreateBasicType(DIBuilderRef builder, const char* name, uint64_t sizeInBits, uint64_t alignment, unsigned encoding) {
    return llvm::wrap(llvm::unwrap(builder)->createBasicType(name, sizeInBits, alignment, encoding));
  }

  DIModuleRef DICreateModule(DIBuilderRef builder, DIScopeOpaqueRef scope,
                             const char* name, const char* configurationMacro,
                             const char* includePath, const char *iSysRoot) {
    return llvm::wrap(llvm::unwrap(builder)->createModule(llvm::unwrap(scope), name, configurationMacro, includePath, iSysRoot));
  }

  DISubprogramRef DICreateFunction(DIBuilderRef builder, DIScopeOpaqueRef scope,
                                   const char* name, const char *linkageName,
                                   DIFileRef file, unsigned lineNo,
                                   DISubroutineTypeRef type, int isLocal,
                                   int isDefinition, unsigned scopeLine) {
    return llvm::wrap(llvm::unwrap(builder)->createFunction(llvm::unwrap(scope),
                                                            name,
                                                            linkageName,
                                                            llvm::unwrap(file),
                                                            lineNo,
                                                            llvm::unwrap(type),
                                                            isLocal,
                                                            isDefinition,
                                                            scopeLine));
  }

  /* */
  DISubroutineTypeRef DICreateSubroutineType(DIBuilderRef builder,
                                             DITypeOpaqueRef* types,
                                             unsigned typesCount) {
    std::vector<llvm::Metadata *> parameterTypes;
    for (int i = 0; i != typesCount; ++i) {
      parameterTypes.push_back(llvm::unwrap(types[i]));
    }
    llvm::DIBuilder *b = llvm::unwrap(builder);
    llvm::DITypeRefArray typeArray = b->getOrCreateTypeArray(parameterTypes);
    return llvm::wrap(b->createSubroutineType(typeArray));
  }

  void DIFunctionAddSubprogram(LLVMValueRef fn, DISubprogramRef sp) {
    auto f = llvm::cast<llvm::Function>(llvm::unwrap(fn));
    auto dsp = llvm::cast<llvm::DISubprogram>(llvm::unwrap(sp));
    f->setSubprogram(dsp);
    if (!dsp->describes(f)) {
      fprintf(stderr, "error!!! f:%s, sp:%s\n", f->getName(), dsp->getLinkageName()); 
    }
  }

  void LLVMBuilderSetDebugLocation(LLVMBuilderRef builder, unsigned line,
                                   unsigned col, DIScopeOpaqueRef scope) {
    auto sp = llvm::unwrap(scope);
    auto llvmBuilder = llvm::unwrap(builder);
    llvmBuilder->SetCurrentDebugLocation(
      llvm::DILocation::get(llvmBuilder->getContext(), line, col, sp, nullptr));
  }

  const char *DIGetSubprogramLinkName(DISubprogramRef sp) {
    return llvm::unwrap(sp)->getLinkageName().str().c_str();
  }
}

