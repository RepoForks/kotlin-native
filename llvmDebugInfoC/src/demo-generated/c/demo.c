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

static void
create_function_with_entry(const char *name) {
  LLVMValueRef function = create_function(name);
  LLVMBasicBlockRef bb = LLVMAppendBasicBlock(function, "entry");
  LLVMPositionBuilderAtEnd(llvm_builder, bb);
}

#define FOO_FUNCTION "foo"
#define MAIN_FUNCTION "main"
#define MAIN_CALLER_FOO_FUNCTION "main$caller$foo"


static void
create_foo() {
  create_function_with_entry(FOO_FUNCTION);
  LLVMBuildRetVoid(llvm_builder);
}

static void
create_main_caller_foo() {
  create_function_with_entry(MAIN_CALLER_FOO_FUNCTION);
  LLVMValueRef fn = LLVMGetNamedFunction(module, FOO_FUNCTION);
  LLVMBuildCall(llvm_builder, fn, NULL, 0, "");
  LLVMBuildRetVoid(llvm_builder);
}

static void
create_main() {
  create_function_with_entry(MAIN_FUNCTION);
  LLVMValueRef fn = LLVMGetNamedFunction(module, MAIN_CALLER_FOO_FUNCTION);
  LLVMBuildCall(llvm_builder, fn, NULL, 0, "");
  LLVMBuildRetVoid(llvm_builder);
}


int
main() {
  module       = LLVMModuleCreateWithName("test");
  di_builder   = DICreateBuilder(module);
  llvm_builder = LLVMCreateBuilderInContext(LLVMGetModuleContext(module));

  create_foo();
  create_main_caller_foo();
  create_main();

  LLVMVerifyModule(module, LLVMPrintMessageAction, NULL);
  LLVMDumpModule(module);
}
