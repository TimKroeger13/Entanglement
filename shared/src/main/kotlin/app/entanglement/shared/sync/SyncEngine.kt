package app.entanglement.shared.sync

import app.entanglement.shared.merge.ThreeWayMerge
import app.entanglement.shared.model.CreateDocRequest
import app.entanglement.shared.model.DocContent
import app.entanglement.shared.model.Observer
import app.entanglement.shared.model.PlacementRequest
import app.entanglement.shared.model.PushRequest
import app.entanglement.shared.text.LineEnding
import kotlinx.serialization.Serializable

/** The local copy of one document on this observer (a file on a PC, app storage on the phone). */
interface Replica {
    /** Current text, or `null` if the copy is gone (moved or deleted). */
    suspend fun read(): String?

    /** Writes [content] (LF line endings); the replica may convert line endings. */
    suspend fun write(content: String)
}

/** The last state this observer and the server agreed on. Needed for the three-way merge. */
@Serializable
data class Base(val version: Int, val content: String)

sealed interface Outcome {
    data object UpToDate : Outcome
    data object Pushed : Outcome
    data object Pulled : Outcome
    data class Merged(val superpositions: Int) : Outcome
    data object Lost : Outcome
}

data class SyncResult(val outcome: Outcome, val base: Base)

/**
 * Brings one document in line with the server.
 *
 * | local changed | server newer | action                                  |
 * |---------------|--------------|-----------------------------------------|
 * | no            | no           | nothing                                 |
 * | yes           | no           | push (rejected → merge)                 |
 * | no            | yes          | pull                                    |
 * | yes           | yes          | three-way merge, write locally, push    |
 *
 * The caller persists the returned [Base]. Network errors propagate; nothing is lost because the
 * local text stays and the old base is kept, so the next run simply repeats.
 */
class SyncEngine(
    private val api: RemoteApi,
    private val observer: Observer,
) {

    suspend fun sync(docId: String, remoteVersion: Int, base: Base, replica: Replica): SyncResult {
        val raw = replica.read() ?: return SyncResult(Outcome.Lost, base)
        val local = LineEnding.normalize(raw)
        val localChanged = local != base.content

        if (!localChanged && remoteVersion == base.version) return SyncResult(Outcome.UpToDate, base)

        if (!localChanged) {
            val current = api.get(docId)
            replica.write(current.content)
            return SyncResult(Outcome.Pulled, Base(current.version, current.content))
        }

        if (remoteVersion == base.version) {
            when (val result = api.push(docId, PushRequest(base.version, local, observer.id))) {
                is PushResult.Accepted -> return SyncResult(Outcome.Pushed, Base(result.doc.version, local))
                is PushResult.Rejected -> return mergeAndPush(docId, base, local, result.current, replica)
            }
        }

        return mergeAndPush(docId, base, local, api.get(docId), replica)
    }

    private suspend fun mergeAndPush(
        docId: String,
        base: Base,
        local: String,
        initialRemote: DocContent,
        replica: Replica,
    ): SyncResult {
        var remote = initialRemote
        repeat(MAX_MERGE_ATTEMPTS) {
            // The newest server version was written by this very observer (e.g. the answer to a push got lost
            // on the way back): the local text grew out of it, so it simply replaces it. Merging would compare
            // this observer with itself and could even produce a superposition "Phone vs Phone".
            val fastForward = remote.updatedBy.isNotEmpty() && remote.updatedBy == observer.id
            val merged = if (fastForward) {
                ThreeWayMerge.Result(local, 0)
            } else {
                ThreeWayMerge.merge(
                    base = base.content,
                    local = local,
                    remote = remote.content,
                    localLabel = observer.name,
                    remoteLabel = remote.updatedByName,
                )
            }
            if (!fastForward) replica.write(merged.text)
            val outcome = if (fastForward) Outcome.Pushed else Outcome.Merged(merged.superpositions)

            if (merged.text == remote.content) return SyncResult(outcome, Base(remote.version, remote.content))

            when (val result = api.push(docId, PushRequest(remote.version, merged.text, observer.id))) {
                is PushResult.Accepted -> return SyncResult(outcome, Base(result.doc.version, merged.text))
                is PushResult.Rejected -> remote = result.current
            }
        }
        // Server keeps changing under us; keep the old base so the next run merges again.
        return SyncResult(Outcome.Merged(0), base)
    }

    /** Entangles a new document that exists on this observer. Returns the first base. */
    suspend fun entangle(docId: String, name: String, content: String, path: String?): Base {
        val normalized = LineEnding.normalize(content)
        val created = api.create(CreateDocRequest(docId, name, normalized, observer.id, path))
        return Base(created.version, created.content)
    }

    /** Places a document that came from another observer on this one ("observes" it). */
    suspend fun place(docId: String, path: String?, replica: Replica): Base {
        val current = api.get(docId)
        replica.write(current.content)
        api.setPlacement(docId, PlacementRequest(observer.id, path))
        return Base(current.version, current.content)
    }

    private companion object {
        const val MAX_MERGE_ATTEMPTS = 5
    }
}
