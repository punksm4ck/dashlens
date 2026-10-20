package app.dashlens

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One logged chunk of time. state is "ONLINE" (online, no order) or "ORDER". */
data class Seg(val startMs: Long, val endMs: Long, val state: String, val meters: Double)

data class DayRow(val key: String, val orderM: Double, val idleM: Double, val orderMs: Long, val idleMs: Long)

data class Totals(val orderM: Double, val idleM: Double, val orderMs: Long, val idleMs: Long) {
    val onlineM: Double get() = orderM + idleM
    val onlineMs: Long get() = orderMs + idleMs
}

/** Every offer the overlay analysed. outcome: PENDING, ACCEPTED, PASSED or UNKNOWN. lat/lon 0,0 = unknown. */
data class OfferRow(
    val id: Long, val ts: Long, val pay: Double, val miles: Double, val minutes: Double, val pickups: Int,
    val verdict: String, val netHr: Double, val perMile: Double, val outcome: String, val lat: Double, val lon: Double,
    val merchant: String = "", val actualMin: Double = 0.0, val actualMi: Double = 0.0,
    val windowMin: Double = 0.0, val tipsIncl: Boolean = false
) {
    val hasLoc: Boolean get() = lat != 0.0 || lon != 0.0
}

/** What you have actually experienced at one merchant. */
data class MerchantStat(val name: String, val seen: Int, val accepted: Int, val done: Int, val avgPay: Double, val avgMin: Double, val avgMi: Double) {
    /** Realised $/hr over completed orders, using [costMi] per mile. */
    fun hourly(costMi: Double): Double = if (avgMin > 0.0) (avgPay - costMi * avgMi) / (avgMin / 60.0) else 0.0
}

data class Earning(val id: Long, val ts: Long, val amount: Double, val platform: String, val kind: String, val note: String)

