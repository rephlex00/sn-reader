package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.data.BookEntity
import org.junit.Test

/**
 * Pure JVM coverage for [folderListing] and [statusOf] — no Robolectric, no Android. Every path is a
 * plain string, so the root filtering, clamp, folder-projection, recursive-count and natural-order
 * rules are all exercised here rather than in a slow Activity test. Paths use "/" because that is
 * `File.separator` on the JVM the tests run on (and on Android).
 */
class FolderListingTest {

    // A minimal BookEntity: only path/unreadable/lastOpenedAtMs matter to this module. `title`
    // carries an id so order assertions can name the book they expect.
    private fun book(
        path: String,
        id: String = path,
        unreadable: Boolean = false,
        lastOpenedAtMs: Long? = null,
    ) = BookEntity(
        path = path,
        sizeBytes = 1_000L,
        modifiedAtMs = 1_700_000_000_000L,
        title = id,
        author = null,
        coverPath = null,
        spineIndex = 0,
        charOffset = 0,
        unreadable = unreadable,
        unreadableReason = if (unreadable) "corrupt" else null,
        addedAtMs = 1_700_000_000_000L,
        lastOpenedAtMs = lastOpenedAtMs,
    )

    private fun bookRows(rows: List<LibraryRow>) = rows.filterIsInstance<LibraryRow.Book>()
    private fun folderRows(rows: List<LibraryRow>) = rows.filterIsInstance<LibraryRow.Folder>()

    // -- statusOf -------------------------------------------------------------------------------

    @Test
    fun `statusOf reports NOT_STARTED for a book never opened and not unreadable`() {
        assertThat(statusOf(book("/lib/a.epub", lastOpenedAtMs = null, unreadable = false)))
            .isEqualTo(BookStatus.NOT_STARTED)
    }

    @Test
    fun `statusOf reports IN_PROGRESS once lastOpenedAtMs is set`() {
        // charOffset stays 0 here on purpose: offset does not distinguish opened-once from never
        // opened (open-time write-back stamps a position), so lastOpenedAtMs alone must decide.
        assertThat(statusOf(book("/lib/a.epub", lastOpenedAtMs = 5L)))
            .isEqualTo(BookStatus.IN_PROGRESS)
    }

    @Test
    fun `statusOf lets unreadable win over an opened book`() {
        assertThat(statusOf(book("/lib/a.epub", unreadable = true, lastOpenedAtMs = 5L)))
            .isEqualTo(BookStatus.UNREADABLE)
    }

    @Test
    fun `statusOf lets unreadable win over a never-opened book`() {
        assertThat(statusOf(book("/lib/a.epub", unreadable = true, lastOpenedAtMs = null)))
            .isEqualTo(BookStatus.UNREADABLE)
    }

    // -- clampToRoot ----------------------------------------------------------------------------
    // The shared clamp the Activity normalizes its currentFolder with — same rule folderListing
    // scopes its listing with, so title/Back and the rendered rows never disagree.

    @Test
    fun `clampToRoot keeps a folder that is under the root`() {
        assertThat(clampToRoot("/lib/fiction", "/lib")).isEqualTo("/lib/fiction")
    }

    @Test
    fun `clampToRoot keeps the root itself`() {
        assertThat(clampToRoot("/lib", "/lib")).isEqualTo("/lib")
    }

    @Test
    fun `clampToRoot collapses a folder outside the root back to the root`() {
        assertThat(clampToRoot("/elsewhere/x", "/lib")).isEqualTo("/lib")
    }

    @Test
    fun `clampToRoot is segment-correct - a name-prefixed sibling is outside`() {
        // /Documents is not under /Document, so it clamps back to /Document.
        assertThat(clampToRoot("/Documents", "/Document")).isEqualTo("/Document")
    }

    @Test
    fun `clampToRoot tolerates a trailing separator on either argument`() {
        assertThat(clampToRoot("/lib/fiction/", "/lib/")).isEqualTo("/lib/fiction")
    }

    // -- root filtering -------------------------------------------------------------------------

    @Test
    fun `books outside root are excluded entirely in flatten mode`() {
        val rows = folderListing(
            books = listOf(book("/lib/in.epub"), book("/elsewhere/out.epub")),
            root = "/lib",
            currentDir = "/lib",
            flatten = true,
        )
        assertThat(bookRows(rows).map { it.entity.path }).containsExactly("/lib/in.epub")
    }

