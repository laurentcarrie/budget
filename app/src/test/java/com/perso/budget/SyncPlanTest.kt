package com.perso.budget

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlanTest {
    private fun local(uid: String, etag: String? = "e1", updatedAt: Long = 100, deleted: Boolean = false) =
        Receipt(1, uid, "2026-11-19 18:52", "THE FROG", 7.0, "/f/$uid.jpg", true, updatedAt, deleted, etag)

    private fun remote(uid: String, updatedAt: Long, deleted: Boolean = false) =
        RemoteTicket(uid, "2026-11-19 18:52", "THE FROG", 7.0, deleted, updatedAt)

    @Test fun fetchesUnknownAndChangedJsonOnly() {
        val known = mapOf("a" to local("a", "e1"), "b" to local("b", "e2"))
        val listing = listOf(S3.Obj("a.json", "e1"), S3.Obj("b.json", "CHANGED"), S3.Obj("c.json", "e3"), S3.Obj("c.jpg", "zz"))
        assertEquals(listOf("b" to "CHANGED", "c" to "e3"), Sync.toFetch(known, listing))
    }

    @Test fun aRowWithoutEtagIsFetchedOnce() =
        assertEquals(listOf("a" to "e1"), Sync.toFetch(mapOf("a" to local("a", null)), listOf(S3.Obj("a.json", "e1"))))

    @Test fun unknownTicketIsInserted() = assertEquals(Sync.Action.INSERT, Sync.decide(null, remote("a", 5)))

    @Test fun unknownDeletedTicketBecomesATombstone() = assertEquals(Sync.Action.TOMBSTONE, Sync.decide(null, remote("a", 5, true)))

    @Test fun newerRemoteDeleteIsApplied() = assertEquals(Sync.Action.APPLY_DELETE, Sync.decide(local("a", updatedAt = 100), remote("a", 200, true)))

    @Test fun newerRemoteUpdateIsApplied() = assertEquals(Sync.Action.APPLY_UPDATE, Sync.decide(local("a", updatedAt = 100), remote("a", 200)))

    @Test fun sameOrOlderRemoteIsKept() {
        assertEquals(Sync.Action.KEEP, Sync.decide(local("a", updatedAt = 100), remote("a", 100)))
        assertEquals(Sync.Action.KEEP, Sync.decide(local("a", updatedAt = 300), remote("a", 200, true)))
    }

    @Test fun firstVersionJsonWithoutNewFieldsIsRead() {
        val t = Sync.parse("ticket_1795276091330", """{"date":"2026-11-20 23:52","shop":"CARBONE 14","amount":14,"currency":"EUR","photo":"ticket_1795276091330.jpg"}""")
        assertEquals(RemoteTicket("ticket_1795276091330", "2026-11-20 23:52", "CARBONE 14", 14.0, false, 0), t)
        assertEquals(Sync.Action.INSERT, Sync.decide(null, t))
        assertEquals(Sync.Action.KEEP, Sync.decide(local("ticket_1795276091330", updatedAt = 0), t))
    }

    @Test fun jsonRoundTrip() {
        val r = local("ticket_9_ab12", updatedAt = 1234, deleted = true)
        assertEquals(remote("ticket_9_ab12", 1234, true), Sync.parse(r.uid, Sync.toJson(r)))
    }

    @Test fun descriptionTravelsInTheJson() {
        val r = local("ticket_9_ab12", updatedAt = 1234).copy(description = "Anniversaire de L\u00e9a, \"cadeau\"")
        val t = Sync.parse(r.uid, Sync.toJson(r))
        assertEquals(r.description, t.description)
        assertEquals(RemoteTicket("ticket_9_ab12", "2026-11-19 18:52", "THE FROG", 7.0, false, 1234, true, r.description), t)
    }

    @Test fun jsonWithoutDescriptionGivesAnEmptyOne() {
        val t = Sync.parse("a", """{"date":"2026-11-20 23:52","shop":"CARBONE 14","amount":14,"deleted":false,"updatedAt":5}""")
        assertEquals("", t.description)
    }

    @Test fun aNewerRemoteDescriptionIsApplied() {
        val mine = local("a", updatedAt = 100)
        val theirs = remote("a", 200).copy(description = "avec Paul")
        assertEquals(Sync.Action.APPLY_UPDATE, Sync.decide(mine, theirs))
    }

    @Test fun categoryTravelsInTheJson() {
        val r = local("ticket_9_ab12", updatedAt = 1234).copy(category = "p\u00e9age")
        assertEquals("p\u00e9age", Sync.parse(r.uid, Sync.toJson(r)).category)
    }

    @Test fun jsonWithoutACategoryGivesAnEmptyOne() =
        assertEquals("", Sync.parse("a", """{"date":"2026-11-20 23:52","shop":"CARBONE 14","amount":14,"updatedAt":5}""").category)
}
