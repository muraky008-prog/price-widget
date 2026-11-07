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

    /** 인터넷에서 새 데이터를 받아 저장. 성공하면 true. */
    fun fetch(ctx: Context): Boolean {
        return try {
            val conn = URL("$DATA_URL?t=${System.currentTimeMillis()}")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Cache-Control", "no-cache")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
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

    fun assets(ctx: Context): List<JSONObject> {
        val arr: JSONArray = load(ctx)?.optJSONArray("assets") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
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
