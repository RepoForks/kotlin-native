#ifndef __DEBUG_INFO_C_H__
# define __DEBUG_INFO_C_H__
# ifdef __cplusplus
extern "C" {
# endif
  typedef struct DIBuilder        *DIBuilderRef;
  typedef struct DICompileUnit    *DICompileUnitRef;
  typedef struct DIFile           *DIFileRef;
  typedef struct DIBasicType      *DIBasicTypeRef;
  typedef struct DIType           *DITypeOpaqueRef;
  typedef struct DISubprogram     *DISubprogramRef;
  typedef struct DIModule         *DIModuleRef;
  typedef struct DIScope          *DIScopeOpaqueRef;
  typedef struct DISubroutineType *DISubroutineTypeRef;
  typedef struct DISubprogram     *DISubprogramRef;
  typedef struct DILocation       *DILocationRef;

  DIBuilderRef DICreateBuilder(LLVMModuleRef module);
  void DIFinalize(DIBuilderRef builder);

  DICompileUnitRef DICreateCompilationUnit(DIBuilderRef builder, unsigned int lang, const char *File, const char* dir, const char * producer, int isOptimized, const char * flags, unsigned int rv);

  DIFileRef DICreateFile(DIBuilderRef builder, const char *filename, const char *directory);

  DIBasicTypeRef DICreateBasicType(DIBuilderRef builder, const char* name, uint64_t sizeInBits, uint64_t alignment, unsigned encoding);

  DIModuleRef DICreateModule(DIBuilderRef builder, DIScopeOpaqueRef scope,
                             const char* name, const char* configurationMacro,
                             const char* includePath, const char *iSysRoot);
  
  DISubprogramRef DICreateFunction(DIBuilderRef builder, DIScopeOpaqueRef scope,
                                   const char* name, const char *linkageName,
                                   DIFileRef file, unsigned lineNo,
                                   DISubroutineTypeRef type, int isLocal,
                                   int isDefinition, unsigned scopeLine);
  
  DISubroutineTypeRef DICreateSubroutineType(DIBuilderRef builder,
                                             DITypeOpaqueRef* types,
                                             unsigned typesCount);

  void DIFunctionAddSubprogram(LLVMValueRef fn, DISubprogramRef sp);
  void LLVMBuilderSetDebugLocation(LLVMBuilderRef builder, unsigned line, unsigned col, DIScopeOpaqueRef scope);
# ifdef __cplusplus
}
# endif
#endif
