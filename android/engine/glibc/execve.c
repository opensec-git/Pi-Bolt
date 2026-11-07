/* execve for Pi-Bolt on Android: run what the kernel refuses to exec in the app's data directory.

   Problem: Android 10+ denies execve() of any file in an app's data directory to apps that target API 29+
   (SELinux: no `execute_no_trans` on app_data_file), but it still lets them map such files as code (`execute` is
   allowed, see system/sepolicy private/untrusted_app_all.te). Scripts installed by npm (node_modules/.bin/*,
   global bins) and pip (console scripts) live there, so `npm run build`, `npx tsc` or `black .` fail with EACCES.

   Solution: for a file whose real path is under APP_DATA, do in user space what the kernel's binfmt handlers do:
   - `#!interp [arg]`: exec the interpreter with the script as its argument, like binfmt_script (up to 4 levels);
   - an ELF: exec the glibc loader (in nativeLibraryDir, so exec is allowed), which maps the program itself:
     `ld.so --argv0 ARGV0 FILE ARGS...`;
   - anything else: ENOEXEC, so callers (bash, execvp) run it as a shell script as usual.
   Everything else goes straight to the kernel. `/bin/X`, `/usr/bin/X` and `/usr/local/bin/X` run PREFIX/bin/X when
   that exists (Android has no /usr/bin/env; `#!/usr/bin/env node` is everywhere), like Termux's termux-exec.

   The decision does not depend on the kernel's answer, so it behaves the same in the verify container (no SELinux)
   as on the phone. Runs in vfork/posix_spawn children too: no allocation, no locks, bounded stack, raw syscalls.
   @PREFIX@ and @APP_DATA@ are substituted by glibc/build-glibc.sh.  */

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <sysdep.h>

#define PIBOLT_PREFIX "@PREFIX@"
#define PIBOLT_APP_DATA "@APP_DATA@/"
#define PIBOLT_LOADER PIBOLT_PREFIX "/lib/ld-linux-aarch64.so.1"
#define MAX_DEPTH 4
#define HDR 256
#define REMAP (sizeof PIBOLT_PREFIX + sizeof "/bin/" + NAME_MAX)

static int
raw_execve (const char *path, char *const argv[], char *const envp[])
{
  return INLINE_SYSCALL_CALL (execve, path, argv, envp);
}

static int
starts_with (const char *s, const char *prefix)
{
  return strncmp (s, prefix, strlen (prefix)) == 0;
}

/* PREFIX/bin/NAME for /bin/NAME, /usr/bin/NAME, /usr/local/bin/NAME when it exists, else PATH.  */
static const char *
remap (const char *path, char *buf)
{
  static const char *const dirs[] = { "/bin/", "/usr/bin/", "/usr/local/bin/" };
  for (size_t i = 0; i < sizeof dirs / sizeof dirs[0]; i++)
    if (starts_with (path, dirs[i]))
      {
	const char *name = path + strlen (dirs[i]);
	size_t n = strlen (name);
	if (n == 0 || n > NAME_MAX || strchr (name, '/') != NULL)
	  return path;
	memcpy (buf, PIBOLT_PREFIX "/bin/", sizeof PIBOLT_PREFIX + 4);
	memcpy (buf + sizeof PIBOLT_PREFIX + 4, name, n + 1);
	if (INTERNAL_SYSCALL_CALL (faccessat, AT_FDCWD, buf, F_OK) == 0)
	  return buf;
	return path;
      }
  return path;
}

/* Reads the first bytes of PATH into HDR and tells whether its real path is in the app's data directory.
   Returns the byte count, or -1 when PATH is not ours to handle (not openable, not a regular file, not in APP_DATA,
   unreadable).  */
