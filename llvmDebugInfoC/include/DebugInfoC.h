#ifndef __DEBUG_INFO_C_H__
# define __DEBUG_INFO_C_H__
# ifdef __cplusplus
extern "C" {
  typedef class llvm::DIBuilder *DIBuilderRef;
  typedef class llvm::DICompileUnit *DICompileUnitRef;
#else
  typedef struct DIBuilder *DIBuilderRef;
  typedef struct DICompileUnit *DICompileUnitRef;

# endif


  DIBuilderRef DICreateBuilder(LLVMModuleRef module);
  void DIFinalize(DIBuilderRef builder);

  DICompileUnitRef DICreateCompilationUnit(DIBuilderRef builder, unsigned int lang, const char *File, const char* dir, const char * producer, int isOptimized, const char * flags, unsigned int rv);

# ifdef __cplusplus
}
# endif
#endif
