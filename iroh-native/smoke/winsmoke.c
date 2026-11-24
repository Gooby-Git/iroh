/* Iroh W-1 Windows smoke: LoadLibrary a native DLL and GetProcAddress every JNI export listed in a text file (one
   decorated name per line); optionally call pure exports that ignore the JNIEnv. Written for Iroh.
   usage: winsmoke.exe <dll> <exports.txt> [name=expectedInt ...]   exit 0 = all found and all calls matched */
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
typedef int (__stdcall *IntFn0)(void *env, void *cls);
int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: winsmoke <dll> <exports.txt> [Java_..name=expected ...]\n"); return 2; }
    HMODULE m = LoadLibraryA(argv[1]);
    if (!m) { printf("FAIL LoadLibrary %s error %lu\n", argv[1], GetLastError()); return 1; }
    FILE *f = fopen(argv[2], "r");
    if (!f) { printf("FAIL cannot open %s\n", argv[2]); return 2; }
    char line[512]; int ok = 0, bad = 0;
    while (fgets(line, sizeof line, f)) {
        line[strcspn(line, "\r\n")] = 0;
        if (!line[0]) continue;
        if (GetProcAddress(m, line)) ok++; else { bad++; printf("MISSING %s\n", line); }
    }
    fclose(f);
    int callBad = 0;
    for (int i = 3; i < argc; i++) {
        char *eq = strchr(argv[i], '='); if (!eq) continue; *eq = 0;
        IntFn0 fn = (IntFn0)GetProcAddress(m, argv[i]);
        int want = atoi(eq + 1), got = fn ? fn(NULL, NULL) : -1;
        printf("call %s -> %d (expected %d)\n", argv[i], got, want);
        if (got != want) callBad++;
    }
    printf("%s: %d exports found, %d missing, %d call mismatches\n", argv[1], ok, bad, callBad);
    FreeLibrary(m);
    return (bad || callBad) ? 1 : 0;
}
