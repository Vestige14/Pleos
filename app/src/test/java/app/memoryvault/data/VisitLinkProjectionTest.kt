package app.memoryvault.data
import app.memoryvault.domain.*
import app.memoryvault.memory.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
class VisitLinkProjectionTest {
    private fun link(status: String = "CONFIRMED", kind: MemoryKind = MemoryKind.VISIT): MemoryAssociationLink {
        val source = MemorySource(MemoryCandidate("v1", kind, "2026-05-15", MemoryTimeBasis.VISIT,
            "", null, null, "더현대 서울", null, null), "story-gift", "fingerprint")
        return MemoryAssociationLink(17, "v1", kind.name, 0, 12, "USER", status,
            MemoryWire.json.encodeToString(source), null)
    }
    @Test fun confirmedVisitProjectsOriginalNoteIdWithoutChangingIt() {
        val result = projectVisitLinks(emptyList(), listOf(link()))
        assertEquals(1, result.size); assertEquals(17L,result.single().noteId)
        assertEquals("story-gift",result.single().visitId); assertEquals(LinkOrigin.USER,result.single().origin)
        assertEquals(NoteLinkStatus.CONFIRMED,result.single().status)
    }
    @Test fun rejectedAndNonVisitSourcesNeverBecomeVisitCards() {
        assertTrue(projectVisitLinks(emptyList(),listOf(link("REJECTED"),link(kind=MemoryKind.NOTE))).isEmpty())
    }
    @Test fun corruptAssociationDoesNotDiscardExistingLinks() {
        val existing=NoteVisitLink(1,"v1",0,2,LinkOrigin.USER,NoteLinkStatus.CONFIRMED)
        assertEquals(listOf(existing),projectVisitLinks(listOf(existing),listOf(link().copy(sourceJson="broken"))))
    }
}
