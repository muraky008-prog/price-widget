package com.muraky.pricewidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** GitHub 에 3시간마다 갱신되는 data.json 을 받아 저장하고 읽는 곳. */
object DataRepo {
    private const val DATA_URL =
        "https://raw.githubusercontent.com/muraky008-prog/price-widget/main/data.json"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("price_widget", Context.MODE_PRIVATE)

    fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (price-widget)")
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 인터넷에서 새 데이터를 받아 저장. 성공하면 true. */
    fun fetch(ctx: Context): Boolean {
        return try {
            val text = httpGet("$DATA_URL?t=${System.currentTimeMillis()}")
            JSONObject(text).getJSONArray("assets") // 형식 검사
            prefs(ctx).edit()
                .putString("json", text)
                .putLong("fetched_at", System.currentTimeMillis())
                .apply()
            true
        } catch (e: Exception) {
            false
        } finally {
            setLoading(ctx, false)
        }
    }

    fun load(ctx: Context): JSONObject? = try {
        prefs(ctx).getString("json", null)?.let { JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    /** 서버(GitHub)에서 계산된 종목 전체 (숨김 포함). */
    fun serverAssets(ctx: Context): List<JSONObject> {
        val arr: JSONArray = load(ctx)?.optJSONArray("assets") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** 화면에 보일 종목: 코인(서버) → 내가 추가한 바이낸스 종목(폰 계산) → 주식(서버). 숨긴 종목 제외. */
    fun assets(ctx: Context): List<JSONObject> {
        val hidden = hidden(ctx)
        val server = serverAssets(ctx).filter { str(it, "id") !in hidden }
        val data = customData(ctx)
        val custom = customList(ctx).mapNotNull { data.optJSONObject(it) }
        return server.filter { str(it, "type") == "coin" } + custom +
            server.filter { str(it, "type") != "coin" }
    }

    /** 바이낸스 Spot USDT 종목 목록 (검색용, 서버가 3시간마다 갱신). */
    fun spotBases(ctx: Context): List<String> {
        val arr = load(ctx)?.optJSONArray("spot_usdt") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // ---------- 내 종목 관리 ----------
    /** 추가한 바이낸스 페어 목록, 예: ["SPYBUSDT"] */
    fun customList(ctx: Context): MutableList<String> = readList(ctx, "custom")
    fun setCustomList(ctx: Context, list: List<String>) = writeList(ctx, "custom", list)

    /** 숨긴 서버 종목 id 목록 */
    fun hidden(ctx: Context): MutableSet<String> = readList(ctx, "hidden").toMutableSet()
    fun setHidden(ctx: Context, set: Set<String>) = writeList(ctx, "hidden", set.toList())

    fun customData(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString("custom_data", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    @Synchronized
    fun putCustomData(ctx: Context, pair: String, asset: JSONObject?) {
        val d = customData(ctx)
        if (asset == null) d.remove(pair) else d.put(pair, asset)
        prefs(ctx).edit().putString("custom_data", d.toString()).apply()
    }

    private fun readList(ctx: Context, key: String): MutableList<String> = try {
        val arr = JSONArray(prefs(ctx).getString(key, "[]") ?: "[]")
        MutableList(arr.length()) { arr.getString(it) }
    } catch (e: Exception) {
        mutableListOf()
    }

    private fun writeList(ctx: Context, key: String, list: List<String>) {
        prefs(ctx).edit().putString(key, JSONArray(list).toString()).apply()
    }

    fun selected(ctx: Context, widgetId: Int): String =
        prefs(ctx).getString("sel_$widgetId", "btc") ?: "btc"

    fun select(ctx: Context, widgetId: Int, assetId: String) {
        prefs(ctx).edit().putString("sel_$widgetId", assetId).apply()
    }

    fun clearSelection(ctx: Context, widgetId: Int) {
        prefs(ctx).edit().remove("sel_$widgetId").apply()
    }

    fun isLoading(ctx: Context) = prefs(ctx).getBoolean("loading", false)
    fun setLoading(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("loading", v).apply()
    }

    // ---------- 표시용 도우미 ----------
    fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "")

    fun num(o: JSONObject, key: String): Double? =
        if (o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() }

    fun fmtPrice(v: Double): String = when {
        v >= 10 -> String.format(Locale.US, "%,.2f", v)
        v >= 1 -> String.format(Locale.US, "%,.4f", v)
        else -> BigDecimal(v).round(MathContext(4)).stripTrailingZeros().toPlainString()
    }

    fun fmtPct(p: Double): String = String.format(Locale.US, "%+.2f%%", p)

    /** S&P 500 같은 지수는 $ 를 붙이지 않음. */
    fun money(asset: JSONObject, v: Double): String =
        if (str(asset, "symbol") == "GSPC") fmtPrice(v) else "$" + fmtPrice(v)
}