    @Test
    fun `a root that is a name-prefix of another dir does not claim that dir's books`() {
        // Segment-correct ancestry: /Document must not claim /Documents/x.epub.
        val rows = folderListing(
            books = listOf(book("/Documents/x.epub")),
            root = "/Document",
            currentDir = "/Document",
            flatten = true,
        )
        assertThat(rows).isEmpty()
    }

    @Test
    fun `all books out of root yields an empty list in folder mode`() {
        val rows = folderListing(
            books = listOf(book("/other/a.epub"), book("/other/b.epub")),
            root = "/lib",
            currentDir = "/lib",
            flatten = false,
        )
        assertThat(rows).isEmpty()
    }

    // -- flatten mode ---------------------------------------------------------------------------

    @Test
    fun `flatten preserves input order and emits no folder rows`() {
        val rows = folderListing(
            books = listOf(
                book("/lib/z.epub"),
                book("/lib/sub/deep.epub"),
                book("/lib/a.epub"),
            ),
            root = "/lib",
            currentDir = "/lib",
            flatten = true,
        )
        assertThat(folderRows(rows)).isEmpty()
        assertThat(bookRows(rows).map { it.entity.path })
            .containsExactly("/lib/z.epub", "/lib/sub/deep.epub", "/lib/a.epub").inOrder()
    }

    @Test
    fun `flatten ignores currentDir and returns every in-root book`() {
        val rows = folderListing(
            books = listOf(book("/lib/a.epub"), book("/lib/sub/b.epub")),
            root = "/lib",
            currentDir = "/lib/sub", // would restrict in folder mode; ignored when flattening
            flatten = true,
        )
        assertThat(bookRows(rows).map { it.entity.path })
            .containsExactly("/lib/a.epub", "/lib/sub/b.epub").inOrder()
    }

    // -- folder mode: books directly in a dir ---------------------------------------------------

    @Test
    fun `a book directly in root shows as a Book row in folder mode at root`() {
        val rows = folderListing(
            books = listOf(book("/lib/loose.epub")),
            root = "/lib",
            currentDir = "/lib",
            flatten = false,
        )
        assertThat(folderRows(rows)).isEmpty()
        assertThat(bookRows(rows).map { it.entity.path }).containsExactly("/lib/loose.epub")
    }

    @Test
    fun `direct book rows preserve the input SQL order`() {
        val rows = folderListing(
            books = listOf(book("/lib/c.epub"), book("/lib/a.epub"), book("/lib/b.epub")),
            root = "/lib",
            currentDir = "/lib",
            flatten = false,
        )
        assertThat(bookRows(rows).map { it.entity.path })
            .containsExactly("/lib/c.epub", "/lib/a.epub", "/lib/b.epub").inOrder()
    }

    // -- folder mode: immediate-child folders + recursive counts --------------------------------

    @Test
    fun `an immediate child folder appears with a recursive count and no deeper folders`() {
        // a/b/c/1, a/2, a/b/3 all live under child "a"; at root only "a" appears, count 3, and
        // neither "b" nor "c" leaks in as a row.
        val rows = folderListing(
            books = listOf(
                book("/lib/a/b/c/1.epub"),
                book("/lib/a/2.epub"),
                book("/lib/a/b/3.epub"),
            ),
            root = "/lib",
            currentDir = "/lib",
            flatten = false,
        )
        assertThat(folderRows(rows)).containsExactly(
            LibraryRow.Folder(name = "a", path = "/lib/a", bookCount = 3),
        )
        assertThat(bookRows(rows)).isEmpty()
    }

    @Test
    fun `descending into a subfolder shows its child folders and its direct books`() {
        val rows = folderListing(
            books = listOf(
                book("/lib/a/b/c/1.epub"),
                book("/lib/a/2.epub"),
                book("/lib/a/b/3.epub"),
            ),
            root = "/lib",
            currentDir = "/lib/a",
            flatten = false,
        )
        // Under /lib/a: child folder "b" holds 2 (b/c/1 and b/3); book 2.epub sits directly here.
        assertThat(folderRows(rows)).containsExactly(
            LibraryRow.Folder(name = "b", path = "/lib/a/b", bookCount = 2),
        )
        assertThat(bookRows(rows).map { it.entity.path }).containsExactly("/lib/a/2.epub")
    }

    @Test
    fun `folders always precede books`() {
        val rows = folderListing(
            books = listOf(book("/lib/loose.epub"), book("/lib/sub/nested.epub")),
            root = "/lib",
            currentDir = "/lib",
            flatten = false,
        )
        assertThat(rows.first()).isInstanceOf(LibraryRow.Folder::class.java)
        assertThat(rows.last()).isInstanceOf(LibraryRow.Book::class.java)
    }

