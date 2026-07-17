package dev.reader.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BookEntity::class], version = 2, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        /**
         * v1 -> v2: adds [BookEntity.progressFraction], the byte-weighted whole-book reading
         * position the library card shows as a percentage. Purely additive — a nullable column
         * with no default — so every existing row keeps its data and simply reads `null` progress
         * until the reader next writes it on a page turn. Registered in
         * [dev.reader.ReaderApplication]'s database builder; without it Room would refuse to open a
         * v1 `library.db` under the v2 schema and crash the library on launch.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN progressFraction REAL")
            }
        }
    }
}
