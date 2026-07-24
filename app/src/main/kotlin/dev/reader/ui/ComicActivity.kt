package dev.reader.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import dev.reader.R
import dev.reader.ReaderApplication
import dev.reader.formats.comic.ComicDocument
import dev.reader.formats.comic.ComicException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Image-based comic reader. Portrait-locked (manifest). Reuses the text reader's tap zones, EPD
 *  refresher and page-turn-driven prefetch, but draws bitmaps, not text. */
open class ComicActivity : AppCompatActivity() {

    private lateinit var pageView: ComicPageView
    private lateinit var readout: TextView
    private lateinit var chrome: View
    private lateinit var directionButton: Button
    private val decoder = ComicPageDecoder()

    private var document: ComicDocument? = null
    private var bookPath: String = ""
    private var pageCount: Int = 0
    private var currentPage: Int = 0
    protected var rtl: Boolean = false
    private var currentBitmap: Bitmap? = null
    private var prefetch: Pair<Int, Bitmap>? = null
    private var opening = false

    private val app get() = application as ReaderApplication
    private val dao get() = app.database.bookDao()

    // ---- Test seams ----
    val pagesShownForTest = mutableListOf<Int>()
    val currentPageForTest: Int get() = currentPage
    val rtlForTest: Boolean get() = rtl
    fun onTapForTest(zone: TapZone) = onTap(zone)
    fun toggleDirectionForTest() = toggleDirection()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageView = ComicPageView(this).apply {
            epd = EinkController.forContext(this@ComicActivity)
            onTap = ::onTap
        }
        readout = TextView(this).apply {
            setBackgroundColor(Color.WHITE); setTextColor(Color.BLACK); setPadding(24, 16, 24, 16)
        }
        val back = Button(this).apply { text = getString(android.R.string.cancel); setOnClickListener { finish() } }
        val direction = Button(this).apply {
            setOnClickListener { toggleDirection() }
        }
        directionButton = direction
        chrome = FrameLayout(this).apply {
            visibility = View.GONE
            addView(back, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            addView(direction, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            addView(readout, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        val container = FrameLayout(this).apply {
            addView(pageView, FrameLayout.LayoutParams(-1, -1))
            addView(chrome, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(container)
        pageView.doOnLayout { openComic() }
    }

    open fun openComic() {
        if (document != null || opening) return
        opening = true
        val path = intent.getStringExtra(ReaderActivity.EXTRA_BOOK_PATH)
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { path?.let { File(it).takeIf(File::isFile) } }
            if (file == null) { finishMissing(); return@launch }
            val opened = withContext(Dispatchers.IO) {
                try { Result.success(ComicDocument.open(file)) }
                catch (e: ComicException) { Result.failure(e) }
            }
            val doc = opened.getOrElse { e ->
                showMessage(e.message ?: getString(android.R.string.dialog_alert_title)); finish(); return@launch
            }
            document = doc
            pageCount = doc.spineSize
            bookPath = file.path
            val stored = withContext(Dispatchers.IO) { dao.getByPath(file.path) }
            rtl = stored?.rightToLeftOverride ?: doc.readingDirectionRtl
            updateDirectionLabel()
            val start = (stored?.spineIndex ?: 0).coerceIn(0, pageCount - 1)
            showPage(start)
            opening = false
        }
    }

    private fun onTap(zone: TapZone) {
        if (chrome.visibility == View.VISIBLE) { toggleChrome(); return }
        when (zone) {
            TapZone.TOGGLE_OVERLAY -> toggleChrome()
            else -> {
                val forward = if (rtl) zone == TapZone.PREVIOUS else zone == TapZone.NEXT
                val target = if (forward) currentPage + 1 else currentPage - 1
                if (target in 0 until pageCount) showPage(target)
            }
        }
    }

    private fun showPage(index: Int) {
        val doc = document ?: return
        lifecycleScope.launch {
            val pre = prefetch
            prefetch = null
            val bmp = if (pre != null && pre.first == index) {
                pre.second
            } else {
                pre?.second?.recycle()
                decoder.decode({ doc.openPage(index) }, reqWidth(), reqHeight())
            }
            val old = currentBitmap
            currentBitmap = bmp
            pageView.show(bmp)
            pageView.fullRefresh()
            if (old != null && old !== bmp) old.recycle()
            currentPage = index
            pagesShownForTest += index
            updateReadout()
            persist(index)
            prefetchNeighbor()
        }
    }

    private fun prefetchNeighbor() {
        val doc = document ?: return
        val next = if (rtl) currentPage - 1 else currentPage + 1
        if (next !in 0 until pageCount) return
        lifecycleScope.launch {
            val bmp = decoder.decode({ doc.openPage(next) }, reqWidth(), reqHeight()) ?: return@launch
            if (prefetch?.first != next) { prefetch?.second?.recycle(); prefetch = next to bmp }
            else bmp.recycle()
        }
    }

    private fun persist(index: Int) {
        val fraction = if (pageCount > 0) (index + 1).toFloat() / pageCount else 0f
        app.positionWriteScope.launch {
            dao.updatePosition(bookPath, index, 0, fraction, System.currentTimeMillis())
        }
    }

    private fun toggleDirection() {
        rtl = !rtl
        app.positionWriteScope.launch { dao.updateRtlOverride(bookPath, rtl) }
        prefetch?.second?.recycle(); prefetch = null
        prefetchNeighbor()
        updateDirectionLabel()
    }

    private fun updateDirectionLabel() {
        directionButton.text = getString(if (rtl) R.string.comic_dir_rtl else R.string.comic_dir_ltr)
    }

    private fun toggleChrome() {
        if (chrome.visibility == View.VISIBLE) {
            chrome.visibility = View.GONE
            pageView.epd.exitFastMode()
        } else {
            pageView.epd.enterFastMode()
            chrome.visibility = View.VISIBLE
        }
    }

    private fun updateReadout() {
        readout.text = getString(R.string.comic_page_readout, currentPage + 1, pageCount)
    }

    private fun reqWidth() = pageView.width.takeIf { it > 0 } ?: 1404
    private fun reqHeight() = pageView.height.takeIf { it > 0 } ?: 1872

    private fun finishMissing() { showMessage(getString(R.string.error_book_missing)); finish() }
    private fun showMessage(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    override fun onStop() {
        if (bookPath.isNotEmpty()) persist(currentPage)
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        if (::pageView.isInitialized) pageView.epd.exitFastMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentBitmap?.recycle()
        prefetch?.second?.recycle()
        document?.close()
    }
}
