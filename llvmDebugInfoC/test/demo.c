#include <llvm-c/Core.h>
#include <DebugInfoC.h>
//clang llvmDebugInfoC/test/demo.c -IllvmDebugInfoC/include/ -Idependencies/all/clang+llvm-3.9.0-darwin-macos/include  -c

//c++ demo.o -Idependencies/all/clang+llvm-3.9.0-darwin-macos/include -Ldependencies/all/clang+llvm-3.9.0-darwin-macos/lib  -lLLVMCore -lLLVMSupport -lncurses -o demo
int main() {
  LLVMModuleRef module = LLVMModuleCreateWithName("test");
  DIBuilderRef builder = DICreateBuilder(module);
  DICreateCompilationUnit(builder, 4, "1.kt", "src", "konanc", 0, "", 0);
    LLVMDumpModule(module);

  return 0;
}
