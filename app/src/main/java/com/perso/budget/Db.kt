package com.perso.budget

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Receipt(val id: Long, val date: String, val shop: String, val amount: Double, val photo: String, val uploaded: Boolean = false)

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "budget.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE receipt(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, shop TEXT, amount REAL, photo TEXT, uploaded INTEGER DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) db.execSQL("ALTER TABLE receipt ADD COLUMN uploaded INTEGER DEFAULT 0")
    }
    fun markUploaded(id: Long) { writableDatabase.execSQL("UPDATE receipt SET uploaded=1 WHERE id=?", arrayOf(id)) }

    fun insert(date: String, shop: String, amount: Double, photo: String) {
        writableDatabase.insert("receipt", null, ContentValues().apply {
            put("date", date); put("shop", shop); put("amount", amount); put("photo", photo)
        })
    }
    fun shops(): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT DISTINCT shop FROM receipt", null).use { while (it.moveToNext()) out += it.getString(0) }
        return out
    }
    fun delete(id: Long) { writableDatabase.delete("receipt", "id=?", arrayOf(id.toString())) }
    fun all(): List<Receipt> {
        val out = mutableListOf<Receipt>()
        readableDatabase.rawQuery("SELECT id,date,shop,amount,photo,uploaded FROM receipt ORDER BY id DESC", null).use {
            while (it.moveToNext()) out += Receipt(it.getLong(0), it.getString(1), it.getString(2), it.getDouble(3), it.getString(4), it.getInt(5) == 1)
        }
        return out
    }
}
