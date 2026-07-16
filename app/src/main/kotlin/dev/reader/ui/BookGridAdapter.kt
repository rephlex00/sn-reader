package dev.reader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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

/**
 * What the grid shows under a book's title, or null to show nothing.
 *
 * The library index ([BookEntity]) has no stored "total chapters"/spine-size column — the grid
 * never opens a book to learn one, since that would mean re-parsing every EPUB on every emission
 * just to render a fraction, defeating the whole point of an indexed library. So this reports
 * what the index actually has: whether the book has ever been opened, and how far into it
 * [BookEntity.spineIndex]/[BookEntity.charOffset] (both written by the reader, once Task 6 wires
 * position memory) say the reader got. A book that has never been opened shows nothing at all —
 * "reading progress if the book has been opened", per the brief — rather than a misleading "0%".
 */
fun progressLabel(lastOpenedAtMs: Long?, spineIndex: Int, charOffset: Int): String? {
    if (lastOpenedAtMs == null) return null
    return if (spineIndex == 0 && charOffset == 0) "Just started" else "Chapter ${spineIndex + 1}"
}

/**
 * The in-memory bitmap cache key for one cover. Keyed on [modifiedAtMs] as well as [coverPath] so
 * that if a cover is ever regenerated in place at the same path (not expected mid-session — the
 * indexer only runs once on library entry — but cheap insurance), a stale decoded bitmap from an
 * earlier key never resurfaces; [BookGridAdapter] simply decodes again under the new key.
 */
fun coverCacheKey(coverPath: String, modifiedAtMs: Long): String = "$coverPath@$modifiedAtMs"

/** Bitmaps held at once — comfortably covers a 15-book library with headroom for a larger one. */
private const val BITMAP_CACHE_SIZE = 60

/**
 * The library grid. Views only, `RecyclerView` + `GridLayoutManager` (set up by
 * [LibraryActivity]) — no Compose, no image library. Covers decode from [BookEntity.coverPath] on
 * a background dispatcher and are cached in a small [LruCache], bound by path so a slow decode
 * that finishes after its holder has been recycled for a different book can never paint into the
 * wrong cell (the classic RecyclerView async-bind bug): [onBindViewHolder] stamps
 * [ViewHolder.boundCoverPath] before launching the decode, and the coroutine checks it again
 * before calling [ImageView.setImageBitmap].
 *
 * [scope] is expected to be an Activity's `lifecycleScope`: decode jobs are children of it, so
 * they are cancelled automatically if the Activity is destroyed mid-decode, on top of the
 * per-holder cancellation this adapter already does on recycle/rebind.
 */
class BookGridAdapter(
    private val scope: CoroutineScope,
    private val onClick: (BookEntity) -> Unit,
) : ListAdapter<BookEntity, BookGridAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val bitmapCache = LruCache<String, Bitmap>(BITMAP_CACHE_SIZE)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val book = getItem(position)

        // A rebind (view reused for a different list position without going through
        // onViewRecycled — e.g. a DiffUtil move) must cancel any decode still in flight for
        // whatever this holder was previously bound to, same as onViewRecycled does.
        holder.job?.cancel()
        holder.job = null

        holder.title.text = book.title
        holder.author.text = book.author ?: ""
        holder.itemView.setOnClickListener { onClick(book) }

        holder.status.text = when {
            book.unreadable -> book.unreadableReason ?: "Unreadable"
            else -> progressLabel(book.lastOpenedAtMs, book.spineIndex, book.charOffset)
        }
        holder.status.visibility = if (holder.status.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        bindCover(holder, book)
    }

    private fun bindCover(holder: ViewHolder, book: BookEntity) {
        val path = book.coverPath
        holder.boundCoverPath = path
        if (path == null) {
            holder.cover.setImageDrawable(null)
            return
        }

        val cacheKey = coverCacheKey(path, book.modifiedAtMs)
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
                // The holder may have been rebound to a different book while this decode was
                // in flight; only paint if it is still bound to the path this decode was for.
                if (holder.boundCoverPath == path) {
                    holder.cover.setImageBitmap(bitmap)
                }
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.job?.cancel()
        holder.job = null
    }

    private fun decodeCover(path: String): Bitmap? = try {
        BitmapFactory.decodeFile(path)
    } catch (e: Exception) {
        // A cover file can vanish or corrupt between index time and bind time (e.g. the user
        // clears app storage of covers by hand); a grid cell with no image beats a crash.
        null
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val author: TextView = view.findViewById(R.id.author)
        val status: TextView = view.findViewById(R.id.status)

        /** The cover path this holder's [ImageView] currently reflects (or is waiting to). */
        var boundCoverPath: String? = null

        /** The in-flight decode for [boundCoverPath], if any — cancelled on recycle/rebind. */
        var job: Job? = null
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BookEntity>() {
            override fun areItemsTheSame(oldItem: BookEntity, newItem: BookEntity) =
                oldItem.path == newItem.path

            override fun areContentsTheSame(oldItem: BookEntity, newItem: BookEntity) =
                oldItem == newItem
        }
    }
}
