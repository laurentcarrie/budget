package com.perso.budget

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * [uid] is the object name in S3 (the photo base name, "ticket_<epochMs>_<device>"), the same on every device.
 * [uploaded] means "in sync with S3". A delete is kept as a [deleted] row so it can be pushed to the other devices.
 */
data class Receipt(
    val id: Long, val uid: String, val date: String, val shop: String, val amount: Double, val photo: String,
    val uploaded: Boolean = false, val updatedAt: Long = 0, val deleted: Boolean = false, val etag: String? = null
)

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "budget.db", null, 3) {
    private val cols = "id,uid,date,shop,amount,photo,uploaded,updated_at,deleted,etag"

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE receipt(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, shop TEXT, amount REAL, photo TEXT, " +
            "uploaded INTEGER DEFAULT 0, uid TEXT, updated_at INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0, etag TEXT)")
        db.execSQL("CREATE UNIQUE INDEX receipt_uid ON receipt(uid)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) db.execSQL("ALTER TABLE receipt ADD COLUMN uploaded INTEGER DEFAULT 0")
        if (o < 3) {
            db.execSQL("ALTER TABLE receipt ADD COLUMN uid TEXT")
            db.execSQL("ALTER TABLE receipt ADD COLUMN updated_at INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE receipt ADD COLUMN deleted INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE receipt ADD COLUMN etag TEXT")
            // The photo base name is already the S3 object name of the tickets uploaded so far.
            val seen = mutableSetOf<String>()
            val rows = mutableListOf<Pair<Long, String>>()
            db.rawQuery("SELECT id,photo FROM receipt ORDER BY id", null).use { while (it.moveToNext()) rows += it.getLong(0) to (it.getString(1) ?: "") }
            for ((id, photo) in rows) {
                val uid = File(photo).nameWithoutExtension.ifEmpty { "ticket_old_$id" }
                if (seen.add(uid)) db.execSQL("UPDATE receipt SET uid=? WHERE id=?", arrayOf(uid, id))
                else db.execSQL("UPDATE receipt SET uid=?, deleted=1 WHERE id=?", arrayOf("${uid}_dup$id", id)) // same photo twice
            }
            db.execSQL("CREATE UNIQUE INDEX receipt_uid ON receipt(uid)")
        }
    }

    private fun read(c: Cursor) = Receipt(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
        c.getDouble(4), c.getString(5) ?: "", c.getInt(6) == 1, c.getLong(7), c.getInt(8) == 1, c.getString(9))

    private fun query(where: String, order: String = "id"): List<Receipt> {
        val out = mutableListOf<Receipt>()
        readableDatabase.rawQuery("SELECT $cols FROM receipt WHERE $where ORDER BY $order", null).use { while (it.moveToNext()) out += read(it) }
        return out
    }

    /** The tickets to show, most recent first. */
    fun all(): List<Receipt> = query("deleted=0", "date DESC, id DESC")

    /** Every row, deleted ones included, by uid. */
    fun everything(): Map<String, Receipt> = query("1=1").associateBy { it.uid }

    /** Rows whose latest state is not in S3 yet. */
    fun pending(): List<Receipt> = query("uploaded=0")

    /** The shops of the saved tickets, the most used first. */
    fun shops(): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT shop FROM receipt WHERE deleted=0 GROUP BY shop ORDER BY COUNT(*) DESC, shop", null).use { while (it.moveToNext()) out += it.getString(0) }
        return out
    }

    /** False when a ticket with this uid is already saved. */
    fun insert(uid: String, date: String, shop: String, amount: Double, photo: String): Boolean =
        writableDatabase.insertWithOnConflict("receipt", null, ContentValues().apply {
            put("uid", uid); put("date", date); put("shop", shop); put("amount", amount); put("photo", photo)
            put("uploaded", 0); put("updated_at", System.currentTimeMillis()); put("deleted", 0)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    /** A manual correction: marks the ticket as not in sync so the next sync pushes it. */
    fun update(id: Long, date: String, shop: String, amount: Double) {
        writableDatabase.execSQL("UPDATE receipt SET date=?, shop=?, amount=?, uploaded=0, updated_at=? WHERE id=?",
            arrayOf(date, shop, amount, System.currentTimeMillis(), id))
    }

    /** Hides the ticket and keeps a deleted row to tell the other devices. */
    fun softDelete(id: Long) {
        writableDatabase.execSQL("UPDATE receipt SET deleted=1, uploaded=0, updated_at=? WHERE id=?", arrayOf(System.currentTimeMillis(), id))
    }

    fun markSynced(id: Long, etag: String?) {
        writableDatabase.execSQL("UPDATE receipt SET uploaded=1, etag=? WHERE id=?", arrayOf(etag, id))
    }

    fun setEtag(uid: String, etag: String) {
        writableDatabase.execSQL("UPDATE receipt SET etag=? WHERE uid=?", arrayOf(etag, uid))
    }

    /** Stores a ticket received from S3 (insert or update), already in sync. */
    fun upsertRemote(uid: String, date: String, shop: String, amount: Double, photo: String, deleted: Boolean, updatedAt: Long, etag: String) {
        val v = ContentValues().apply {
            put("date", date); put("shop", shop); put("amount", amount); put("deleted", if (deleted) 1 else 0)
            put("updated_at", updatedAt); put("uploaded", 1); put("etag", etag)
            if (photo.isNotEmpty()) put("photo", photo)
        }
        if (writableDatabase.update("receipt", v, "uid=?", arrayOf(uid)) == 0) {
            v.put("uid", uid); if (!v.containsKey("photo")) v.put("photo", "")
            writableDatabase.insert("receipt", null, v)
        }
    }
}
