package app.entanglement.shared.model

import kotlinx.serialization.Serializable

/** A device taking part in the entanglement (a PC, a laptop, the phone). */
@Serializable
data class Observer(
    val id: String,
    val name: String,
    val platform: Platform,
)

@Serializable
enum class Platform { DESKTOP, ANDROID }

/**
 * Where an entangled document lives on one observer. An observer without a placement has not
 * placed the document yet (it is "unobserved" there). `path == null` means it lives inside the app (phone).
 */
@Serializable
data class Placement(
    val observerId: String,
    val observerName: String,
    val platform: Platform,
    val path: String?,
)

/** Document metadata without content, as listed by the server. */
@Serializable
data class DocSummary(
    val id: String,
    val name: String,
    val version: Int,
    val updatedAt: Long,
    val updatedByName: String,
    val decohered: Boolean,
    /** Observer id of the last writer ("" from servers before 1.1). */
    val updatedBy: String = "",
    val placements: List<Placement>,
)

/** One document including its current text. Content always uses LF line endings. */
@Serializable
data class DocContent(
    val id: String,
    val name: String,
    val version: Int,
    val content: String,
    val updatedAt: Long,
    val updatedByName: String,
    /** Observer id of the last writer ("" from servers before 1.1). */
    val updatedBy: String = "",
)

@Serializable
data class RegisterObserverRequest(val name: String, val platform: Platform)

@Serializable
data class CreateDocRequest(
    val id: String,
    val name: String,
    val content: String,
    val observerId: String,
    val path: String?,
)

@Serializable
data class PushRequest(
    val baseVersion: Int,
    val content: String,
    val observerId: String,
)

@Serializable
data class RenameRequest(val name: String)

@Serializable
data class PlacementRequest(val observerId: String, val path: String?)
