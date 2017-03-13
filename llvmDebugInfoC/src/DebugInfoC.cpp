#include <llvm/IR/DebugInfo.h>
#include <llvm/IR/DIBuilder.h>
#include "DebugInfoC.h"

/** 
 * c++ --std=c++11 llvmDebugInfoC/src/DebugInfoC.cpp -IllvmDebugInfoC/include/ -Idependencies/all/clang+llvm-3.9.0-darwin-macos/include -Ldependencies/all/clang+llvm-3.9.0-darwin-macos/lib  -lLLVMCore -lLLVMSupport -lncurses -shared -o libLLVMDebugInfoC.dylib
 */

namespace llvm {
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(DIBuilder, DIBuilderRef)
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(DICompileUnit, DICompileUnitRef)
DEFINE_SIMPLE_CONVERSION_FUNCTIONS(Module, LLVMModuleRef)
}

extern "C" {

  DICompileUnitRef DICreateCompilationUnit(DIBuilderRef builder, unsigned int lang,
                                           const char *file, const char* dir,
                                           const char * producer, int isOptimized,
                                           const char * flags, unsigned int rv) {
    return llvm::wrap(llvm::unwrap(builder)->createCompileUnit(lang, file, dir, producer, isOptimized, flags, rv));
  }


  DIBuilderRef DICreateBuilder(LLVMModuleRef module) {
    return llvm::wrap(new llvm::DIBuilder(* llvm::unwrap(module)));
  }
}