    // -- folder mode: sibling names where one prefixes the other --------------------------------

    @Test
    fun `sibling folders where one name prefixes the other both appear without count bleed`() {
        val rows = folderListing(
            books = listOf(
                book("/lib/Books/a.epub"),
                book("/lib/Books2/b.epub"),
                book("/lib/Books2/c.epub"),
            ),
            root = "/lib",
            currentDir = "/lib",
            flatten = false,
        )
        assertThat(folderRows(rows)).containsExactly(
            LibraryRow.Folder(name = "Books", path = "/lib/Books", bookCount = 1),
            LibraryRow.Folder(name = "Books2", path = "/lib/Books2", bookCount = 2),
        ).inOrder()
    }

    // -- folder mode: natural ordering of folder names ------------------------------------------

    @Test
    fun `folder rows sort by natural name order so page2 precedes page10`() {
        val rows = folderListing(
            books = listOf(
                book("/lib/page10/a.epub"),
                book("/lib/page2/b.epub"),
            ),
            root = "/lib",
            currentDir = "/lib",
            flatten = false,
        )
        assertThat(folderRows(rows).map { it.name }).containsExactly("page2", "page10").inOrder()
    }

    // -- clamp / trailing-separator tolerance ---------------------------------------------------

    @Test
    fun `currentDir equal to root lists root's own folders and books`() {
        val rows = folderListing(
            books = listOf(book("/lib/sub/a.epub"), book("/lib/loose.epub")),
            root = "/lib",
            currentDir = "/lib",
            flatten = false,
        )
        assertThat(folderRows(rows).map { it.name }).containsExactly("sub")
        assertThat(bookRows(rows).map { it.entity.path }).containsExactly("/lib/loose.epub")
    }

    @Test
    fun `a trailing separator on currentDir is tolerated`() {
        val rows = folderListing(
            books = listOf(book("/lib/sub/a.epub")),
            root = "/lib",
            currentDir = "/lib/", // stray trailing slash must not break child derivation
            flatten = false,
        )
        assertThat(folderRows(rows)).containsExactly(
            LibraryRow.Folder(name = "sub", path = "/lib/sub", bookCount = 1),
        )
    }

    @Test
    fun `a trailing separator on root is tolerated`() {
        val rows = folderListing(
            books = listOf(book("/lib/sub/a.epub"), book("/lib/loose.epub")),
            root = "/lib/", // stray trailing slash on root
            currentDir = "/lib",
            flatten = false,
        )
        assertThat(folderRows(rows)).containsExactly(
            LibraryRow.Folder(name = "sub", path = "/lib/sub", bookCount = 1),
        )
        assertThat(bookRows(rows).map { it.entity.path }).containsExactly("/lib/loose.epub")
    }

    @Test
    fun `a currentDir outside root is clamped to root`() {
        // A stale lastFolderPath after a root change: it is not under the new root, so the listing
        // falls back to root rather than showing nothing.
        val rows = folderListing(
            books = listOf(book("/lib/sub/a.epub"), book("/lib/loose.epub")),
            root = "/lib",
            currentDir = "/oldroot/gone",
            flatten = false,
        )
        assertThat(folderRows(rows).map { it.name }).containsExactly("sub")
        assertThat(bookRows(rows).map { it.entity.path }).containsExactly("/lib/loose.epub")
    }

    @Test
    fun `a currentDir that only name-prefixes root is treated as outside and clamped`() {
        // /lib-old shares a textual prefix with /lib but is not under it — non-ancestry is "outside".
        val rows = folderListing(
            books = listOf(book("/lib/loose.epub")),
            root = "/lib",
            currentDir = "/lib-old",
            flatten = false,
        )
        assertThat(bookRows(rows).map { it.entity.path }).containsExactly("/lib/loose.epub")
    }

    // -- empty input ----------------------------------------------------------------------------

    @Test
    fun `an empty book list yields an empty listing in flatten mode`() {
        assertThat(folderListing(emptyList(), root = "/lib", currentDir = "/lib", flatten = true))
            .isEmpty()
    }

    @Test
    fun `an empty book list yields an empty listing in folder mode`() {
        assertThat(folderListing(emptyList(), root = "/lib", currentDir = "/lib", flatten = false))
            .isEmpty()
    }
}
