package app.memoryvault.data

import app.memoryvault.memory.*
import app.memoryvault.domain.LinkOrigin
import app.memoryvault.domain.NoteLinkStatus

/** Read-only projection: an explicit cross-date connection keeps its original note identity. */
internal fun projectVisitLinks(legacy: List<NoteVisitLink>, associations: List<MemoryAssociationLink>): List<NoteVisitLink> =
    (legacy + associations.mapNotNull { link ->
        if (link.kind != "VISIT" || link.status !in setOf("CONFIRMED", "SUGGESTED")) return@mapNotNull null
        runCatching {
            val source = MemoryWire.json.decodeFromString<MemorySource>(link.sourceJson)
            if (source.candidate.kind != MemoryKind.VISIT || source.localRef.isBlank()) null else
                NoteVisitLink(link.noteId, source.localRef, link.startCp, link.endCp,
                    LinkOrigin.valueOf(link.origin), NoteLinkStatus.valueOf(link.status))
        }.getOrNull()
    }).distinctBy { listOf(it.noteId, it.visitId, it.startCp, it.endCp, it.origin) }
