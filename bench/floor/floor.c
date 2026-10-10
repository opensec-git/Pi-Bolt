/* The process floor: the least a program costs to start, print and exit on Windows, measured by benchmark.py --floor next to
 * the builds. Process creation, the loader and Defender's check of a new process cost 10-25 ms and some 2,000 page faults there
 * even for this (under a millisecond on Linux): what is left of a build's figure over this one's is the build's own.
 *
 * It takes the arguments Pi gets and answers the way benchmark.py's scenarios wait for:
 *   --version                  one line
 *   -p ... PROMPT              the line that ends the scripted model's answer (harness.DONE)
 *   anything else              the TUI's stand-in: "fake-model" on the screen (time to interactive), then, for every line typed,
 *                              the end of the nth answer (harness.done(n)), until /quit or the end of input
 * Nothing else: no model is called, no file read.
 *
 * Built by run-suite.ps1 (or by hand) the way the real executable is linked:
 *   clang-cl /nologo /O2 /MT /guard:cf floor.c /Fefloor.exe /link /DYNAMICBASE /HIGHENTROPYVA /NXCOMPAT /guard:cf
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <string.h>

#define DONE "Done: read all four files"

static void say(const char *text) {
    DWORD n;
    WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), text, (DWORD)strlen(text), &n, NULL);
}

/* A line of input without its line end, in `line`; 0 at the end of input. */
static int read_line(HANDLE in, char *line, int size) {
    int len = 0;
    for (;;) {
        char c;
        DWORD n = 0;
        if (!ReadFile(in, &c, 1, &n, NULL) || n == 0)
            return len > 0 ? (line[len] = 0, 1) : 0;
        if (c == '\r' || c == '\n') {
            if (len == 0)
                continue; /* (the \n after a \r, or an empty line) */
            line[len] = 0;
            return 1;
        }
        if (len < size - 1)
            line[len++] = c;
    }
}

static void interactive(void) {
    static const char head[] = DONE " (prompt ";
    char line[4096], out[sizeof head + 16];
    HANDLE in = GetStdHandle(STD_INPUT_HANDLE);
    unsigned prompt = 0;
    say("floor (fake/fake-model)\r\n");
    while (read_line(in, line, sizeof line)) {
        if (strcmp(line, "/quit") == 0)
            return;
        /* One write per answer. (No wsprintf: that is user32.dll's, and loading it is a cost the floor must not have.) */
        char digits[12], *d = digits + sizeof digits;
        unsigned n = ++prompt;
        *--d = 0;
        do
            *--d = (char)('0' + n % 10);
        while (n /= 10);
        size_t len = sizeof head - 1, dlen = strlen(d);
        memcpy(out, head, len);
        memcpy(out + len, d, dlen);
        memcpy(out + len + dlen, ").\r\n", 5);
        say(out);
    }
}

int main(int argc, char **argv) {
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--version") == 0) {
            say("0.0.0 (process floor)\r\n");
            return 0;
        }
        if (strcmp(argv[i], "-p") == 0 || strcmp(argv[i], "--print") == 0) {
            say(DONE ".\r\n");
            return 0;
        }
    }
    interactive();
    return 0;
}
