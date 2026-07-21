package dev.reader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R
import dev.reader.data.BookEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the grid shows under a book's title, or null to show nothing.
 *
 * One label form only: a percentage, or nothing at all. [BookEntity.progressFraction] is the
 * byte-weighted whole-book position the reader stored (schema v2), the same value the in-book
 * progress bar shows.
 *
 * Anything else shows nothing. That covers a never-opened book (where "0%" would misrepresent an
 * untouched book) and a row opened before that column shipped, whose progress simply isn't known.
 * The earlier "Section N" spine-index fallback is gone deliberately: nothing backfilled those rows,
 * so they sat next to percentages forever, and a grid mixing "Section 8" with "43%" reads as two
 * half-built features rather than one.
 */
fun progressLabel(
    lastOpenedAtMs: Long?,
    spineIndex: Int,
    charOffset: Int,
    progressFraction: Float?,
): String? {
    if (lastOpenedAtMs == null) return null
    val fraction = progressFraction ?: return null
    return "${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}%"
}

/**
 * Cleans a raw `dc:creator` string for display. EPUB metadata routinely leaves a dangling
 * separator — e.g. "Andy Weir;" from a creator list whose second entry is empty — which the stored
 * value carries verbatim. Strips leading/trailing separator punctuation (`;`, `,`) and whitespace
 * so the byline reads "Andy Weir", while leaving internal punctuation ("Weir, Andy") untouched.
 * Pure and total; null or all-separator input yields "".
 */
internal fun formatAuthor(raw: String?): String =
    raw?.trim { it.isWhitespace() || it == ';' || it == ',' }.orEmpty()

/**
 * The badge shown for a book in **list** mode: the reading status from [statusOf], as a string
 * resource the caller resolves. Tiles keep [progressLabel]'s behavior instead (progress-if-opened,
 * nothing if never opened) — status text is a list-mode affordance only, per the brief.
 *
 * Returns a resource id rather than a string so this stays pure and testable without a Context,
 * which it can now do because no case interpolates anything: an unreadable book reports only that
 * it will not open, never the stored exception text.
 */
@StringRes
fun statusTextRes(book: BookEntity): Int = when (statusOf(book)) {
    BookStatus.IN_PROGRESS -> R.string.status_in_progress
    BookStatus.NOT_STARTED -> R.string.status_not_started
    // Deliberately not the stored reason. That string is a wrapped exception message
    // ("Not a readable EPUB archive: error in opening zip file"), which tells a reader nothing
    // they can act on. It stays in the index for logging; the shelf just says the book won't open.
    BookStatus.UNREADABLE -> R.string.status_unreadable
}

/**
 * A file size for list-mode rows, in the largest unit that keeps the number small: bytes under 1
 * KiB, otherwise one decimal place of KB/MB/GB/TB (binary 1024 steps — this is a file size, not a
 * disk-marketing figure). Pure; formats with [Locale.US] so a test asserts one exact string
 * regardless of the JVM's default locale (a comma decimal separator would otherwise flake).
 */
fun humanReadableSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024
        unit++
    } while (value >= 1024 && unit < units.size - 1)
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

/**
 * The in-memory bitmap cache key for one cover. Keyed on [modifiedAtMs] as well as [coverPath] so
 * that if a cover is ever regenerated in place at the same path (not expected mid-session — the
 * indexer only runs once on library entry — but cheap insurance), a stale decoded bitmap from an
 * earlier key never resurfaces; [BookGridAdapter] simply decodes again under the new key.
 */
fun coverCacheKey(coverPath: String, modifiedAtMs: Long): String = "$coverPath@$modifiedAtMs"

/**
 * Cache budget in bytes, not entry count. A bound of 60 *entries* with no [LruCache.sizeOf]
 * override sounds generous for a 15-book library but is the wrong axis for a growing one: at
 * [Bitmap.Config.ARGB_8888] (the default `BitmapFactory.decodeFile` produces for an 8-bit
 * grayscale cover PNG) a 224x360 cover is ~322 KB, so 60 of them is ~19 MB worst case — and that
 * ceiling doesn't move no matter how large a single cover happens to be. Bounding by bytes with
 * [Bitmap.Config.RGB_565] decoding (see [decodeCover] — free precision loss on a grayscale panel,
 * halving the per-cover cost to ~161 KB) keeps memory use predictable regardless of library size
 * or individual cover dimensions: ~8 MB is roughly 50 covers at that size, with headroom.
 */
