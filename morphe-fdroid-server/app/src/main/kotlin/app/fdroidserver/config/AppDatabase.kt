package app.fdroidserver.config

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.concurrent.Executors

/**
 * Opens the SQLite database (replacing [ConfigStore]/`JsonCacheStore`'s file
 * load/save), creates the schema on first run, and seeds the single
 * [Settings] row.
 *
 * All reads/writes are confined to one dedicated single-thread executor
 * (rather than whatever coroutine dispatcher the caller happens to be on).
 * This is the direct replacement for `ConfigStore`'s `Mutex` - SQLite only
 * safely supports one writer at a time, and the same "the admin page fires
 * several concurrent API requests on load" concurrency hazard documented on
 * the old `ConfigStore`/`update()` applies here too. Pinning every
 * transaction to one thread serializes them for free without needing a
 * separate lock on top of Exposed's own transaction handling.
 *
 * [dbDir] is a *directory* (matching the mounted Docker volume/`DB_PATH`
 * env var), not a file - the database file itself (`app.db`) is created
 * inside it on first run if it doesn't already exist yet.
 */
class AppDatabase(dbDir: File) {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "db-writer").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val db: Database

    init {
        dbDir.mkdirs()
        val dbFile = File(dbDir, "app.db")
        db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction(db) {
            // createMissingTablesAndColumns (not just create) so existing
            // SQLite databases from before a column was added (e.g.
            // include_experimental_versions) get it added via ALTER TABLE
            // instead of silently missing it.
            SchemaUtils.createMissingTablesAndColumns(
                Settings, GithubRepos, GithubCheckedReleases,
                PatchLibraryTable, PatchTargets, PatchAttachments, PatchCheckedEntries,
            )
            if (Settings.selectAll().empty()) {
                Settings.insert { it[id] = 1 }
            }
        }
    }

    /** Runs [block] as one transaction on the dedicated DB thread. Every
     * read-modify-write call site in [AppConfig] should do its work inside a
     * single [tx] call (mirroring how `ConfigStore.update` required one
     * locked load+mutate+save), not several separate ones, to avoid
     * lost-update races between concurrent admin API requests. */
    suspend fun <T> tx(block: () -> T): T = withContext(dispatcher) {
        transaction(db) { block() }
    }
}