data class Expense(val id: Long, val ts: Long, val amount: Double, val category: String, val note: String, val receipt: String)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "dashlens.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE seg(id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, " +
                "end_ms INTEGER NOT NULL, state TEXT NOT NULL, meters REAL NOT NULL)"
        )
        db.execSQL("CREATE INDEX seg_start ON seg(start_ms)")
        createV2(db)
        addV3(db)
    }

    private fun addV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE offer ADD COLUMN merchant TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE offer ADD COLUMN actual_min REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE offer ADD COLUMN actual_mi REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE offer ADD COLUMN window_min REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE offer ADD COLUMN tips_incl INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS offer_merchant ON offer(merchant)")
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS offer(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "pay REAL NOT NULL, miles REAL NOT NULL, minutes REAL NOT NULL, pickups INTEGER NOT NULL, " +
                "verdict TEXT NOT NULL, net_hr REAL NOT NULL, per_mile REAL NOT NULL, outcome TEXT NOT NULL, " +
                "lat REAL NOT NULL, lon REAL NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS offer_ts ON offer(ts)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS earning(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "amount REAL NOT NULL, platform TEXT NOT NULL, kind TEXT NOT NULL, note TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS earning_ts ON earning(ts)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS expense(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "amount REAL NOT NULL, category TEXT NOT NULL, note TEXT NOT NULL, receipt TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS expense_ts ON expense(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)      // upgrades keep every logged mile and offer
        if (oldVersion < 3) addV3(db)
    }

    // ------------------------------------------------------------------ mileage segments
    fun insert(startMs: Long, endMs: Long, state: String, meters: Double) {
        val v = ContentValues().apply {
            put("start_ms", startMs); put("end_ms", endMs); put("state", state); put("meters", meters)
        }
        writableDatabase.insert("seg", null, v)
    }

    fun firstStart(): Long? =
        readableDatabase.rawQuery("SELECT MIN(start_ms) FROM seg", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    fun segments(fromMs: Long, toMs: Long): List<Seg> {
        val out = ArrayList<Seg>()
        readableDatabase.rawQuery(
            "SELECT start_ms,end_ms,state,meters FROM seg WHERE start_ms>=? AND start_ms<? ORDER BY start_ms",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Seg(c.getLong(0), c.getLong(1), c.getString(2), c.getDouble(3)))
        }
        return out
    }

    fun totals(fromMs: Long, toMs: Long): Totals {
        var om = 0.0; var im = 0.0; var oms = 0L; var ims = 0L
        readableDatabase.rawQuery(
            "SELECT state, SUM(meters), SUM(end_ms-start_ms) FROM seg " +
                "WHERE start_ms>=? AND start_ms<? GROUP BY state",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == "ORDER") { om = c.getDouble(1); oms = c.getLong(2) }
                else { im = c.getDouble(1); ims = c.getLong(2) }
            }
        }
        return Totals(om, im, oms, ims)
    }

    /** Grouped by local-time key, e.g. fmt "%Y-%m-%d" (days) or "%Y-%m" (months). */
    fun grouped(fromMs: Long, toMs: Long, fmt: String): List<DayRow> {
        val m = LinkedHashMap<String, DoubleArray>()   // [orderM, idleM, orderMs, idleMs]
        readableDatabase.rawQuery(
            "SELECT strftime('" + fmt + "', start_ms/1000, 'unixepoch', 'localtime') AS k, state, " +
                "SUM(meters), SUM(end_ms-start_ms) FROM seg WHERE start_ms>=? AND start_ms<? " +
                "GROUP BY k, state ORDER BY k",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val a = m.getOrPut(c.getString(0)) { DoubleArray(4) }
                if (c.getString(1) == "ORDER") { a[0] = c.getDouble(2); a[2] = c.getDouble(3) }
                else { a[1] = c.getDouble(2); a[3] = c.getDouble(3) }
            }
        }
        return m.map { (k, a) -> DayRow(k, a[0], a[1], a[2].toLong(), a[3].toLong()) }
    }

    // ------------------------------------------------------------------ offers
    fun insertOffer(o: OfferRow): Long {
        val v = ContentValues().apply {
            put("ts", o.ts); put("pay", o.pay); put("miles", o.miles); put("minutes", o.minutes)
            put("pickups", o.pickups); put("verdict", o.verdict); put("net_hr", o.netHr)
            put("per_mile", o.perMile); put("outcome", o.outcome); put("lat", o.lat); put("lon", o.lon)
            put("merchant", o.merchant); put("actual_min", o.actualMin); put("actual_mi", o.actualMi)
            put("window_min", o.windowMin); put("tips_incl", if (o.tipsIncl) 1 else 0)
        }
        return writableDatabase.insert("offer", null, v)
    }

    fun setOutcome(id: Long, outcome: String) {
        writableDatabase.execSQL("UPDATE offer SET outcome=? WHERE id=?", arrayOf<Any>(outcome, id))
    }

    fun outcomeOf(id: Long): String? =
        readableDatabase.rawQuery("SELECT outcome FROM offer WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun readOffer(c: Cursor) = OfferRow(
        c.getLong(0), c.getLong(1), c.getDouble(2), c.getDouble(3), c.getDouble(4), c.getInt(5),
        c.getString(6), c.getDouble(7), c.getDouble(8), c.getString(9), c.getDouble(10), c.getDouble(11),
        c.getString(12), c.getDouble(13), c.getDouble(14), c.getDouble(15), c.getInt(16) == 1
    )

    fun offers(fromMs: Long, toMs: Long, limit: Int = 1000): List<OfferRow> {
        val out = ArrayList<OfferRow>()
        readableDatabase.rawQuery(
            "SELECT id,ts,pay,miles,minutes,pickups,verdict,net_hr,per_mile,outcome,lat,lon,merchant,actual_min,actual_mi,window_min,tips_incl FROM offer " +
                "WHERE ts>=? AND ts<? ORDER BY ts DESC LIMIT " + limit,
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c -> while (c.moveToNext()) out.add(readOffer(c)) }
        return out
    }

    fun setActual(id: Long, minutes: Double, miles: Double) {
        writableDatabase.execSQL("UPDATE offer SET actual_min=?, actual_mi=? WHERE id=?", arrayOf<Any>(minutes, miles, id))
    }

    /** Accepted offers that have no measured order time yet, oldest first. */
    fun unmeasuredAccepted(sinceMs: Long): List<OfferRow> {
        val out = ArrayList<OfferRow>()
        readableDatabase.rawQuery(
            "SELECT id,ts,pay,miles,minutes,pickups,verdict,net_hr,per_mile,outcome,lat,lon,merchant,actual_min,actual_mi,window_min,tips_incl " +
                "FROM offer WHERE outcome='ACCEPTED' AND actual_min=0 AND ts>=? ORDER BY ts",
            arrayOf(sinceMs.toString())
        ).use { c -> while (c.moveToNext()) out.add(readOffer(c)) }
        return out
    }

    /** (offer miles, measured order minutes) for recent completed orders, newest first. */
    fun calibrationSamples(limit: Int): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        readableDatabase.rawQuery(
            "SELECT miles, actual_min FROM offer WHERE outcome='ACCEPTED' AND actual_min>0 AND miles>0 ORDER BY ts DESC LIMIT " + limit, null
        ).use { c -> while (c.moveToNext()) out.add(Pair(c.getDouble(0), c.getDouble(1))) }
        return out
    }

    fun merchant(name: String): MerchantStat? {
        if (name.isBlank()) return null
        return readableDatabase.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN outcome='ACCEPTED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN outcome='ACCEPTED' AND actual_min>0 THEN 1 ELSE 0 END), " +
                "COALESCE(AVG(CASE WHEN outcome='ACCEPTED' THEN pay END),0), " +
                "COALESCE(AVG(CASE WHEN outcome='ACCEPTED' AND actual_min>0 THEN actual_min END),0), " +
                "COALESCE(AVG(CASE WHEN outcome='ACCEPTED' AND actual_min>0 THEN actual_mi END),0) FROM offer WHERE merchant=?",
            arrayOf(name)
        ).use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) MerchantStat(name, c.getInt(0), c.getInt(1), c.getInt(2), c.getDouble(3), c.getDouble(4), c.getDouble(5)) else null
        }
    }

    fun merchants(fromMs: Long, toMs: Long): List<MerchantStat> {
        val out = ArrayList<MerchantStat>()
        readableDatabase.rawQuery(
            "SELECT merchant, COUNT(*), SUM(CASE WHEN outcome='ACCEPTED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN outcome='ACCEPTED' AND actual_min>0 THEN 1 ELSE 0 END), " +
                "COALESCE(AVG(CASE WHEN outcome='ACCEPTED' THEN pay END),0), " +
                "COALESCE(AVG(CASE WHEN outcome='ACCEPTED' AND actual_min>0 THEN actual_min END),0), " +
                "COALESCE(AVG(CASE WHEN outcome='ACCEPTED' AND actual_min>0 THEN actual_mi END),0) " +
                "FROM offer WHERE merchant<>'' AND ts>=? AND ts<? GROUP BY merchant ORDER BY 3 DESC, 2 DESC",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(MerchantStat(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getDouble(4), c.getDouble(5), c.getDouble(6)))
        }
        return out
    }

    /** Sum of pay and count of ACCEPTED offers. */
    fun acceptedPay(fromMs: Long, toMs: Long): Pair<Double, Int> =
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(pay),0), COUNT(*) FROM offer WHERE outcome='ACCEPTED' AND ts>=? AND ts<?",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c -> if (c.moveToFirst()) Pair(c.getDouble(0), c.getInt(1)) else Pair(0.0, 0) }

    // ------------------------------------------------------------------ earnings
    fun insertEarning(ts: Long, amount: Double, platform: String, kind: String, note: String) {
        val v = ContentValues().apply {
            put("ts", ts); put("amount", amount); put("platform", platform); put("kind", kind); put("note", note)
        }
        writableDatabase.insert("earning", null, v)
    }

    fun earnings(fromMs: Long, toMs: Long): List<Earning> {
        val out = ArrayList<Earning>()
        readableDatabase.rawQuery(
            "SELECT id,ts,amount,platform,kind,note FROM earning WHERE ts>=? AND ts<? ORDER BY ts DESC",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Earning(c.getLong(0), c.getLong(1), c.getDouble(2), c.getString(3), c.getString(4), c.getString(5)))
        }
        return out
    }

    fun deleteEarning(id: Long) { writableDatabase.delete("earning", "id=?", arrayOf(id.toString())) }

    fun sumEarnings(fromMs: Long, toMs: Long): Double =
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(amount),0) FROM earning WHERE ts>=? AND ts<?",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

    // ------------------------------------------------------------------ expenses
    fun insertExpense(ts: Long, amount: Double, category: String, note: String, receipt: String) {
        val v = ContentValues().apply {
            put("ts", ts); put("amount", amount); put("category", category); put("note", note); put("receipt", receipt)
        }
        writableDatabase.insert("expense", null, v)
    }

    fun expenses(fromMs: Long, toMs: Long): List<Expense> {
        val out = ArrayList<Expense>()
        readableDatabase.rawQuery(
            "SELECT id,ts,amount,category,note,receipt FROM expense WHERE ts>=? AND ts<? ORDER BY ts DESC",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Expense(c.getLong(0), c.getLong(1), c.getDouble(2), c.getString(3), c.getString(4), c.getString(5)))
        }
        return out
    }

    fun deleteExpense(id: Long) { writableDatabase.delete("expense", "id=?", arrayOf(id.toString())) }

    fun expenseByCat(fromMs: Long, toMs: Long): Map<String, Double> {
        val m = LinkedHashMap<String, Double>()
        readableDatabase.rawQuery(
            "SELECT category, SUM(amount) FROM expense WHERE ts>=? AND ts<? GROUP BY category ORDER BY category",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getDouble(1) }
        return m
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(c.applicationContext).also { inst = it }
        }
    }
}
