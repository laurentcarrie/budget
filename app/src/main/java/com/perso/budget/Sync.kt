package com.perso.budget

import org.json.JSONObject
import java.io.File

/** A ticket as described by its <uid>.json object in S3. */
data class RemoteTicket(val uid: String, val date: String, val shop: String, val amount: Double, val deleted: Boolean, val updatedAt: Long)

/**
 * Two-way sync of the tickets through the S3 prefix: push what changed here, then pull what the other devices
 * pushed. Each ticket is <uid>.jpg + <uid>.json; a delete is a <uid>.json with deleted=true (no S3 delete needed).
 */
object Sync {
    data class Result(val sent: Int, val received: Int, val deleted: Int) {
        val changed get() = sent + received + deleted > 0
        override fun toString() = "sent $sent, received $received, deleted $deleted"
    }

    enum class Action { INSERT, TOMBSTONE, APPLY_UPDATE, APPLY_DELETE, KEEP }

    /** The remote tickets to download: unknown here, or whose JSON changed since we last saw it. Pairs of uid and etag. */
    fun toFetch(local: Map<String, Receipt>, remote: List<S3.Obj>): List<Pair<String, String>> =
        remote.filter { it.name.endsWith(".json") }
            .map { it.name.removeSuffix(".json") to it.etag }
            .filter { (uid, etag) -> local[uid]?.etag != etag }

    /** What to do with a downloaded ticket: the most recent change wins. */
    fun decide(local: Receipt?, remote: RemoteTicket): Action = when {
        local == null -> if (remote.deleted) Action.TOMBSTONE else Action.INSERT
        remote.updatedAt > local.updatedAt -> if (remote.deleted) Action.APPLY_DELETE else Action.APPLY_UPDATE
        else -> Action.KEEP
    }

    /** Tolerates the first JSON files, which had no uid, deleted or updatedAt. */
    fun parse(uid: String, json: String): RemoteTicket {
        val o = JSONObject(json)
        return RemoteTicket(uid, o.optString("date"), o.optString("shop"), o.optDouble("amount", 0.0),
            o.optBoolean("deleted", false), o.optLong("updatedAt", 0))
    }

    fun toJson(r: Receipt): String = JSONObject().put("uid", r.uid).put("date", r.date).put("shop", r.shop)
        .put("amount", r.amount).put("currency", "EUR").put("photo", "${r.uid}.jpg")
        .put("deleted", r.deleted).put("updatedAt", r.updatedAt).toString(2)

    /** Runs a full sync; throws on the first network or S3 error (what was done before stays done). */
    fun run(cfg: S3.Config, db: Db, dir: File): Result {
        var sent = 0; var received = 0; var deleted = 0

        for (r in db.pending()) {
            val photo = File(r.photo)
            if (!r.deleted && photo.exists()) S3.put(cfg, "${r.uid}.jpg", photo.readBytes(), "image/jpeg")
            val etag = S3.put(cfg, "${r.uid}.json", toJson(r).toByteArray(), "application/json")
            db.markSynced(r.id, etag)
            if (!r.deleted) sent++
        }

        val local = db.everything()
        for ((uid, etag) in toFetch(local, S3.list(cfg))) {
            val t = parse(uid, String(S3.get(cfg, "$uid.json"), Charsets.UTF_8))
            val mine = local[uid]
            when (decide(mine, t)) {
                Action.INSERT -> {
                    val photo = File(dir, "$uid.jpg")
                    val path = try { photo.writeBytes(S3.get(cfg, "$uid.jpg")); photo.absolutePath }
                    catch (e: java.io.IOException) { if (e.message?.startsWith("S3 404") == true) "" else throw e }
                    db.upsertRemote(uid, t.date, t.shop, t.amount, path, false, t.updatedAt, etag); received++
                }
                Action.TOMBSTONE -> db.upsertRemote(uid, t.date, t.shop, t.amount, "", true, t.updatedAt, etag)
                Action.APPLY_UPDATE -> { db.upsertRemote(uid, t.date, t.shop, t.amount, "", false, t.updatedAt, etag); received++ }
                Action.APPLY_DELETE -> {
                    db.upsertRemote(uid, t.date, t.shop, t.amount, "", true, t.updatedAt, etag)
                    mine?.photo?.takeIf { it.isNotEmpty() }?.let { File(it).delete() }
                    deleted++
                }
                Action.KEEP -> db.setEtag(uid, etag)
            }
        }
        return Result(sent, received, deleted)
    }
}
