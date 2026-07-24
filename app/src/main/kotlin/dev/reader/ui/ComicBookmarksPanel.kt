package dev.reader.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.reader.R
import dev.reader.data.BookmarkEntity

/** A minimal bookmarks list for comics: one tappable row per bookmarked page. */
class ComicBookmarksPanel(context: Context, private val onJump: (Int) -> Unit) : ScrollView(context) {
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(Color.WHITE)
        addView(list)
    }

    fun show(bookmarks: List<BookmarkEntity>) {
        list.removeAllViews()
        bookmarks.sortedBy { it.spineIndex }.forEach { bm ->
            list.addView(TextView(context).apply {
                text = context.getString(R.string.comic_bookmark_row, bm.spineIndex + 1)
                setTextColor(Color.BLACK); setPadding(24, 24, 24, 24); gravity = Gravity.START
                setOnClickListener { onJump(bm.spineIndex) }
            })
        }
    }
}
