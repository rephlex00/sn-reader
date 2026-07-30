package dev.reader.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Proves [LibraryDatabase.MIGRATION_1_2] on a real SQLite database: it is the only thing standing
 * between a v1 `library.db` on the user's device and the v2 schema the updated app expects — a
 * wrong or missing migration crashes the library on first launch after an update.
 *
 * Builds the exact v1 `books` table (from `schemas/…/1.json`), populates it, runs the migration,
 * and checks that every existing row survives and the new `progressFraction` column is present and
 * defaults to null. A separate open of the real [LibraryDatabase] (BookDaoTest) validates the v2
 * entity against Room's generated schema, so together they cover both "the SQL preserves data" and
 * "the migrated shape matches what Room expects".
 */
@RunWith(RobolectricTestRunner::class)
class LibraryDatabaseMigrationTest {

    private val v1CreateSql =
        "CREATE TABLE IF NOT EXISTS `books` (`path` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
            "`modifiedAtMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `author` TEXT, " +
            "`coverPath` TEXT, `spineIndex` INTEGER NOT NULL, `charOffset` INTEGER NOT NULL, " +
            "`unreadable` INTEGER NOT NULL, `unreadableReason` TEXT, `addedAtMs` INTEGER NOT NULL, " +
            "`lastOpenedAtMs` INTEGER, PRIMARY KEY(`path`))"

