#include <stdlib.h>
#include <llvm-c/Analysis.h>
#include <DebugInfoC.h>
/**
 * this demo produces bitcode for case when several functions generated from one source code.
 * e.g. for following source:
 * 0:123456789012345678901
 * 1: fun main():Int {
 * 2:   {foo()}()
 * 3:   return 0
 * 4: }
 * 5  fun foo() {}
 *
 * we produce following IR (line and column numbers are in parencies)
 * fun main$caller$foo(2:4)
 *     call foo(5:2)
 * fun main(1:2)
 *     call main$caller$foo(2:12)
 *     return0(3:4)
 * fun foo(5:2)
 *     return(5:14)
 */

LLVMModuleRef  module;
DIBuilderRef   di_builder;
LLVMBuilderRef llvm_builder;

static LLVMValueRef
create_function(const char* name) {
  LLVMValueRef functionType = LLVMFunctionType(LLVMVoidType(), NULL, 0, 0);
  return LLVMAddFunction(module, name, functionType);
}

int
main() {
  module       = LLVMModuleCreateWithName("test");
  di_builder   = DICreateBuilder(module);
  llvm_builder = LLVMCreateBuilderInContext(LLVMGetModuleContext(module));
  LLVMValueRef function_foo = create_function("foo");
  LLVMBasicBlockRef bb = LLVMAppendBasicBlock(function_foo, "entry");
  LLVMPositionBuilderAtEnd(llvm_builder, bb);
  LLVMBuildRet(llvm_builder, LLVMConstInt(LLVMInt32Type(), 0x0badbabel, 0));
  LLVMVerifyModule(module, LLVMPrintMessageAction, NULL);
  LLVMDumpModule(module);
}
