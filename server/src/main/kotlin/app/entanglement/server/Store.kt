package app.entanglement.server

import app.entanglement.shared.model.CreateDocRequest
import app.entanglement.shared.model.DocContent
import app.entanglement.shared.model.DocSummary
import app.entanglement.shared.model.Placement
import app.entanglement.shared.model.Platform
import app.entanglement.shared.model.PushRequest
import app.entanglement.shared.model.RegisterObserverRequest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class NotFoundException(message: String) : Exception(message)
class GoneException(message: String) : Exception(message)

sealed interface PushOutcome {
    data class Accepted(val doc: DocContent) : PushOutcome
    data class Stale(val current: DocContent) : PushOutcome
}

/**
 * SQLite storage. One connection, all access serialized: a single user with a handful of
 * devices never needs more, and it keeps the version check race-free.
 */
class Store(jdbcUrl: String, private val keepVersions: Int = 50, private val clock: () -> Long = System::currentTimeMillis) :
    AutoCloseable {

    private val conn: Connection = DriverManager.getConnection(jdbcUrl)

    init {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.execute(
                """CREATE TABLE IF NOT EXISTS observers(
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, platform TEXT NOT NULL, last_seen INTEGER NOT NULL)""",
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS documents(
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, version INTEGER NOT NULL, content TEXT NOT NULL,
                    updated_at INTEGER NOT NULL, updated_by TEXT NOT NULL, decohered INTEGER NOT NULL DEFAULT 0)""",
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS versions(
                    doc_id TEXT NOT NULL REFERENCES documents(id), version INTEGER NOT NULL, content TEXT NOT NULL,
                    observer_id TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(doc_id, version))""",
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS placements(
                    doc_id TEXT NOT NULL REFERENCES documents(id), observer_id TEXT NOT NULL REFERENCES observers(id),
                    path TEXT, PRIMARY KEY(doc_id, observer_id))""",
            )
        }
    }

    @Synchronized
    fun registerObserver(id: String, request: RegisterObserverRequest) {
        conn.prepareStatement(
            """INSERT INTO observers(id, name, platform, last_seen) VALUES(?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET name=excluded.name, platform=excluded.platform, last_seen=excluded.last_seen""",
        ).use {
            it.setString(1, id)
            it.setString(2, request.name)
            it.setString(3, request.platform.name)
            it.setLong(4, clock())
            it.executeUpdate()
        }
    }

    @Synchronized
    fun list(): List<DocSummary> {
        val placements = mutableMapOf<String, MutableList<Placement>>()
        conn.prepareStatement(
            """SELECT p.doc_id, p.observer_id, o.name, o.platform, p.path
               FROM placements p JOIN observers o ON o.id = p.observer_id ORDER BY o.name""",
        ).use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    placements.getOrPut(rs.getString(1)) { mutableListOf() } += Placement(
                        observerId = rs.getString(2),
                        observerName = rs.getString(3),
                        platform = Platform.valueOf(rs.getString(4)),
                        path = rs.getString(5),
                    )
                }
            }
        }
        return conn.prepareStatement(
            """SELECT d.id, d.name, d.version, d.updated_at, COALESCE(o.name, d.updated_by), d.decohered
               FROM documents d LEFT JOIN observers o ON o.id = d.updated_by ORDER BY d.name COLLATE NOCASE""",
        ).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getString(1)
                        add(
                            DocSummary(
                                id = id,
                                name = rs.getString(2),
                                version = rs.getInt(3),
                                updatedAt = rs.getLong(4),
                                updatedByName = rs.getString(5),
                                decohered = rs.getInt(6) != 0,
                                placements = placements[id].orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    fun get(id: String): DocContent = find(id) ?: throw NotFoundException("No document $id")

    /** Idempotent: creating an id that already exists returns the existing document (safe client retries). */
    @Synchronized
    fun create(request: CreateDocRequest): DocContent {
        find(request.id)?.let { return it }
        requireObserver(request.observerId)
        val now = clock()
        transaction {
            conn.prepareStatement(
                "INSERT INTO documents(id, name, version, content, updated_at, updated_by) VALUES(?,?,1,?,?,?)",
            ).use {
                it.setString(1, request.id)
                it.setString(2, request.name)
                it.setString(3, request.content)
                it.setLong(4, now)
                it.setString(5, request.observerId)
                it.executeUpdate()
            }
            insertVersion(request.id, 1, request.content, request.observerId, now)
            upsertPlacement(request.id, request.observerId, request.path)
        }
        return get(request.id)
    }

    @Synchronized
    fun push(id: String, request: PushRequest): PushOutcome {
        val current = find(id) ?: throw NotFoundException("No document $id")
        if (isDecohered(id)) throw GoneException("Document $id is decohered")
        if (request.baseVersion != current.version) return PushOutcome.Stale(current)
        if (request.content == current.content) return PushOutcome.Accepted(current)
        requireObserver(request.observerId)

        val next = current.version + 1
        val now = clock()
        transaction {
            conn.prepareStatement(
                "UPDATE documents SET version=?, content=?, updated_at=?, updated_by=? WHERE id=? AND version=?",
            ).use {
                it.setInt(1, next)
                it.setString(2, request.content)
                it.setLong(3, now)
                it.setString(4, request.observerId)
                it.setString(5, id)
                it.setInt(6, current.version)
                it.executeUpdate()
            }
            insertVersion(id, next, request.content, request.observerId, now)
            conn.prepareStatement("DELETE FROM versions WHERE doc_id=? AND version<=?").use {
                it.setString(1, id)
                it.setInt(2, next - keepVersions)
                it.executeUpdate()
            }
        }
        return PushOutcome.Accepted(get(id))
    }

    @Synchronized
    fun setPlacement(docId: String, observerId: String, path: String?) {
        find(docId) ?: throw NotFoundException("No document $docId")
        requireObserver(observerId)
        upsertPlacement(docId, observerId, path)
    }

    @Synchronized
    fun decohere(id: String) {
        find(id) ?: throw NotFoundException("No document $id")
        conn.prepareStatement("UPDATE documents SET decohered=1, updated_at=? WHERE id=?").use {
            it.setLong(1, clock())
            it.setString(2, id)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun versionCount(docId: String): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM versions WHERE doc_id=?").use {
            it.setString(1, docId)
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    override fun close() = conn.close()

    private fun find(id: String): DocContent? =
        conn.prepareStatement(
            """SELECT d.id, d.name, d.version, d.content, d.updated_at, COALESCE(o.name, d.updated_by)
               FROM documents d LEFT JOIN observers o ON o.id = d.updated_by WHERE d.id=?""",
        ).use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toDoc() else null }
        }

    private fun isDecohered(id: String): Boolean =
        conn.prepareStatement("SELECT decohered FROM documents WHERE id=?").use {
            it.setString(1, id)
            it.executeQuery().use { rs -> rs.next() && rs.getInt(1) != 0 }
        }

    private fun requireObserver(id: String) {
        val known = conn.prepareStatement("SELECT 1 FROM observers WHERE id=?").use {
            it.setString(1, id)
            it.executeQuery().use(ResultSet::next)
        }
        if (!known) throw IllegalArgumentException("Unknown observer $id – register it first")
    }

    private fun insertVersion(docId: String, version: Int, content: String, observerId: String, at: Long) {
        conn.prepareStatement(
            "INSERT INTO versions(doc_id, version, content, observer_id, created_at) VALUES(?,?,?,?,?)",
        ).use {
            it.setString(1, docId)
            it.setInt(2, version)
            it.setString(3, content)
            it.setString(4, observerId)
            it.setLong(5, at)
            it.executeUpdate()
        }
    }

    private fun upsertPlacement(docId: String, observerId: String, path: String?) {
        conn.prepareStatement(
            """INSERT INTO placements(doc_id, observer_id, path) VALUES(?,?,?)
               ON CONFLICT(doc_id, observer_id) DO UPDATE SET path=excluded.path""",
        ).use {
            it.setString(1, docId)
            it.setString(2, observerId)
            it.setString(3, path)
            it.executeUpdate()
        }
    }

    private inline fun transaction(block: () -> Unit) {
        conn.autoCommit = false
        try {
            block()
            conn.commit()
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun ResultSet.toDoc() = DocContent(
        id = getString(1),
        name = getString(2),
        version = getInt(3),
        content = getString(4),
        updatedAt = getLong(5),
        updatedByName = getString(6),
    )
}
