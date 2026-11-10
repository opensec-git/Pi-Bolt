import Foundation
@testable import PiBoltProtocol
import Testing

/// The Android `RemoteReviewTest` (android/protocol): the read-only review commands (docs/remote-control.md, "Review"),
/// plus the diff rows the review screens draw.
struct RemoteReviewTests {
    private func json(_ text: String) -> JSON { SessionReducer.parse(text) }

    private func ok(_ command: String, _ data: String) -> RpcResponse {
        RpcResponse(command: command, success: true, data: json(data), error: nil)
    }

    private func fields(_ request: ReviewRequest) -> JSON { .object(request.fields) }

    @Test func helloSaysWhetherTheHostReviews() {
        let newer = SessionReducer.fromHello(json(#"{"type":"remote_hello","host":{"name":"mac","version":"1.2.0","cwd":"/w","review":true},"messages":[]}"#))
        #expect(newer.host?.review == true)
        let older = SessionReducer.fromHello(json(#"{"type":"remote_hello","host":{"name":"mac","version":"1.1.4","cwd":"/w"},"messages":[]}"#))
        #expect(older.host?.review == false)
    }

    @Test func requestsCarryTheirFields() {
        #expect(ReviewRequest.status().type == "rc_git_status")
        #expect(fields(.status()) == json("{}"))
        #expect(fields(.diff("src/a.ts", staged: false, origPath: "src/old.ts")) == json(#"{"path":"src/a.ts","staged":false}"#))
        #expect(fields(.diff("b.ts", staged: true, origPath: "a.ts")) == json(#"{"path":"b.ts","staged":true,"origPath":"a.ts"}"#))
        #expect(fields(.log(limit: 500, skip: -3)) == json(#"{"limit":200,"skip":0}"#))
        #expect(fields(.log(limit: 0, skip: 100)) == json(#"{"limit":1,"skip":100}"#))
        #expect(ReviewRequest.show("abc1234").type == "rc_git_show")
        #expect(fields(.listDir("")) == json(#"{"path":""}"#))
        #expect(fields(.readFile("docs/a.md")) == json(#"{"path":"docs/a.md"}"#))
    }

    @Test func statusReadsBranchEntriesAndCounts() throws {
        let status = try RemoteGitStatus.from(ok("rc_git_status", """
        {"repo":true,"branch":{"head":"main","oid":"4f1c2d","upstream":"origin/main","ahead":2,"behind":1},
        "entries":[
          {"path":"src/app.ts","index":null,"worktree":"M","kind":"modified","conflicted":false,"staged":null,"unstaged":{"added":3,"removed":1}},
          {"path":"docs/new.md","origPath":"docs/old.md","index":"R","worktree":null,"kind":"renamed","conflicted":false,"staged":{"added":1,"removed":0},"unstaged":null},
          {"path":"notes.md","index":null,"worktree":"?","kind":"untracked","conflicted":false,"staged":null,"unstaged":null},
          {"path":"both.ts","index":"U","worktree":"U","kind":"conflicted","conflicted":true,"staged":null,"unstaged":null},
          {"index":"M"},
          {"path":"odd.ts","index":"MM","worktree":"X"}
        ],"total":{"added":4,"removed":1},"truncated":false}
        """))
        #expect(status.repo)
        #expect(status.branch?.head == "main")
        #expect(status.branch?.ahead == 2)
        #expect(status.branch?.behind == 1)
        #expect(status.entries.map(\.path) == ["src/app.ts", "docs/new.md", "notes.md", "both.ts", "odd.ts"])
        let app = status.entries[0]
        #expect(app.index == nil)
        #expect(app.worktree == "M")
        #expect(app.unstaged == RemoteLineCount(added: 3, removed: 1))
        #expect(app.staged == nil)
        #expect(status.entries[1].origPath == "docs/old.md")
        #expect(status.entries[1].index == "R")
        #expect(status.entries[2].worktree == "?")
        #expect(status.entries[3].conflicted)
        // Not a single known letter: no change on that side.
        #expect(status.entries[4].index == nil)
        #expect(status.entries[4].worktree == nil)
        #expect(status.total == RemoteLineCount(added: 4, removed: 1))
    }

    @Test func statusOutsideARepository() throws {
        let status = try RemoteGitStatus.from(ok("rc_git_status", #"{"repo":false,"branch":null,"entries":[],"total":{"added":0,"removed":0},"truncated":false}"#))
        #expect(!status.repo)
        #expect(status.branch == nil)
        #expect(status.entries.isEmpty)
        #expect(status.total == .zero)
        // An older or odd host that sends less still parses.
        #expect(try RemoteGitStatus.from(ok("rc_git_status", "{}")).total == .zero)
    }

    @Test func failuresThrowTheirError() {
        #expect(throws: ReviewError(".env looks like a secret file")) {
            try RemoteGitStatus.from(RpcResponse(command: "rc_read_file", success: false, data: nil, error: ".env looks like a secret file"))
        }
        #expect(throws: ReviewError.self) { try RemoteFile.from(.failure("rc_read_file", "")) }
        #expect(throws: ReviewError.self) { try RemoteDiff.from(RpcResponse(command: "rc_git_diff", success: true, data: nil, error: nil)) }
    }

    @Test func diffKeepsTheText() throws {
        let diff = try RemoteDiff.from(ok("rc_git_diff", #"{"path":"a.txt","staged":false,"untracked":true,"diff":"diff --git a/a.txt b/a.txt\n+x\n","truncated":true}"#))
        #expect(diff.path == "a.txt")
        #expect(diff.untracked)
        #expect(diff.truncated)
        #expect(diff.diff == "diff --git a/a.txt b/a.txt\n+x\n")
    }

    @Test func logKeepsOnlyCommitsWithHashes() throws {
        let log = try RemoteLog.from(ok("rc_git_log", """
        {"repo":true,"more":true,"commits":[
          {"hash":"0123456789abcdef0123456789abcdef01234567","short":"0123456","parents":["89abcdef","--evil"],"author":"Ann","email":"ann@x","time":1700000000,"subject":"Fix","body":"Body","refs":["HEAD -> main","tag: v1"]},
          {"hash":"HEAD","subject":"not a commit"},
          {"hash":"abcdef1","subject":"short only"}
        ]}
        """))
        #expect(log.more)
        #expect(log.commits.map(\.subject) == ["Fix", "short only"])
        let first = log.commits[0]
        #expect(first.parents == ["89abcdef"])
        #expect(first.time == 1_700_000_000)
        #expect(first.refs == ["HEAD -> main", "tag: v1"])
        // A repository decides how many refs point at a commit: the phone keeps the first few and the count.
        let many = RemoteCommit.from(.object(["hash": "abcd1234", "refs": .array((0 ..< 50_000).map { .string("tag: t\($0)") })]))
        #expect(many?.refs.count == RemoteCommit.maxRefs)
        #expect(many?.refCount == 50_000)
        #expect(log.commits[1].short == "abcdef1")
    }

    @Test func showNamesWithheldFiles() throws {
        let show = try RemoteShow.from(ok("rc_git_show", #"{"commit":{"hash":"abcdef12","short":"abcdef1","subject":"s"},"diff":"diff --git a/x b/x\n","truncated":false,"withheld":[".env"]}"#))
        #expect(show.commit?.hash == "abcdef12")
        #expect(show.withheld == [".env"])
        #expect(try RemoteShow.from(ok("rc_git_show", #"{"commit":null,"diff":""}"#)).commit == nil)
    }

    @Test func listingDropsNamesThatAreNotOneSegment() throws {
        let listing = try RemoteListing.from(ok("rc_list_dir", """
        {"path":"src","truncated":false,"entries":[
          {"name":"a.ts","kind":"file","size":12,"mtime":1700000000000},
          {"name":"lib","kind":"dir","size":0,"mtime":1},
          {"name":"away","kind":"other","symlink":true,"outside":true,"size":0,"mtime":1},
          {"name":"../up","kind":"file"},{"name":"a/b","kind":"file"},{"name":"..","kind":"dir"},{"kind":"file"}
        ]}
        """))
        #expect(listing.path == "src")
        #expect(listing.entries.map(\.name) == ["a.ts", "lib", "away"])
        #expect(listing.entries[0].file)
        #expect(listing.entries[0].size == 12)
        #expect(listing.entries[1].dir)
        #expect(listing.entries[2].outside)
        #expect(remoteChild(listing.path, "a.ts") == "src/a.ts")
        #expect(remoteChild("", "a.ts") == "a.ts")
    }

    @Test func fileContentIsLeftOutForBinaries() throws {
        let text = try RemoteFile.from(ok("rc_read_file", #"{"path":"a.txt","size":5000000,"mtime":1,"binary":false,"truncated":true,"content":"hello"}"#))
        #expect(text.content == "hello")
        #expect(text.truncated)
        #expect(text.size == 5_000_000)
        let binary = try RemoteFile.from(ok("rc_read_file", #"{"path":"a.png","size":6,"mtime":1,"binary":true,"truncated":false,"content":"ignored"}"#))
        #expect(binary.content == nil)
        #expect(binary.binary)
    }

    // MARK: Diff rows

    @Test func diffRowsCarryBothLineNumbers() {
        let rows = DiffLines.parse("""
        diff --git a/src/app.ts b/src/app.ts
        index 1111111..2222222 100644
        --- a/src/app.ts
        +++ b/src/app.ts
        @@ -10,4 +10,5 @@ function main() {
         keep
        -old line
        +new line
        +added line

         tail
        \\ No newline at end of file

        """)
        #expect(rows.map(\.kind) == [.file, .meta, .meta, .meta, .hunk, .context, .removed, .added, .added, .context, .context, .noNewline])
        #expect(rows[0].text == "src/app.ts")
        #expect(rows[5] == DiffRow(kind: .context, text: "keep", old: 10, new: 10))
        #expect(rows[6] == DiffRow(kind: .removed, text: "old line", old: 11))
        #expect(rows[7] == DiffRow(kind: .added, text: "new line", new: 11))
        #expect(rows[8] == DiffRow(kind: .added, text: "added line", new: 12))
        // An empty line in a hunk is context whose space was stripped.
        #expect(rows[9] == DiffRow(kind: .context, text: "", old: 12, new: 13))
        #expect(rows[10] == DiffRow(kind: .context, text: "tail", old: 13, new: 14))
        #expect(DiffLines.counts(rows) == RemoteLineCount(added: 2, removed: 1))
    }

    @Test func diffRowsOfANewFileAndSeveralFiles() {
        let rows = DiffLines.parse("""
        diff --git a/notes.md b/notes.md
        new file mode 100644
        --- /dev/null
        +++ b/notes.md
        @@ -0,0 +1,2 @@
        +one
        +two
        diff --git a/b.txt b/b.txt
        @@ -3 +3 @@
        -x
        +y
        """)
        #expect(rows.filter { $0.kind == .file }.map(\.text) == ["notes.md", "b.txt"])
        #expect(rows.filter { $0.kind == .added }.map(\.new) == [1, 2, 3])
        #expect(rows.first { $0.kind == .removed }?.old == 3)
    }

    @Test func textOutsideFilesIsKept() {
        // `git show` style text before the first file, and stat text after a hunk ends it.
        let rows = DiffLines.parse("commit header\n\ndiff --git a/x b/x\n@@ -1 +1 @@\n-a\n+b\n 1 file changed\nnot a diff line\n")
        #expect(rows[0] == DiffRow(kind: .other, text: "commit header"))
        #expect(rows[1].kind == .other)
        #expect(rows.last == DiffRow(kind: .meta, text: "not a diff line"))
        // A malformed hunk header still shows, and numbers nothing.
        let odd = DiffLines.parse("diff --git a/x b/x\n@@ broken @@\n+a\n")
        #expect(odd.map(\.kind) == [.file, .hunk, .meta])
        #expect(DiffLines.parse("").isEmpty)
    }
}
