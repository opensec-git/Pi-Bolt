package opensec.pibolt.app

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import opensec.pibolt.app.ui.packages.AddedResource
import opensec.pibolt.app.ui.packages.CatalogEntry
import opensec.pibolt.app.ui.packages.CatalogException
import opensec.pibolt.app.ui.packages.CatalogJson
import opensec.pibolt.app.ui.packages.CompatNote
import opensec.pibolt.app.ui.packages.InstallStatus
import opensec.pibolt.app.ui.packages.PackageCatalog
import opensec.pibolt.app.ui.packages.PackageCompat
import opensec.pibolt.app.ui.packages.PackageFormat
import opensec.pibolt.app.ui.packages.PackageLinks
import opensec.pibolt.app.ui.packages.PackageManifest
import opensec.pibolt.app.ui.packages.SourceStatus
import opensec.pibolt.app.ui.packages.loadDetail
import opensec.pibolt.app.ui.projects.transfer.ShareAction
import opensec.pibolt.app.ui.projects.transfer.ShareRouting
import opensec.pibolt.app.ui.projects.transfer.SharedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageBrowseTest {
    // --- Links ---

    @Test
    fun installLinkProposesNpmAndKnownGitSources() {
        assertEquals("npm:pi-foo", PackageLinks.fromUri("pibolt://install?source=npm:pi-foo"))
        assertEquals("npm:@scope/pi-foo@1.2.3", PackageLinks.fromUri("pibolt://install?source=npm%3A%40scope%2Fpi-foo%401.2.3"))
        assertEquals("npm:pi-foo", PackageLinks.fromUri("pibolt://install?source=pi-foo"))
        assertEquals("git:github.com/o/r@v1", PackageLinks.fromUri("pibolt://install?source=git:github.com/o/r@v1"))
        assertEquals("git:github.com/o/r", PackageLinks.fromUri("pibolt://install?other=1&source=https://github.com/o/r"))
    }

    @Test
    fun installLinkRefusesPathsHostsAndJunk() {
        for (bad in listOf(
            "pibolt://install",
            "pibolt://install?source=",
            "pibolt://install?source=/data/local/tmp/x",
            "pibolt://install?source=./x",
            "pibolt://install?source=../x",
            "pibolt://install?source=git:evil.example/o/r",
            "pibolt://install?source=ssh://git@github.com/o/r",
            "pibolt://install?source=http://github.com/o/r",
            "pibolt://install?source=npm:Bad%20Name",
            "pibolt://install?source=npm:foo%20--global",
            "pibolt://install?source=git:github.com/o/r@..%2F..",
            "pibolt://pair#abc",
            "https://evil.example/packages/foo",
            "http://pi.dev/packages/foo",
            "not a link",
        )) assertNull(bad, PackageLinks.fromUri(bad))
    }

    @Test
    fun webPagesMapToSources() {
        assertEquals("npm:pi-subagents", PackageLinks.fromUri("https://pi.dev/packages/pi-subagents"))
        assertEquals("npm:@juicesharp/rpiv-todo", PackageLinks.fromUri("https://pi.dev/packages/@juicesharp/rpiv-todo"))
        assertEquals("npm:@juicesharp/rpiv-todo", PackageLinks.fromUri("https://pi.dev/packages/%40juicesharp/rpiv-todo"))
        assertNull(PackageLinks.fromUri("https://pi.dev/packages"))
        assertNull(PackageLinks.fromUri("https://pi.dev/docs/latest"))
        assertEquals("npm:pi-foo", PackageLinks.fromUri("https://www.npmjs.com/package/pi-foo"))
        assertEquals("npm:@s/pi-foo@2.0.1", PackageLinks.fromUri("https://www.npmjs.com/package/@s/pi-foo/v/2.0.1"))
        assertNull(PackageLinks.fromUri("https://www.npmjs.com/package/pi-foo/access"))
        assertEquals("git:github.com/o/r", PackageLinks.fromUri("https://github.com/o/r"))
        assertEquals("git:github.com/o/r", PackageLinks.fromUri("https://github.com/o/r.git"))
        assertEquals("git:github.com/o/r@v1.2", PackageLinks.fromUri("https://github.com/o/r/tree/v1.2"))
        assertEquals("git:gitlab.com/o/r@main", PackageLinks.fromUri("https://gitlab.com/o/r/-/tree/main"))
        // A page inside a repository (a monorepo's package, a file) is not an installable source.
        assertNull(PackageLinks.fromUri("https://github.com/o/r/tree/main/packages/a"))
        assertNull(PackageLinks.fromUri("https://github.com/o/r/blob/main/README.md"))
        assertNull(PackageLinks.fromUri("https://github.com/o"))
    }

    @Test
    fun sharedTextFindsTheFirstPackageLinkOrInstallCommand() {
        assertEquals("npm:pi-foo", PackageLinks.fromSharedText("Look at this: https://pi.dev/packages/pi-foo."))
        assertEquals("npm:pi-foo", PackageLinks.fromSharedText("https://example.com/x then https://www.npmjs.com/package/pi-foo"))
        assertEquals("npm:@s/x", PackageLinks.fromSharedText("pi install npm:@s/x"))
        assertEquals("git:github.com/o/r", PackageLinks.fromSharedText("run `pi install -l git:github.com/o/r`".replace("`", " ")))
        assertNull(PackageLinks.fromSharedText("pi install ./local"))
        assertNull(PackageLinks.fromSharedText("just some words"))
        assertNull(PackageLinks.fromSharedText(null))
    }

    @Test
    fun sourceParts() {
        assertEquals("@s/x" to "1.0.0", PackageLinks.npmParts("npm:@s/x@1.0.0"))
        assertEquals("x" to null, PackageLinks.npmParts("npm:x"))
        assertNull(PackageLinks.npmParts("git:github.com/o/r"))
        assertEquals(Triple("o", "r", "v1"), PackageLinks.githubParts("git:github.com/o/r@v1"))
        assertNull(PackageLinks.githubParts("git:gitlab.com/o/r"))
        assertEquals(setOf("x", "@s/y"), PackageFormat.installedNames(listOf("npm:x@1.2", "npm:@s/y", "git:github.com/o/r", "/abs")))
    }

    @Test
    fun sharedPackageLinkDefaultsToInstall() {
        val link = ShareRouting.route(SharedContent(emptyList(), "https://pi.dev/packages/pi-foo"), maxImages = 4)
        assertEquals(listOf(ShareAction.InstallPackage, ShareAction.InsertText), link.actions)
        assertFalse(link.needsProject(ShareAction.InstallPackage))
        assertEquals("npm:pi-foo", ShareRouting.packageSource(SharedContent(emptyList(), "https://pi.dev/packages/pi-foo")))
        val text = ShareRouting.route(SharedContent(emptyList(), "hello"), maxImages = 4)
        assertEquals(listOf(ShareAction.InsertText), text.actions)
    }

    // --- Parsing ---

    private val search = """
        {"objects":[
          {"downloads":{"monthly":97075,"weekly":19176},"package":{"name":"@juicesharp/rpiv-todo","version":"2.15.0",
            "description":" A todo list. ","keywords":["pi-package","todo"],
            "links":{"repository":"git+https://github.com/juicesharp/rpiv-mono.git","npm":"https://www.npmjs.com/package/@juicesharp/rpiv-todo"}}},
          {"package":{"name":"bare","version":"0.1.0"}},
          {"package":{"version":"1"}}
        ],"total":13435}
    """.trimIndent()

    @Test
    fun parsesSearch() {
        val page = CatalogJson.parseSearch(search)
        assertEquals(13435, page.total)
        assertEquals(2, page.entries.size)
        val e = page.entries[0]
        assertEquals("@juicesharp/rpiv-todo", e.name)
        assertEquals("A todo list.", e.description)
        assertEquals(19176L, e.weeklyDownloads)
        assertEquals("https://github.com/juicesharp/rpiv-mono", e.repository)
        assertNull(page.entries[1].weeklyDownloads)
        assertEquals("v2.15.0 · 19k weekly · A todo list.", PackageFormat.meta(e))
        assertThrows(CatalogException::class.java) { CatalogJson.parseSearch("<html>") }
    }

    @Test
    fun formatsCounts() {
        assertEquals("950", PackageFormat.count(950))
        assertEquals("1k", PackageFormat.count(1_000))
        assertEquals("1.2k", PackageFormat.count(1_234))
        assertEquals("19k", PackageFormat.count(19_176))
        assertEquals("1M", PackageFormat.count(1_000_000))
        assertEquals("3.1M", PackageFormat.count(3_108_830))
    }

    @Test
    fun repositoryUrls() {
        fun r(s: String) = CatalogJson.repositoryUrl(kotlinx.serialization.json.JsonPrimitive(s))
        assertEquals("https://github.com/o/r", r("git+https://github.com/o/r.git"))
        assertEquals("https://github.com/o/r", r("git://github.com/o/r.git"))
        assertEquals("https://github.com/o/r", r("git+ssh://git@github.com/o/r.git"))
        assertEquals("https://github.com/o/r", r("git@github.com:o/r.git"))
        assertEquals("https://github.com/o/r", r("github:o/r"))
        assertEquals("https://gitlab.com/o/r", r("gitlab:o/r"))
        assertEquals("https://github.com/o/r", r("o/r"))
        assertNull(r("file:../x"))
        assertNull(r("javascript:alert(1)"))
    }

    private fun manifest(json: String): PackageManifest = CatalogJson.parseManifest(json)

    @Test
    fun parsesManifest() {
        val m = manifest(
            """{"name":"pi-x","version":"1.0.0","description":"X","license":"MIT","repository":{"type":"git","url":"git+https://github.com/o/x.git"},
               "keywords":["pi-package"],"pi":{"extensions":["./index.ts"],"skills":"./skills","image":"https://x/y.png"},
               "os":["darwin"],"cpu":["!arm64"],"scripts":{"test":"vitest","postinstall":"node setup.js"},"gypfile":true,
               "dependencies":{"a":"^1"},"optionalDependencies":{"@x/x-linux-arm64-gnu":"1.0.0"},"dist":{"unpackedSize":1234}}""",
        )
        assertEquals("pi-x", m.name)
        assertEquals("MIT", m.license)
        assertEquals("https://github.com/o/x", m.repository)
        assertEquals(listOf("./index.ts"), m.pi?.extensions)
        assertEquals(listOf("./skills"), m.pi?.skills)
        assertEquals(listOf("darwin"), m.os)
        assertEquals("node setup.js", m.scripts["postinstall"])
        assertTrue(m.gypfile)
        assertEquals(1234L, m.unpackedSize)
        assertNull(manifest("""{"name":"y"}""").pi)
    }

    @Test
    fun resourcesFromTheManifestAndFiles() {
        val m = manifest("""{"name":"p","pi":{"extensions":["./src/index.ts","./more"],"skills":["./res/skills"],"prompts":["./res/prompts/*.md"],"themes":["./t/dark.json","!./t/old.json"]}}""")
        val files = listOf(
            "package.json", "src/index.ts", "src/util.ts", "more/a.ts", "more/b/index.js", "more/b/helper.js", "more/types.d.ts",
            "res/skills/review/SKILL.md", "res/skills/review/notes.md", "res/skills/deep/nested/SKILL.md",
            "res/prompts/fix.md", "res/prompts/readme.txt", "res/prompts/sub/x.md", "t/dark.json", "t/old.json",
        )
        assertEquals(
            listOf(
                AddedResource("extension", "src"), AddedResource("extension", "a.ts"), AddedResource("extension", "b"),
                AddedResource("skill", "review"), AddedResource("skill", "nested"),
                AddedResource("prompt", "fix"),
                AddedResource("theme", "dark"),
            ),
            CatalogJson.resources(m, files),
        )
        // Without the file list, the declared paths.
        assertEquals(
            listOf(AddedResource("extension", "src/index.ts"), AddedResource("extension", "more"), AddedResource("skill", "res/skills"), AddedResource("prompt", "res/prompts/*.md"), AddedResource("theme", "t/dark.json")),
            CatalogJson.resources(m, null),
        )
    }

    @Test
    fun manifestOverridesApplyLikePi() {
        val m = manifest(
            """{"name":"p","pi":{"prompts":["./prompts","!./prompts/internal.md","!draft-*.md","+prompts/draft-keep.md"],
               "skills":["./skills","!old","-skills/gone"],"extensions":["./ext","-ext/b.ts"]}}""",
        )
        val files = listOf(
            "prompts/fix.md", "prompts/internal.md", "prompts/draft-a.md", "prompts/draft-keep.md",
            "skills/review/SKILL.md", "skills/old/SKILL.md", "skills/gone/SKILL.md",
            "ext/a.ts", "ext/b.ts",
        )
        assertEquals(
            listOf(AddedResource("extension", "a.ts"), AddedResource("skill", "review"), AddedResource("prompt", "fix"), AddedResource("prompt", "draft-keep")),
            CatalogJson.resources(m, files),
        )
        // Without the file list, only the source entries are listed.
        assertEquals(
            listOf(AddedResource("extension", "ext"), AddedResource("skill", "skills"), AddedResource("prompt", "prompts")),
            CatalogJson.resources(m, null),
        )
    }

    @Test
    fun discoveryFollowsPiDirectoryRules() {
        val m = manifest("""{"name":"p","pi":{"skills":["./skills/*","./more"],"prompts":["./prompts"],"themes":["./themes"],"extensions":["./ext","./single"]}}""")
        val files = listOf(
            // A glob matching directories discovers inside them.
            "skills/review/SKILL.md", "skills/review/deeper/SKILL.md", "skills/plain/notes.md",
            // A declared skills directory: top-level .md files are skills, SKILL.md folders at any depth.
            "more/quick.md", "more/a/b/SKILL.md", "more/.hidden/SKILL.md", "more/node_modules/x/SKILL.md",
            // Prompts and themes are recursive.
            "prompts/top.md", "prompts/review/security.md", "prompts/.drafts/x.md", "themes/dark/custom.json",
            // An extension directory with subfolders; a directory with its own index is one extension.
            "ext/a.js", "ext/tool/index.js", "ext/tool/index.ts", "ext/lib/util.ts", "single/index.ts", "single/helper.ts",
        )
        assertEquals(
            listOf(
                AddedResource("extension", "a.js"), AddedResource("extension", "tool"), AddedResource("extension", "single"),
                // skills/plain is a matched directory, so its top-level notes.md is a skill there, as in Pi.
                AddedResource("skill", "review"), AddedResource("skill", "notes"), AddedResource("skill", "quick"), AddedResource("skill", "b"),
                AddedResource("prompt", "top"), AddedResource("prompt", "security"),
                AddedResource("theme", "custom"),
            ),
            CatalogJson.resources(m, files),
        )
    }

    @Test
    fun globsTakeBracesAndClassesAndEmptyFieldsLoadNone() {
        assertEquals(listOf("src/*.ts", "src/*.js"), CatalogJson.expandBraces("src/*.{ts,js}"))
        assertEquals(listOf("a/x1", "a/x2", "a/y"), CatalogJson.expandBraces("a/{x{1,2},y}"))
        assertNull(CatalogJson.expandBraces("a/{x,y"))
        assertNull(CatalogJson.expandBraces("{a,b}{c,d}{e,f}{g,h}{i,j}{k,l}"))
        assertTrue(CatalogJson.globMatches("prompts/*[0-9].md", "prompts/fix2.md"))
        assertFalse(CatalogJson.globMatches("prompts/*[0-9].md", "prompts/fix.md"))
        assertTrue(CatalogJson.globMatches("p/[!a]*.md", "p/b.md"))
        assertFalse(CatalogJson.globMatches("p/[!a]*.md", "p/a.md"))
        val m = manifest("""{"name":"p","pi":{"extensions":["./src/*.{ts,js}"],"prompts":["./prompts/*[0-9].md"],"skills":[]}}""")
        val files = listOf("src/a.ts", "src/b.js", "src/c.css", "prompts/p1.md", "prompts/p.md", "skills/s/SKILL.md", "themes/t.json")
        // skills: [] and an absent themes field load none, though the conventional directories exist.
        assertEquals(
            listOf(AddedResource("extension", "a.ts"), AddedResource("extension", "b.js"), AddedResource("prompt", "p1")),
            CatalogJson.resources(m, files),
        )
    }

    @Test
    fun globsMatchBySegment() {
        assertTrue(CatalogJson.globMatches("res/prompts/*.md", "res/prompts/fix.md"))
        assertFalse(CatalogJson.globMatches("res/prompts/*.md", "res/prompts/sub/fix.md"))
        assertTrue(CatalogJson.globMatches("res/**/*.md", "res/prompts/sub/fix.md"))
        assertTrue(CatalogJson.globMatches("res/**/*.md", "res/fix.md"))
        assertTrue(CatalogJson.globMatches("t/?ark.json", "t/dark.json"))
        assertFalse(CatalogJson.globMatches("t/*.json", "t/dark.jsonx"))
        assertTrue(CatalogJson.globMatches("**", "a/b/c"))
    }

    @Test(timeout = 5_000)
    fun hostileGlobsStayFast() {
        // An author-controlled manifest and file list: nested ** and many stars against long non-matching paths.
        val pattern = (1..30).joinToString("/") { "**" } + "/" + "*a".repeat(60) + "b"
        val path = (1..60).joinToString("/") { "a".repeat(15) }
        assertFalse(CatalogJson.globMatches(pattern, path))
        val m = manifest("""{"name":"p","pi":{"prompts":["${"**/".repeat(40)}${"*a".repeat(100)}.md"]}}""")
        val files = List(2_000) { i -> (1..20).joinToString("/") { "a".repeat(20) } + "/x$i.md" }
        assertEquals(emptyList<AddedResource>(), CatalogJson.resources(m, files))
    }

    @Test(timeout = 10_000)
    fun aHugeFileTreeFallsBackToTheDeclaredPaths() {
        // 10k files under distinct 61-level chains would be ~600k directories: the index stops and lists the manifest.
        val m = manifest("""{"name":"p","pi":{"themes":["./themes"]}}""")
        val files = List(10_000) { i -> (1..60).joinToString("/") { "d$i" } + "/f.json" }
        assertEquals(listOf(AddedResource("theme", "themes")), CatalogJson.resources(m, files))
    }

    @Test
    fun authorStringsAndArraysAreCapped() {
        val many = (1..100_000).joinToString(",") { "\"./e$it.ts\"" }
        val m = manifest("""{"name":"p","description":"${"x".repeat(100_000)}","license":"${"L".repeat(5_000)}","keywords":[$many],"pi":{"extensions":[$many]}}""")
        assertEquals(500, m.description!!.length)
        assertEquals(300, m.license!!.length)
        assertEquals(64, m.keywords.size)
        assertEquals(64, m.pi!!.extensions!!.size)
        assertEquals(32, CatalogJson.resources(m, null).size)
    }

    @Test
    fun resourcesFromConventionalDirectories() {
        val m = manifest("""{"name":"p"}""")
        val files = listOf("extensions/a.ts", "extensions/b/index.ts", "extensions/b/lib.ts", "skills/s1/SKILL.md", "prompts/p.md", "themes/t.json", "lib/x.ts")
        assertEquals(
            listOf(AddedResource("extension", "a.ts"), AddedResource("extension", "b"), AddedResource("skill", "s1"), AddedResource("prompt", "p"), AddedResource("theme", "t")),
            CatalogJson.resources(m, files),
        )
        assertEquals(emptyList<AddedResource>(), CatalogJson.resources(m, null))
    }

    // --- Compatibility ---

    private fun levels(notes: List<CompatNote>) = notes.map { it.level to it.title }

    @Test
    fun plainPackageHasNoNotes() {
        val m = manifest("""{"name":"p","scripts":{"test":"vitest","build":"tsc"},"dependencies":{"chalk":"^5"}}""")
        assertEquals(emptyList<CompatNote>(), PackageCompat.check(m, listOf("index.ts", "package.json")))
    }

    @Test
    fun platformFieldsBlock() {
        val notes = PackageCompat.check(manifest("""{"name":"p","os":["darwin","win32"],"cpu":["x64"],"libc":["musl"]}"""), null)
        assertEquals(
            listOf(CompatNote.Level.Blocks to "Not for Linux", CompatNote.Level.Blocks to "Not for arm64", CompatNote.Level.Blocks to "Not for glibc"),
            levels(notes),
        )
        assertTrue(PackageCompat.check(manifest("""{"name":"p","os":["!linux"]}"""), null).any { it.title == "Not for Linux" })
        assertEquals(emptyList<CompatNote>(), PackageCompat.check(manifest("""{"name":"p","os":["linux","darwin"],"cpu":["arm64","x64"],"libc":["glibc"]}"""), null))
        assertEquals(emptyList<CompatNote>(), PackageCompat.check(manifest("""{"name":"p","os":["!win32"],"cpu":["!x64"]}"""), null))
        assertEquals(emptyList<CompatNote>(), PackageCompat.check(manifest("""{"name":"p","os":["any"],"cpu":["any"],"libc":["any"]}"""), null))
    }

    @Test
    fun nativeAddonsWarn() {
        // binding.gyp, no prebuilds: builds from source, and there is no compiler.
        val gyp = PackageCompat.check(manifest("""{"name":"p","scripts":{"install":"node-gyp rebuild"}}"""), listOf("binding.gyp", "src/a.cc"))
        assertEquals(listOf(CompatNote.Level.Warns to "Builds native code", CompatNote.Level.Notes to "Runs install scripts"), levels(gyp))
        assertTrue(gyp.last().detail.contains("install: node-gyp rebuild"))

        // Prebuilds for other platforms only; Android (Termux) and musl do not count.
        val other = PackageCompat.check(manifest("""{"name":"p"}"""), listOf("prebuilds/darwin-arm64/p.node", "prebuilds/android-arm64/p.node", "prebuilds/linux-arm64/p.musl.node", "binding.gyp"))
        assertEquals(listOf(CompatNote.Level.Warns to "No prebuilt binary for this phone", CompatNote.Level.Warns to "Builds native code"), levels(other))
        assertTrue(other[0].detail.contains("android-arm64, darwin-arm64, linux-arm64"))

        // A linux-arm64 glibc prebuild fits.
        assertEquals(emptyList<CompatNote>(), PackageCompat.check(manifest("""{"name":"p","gypfile":true}"""), listOf("prebuilds/linux-arm64/p.glibc.node", "prebuilds/darwin-x64+arm64/p.node", "binding.gyp")))

        // Loose .node binaries.
        assertEquals(listOf(CompatNote.Level.Warns to "Contains native binaries"), levels(PackageCompat.check(manifest("""{"name":"p"}"""), listOf("build/Release/x.node"))))
    }

    @Test
    fun platformPackagesAndNativeDependencies() {
        val missing = PackageCompat.check(manifest("""{"name":"p","optionalDependencies":{"@p/p-darwin-arm64":"1","@p/p-win32-x64-msvc":"1","@p/p-linux-arm64-musl":"1","@p/p-android-arm64":"1"}}"""), null)
        assertEquals(listOf(CompatNote.Level.Warns to "No native build for this phone"), levels(missing))
        assertEquals(emptyList<CompatNote>(), PackageCompat.check(manifest("""{"name":"p","optionalDependencies":{"@p/p-darwin-arm64":"1","@p/p-linux-arm64-gnu":"1"}}"""), null))
        assertEquals(emptyList<CompatNote>(), PackageCompat.check(manifest("""{"name":"p","optionalDependencies":{"@esbuild/linux-arm64":"1","@esbuild/darwin-x64":"1"}}"""), null))
        assertTrue(PackageCompat.fitsEngine("@img/sharp-linux-arm64"))
        assertFalse(PackageCompat.fitsEngine("@img/sharp-linuxmusl-arm64"))
        assertFalse(PackageCompat.fitsEngine("x-android-arm64"))

        val deps = PackageCompat.check(manifest("""{"name":"p","dependencies":{"better-sqlite3":"^12","node-pty":"^1","chalk":"^5"}}"""), null)
        assertEquals(listOf(CompatNote.Level.Warns to "Native dependencies"), levels(deps))
        assertTrue(deps[0].detail.startsWith("better-sqlite3, node-pty use native code"))
        assertEquals(listOf(CompatNote.Level.Warns to "Native addon"), levels(PackageCompat.check(manifest("""{"name":"p","dependencies":{"node-gyp-build":"^4"}}"""), null)))
    }

    // --- Catalog over HTTP ---

    @Test
    fun catalogReadsTheRegistryWithPlainGets() = runBlocking {
        val server = MockWebServer()
        val seen = java.util.Collections.synchronizedList(ArrayList<RecordedRequest>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.target
                fun ok(body: String) = MockResponse.Builder().body(body).build()
                return when {
                    path.startsWith("/-/v1/search") -> ok(search)
                    path == "/@juicesharp%2Frpiv-todo/latest" -> ok("""{"name":"@juicesharp/rpiv-todo","version":"2.15.0","pi":{"extensions":["./index.ts"]},"scripts":{"postinstall":"node x.js"}}""")
                    path == "/v1/packages/npm/@juicesharp/rpiv-todo@2.15.0?structure=flat" -> ok("""{"files":[{"name":"/index.ts"},{"name":"/package.json"}]}""")
                    path == "/downloads/point/last-week/@juicesharp/rpiv-todo" -> ok("""{"downloads":42}""")
                    path == "/o/r/HEAD/package.json" -> ok("""{"name":"r","os":["darwin"]}""")
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val catalog = PackageCatalog(OkHttpClient(), registry = base, downloadsApi = base, filesApi = base, githubRaw = base)
            val page = catalog.search("todo list")
            assertEquals(2, page.entries.size)
            val searchUrl = seen.first().url
            assertEquals("keywords:pi-package todo list", searchUrl.queryParameter("text"))
            assertEquals("0", searchUrl.queryParameter("from"))

            val detail = loadDetail(catalog, "npm:@juicesharp/rpiv-todo", known = null)
            assertNull(detail.error)
            assertEquals(42L, detail.weeklyDownloads)
            assertEquals(listOf(AddedResource("extension", "index.ts")), detail.resources)
            assertEquals(listOf(CompatNote.Level.Notes to "Runs install scripts"), levels(detail.notes))

            // A known search entry's downloads are not fetched again.
            val known = CatalogEntry("@juicesharp/rpiv-todo", "2.15.0", null, 7, null)
            assertEquals(7L, loadDetail(catalog, "npm:@juicesharp/rpiv-todo", known).weeklyDownloads)

            val gh = loadDetail(catalog, "git:github.com/o/r", null)
            assertTrue(gh.blocked)

            assertEquals("pi-missing is not on npm.", loadDetail(catalog, "npm:pi-missing", null).error)
            assertEquals("Details are not available for this source.", loadDetail(catalog, "git:gitlab.com/o/r", null).error)

            // Nothing but GETs, no cookies or credentials.
            assertTrue(seen.all { it.method == "GET" && it.headers["Cookie"] == null && it.headers["Authorization"] == null })
        } finally {
            server.close()
        }
    }

    @Test
    fun catalogReportsOfflineAsAMessage() = runBlocking {
        val server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        server.close()
        val catalog = PackageCatalog(OkHttpClient(), registry = base)
        val e = assertThrows(CatalogException::class.java) { runBlocking { catalog.search("") } }
        assertTrue(e.message!!.startsWith("Could not reach the package registry"))
        assertTrue(loadDetail(catalog, "npm:x", null).error!!.startsWith("Could not reach"))
    }

    @Test
    fun installedComparesTheRequestedVersion() {
        val sources = listOf("npm:foo@1.0.0", "npm:@s/bar", "git:github.com/o/r")
        assertTrue(PackageFormat.isInstalled("npm:foo", sources))
        assertTrue(PackageFormat.isInstalled("npm:foo@1.0.0", sources))
        assertFalse(PackageFormat.isInstalled("npm:foo@2.0.0", sources))
        assertTrue(PackageFormat.isInstalled("npm:@s/bar", sources))
        assertTrue(PackageFormat.isInstalled("git:github.com/o/r", sources))
        assertFalse(PackageFormat.isInstalled("git:github.com/o/r@v2", sources))
        assertFalse(PackageFormat.isInstalled("npm:baz", sources))
    }

    @Test(timeout = 5_000)
    fun hostileManifestWorkIsBounded() {
        // Many deep globs against the largest file list the catalog keeps: the budget ends the work.
        val globs = List(200) { i -> "**/".repeat(7) + "*a".repeat(30) + "$i.md" }.joinToString(",") { "\"$it\"" }
        val m = manifest("""{"name":"p","pi":{"prompts":[$globs],"skills":[$globs]}}""")
        val files = List(20_000) { i -> (1..60).joinToString("/") { "a".repeat(15) } + "/x$i.md" }
        assertEquals(emptyList<AddedResource>(), CatalogJson.resources(m, files))
        // Deeper than 8 segments is refused outright.
        assertFalse(CatalogJson.globMatches("a/b/c/d/e/f/g/h/*.md", "a/b/c/d/e/f/g/h/x.md"))
        assertTrue(CatalogJson.globMatches("a/b/c/d/e/f/g/*.md", "a/b/c/d/e/f/g/x.md"))
    }

    @Test(timeout = 20_000)
    fun stalledRegistryTimesOut() = runBlocking {
        // The app's client has no read timeout; the catalog's own does, so a server that never answers is an error.
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body("{}").headersDelay(120, java.util.concurrent.TimeUnit.SECONDS).build())
        server.start()
        try {
            val catalog = PackageCatalog(OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build(), registry = server.url("/").toString().trimEnd('/'), readTimeoutMs = 500)
            val e = assertThrows(CatalogException::class.java) { runBlocking { catalog.search("") } }
            assertTrue(e.message!!.startsWith("Could not reach"))
        } finally {
            server.close()
        }
    }

    @Test
    fun installStatusBelongsToItsPackage() {
        val running = InstallStatus.Running("Installing a")
        val done = InstallStatus.Done("Installed a")
        assertEquals(InstallStatus.Idle, SourceStatus.of("npm:a", null))
        assertEquals(running, SourceStatus.of("npm:a", SourceStatus("npm:a", running)))
        // Another package's install keeps this one waiting, but its result never shows here.
        assertEquals(running, SourceStatus.of("npm:b", SourceStatus("npm:a", running)))
        assertEquals(InstallStatus.Idle, SourceStatus.of("npm:b", SourceStatus("npm:a", done)))
        assertEquals(done, SourceStatus.of("npm:a", SourceStatus("npm:a", done)))
    }

    @Test
    fun installArguments() {
        assertEquals(listOf("install", "npm:x"), PackageFormat.installArgs("npm:x", local = false))
        assertEquals(listOf("install", "-l", "npm:x"), PackageFormat.installArgs("npm:x", local = true))
    }
}