private const val BITMAP_CACHE_BYTES = 8 * 1024 * 1024

/**
 * The unified library adapter. Views only, `RecyclerView` + `GridLayoutManager` (set up by
 * [LibraryActivity]) — no Compose, no image library. It renders a [folderListing] projection —
 * `List<LibraryRow>` of folders and books — in one of two presentations chosen by [viewMode]:
 *
 * - **Tiles** ([ViewMode.TILES]): today's cover grid for [LibraryRow.Book] rows (the look is
 *   unchanged), with [LibraryRow.Folder] rows as full-span cells above them (the Activity's
 *   `GridLayoutManager.spanSizeLookup` reads [getItemViewType] and gives everything but a book
 *   tile the full span).
 * - **List** ([ViewMode.LIST]): every row single-span — book rows carry title/author/size and a
 *   [statusTextRes] badge and **never touch the cover cache or launch a decode**; folder rows carry
 *   name and book count.
 *
 * Covers decode from [BookEntity.coverPath] on a background dispatcher and are cached in a small
 * [LruCache], with two layers against the classic RecyclerView async-bind bug (a slow decode
 * finishing after its holder has been reused painting into the wrong cell): the in-flight
 * [BookTileViewHolder.job] is cancelled on every recycle/rebind, and — should a decode ever slip
 * past that — the coroutine re-checks [BookTileViewHolder.boundCacheKey] before calling
 * [ImageView.setImageBitmap]. The stamp is the full [coverCacheKey] (path AND mtime), not the bare
 * path: a cover regenerated in place at the same path is different bytes, and comparing paths would
 * wave the stale decode through. Only book *tiles* run any of this machinery; the three other row
 * types have no cover, no job, and no cache interaction at all.
 *
 * [scope] is expected to be an Activity's `lifecycleScope`: decode jobs are children of it, so
 * they are cancelled automatically if the Activity is destroyed mid-decode, on top of the
 * per-holder cancellation this adapter already does on recycle/rebind.
 *
 * `open`, not `final`: [BookGridAdapterBindTest]'s Robolectric coverage substitutes [decodeCover]
 * via a test subclass — the one point where this class touches real image bytes — to make decode
 * timing deterministic. No other member is `open`.
 */
