package com.muraky.pricewidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 내가 추가한 바이낸스 Spot 종목의 지지/저항선을 폰에서 직접 계산.
 * 서버(compute.py)와 같은 규칙: 50/100/200일, 50/100/200주 이평, BMSB(20주 SMA / 21주 EMA).
 */
object Calc {
    private const val BINANCE = "https://data-api.binance.vision"

    /**
     * 바이낸스 주식 토큰 → 원래 주식 티커, 표시 이름.
     * 토큰은 상장 기간이 짧아 장기 이평을 원주식 과거 데이터로 계산 (가격은 1:1로 움직임).
     */
    val TOKENIZED = mapOf(
        "SPYB" to ("SPY" to "S&P 500 (SPY 토큰)"),
        "QQQB" to ("QQQ" to "나스닥100 (QQQ 토큰)"),
        "NVDAB" to ("NVDA" to "엔비디아 토큰"),
        "TSLAB" to ("TSLA" to "테슬라 토큰"),
        "AAPLB" to ("AAPL" to "애플 토큰"),
        "MSTRB" to ("MSTR" to "스트래티지 토큰"),
        "COINB" to ("COIN" to "코인베이스 토큰"),
    )

    fun displayName(base: String): String = TOKENIZED[base]?.second ?: "$base/USDT"

    /** 추가한 종목 전부 다시 계산. 실패한 종목은 이전 값에 stale 표시. */
    fun refreshAll(ctx: Context) {
        val old = DataRepo.customData(ctx)
        for (pair in DataRepo.customList(ctx)) {
            try {
                DataRepo.putCustomData(ctx, pair, compute(pair))
            } catch (e: Exception) {
                old.optJSONObject(pair)?.let {
                    it.put("stale", true)
                    DataRepo.putCustomData(ctx, pair, it)
                }
            }
        }
    }

    fun compute(pair: String): JSONObject {
        val base = pair.removeSuffix("USDT")
        var daily = binanceCloses(pair, "1d")
        var weekly = binanceCloses(pair, "1w")
        if (daily.isEmpty()) throw IllegalStateException("$pair 데이터 없음")
        val price = daily.last()
        var note: String? = null

        val stock = TOKENIZED[base]?.first
        if (stock != null && (daily.size < 200 || weekly.size < 200)) {
            try {
                val yd = yahooCloses(stock, "1d", "2y").toMutableList()
                val yw = yahooCloses(stock, "1wk", "10y").toMutableList()
                if (yd.size > daily.size) {
                    yd[yd.size - 1] = price
                    yw[yw.size - 1] = price
                    daily = yd
                    weekly = yw
                    note = "장기 이평은 원주식 $stock 기준"
                }
            } catch (e: Exception) {
            }
        }
        if (note == null && (daily.size < 200 || weekly.size < 200)) {
            note = "상장 기간이 짧아 일부 이평 없음"
        }

        val chg = try {
            JSONObject(DataRepo.httpGet("$BINANCE/api/v3/ticker/24hr?symbol=$pair"))
                .optDouble("priceChangePercent")
        } catch (e: Exception) {
            Double.NaN
        }

        val (res, sup) = split(price, levels(daily, weekly))
        return JSONObject().apply {
            put("id", pair.lowercase())
            put("pair", pair)
            put("symbol", base)
            put("name", displayName(base))
            put("type", "custom")
            put("price", price)
            if (!chg.isNaN()) put("change24h", chg) else put("change24h", JSONObject.NULL)
            put("resistance", res)
            put("support", sup)
            put("note", note ?: JSONObject.NULL)
        }
    }

    private fun binanceCloses(pair: String, interval: String): List<Double> {
        val arr = JSONArray(DataRepo.httpGet("$BINANCE/api/v3/klines?symbol=$pair&interval=$interval&limit=1000"))
        if (arr.length() == 0) return emptyList()
        // 마지막 캔들 마감 시각이 3일 넘게 지났으면 상장폐지된 페어
        val lastClose = arr.getJSONArray(arr.length() - 1).getLong(6)
        if (System.currentTimeMillis() - lastClose > 3L * 86_400_000) return emptyList()
        return (0 until arr.length()).map { arr.getJSONArray(it).getString(4).toDouble() }
    }

    private fun yahooCloses(ticker: String, interval: String, range: String): List<Double> {
        val d = JSONObject(DataRepo.httpGet(
            "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?range=$range&interval=$interval",
        ))
        val close = d.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            .getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            .getJSONArray("close")
        return (0 until close.length()).filter { !close.isNull(it) }.map { close.getDouble(it) }
    }

    private fun sma(v: List<Double>, n: Int): Double? =
        if (v.size < n) null else v.takeLast(n).average()

    private fun ema(v: List<Double>, n: Int): Double? {
        if (v.size < n) return null
        val k = 2.0 / (n + 1)
        var e = v.take(n).average()
        for (x in v.drop(n)) e = x * k + e * (1 - k)
        return e
    }

    private fun levels(daily: List<Double>, weekly: List<Double>): Map<String, Double?> {
        val m = linkedMapOf(
            "50일 이평" to sma(daily, 50),
            "100일 이평" to sma(daily, 100),
            "200일 이평" to sma(daily, 200),
            "50주 이평" to sma(weekly, 50),
            "100주 이평" to sma(weekly, 100),
            "200주 이평" to sma(weekly, 200),
        )
        val s20 = sma(weekly, 20)
        val e21 = ema(weekly, 21)
        if (s20 != null && e21 != null) {
            m["BMSB 상단"] = maxOf(s20, e21)
            m["BMSB 중앙"] = (s20 + e21) / 2
            m["BMSB 하단"] = minOf(s20, e21)
        }
        return m
    }

    private fun split(price: Double, levels: Map<String, Double?>): Pair<JSONArray, JSONArray> {
        val items = levels.mapNotNull { (k, v) -> v?.let { k to it } }
        fun obj(p: Pair<String, Double>) = JSONObject().apply {
            put("name", p.first)
            put("value", p.second)
            put("pct", Math.round((p.second / price - 1) * 10000) / 100.0)
        }
        val res = JSONArray()
        items.filter { it.second > price }.sortedBy { it.second }.forEach { res.put(obj(it)) }
        val sup = JSONArray()
        items.filter { it.second <= price }.sortedByDescending { it.second }.forEach { sup.put(obj(it)) }
        return res to sup
    }
}
