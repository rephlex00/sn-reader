package dev.reader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import dev.reader.engine.NavTarget
import dev.reader.engine.PageNavigator
import dev.reader.engine.ReadingState
import dev.reader.engine.RenderConfig
import dev.reader.formats.epub.EpubDocument
import dev.reader.formats.epub.EpubException
import dev.reader.formats.epub.PaginatedChapter
import dev.reader.formats.render.AndroidMeasuredChapter
import dev.reader.formats.render.AndroidTextMeasurer
import dev.reader.formats.render.SpannedChapterBuilder
import dev.reader.formats.render.TypefaceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opens the first EPUB found under the Supernote's /Document folder and turns its pages.
 *
 * All calls into [EpubDocument.chapter] happen from this Activity's UI thread (either directly
 * from a lifecycleScope coroutine resumed on Dispatchers.Main, or from [PageView]'s tap
 * callback, which View always delivers on the main thread) because `chapter()`'s cache is
 * documented as not thread-safe. Only opening the document (pure I/O, no cache involved yet)
 * runs on Dispatchers.IO.
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var pageView: PageView
    private var document: EpubDocument? = null
    private var navigator: PageNavigator? = null
    private var state = ReadingState(0, 0)
    private var config: RenderConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageView = PageView(this)
        setContentView(pageView)

        if (!Environment.isExternalStorageManager()) {
            requestAllFilesAccess()
            return
        }
        pageView.doOnLayout { openFirstBook() }
    }

    override fun onResume() {
        super.onResume()
        if (document == null && Environment.isExternalStorageManager()) {
            pageView.doOnLayout { openFirstBook() }
        }
    }

    override fun onDestroy() {
        document?.close()
        document = null
        super.onDestroy()
    }

    /**
     * Supernote keeps user books in /Document; reading them in place is the whole point,
     * so all-files access is the only workable permission on Android 11.
     */
    private fun requestAllFilesAccess() {
        showMessage("Grant all-files access so Reader can open books in your Document folder.")
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun openFirstBook() {
        if (document != null) return

        // pageView.width/height can still be 0 here in principle (doOnLayout fires on any
        // layout pass, not only one that gave the view real bounds). RenderConfig's init
        // throws on a non-positive content width/height, which would otherwise crash this
        // coroutine and the app. Guard it and simply wait for a layout pass that has bounds.
        val width = pageView.width
        val height = pageView.height
        if (width <= 0 || height <= 0) {
            pageView.doOnLayout { openFirstBook() }
            return
        }

        val renderConfig = RenderConfig(
            fontFamily = "serif",
            textSizePx = 34f,
            lineSpacingMultiplier = 1.4f,
            marginPx = 48,
            justified = true,
            hyphenated = true,
            viewportWidthPx = width,
            viewportHeightPx = height,
        )
        config = renderConfig

        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { findFirstEpub() }
            if (file == null) {
                showMessage("No EPUB found in /Document.")
                return@launch
            }
            try {
                val doc = withContext(Dispatchers.IO) {
                    EpubDocument.open(
                        file,
                        AndroidTextMeasurer(SpannedChapterBuilder(), TypefaceProvider.Platform),
                    )
                }
                document = doc
                navigator = PageNavigator(doc.spineSize)
                pageView.onTap = ::onTap
                showPage(ReadingState(0, 0))
            } catch (e: EpubException) {
                showMessage("Couldn't open this book: ${e.message}")
            } catch (e: Exception) {
                // open() is documented to throw only EpubException, but that promise is only as
                // good as every path inside EpubPackageParser/EpubTocParser honouring it (e.g. a
                // raw XmlPullParserException or IOException from a corrupt-but-zip-valid file).
                // A malformed book must never crash the app, so nothing escapes this boundary.
                showMessage("Couldn't open this book: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun findFirstEpub(): File? = try {
        val documents = File(Environment.getExternalStorageDirectory(), "Document")
        documents.walkTopDown()
            .filter { it.isFile && it.extension.equals("epub", ignoreCase = true) }
            .firstOrNull()
    } catch (e: SecurityException) {
        // A denied or half-revoked all-files-access grant can surface here as walkTopDown
        // touches directories; treat it as "nothing found" rather than crashing.
        null
    }

    private fun onTap(zone: TapZone) {
        when (zone) {
            TapZone.NEXT -> advance()
            TapZone.PREVIOUS -> retreat()
            // The overlay arrives with the reading chrome in Plan 4.
            TapZone.TOGGLE_OVERLAY -> Unit
        }
    }

    /**
     * Turns the page forward, looping past any empty chapters.
     *
     * [PageNavigator.next] only knows the *current* chapter's page count, so
     * `Page(spineIndex + 1, 0)` is not guaranteed to land somewhere with pages (a chapter
     * missing from the archive parses to zero pages — see `EpubDocument.chapter()`). If the
     * landed-on chapter turns out to be empty, ask the navigator to advance again from there,
     * repeating until a chapter with at least one page is found or the book ends.
     */
    private fun advance() {
        val nav = navigator ?: return
        val doc = document ?: return
        val cfg = config ?: return

        var current = state
        while (true) {
            val pagesInCurrentChapter = doc.chapter(current.spineIndex, cfg).pages.size
            when (val target = nav.next(current, pagesInCurrentChapter)) {
                is NavTarget.Page -> {
                    if (doc.chapter(target.spineIndex, cfg).pages.isEmpty()) {
                        current = ReadingState(target.spineIndex, 0)
                        continue
                    }
                    showPage(ReadingState(target.spineIndex, target.pageIndex))
                    return
                }
                NavTarget.AtEnd -> return
                NavTarget.AtStart, is NavTarget.LastPageOf -> return // next() never returns these
            }
        }
    }

    /**
     * Turns the page backward, skipping past any empty chapters.
     *
     * [PageNavigator.previous] cannot know whether the previous chapter has any pages, so it
     * always answers [NavTarget.LastPageOf] blindly. Resolving that as `pages.size - 1` on an
     * empty chapter yields a bogus `pageIndex = -1`. Instead, walk backward chapter by chapter
     * until one with pages turns up, or the book's start is reached.
     */
    private fun retreat() {
        val doc = document ?: return
        val cfg = config ?: return
        val nav = navigator ?: return

        when (val first = nav.previous(state)) {
            is NavTarget.Page -> showPage(ReadingState(first.spineIndex, first.pageIndex))
            NavTarget.AtStart -> Unit
            NavTarget.AtEnd -> Unit // previous() never returns this
            is NavTarget.LastPageOf -> {
                var spineIndex = first.spineIndex
                while (true) {
                    val pages = doc.chapter(spineIndex, cfg).pages
                    if (pages.isNotEmpty()) {
                        showPage(ReadingState(spineIndex, pages.lastIndex))
                        return
                    }
                    if (spineIndex == 0) return // every chapter before here was empty too
                    spineIndex -= 1
                }
            }
        }
    }

    private fun showPage(next: ReadingState) {
        val doc = document ?: return
        val cfg = config ?: return
        val chapter: PaginatedChapter = doc.chapter(next.spineIndex, cfg)
        if (chapter.pages.isEmpty()) return

        val pageIndex = next.pageIndex.coerceIn(0, chapter.pages.lastIndex)
        state = next.copy(pageIndex = pageIndex)

        val layout = (chapter.measured as AndroidMeasuredChapter).layout
        pageView.show(layout, chapter.pages[pageIndex], cfg.marginPx)
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
