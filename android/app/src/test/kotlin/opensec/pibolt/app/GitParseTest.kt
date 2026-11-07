package opensec.pibolt.app

import opensec.pibolt.app.git.Change
import opensec.pibolt.app.git.GitErrors
import opensec.pibolt.app.git.GitLogFormat
import opensec.pibolt.app.git.GitProgress
import opensec.pibolt.app.git.PorcelainV2
import opensec.pibolt.app.git.UnifiedDiffParser
import opensec.pibolt.app.git.github.GitHubRemote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitParseTest {
    private fun z(vararg records: String) = records.joinToString("\u0000", postfix = "\u0000")

    @Test
    fun porcelainV2HeadersAndEveryKindOfEntry() {
        val raw = z(
            "# branch.oid 1a2b3c4d5e6f7a8b9c0d1a2b3c4d5e6f7a8b9c0d",
            "# branch.head feature/login",
            "# branch.upstream origin/feature/login",
            "# branch.ab +3 -1",
            "1 M. N... 100644 100644 100644 aaaa bbbb src/main.kt",
            "1 .M N... 100644 100644 100644 aaaa aaaa README.md",
            "1 MM N... 100644 100644 100644 aaaa bbbb both sides.txt",
            "1 A. N... 000000 100644 100644 0000 cccc new file.txt",
            "1 .D N... 100644 100644 000000 aaaa aaaa gone.txt",
            "2 R. N... 100644 100644 100644 aaaa aaaa R100 docs/new name.md",
            "docs/old name.md",
            "u UU N... 100644 100644 100644 100644 aaaa bbbb cccc conflict.kt",
            "? untracked dir/notes.txt",
            "! build/out.bin",
        )
        val s = PorcelainV2.parse(raw)
        assertEquals("feature/login", s.branch.head)
        assertEquals("origin/feature/login", s.branch.upstream)
        assertEquals(3, s.branch.ahead)
        assertEquals(1, s.branch.behind)
        assertFalse(s.branch.detached)
        assertFalse(s.branch.initial)
        assertEquals(8, s.entries.size, "ignored files are not listed")

        val byPath = s.entries.associateBy { it.path }
        assertEquals(Change.Modified, byPath.getValue("src/main.kt").index)
        assertNull(byPath.getValue("src/main.kt").worktree)
        assertEquals(Change.Modified, byPath.getValue("README.md").worktree)
        assertTrue(byPath.getValue("both sides.txt").staged && byPath.getValue("both sides.txt").unstaged)
        assertEquals(Change.Added, byPath.getValue("new file.txt").index)
        assertEquals(Change.Deleted, byPath.getValue("gone.txt").worktree)
        val renamed = byPath.getValue("docs/new name.md")
        assertEquals(Change.Renamed, renamed.index)
        assertEquals("docs/old name.md", renamed.origPath)
        assertTrue(byPath.getValue("conflict.kt").conflicted)
        assertTrue(byPath.getValue("untracked dir/notes.txt").untracked)

        assertEquals(listOf("src/main.kt", "both sides.txt", "new file.txt", "docs/new name.md"), s.staged.map { it.path })
        assertEquals(listOf("README.md", "both sides.txt", "gone.txt", "untracked dir/notes.txt"), s.unstaged.map { it.path })
        assertEquals(listOf("conflict.kt"), s.conflicts.map { it.path })
    }

    @Test
    fun porcelainV2InitialDetachedAndOddPaths() {
        val initial = PorcelainV2.parse(z("# branch.oid (initial)", "# branch.head main", "? a"))
        assertTrue(initial.branch.initial)
        assertNull(initial.branch.upstream)
        assertEquals(0, initial.branch.ahead)

        val detached = PorcelainV2.parse(z("# branch.oid abc", "# branch.head (detached)"))
        assertTrue(detached.branch.detached)
        assertTrue(detached.clean)

        // -z keeps newlines and quotes in names as they are.
        val odd = PorcelainV2.parse(z("1 .M N... 100644 100644 100644 a a line\nbreak \"q\".txt"))
        assertEquals("line\nbreak \"q\".txt", odd.entries.single().path)
        assertTrue(PorcelainV2.parse("").clean)
    }

    @Test
    fun unifiedDiffSplitsFilesAndHunks() {
        val raw = """
            diff --git a/src/app.kt b/src/app.kt
            index 1111111..2222222 100644
            --- a/src/app.kt
            +++ b/src/app.kt
            @@ -1,3 +1,3 @@
             package app
            -val a = 1
            +val a = 2
             fun main() {}
            @@ -10,2 +10,3 @@ fun main() {}
             // end
            +// more
             // done
            diff --git a/new.txt b/new.txt
            new file mode 100644
            index 0000000..3333333
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1 @@
            +hello
            \ No newline at end of file
            diff --git a/old.md b/renamed.md
            similarity index 90%
            rename from old.md
            rename to renamed.md
            diff --git a/logo.png b/logo.png
            index 4444444..5555555 100644
            Binary files a/logo.png and b/logo.png differ
            diff --git "a/caf\303\251.txt" "b/caf\303\251.txt"
            deleted file mode 100644
            --- "a/caf\303\251.txt"
            +++ /dev/null
            @@ -1 +0,0 @@
            -bye
        """.trimIndent() + "\n"
        val files = UnifiedDiffParser.parse(raw)
        assertEquals(listOf("src/app.kt", "new.txt", "renamed.md", "logo.png", "café.txt"), files.map { it.path })

        val app = files[0]
        assertEquals(2, app.hunks.size)
        assertEquals(2, app.added)
        assertEquals(1, app.removed)
        assertEquals("@@ -10,2 +10,3 @@ fun main() {}", app.hunks[1].header)
        assertEquals(listOf(" // end", "+// more", " // done"), app.hunks[1].lines)

        // A one-hunk patch keeps the file header so `git apply` can place it.
        val patch = app.patchFor(1)
        assertTrue(patch.startsWith("diff --git a/src/app.kt b/src/app.kt\nindex 1111111..2222222 100644\n--- a/src/app.kt\n+++ b/src/app.kt\n@@ -10,2"))
        assertFalse("val a = 2" in patch)
        assertTrue(patch.endsWith(" // done\n"))

        assertTrue(files[1].newFile)
        assertEquals(listOf("+hello", "\\ No newline at end of file"), files[1].hunks.single().lines)
        assertEquals("old.md", files[2].oldPath)
        assertTrue(files[2].hunks.isEmpty())
        assertTrue(files[3].binary)
        assertTrue(files[4].deleted)
        assertEquals(1, files[4].removed)
        assertTrue(UnifiedDiffParser.parse("").isEmpty())
    }

    @Test
    fun logRecordsWithBodiesRefsAndMerges() {
        val us = '\u001f'
        val rs = '\u001e'
        val raw = "aaaa1111${us}aaaa111${us}bbbb2222 cccc3333${us}Ada Lovelace${us}ada@example.com${us}1700000000${us}HEAD -> main, origin/main${us}Merge branch 'x'${us}Body line 1\n\nBody line 2\n$rs\n" +
            "bbbb2222${us}bbbb222${us}${us}Linus${us}l@example.com${us}1600000000${us}${us}Initial commit${us}$rs\n"
        val log = GitLogFormat.parseLog(raw)
        assertEquals(2, log.size)
        assertTrue(log[0].merge)
        assertEquals(listOf("HEAD -> main", "origin/main"), log[0].refs)
        assertEquals("Body line 1\n\nBody line 2", log[0].body)
        assertEquals(1_700_000_000, log[0].time)
        assertEquals(emptyList(), log[1].parents)
        assertEquals("", log[1].body)
        assertEquals("Initial commit", log[1].subject)
    }

    @Test
    fun branchesSkipRemoteHeadAndKeepTracking() {
        val us = '\u001f'
        val rs = '\u001e'
        val raw = "refs/heads/main$us*${us}origin/main${us}ahead 2$us${"1700000000"}${us}Fix it$rs\n" +
            "refs/heads/topic$us ${us}${us}${us}1690000000${us}WIP$rs\n" +
            "refs/remotes/origin/HEAD$us ${us}${us}${us}1700000000${us}Fix it$rs\n" +
            "refs/remotes/origin/feature/x$us ${us}${us}${us}1680000000${us}Feature$rs\n"
        val b = GitLogFormat.parseBranches(raw)
        assertEquals(listOf("main", "topic", "origin/feature/x"), b.map { it.name })
        assertTrue(b[0].current)
        assertEquals("ahead 2", b[0].track)
        assertEquals("origin/main", b[0].upstream)
        assertNull(b[1].upstream)
        assertTrue(b[2].remote)
        assertEquals("feature/x", b[2].localName)
    }

    @Test
    fun branchNameRules() {
        assertNull(GitLogFormat.branchNameError("feature/login-2"))
        for (bad in listOf("", "has space", "a..b", "-x", "x/", "x.lock", "a~b", "a:b", ".hidden", "HEAD", "a//b", "x@{1}")) {
            assertTrue(GitLogFormat.branchNameError(bad) != null, bad)
        }
        assertEquals("A branch with this name exists", GitLogFormat.branchNameError("main", listOf("main")))
    }

    @Test
    fun progressAndErrors() {
        assertEquals(GitProgress("Receiving objects", 45), GitProgress.parse("Receiving objects:  45% (9/20), 1.2 MiB | 3 MiB/s"))
        assertEquals(GitProgress("Compressing objects", 100), GitProgress.parse("remote: Compressing objects: 100% (3/3), done."))
        assertEquals(GitProgress("Enumerating objects", null), GitProgress.parse("remote: Enumerating objects: 5, done."))
        assertNull(GitProgress.parse("fatal: unable to access"))

        assertTrue("Pull first" in GitErrors.explain(" ! [rejected]        main -> main (fetch first)\nerror: failed to push some refs\nhint: Updates were rejected"))
        assertTrue("Sign in" in GitErrors.explain("fatal: could not read Username for 'https://github.com': terminal prompts disabled"))
        assertTrue("network" in GitErrors.explain("fatal: unable to access 'https://github.com/a/b/': Could not resolve host: github.com"))
        assertTrue("name and email" in GitErrors.explain("Author identity unknown\n*** Please tell me who you are."))
        assertEquals("Pathspec 'x' did not match any files", GitErrors.explain("error: pathspec 'x' did not match any files"))
    }

    @Test
    fun githubRemotes() {
        assertEquals("opensec-git" to "Pi-Bolt", GitHubRemote.parse("https://github.com/opensec-git/Pi-Bolt.git"))
        assertEquals("o" to "r", GitHubRemote.parse("https://user@github.com/o/r"))
        assertEquals("o" to "r.js", GitHubRemote.parse("git@github.com:o/r.js.git"))
        assertEquals("o" to "r", GitHubRemote.parse("ssh://git@github.com/o/r.git"))
        assertNull(GitHubRemote.parse("https://gitlab.com/o/r.git"))
        assertNull(GitHubRemote.parse("https://github.com.evil.example/o/r.git"))
    }
}