open class BookGridAdapter(
    private val scope: CoroutineScope,
    private val onBookClick: (BookEntity) -> Unit,
    private val onFolderClick: (String) -> Unit,
) : ListAdapter<LibraryRow, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    /**
     * The active presentation. Not set directly by callers — [render] owns it, so a change is
     * always paired with the full rebind a view-type switch needs (see [render]). Defaults to
     * [ViewMode.TILES] so an adapter that is never told otherwise renders today's grid.
     */
    var viewMode: ViewMode = ViewMode.TILES
        private set

    private val bitmapCache = object : LruCache<String, Bitmap>(BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * True when a [viewMode] change has happened but the full rebind it requires has not been
     * dispatched yet. Cleared by [commitPendingModeInvalidation], which every [render] submission
     * passes as its commit callback. The flag — rather than hanging `notifyDataSetChanged` directly
     * off the mode-changing submission — is what makes the toggle race-proof: [AsyncListDiffer]
     * drops a submission's commit callback when a NEWER submission supersedes it mid-diff, so a
     * Room emission landing right after a toggle would silently swallow the rebind and leave
     * holders painted in the old presentation. With the flag, whichever submission wins the
     * generation race performs the invalidation.
     */
    private var modeInvalidationPending = false

    /**
     * Submit the rows to show and the mode to show them in, in one call. When only the rows change
     * (a new Room emission, a folder descent, a flatten/sort change) DiffUtil does the minimal
     * rebind. When the **mode** changes the row list is typically identical, so DiffUtil would
     * compute no change and leave every holder painted in the old presentation — a view-type
     * switch needs each holder recreated, so a full rebind is forced once the winning submission
     * commits (see [modeInvalidationPending] for why it must be the *winning* one, not this one).
     * [RecyclerView.setItemAnimator] is null (see [LibraryActivity]), so that full rebind is a
     * single clean redraw, not an animated cross-fade — a toggle stays e-ink-safe.
     */
    fun render(rows: List<LibraryRow>, mode: ViewMode) {
        if (mode != viewMode) {
            viewMode = mode
            modeInvalidationPending = true
        }
        submitList(rows) { commitPendingModeInvalidation() }
    }

    private fun commitPendingModeInvalidation() {
        if (!modeInvalidationPending) return
        modeInvalidationPending = false
        // Invalidate everything so RecyclerView re-queries getItemViewType and recreates holders
        // in the new presentation. Runs after the commit, so it rebinds the committed rows.
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is LibraryRow.Book -> if (viewMode == ViewMode.TILES) VIEW_TYPE_BOOK_TILE else VIEW_TYPE_BOOK_ROW
        is LibraryRow.Folder -> if (viewMode == ViewMode.TILES) VIEW_TYPE_FOLDER_TILE else VIEW_TYPE_FOLDER_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_BOOK_TILE -> BookTileViewHolder(inflater.inflate(R.layout.item_book, parent, false))
            VIEW_TYPE_FOLDER_TILE -> FolderViewHolder(inflater.inflate(R.layout.item_folder_tile, parent, false))
            VIEW_TYPE_BOOK_ROW -> BookRowViewHolder(inflater.inflate(R.layout.item_book_row, parent, false))
            VIEW_TYPE_FOLDER_ROW -> FolderViewHolder(inflater.inflate(R.layout.item_folder_row, parent, false))
            else -> error("Unknown view type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is LibraryRow.Book -> when (holder) {
                is BookTileViewHolder -> bindBookTile(holder, row.entity)
                is BookRowViewHolder -> bindBookRow(holder, row.entity)
                else -> error("Book row bound to ${holder::class.simpleName}")
            }
            is LibraryRow.Folder -> when (holder) {
                is FolderViewHolder -> bindFolder(holder, row)
                else -> error("Folder row bound to ${holder::class.simpleName}")
            }
        }
    }

    private fun bindBookTile(holder: BookTileViewHolder, book: BookEntity) {
        // A rebind (view reused for a different list position without going through
        // onViewRecycled — e.g. a DiffUtil move) must cancel any decode still in flight for
        // whatever this holder was previously bound to, same as onViewRecycled does.
        holder.job?.cancel()
        holder.job = null

        holder.title.text = book.title
        holder.author.text = formatAuthor(book.author)
        holder.itemView.setOnClickListener { onBookClick(book) }

        holder.status.text = when {
            book.unreadable -> book.unreadableReason ?: "Unreadable"
            else -> progressLabel(book.lastOpenedAtMs, book.spineIndex, book.charOffset, book.progressFraction)
        }
        holder.status.visibility = if (holder.status.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        bindCover(holder, book)
    }

    private fun bindBookRow(holder: BookRowViewHolder, book: BookEntity) {
        holder.title.text = book.title
        val author = formatAuthor(book.author)
        holder.author.text = author
        holder.author.visibility = if (author.isEmpty()) View.GONE else View.VISIBLE
        holder.size.text = humanReadableSize(book.sizeBytes)
        holder.status.text = holder.itemView.context.getString(statusTextRes(book))
        holder.itemView.setOnClickListener { onBookClick(book) }
    }

    private fun bindFolder(holder: FolderViewHolder, folder: LibraryRow.Folder) {
        // A drawn glyph, not an image asset: "▸ name (count)", black on white, crisp on e-ink.
        holder.label.text = holder.itemView.context
            .getString(R.string.folder_row_label, folder.name, folder.bookCount)
        holder.itemView.setOnClickListener { onFolderClick(folder.path) }
    }

    private fun bindCover(holder: BookTileViewHolder, book: BookEntity) {
        val path = book.coverPath
        if (path == null) {
            holder.boundCacheKey = null
            holder.cover.setImageDrawable(null)
            return
        }

        val cacheKey = coverCacheKey(path, book.modifiedAtMs)
        holder.boundCacheKey = cacheKey
        val cached = bitmapCache.get(cacheKey)
        if (cached != null) {
            holder.cover.setImageBitmap(cached)
            return
        }

        // Clear immediately: without this, a recycled holder keeps showing the PREVIOUS book's
        // cover on screen for the whole time this one's decode is in flight.
        holder.cover.setImageDrawable(null)
        holder.job = scope.launch(Dispatchers.IO) {
            val bitmap = decodeCover(path)
            withContext(Dispatchers.Main.immediate) {
                if (bitmap != null) bitmapCache.put(cacheKey, bitmap)
                // The holder may have been rebound while this decode was in flight; only paint
                // if it is still bound to the exact (path, mtime) this decode was for. The
                // cancellation in onBindViewHolder/onViewRecycled normally stops a stale decode
                // before it gets here — this is the second layer, and it must compare the full
                // cache key: a same-path rebind with a new mtime is DIFFERENT bytes.
                if (holder.boundCacheKey == cacheKey) {
                    holder.cover.setImageBitmap(bitmap)
                }
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        // Only book tiles ever start a decode; the other three row types have no job to cancel.
        if (holder is BookTileViewHolder) {
            holder.job?.cancel()
            holder.job = null
        }
    }

    /**
     * Decodes one cover file. `protected open` purely as a test seam: [BookGridAdapterBindTest]
     * substitutes an implementation that blocks until released, so rebind-cancels-decode and
     * stale-decode-never-paints are testable deterministically. Always called on [Dispatchers.IO].
     */
    protected open fun decodeCover(path: String): Bitmap? = try {
        // RGB_565, not the decoder's ARGB_8888 default: this panel is grayscale, so the alpha
        // channel and extra color precision ARGB_8888 carries are pure waste — RGB_565 halves
        // per-cover memory for free. See BITMAP_CACHE_BYTES for the arithmetic.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        BitmapFactory.decodeFile(path, options)
    } catch (e: Exception) {
        // A cover file can vanish or corrupt between index time and bind time (e.g. the user
        // clears app storage of covers by hand); a grid cell with no image beats a crash.
        null
    }

    /**
     * A book cover tile — the only holder that runs the cover-decode machinery. Its fields
     * ([cover], [boundCacheKey], [job]) are exactly what [bindCover] and [BookGridAdapterBindTest]
     * reach for; the three other row types deliberately have none of them.
     */
    class BookTileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val author: TextView = view.findViewById(R.id.author)
        val status: TextView = view.findViewById(R.id.status)

        /**
         * The [coverCacheKey] this holder's [ImageView] currently reflects (or is waiting to) —
         * the full (path, mtime) key, not the bare path, so an in-flight decode of stale bytes
         * can never paint after a same-path rebind with a new mtime. Null for a coverless book.
         */
        var boundCacheKey: String? = null

        /** The in-flight decode for [boundCacheKey], if any — cancelled on recycle/rebind. */
        var job: Job? = null
    }

    /** A book in list mode: text only, no cover, no decode. */
    class BookRowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val author: TextView = view.findViewById(R.id.author)
        val size: TextView = view.findViewById(R.id.size)
        val status: TextView = view.findViewById(R.id.status)
    }

    /** A folder in either mode — the tile and list folder layouts share this one label field. */
    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.folder_label)
    }

    companion object {
        const val VIEW_TYPE_BOOK_TILE = 0
        const val VIEW_TYPE_FOLDER_TILE = 1
        const val VIEW_TYPE_BOOK_ROW = 2
        const val VIEW_TYPE_FOLDER_ROW = 3

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LibraryRow>() {
            override fun areItemsTheSame(oldItem: LibraryRow, newItem: LibraryRow): Boolean = when {
                oldItem is LibraryRow.Book && newItem is LibraryRow.Book ->
                    oldItem.entity.path == newItem.entity.path
                oldItem is LibraryRow.Folder && newItem is LibraryRow.Folder ->
                    oldItem.path == newItem.path
                else -> false
            }

            override fun areContentsTheSame(oldItem: LibraryRow, newItem: LibraryRow): Boolean =
                oldItem == newItem
        }
    }
}