static ssize_t
probe (const char *path, char *hdr)
{
  /* O_NONBLOCK: a FIFO must not block here (the kernel refuses to exec it anyway).  */
  int fd = INTERNAL_SYSCALL_CALL (openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
  if (fd < 0)
    return -1;
  struct stat st;
  if (INTERNAL_SYSCALL_CALL (fstat, fd, &st) != 0 || !S_ISREG (st.st_mode))
    {
      INTERNAL_SYSCALL_CALL (close, fd);
      return -1;
    }
  char proc[32] = "/proc/self/fd/";
  char digits[12];
  int nd = 0;
  for (unsigned int v = fd; nd == 0 || v != 0; v /= 10)
    digits[nd++] = '0' + v % 10;
  size_t at = strlen (proc);
  while (nd > 0)
    proc[at++] = digits[--nd];
  proc[at] = '\0';
  char real[PATH_MAX];
  long int len = INTERNAL_SYSCALL_CALL (readlinkat, AT_FDCWD, proc, real, sizeof real - 1);
  ssize_t got = -1;
  if (len > 0)
    {
      real[len] = '\0';
      if (starts_with (real, PIBOLT_APP_DATA))
	{
	  long int r = INTERNAL_SYSCALL_CALL (read, fd, hdr, HDR - 1);
	  got = r < 0 ? -1 : r;
	}
    }
  INTERNAL_SYSCALL_CALL (close, fd);
  return got;
}

int
__execve (const char *path, char *const argv[], char *const envp[])
{
  if (path == NULL || argv == NULL || envp == NULL)
    return raw_execve (path, argv, envp);

  size_t argc = 0;
  while (argv[argc] != NULL)
    argc++;
  /* Each script level puts at most 2 words in front, the loader 3; plus the NULL.  */
  char *nargv[argc + 2 * MAX_DEPTH + 4];
  char hdrs[MAX_DEPTH + 1][HDR];
  char remapped[MAX_DEPTH + 1][REMAP];
  char *const *cur = argv;
  const char *file = path;

  for (int depth = 0; depth <= MAX_DEPTH; depth++)
    {
      const char *p = remap (file, remapped[depth]);
      char *hdr = hdrs[depth];
      ssize_t n = probe (p, hdr);
      if (n < 0)
	return raw_execve (p, cur, envp);
      if (INTERNAL_SYSCALL_CALL (faccessat, AT_FDCWD, p, X_OK) != 0)
	{
	  __set_errno (EACCES);
	  return -1;
	}
      hdr[n] = '\0';

      size_t curc = 0;
      while (cur[curc] != NULL)
	curc++;

      if (n >= 4 && memcmp (hdr, "\177ELF", 4) == 0)
	{
	  /* nargv may already hold CUR: build from the end backwards so nothing is overwritten.  */
	  size_t total = 4 + (curc > 0 ? curc - 1 : 0);
	  nargv[total] = NULL;
	  for (size_t i = curc; i > 1; i--)
	    nargv[3 + i - 1] = cur[i - 1];
	  nargv[3] = (char *) p;
	  nargv[2] = curc > 0 ? cur[0] : (char *) p;
	  nargv[1] = (char *) "--argv0";
	  nargv[0] = (char *) PIBOLT_LOADER;
	  return raw_execve (PIBOLT_LOADER, nargv, envp);
	}

      if (n < 2 || hdr[0] != '#' || hdr[1] != '!')
	{
	  __set_errno (ENOEXEC);
	  return -1;
	}
      /* binfmt_script: the first line; interpreter = first word, optional argument = the rest, trimmed.  */
      char *line = hdr + 2;
      char *nl = memchr (line, '\n', n - 2);
      if (nl == NULL)
	{
	  /* Longer than the header: the kernel (5.1+) also refuses a truncated interpreter.  */
	  __set_errno (ENOEXEC);
	  return -1;
	}
      *nl = '\0';
      while (*line == ' ' || *line == '\t')
	line++;
      char *end = nl;
      while (end > line && (end[-1] == ' ' || end[-1] == '\t' || end[-1] == '\r'))
	*--end = '\0';
      if (*line == '\0')
	{
	  __set_errno (ENOEXEC);
	  return -1;
	}
      char *interp = line;
      char *arg = NULL;
      char *sp = interp;
      while (*sp != '\0' && *sp != ' ' && *sp != '\t')
	sp++;
      if (*sp != '\0')
	{
	  *sp++ = '\0';
	  while (*sp == ' ' || *sp == '\t')
	    sp++;
	  if (*sp != '\0')
	    arg = sp;
	}
      if (depth == MAX_DEPTH)
	{
	  __set_errno (ELOOP);
	  return -1;
	}
      /* New argv: interp [arg] FILE cur[1..].  */
      size_t front = arg != NULL ? 3 : 2;
      size_t total = front + (curc > 0 ? curc - 1 : 0);
      nargv[total] = NULL;
      for (size_t i = curc; i > 1; i--)
	nargv[front + i - 2] = cur[i - 1];
      nargv[front - 1] = (char *) p;
      if (arg != NULL)
	nargv[1] = arg;
      nargv[0] = interp;
      cur = nargv;
      file = interp;
    }
  __set_errno (ELOOP);
  return -1;
}

weak_alias (__execve, execve)
