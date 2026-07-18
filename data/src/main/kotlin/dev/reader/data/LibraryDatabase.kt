package dev.reader.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BookEntity::class, BookmarkEntity::class], version = 3, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        /**
         * v1 -> v2: adds [BookEntity.progressFraction] (see git history). Purely additive.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN progressFraction REAL")
            }
        }

        /**
         * v2 -> v3: creates the `bookmarks` table (page-level bookmarks). Purely additive — no
         * existing row is touched. The CREATE statements MUST match Room's generated v3 schema
         * (`schemas/…/3.json`) exactly, or Room's runtime schema validation refuses to open a
         * migrated DB. Registered in [dev.reader.ReaderApplication]'s builder.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`bookPath` TEXT NOT NULL, `spineIndex` INTEGER NOT NULL, " +
                        "`charOffset` INTEGER NOT NULL, `progressFraction` REAL NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`bookPath`) REFERENCES `books`(`path`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmarks_bookPath` ON `bookmarks` (`bookPath`)",
                )
            }
        }
    }
}