    private fun openV1(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(v1CreateSql)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private val v2CreateSql =
        "CREATE TABLE IF NOT EXISTS `books` (`path` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
            "`modifiedAtMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `author` TEXT, " +
            "`coverPath` TEXT, `spineIndex` INTEGER NOT NULL, `charOffset` INTEGER NOT NULL, " +
            "`unreadable` INTEGER NOT NULL, `unreadableReason` TEXT, `addedAtMs` INTEGER NOT NULL, " +
            "`lastOpenedAtMs` INTEGER, `progressFraction` REAL, PRIMARY KEY(`path`))"

    private fun openV2(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(v2CreateSql)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migration 1 to 2 adds progressFraction as null and preserves existing rows`() {
        val db = openV1()
        db.execSQL(
            "INSERT INTO books (path, sizeBytes, modifiedAtMs, title, author, coverPath, spineIndex, " +
                "charOffset, unreadable, unreadableReason, addedAtMs, lastOpenedAtMs) " +
                "VALUES ('/a.epub', 100, 200, 'Kept Title', 'Kept Author', null, 3, 4521, 0, null, 5, 9000)",
        )

        LibraryDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT progressFraction, spineIndex, charOffset, title, author, lastOpenedAtMs FROM books WHERE path = '/a.epub'")
            .use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.isNull(0)).isTrue() // progressFraction: unset for a pre-v2 row
                assertThat(c.getInt(1)).isEqualTo(3) // position preserved
                assertThat(c.getInt(2)).isEqualTo(4521)
                assertThat(c.getString(3)).isEqualTo("Kept Title")
                assertThat(c.getString(4)).isEqualTo("Kept Author")
                assertThat(c.getLong(5)).isEqualTo(9000)
            }
        db.close()
    }

    @Test
    fun `after migration the new column is writable`() {
        val db = openV1()
        db.execSQL(
            "INSERT INTO books (path, sizeBytes, modifiedAtMs, title, spineIndex, charOffset, " +
                "unreadable, addedAtMs) VALUES ('/b.epub', 1, 2, 'B', 0, 0, 0, 3)",
        )
        LibraryDatabase.MIGRATION_1_2.migrate(db)

        db.execSQL("UPDATE books SET progressFraction = 0.5 WHERE path = '/b.epub'")

        db.query("SELECT progressFraction FROM books WHERE path = '/b.epub'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getFloat(0)).isEqualTo(0.5f)
        }
        db.close()
    }

    @Test
    fun `migration 2 to 3 creates the bookmarks table and preserves books`() {
        val db = openV2()
        db.execSQL(
            "INSERT INTO books (path, sizeBytes, modifiedAtMs, title, spineIndex, charOffset, " +
                "unreadable, addedAtMs, progressFraction) VALUES ('/a.epub', 1, 2, 'T', 0, 0, 0, 3, 0.4)",
        )

        LibraryDatabase.MIGRATION_2_3.migrate(db)

        // Books survive.
        db.query("SELECT progressFraction FROM books WHERE path = '/a.epub'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getFloat(0)).isEqualTo(0.4f)
        }
        // The bookmarks table now exists and accepts a row.
        db.execSQL(
            "INSERT INTO bookmarks (bookPath, spineIndex, charOffset, progressFraction, createdAtMs) " +
                "VALUES ('/a.epub', 2, 50, 0.5, 9)",
        )
        db.query("SELECT count(*) FROM bookmarks").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        db.close()
    }

    private val v3BooksCreateSql = v2CreateSql // books shape is unchanged v2→v3

    private val v3BookmarksCreateSql =
        "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`bookPath` TEXT NOT NULL, `spineIndex` INTEGER NOT NULL, `charOffset` INTEGER NOT NULL, " +
            "`progressFraction` REAL NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
            "FOREIGN KEY(`bookPath`) REFERENCES `books`(`path`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    private fun openV3(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v3BooksCreateSql)
                    db.execSQL(v3BookmarksCreateSql)
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookPath` ON `bookmarks` (`bookPath`)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migration 3 to 4 creates the highlights table and preserves books and bookmarks`() {
        val db = openV3()
        db.execSQL(
            "INSERT INTO books (path, sizeBytes, modifiedAtMs, title, spineIndex, charOffset, " +
                "unreadable, addedAtMs, progressFraction) VALUES ('/a.epub', 1, 2, 'T', 0, 0, 0, 3, 0.4)",
        )
        db.execSQL(
            "INSERT INTO bookmarks (bookPath, spineIndex, charOffset, progressFraction, createdAtMs) " +
                "VALUES ('/a.epub', 2, 50, 0.5, 9)",
        )

        LibraryDatabase.MIGRATION_3_4.migrate(db)

        // Books and bookmarks survive.
        db.query("SELECT progressFraction FROM books WHERE path = '/a.epub'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getFloat(0)).isEqualTo(0.4f)
        }
        db.query("SELECT count(*) FROM bookmarks").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        // The highlights table now exists and accepts a row.
        db.execSQL(
            "INSERT INTO highlights (bookPath, spineIndex, startOffset, endOffset, text, progressFraction, createdAtMs) " +
                "VALUES ('/a.epub', 2, 10, 40, 'hi', 0.5, 9)",
        )
        db.query("SELECT count(*) FROM highlights").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        db.close()
    }

    private val v4BooksCreateSql = v2CreateSql // books shape is unchanged v2->v4

    private val v4HighlightsCreateSql =
        "CREATE TABLE IF NOT EXISTS `highlights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`bookPath` TEXT NOT NULL, `spineIndex` INTEGER NOT NULL, `startOffset` INTEGER NOT NULL, " +
            "`endOffset` INTEGER NOT NULL, `text` TEXT NOT NULL, `progressFraction` REAL NOT NULL, " +
            "`createdAtMs` INTEGER NOT NULL, " +
            "FOREIGN KEY(`bookPath`) REFERENCES `books`(`path`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    private fun openV4(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v4BooksCreateSql)
                    db.execSQL(v3BookmarksCreateSql)
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookPath` ON `bookmarks` (`bookPath`)")
                    db.execSQL(v4HighlightsCreateSql)
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_bookPath` ON `highlights` (`bookPath`)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migration 4 to 5 adds rightToLeftOverride as null and preserves existing rows`() {
        val db = openV4()
        db.execSQL(
            "INSERT INTO books (path, sizeBytes, modifiedAtMs, title, spineIndex, charOffset, " +
                "unreadable, addedAtMs, progressFraction) VALUES ('/a.cbz', 1, 2, 'T', 0, 0, 0, 3, 0.4)",
        )

        LibraryDatabase.MIGRATION_4_5.migrate(db)

        db.query("SELECT rightToLeftOverride, progressFraction FROM books WHERE path = '/a.cbz'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue() // rightToLeftOverride: unset for a pre-v5 row
            assertThat(c.getFloat(1)).isEqualTo(0.4f) // existing column preserved
        }
        db.close()
    }

    @Test
    fun `after migration 4 to 5 the new column is writable`() {
        val db = openV4()
        db.execSQL(
            "INSERT INTO books (path, sizeBytes, modifiedAtMs, title, spineIndex, charOffset, " +
                "unreadable, addedAtMs) VALUES ('/b.cbz', 1, 2, 'B', 0, 0, 0, 3)",
        )
        LibraryDatabase.MIGRATION_4_5.migrate(db)

        db.execSQL("UPDATE books SET rightToLeftOverride = 1 WHERE path = '/b.cbz'")

        db.query("SELECT rightToLeftOverride FROM books WHERE path = '/b.cbz'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        db.close()
    }

    @Test
    fun `migration 5 to 6 adds excerpt as null and preserves existing marks`() {
        val db = openV4()
        LibraryDatabase.MIGRATION_4_5.migrate(db)
        db.execSQL(
            "INSERT INTO books (path, sizeBytes, modifiedAtMs, title, spineIndex, charOffset, " +
                "unreadable, addedAtMs) VALUES ('/a.epub', 1, 2, 'T', 0, 0, 0, 3)",
        )
        db.execSQL(
            "INSERT INTO bookmarks (bookPath, spineIndex, charOffset, progressFraction, createdAtMs) " +
                "VALUES ('/a.epub', 3, 120, 0.25, 9)",
        )

        LibraryDatabase.MIGRATION_5_6.migrate(db)

        db.query("SELECT excerpt, spineIndex, progressFraction FROM bookmarks").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue() // excerpt: unset for a pre-v6 mark
            assertThat(c.getInt(1)).isEqualTo(3)
            assertThat(c.getFloat(2)).isEqualTo(0.25f)
        }
        // And the new column is writable.
        db.execSQL("UPDATE bookmarks SET excerpt = 'It was the best of times…' WHERE spineIndex = 3")
        db.query("SELECT excerpt FROM bookmarks WHERE spineIndex = 3").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("It was the best of times…")
        }
        db.close()
    }
}
