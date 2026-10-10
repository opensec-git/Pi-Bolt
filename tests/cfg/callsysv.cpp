// WTF::callSysV() (wtf/SysVCall.h) under /guard:cf (tests\cfg\run.ps1 builds and runs it):
//   callsysv_test valid      calls a sysv_abi function through it            -> prints 7, exit 0
//   callsysv_test invalid    calls 16 bytes into that function through it   -> fail-fast (0xC0000409), the call never runs
#include "config.h"
#include <cstdio>
#include <cstring>
#include <wtf/SysVCall.h>

extern "C" __declspec(noinline) int SYSV_ABI addOne(int x)
{
    return x + 1;
}

int main(int argc, char** argv)
{
    using Function = int(SYSV_ABI*)(int);
    Function f = addOne;
    if (argc > 1 && !strcmp(argv[1], "invalid"))
        f = reinterpret_cast<Function>(reinterpret_cast<uintptr_t>(f) + 16);
    printf("%d\n", callSysV(f, 6));
    return 0;
}


