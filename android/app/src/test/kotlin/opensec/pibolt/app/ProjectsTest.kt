package opensec.pibolt.app

import opensec.pibolt.app.data.ProjectMeta
import opensec.pibolt.app.data.ProjectsCodec
import opensec.pibolt.app.data.ProjectsFile
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.projects.FileEntry
import opensec.pibolt.app.ui.projects.ProjectEntry
import opensec.pibolt.app.ui.projects.ProjectFiles
import opensec.pibolt.app.ui.projects.ProjectRules
import opensec.pibolt.app.ui.providers.ProviderCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectsTest {
    @Test
    fun projectNames() {
        assertNull(ProjectRules.nameError("my-app", emptyList()))
        assertNull(ProjectRules.nameError("  spaced name  ", emptyList()))
        assertNotNull(ProjectRules.nameError("", emptyList()))
        assertNotNull(ProjectRules.nameError("   ", emptyList()))
        assertNotNull(ProjectRules.nameError(".", emptyList()))
        assertNotNull(ProjectRules.nameError("..", emptyList()))
        assertNotNull(ProjectRules.nameError(".hidden", emptyList()))
        assertNotNull(ProjectRules.nameError("a/b", emptyList()))
        assertNotNull(ProjectRules.nameError("a\\b", emptyList()))
        assertNotNull(ProjectRules.nameError("tab\there", emptyList()))
        assertNotNull(ProjectRules.nameError("x".repeat(ProjectRules.MAX_NAME + 1), emptyList()))
        // Case-insensitive: some filesystems would collide.
        assertNotNull(ProjectRules.nameError("My-App", listOf("my-app")))
    }

    @Test
    fun gitUrls() {
        for (ok in listOf(
            "https://github.com/user/repo.git",
            "https://github.com/user/repo",
            "http://git.local/repo",
            "ssh://git@github.com/user/repo.git",
            "git://example.org/repo.git",
            "git@github.com:user/repo.git",
        )) assertTrue(ProjectRules.isGitUrl(ok), ok)
        for (bad in listOf(
            "",
            "github.com/user/repo",
            "https://",
            "https://github.com",
            "git@github.com",
            "git@github.com:",
            "https://github.com/user/repo; rm -rf ~",
            "https://github.com/user/repo && echo",
            "https://github.com/\$(whoami)/repo",
            "https://github.com/user/re po",
            "ftp://example.org/repo",
        )) assertFalse(ProjectRules.isGitUrl(bad), bad)
    }

    @Test
    fun repoNames() {
        assertEquals("repo", ProjectRules.repoName("https://github.com/user/repo.git"))
        assertEquals("repo", ProjectRules.repoName("https://github.com/user/repo/"))
        assertEquals("pi-bolt", ProjectRules.repoName("git@github.com:opensec/pi-bolt.git"))
        assertEquals("tool", ProjectRules.repoName("git@host:tool.git"))
        assertEquals("", ProjectRules.repoName(""))
    }

    @Test
    fun clonePromptAsksForAnOverviewOfTheClone() {
        val p = ProjectRules.clonePrompt(" https://github.com/user/repo.git ")
        assertTrue("cloned https://github.com/user/repo.git into this folder" in p)
        assertTrue("git clone" !in p, "the app clones; Pi does not run it again")
    }

    private fun entry(name: String, pinned: Boolean = false, opened: Long = 0, session: Long? = null, title: String? = null) = ProjectEntry(
        name = name,
        path = "/p/$name",
        meta = ProjectMeta(title = title, pinned = pinned, lastOpenedAt = opened),
        lastSession = session?.let { SessionSummary("/s/$name", name, null, null, it, 1) },
        sessionCount = if (session != null) 1 else 0,
    )

    @Test
    fun orderPinnedThenRecentThenTitle() {
        val ordered = ProjectRules.order(
            listOf(
                entry("b"),
                entry("a"),
                entry("recent", session = 500),
                entry("older", opened = 100),
                entry("pinned-old", pinned = true, opened = 1),
                entry("pinned-new", pinned = true, session = 900),
            ),
        ).map { it.name }
        assertEquals(listOf("pinned-new", "pinned-old", "recent", "older", "a", "b"), ordered)
    }

    @Test
    fun sessionTitles() {
        val s = SessionSummary("/f", "id", null, "First line\nsecond", 0, 2)
        assertEquals("First line", ProjectRules.sessionTitle(s))
        assertEquals("Named", ProjectRules.sessionTitle(s.copy(name = "Named")))
        assertEquals("Untitled session", ProjectRules.sessionTitle(s.copy(preview = " ")))
    }

    @Test
    fun ago() {
        val now = 10_000_000_000L
        assertEquals("never", ProjectRules.ago(0, now))
        assertEquals("just now", ProjectRules.ago(now - 30_000, now))
        assertEquals("5 min ago", ProjectRules.ago(now - 5 * 60_000, now))
        assertEquals("3 h ago", ProjectRules.ago(now - 3 * 3_600_000, now))
        assertEquals("2 d ago", ProjectRules.ago(now - 2 * 86_400_000L, now))
        assertEquals("2 wk ago", ProjectRules.ago(now - 15 * 86_400_000L, now))
        assertEquals("just now", ProjectRules.ago(now + 5_000, now))
    }

    @Test
    fun projectsFileRoundTrips() {
        val f = ProjectsFile(
            projects = mapOf("a" to ProjectMeta(title = "Alpha", pinned = true, lastOpenedAt = 42), "b" to ProjectMeta()),
            providerPromptSeen = true,
        )
        assertEquals(f, ProjectsCodec.decode(ProjectsCodec.encode(f)))
        assertEquals(ProjectsFile(), ProjectsCodec.decode(null))
        assertEquals(ProjectsFile(), ProjectsCodec.decode("not json"))
        assertEquals(ProjectsFile(), ProjectsCodec.decode("[1,2]"))
        assertEquals(ProjectMeta(), ProjectsCodec.decode("""{"projects":{"x":{"title":"  "}}}""").projects["x"])
    }

    @Test
    fun providerSearchAndKeys() {
        assertEquals(ProviderCatalog.all, ProviderCatalog.search(" "))
        assertEquals(listOf("anthropic"), ProviderCatalog.search("anthro").map { it.id })
        assertTrue(ProviderCatalog.search("CLAUDE").any { it.id == "anthropic" })
        assertTrue(ProviderCatalog.all.map { it.id }.toSet().size == ProviderCatalog.all.size)
        assertEquals("Custom x", ProviderCatalog.name("custom-x"))

        assertEquals("sk-ant-123", ProviderCatalog.cleanKey("  sk-ant-123\n"))
        assertEquals("sk-ant-123", ProviderCatalog.cleanKey("\"sk-ant-123\""))
        assertEquals("sk-ant-123", ProviderCatalog.cleanKey("export ANTHROPIC_API_KEY=sk-ant-123"))
        assertEquals("sk-ant-123", ProviderCatalog.cleanKey("ANTHROPIC_API_KEY='sk-ant-123'"))
        assertEquals("abc=def", ProviderCatalog.cleanKey("abc=def"))
        assertNull(ProviderCatalog.cleanKey("   "))
        assertNull(ProviderCatalog.cleanKey("two words"))

        assertEquals("sk-a…6789", ProviderCatalog.mask("sk-ant-0123456789"))
        assertEquals("••••", ProviderCatalog.mask("abcd"))
    }

    @Test
    fun filesFoldersFirstThenByName() {
        val ordered = ProjectFiles.order(
            listOf(FileEntry("b.txt", false, 1), FileEntry("src", true, 0), FileEntry("A.md", false, 2), FileEntry(".git", true, 0), FileEntry("assets", true, 0)),
        ).map { it.name }
        assertEquals(listOf(".git", "assets", "src", "A.md", "b.txt"), ordered)
    }

    @Test
    fun fileSizes() {
        assertEquals("0 B", ProjectFiles.size(0))
        assertEquals("812 B", ProjectFiles.size(812))
        assertEquals("1 KB", ProjectFiles.size(1024))
        assertEquals("4.2 KB", ProjectFiles.size(4300))
        assertEquals("13 MB", ProjectFiles.size(13L * 1024 * 1024))
        assertEquals("1.5 GB", ProjectFiles.size(3L * 512 * 1024 * 1024))
    }

    @Test
    fun largeMessagePlaceholderIsAPlainFirstScreen() {
        val text = (1..3000).joinToString("\n") { "line $it with some words" }
        val doc = Markdown.placeholder(text)
        assertEquals(text, doc.text)
        val block = doc.blocks.single() as MdBlock.Paragraph
        assertTrue(block.text.length <= 4_000)
        assertTrue(block.text.startsWith("line 1 "))
    }
}
