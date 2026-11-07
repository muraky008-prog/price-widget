package com.muraky.pricewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject

class PriceWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it) }
        Sync.schedule(ctx)
        if (DataRepo.load(ctx) == null) Sync.now(ctx)
    }

    override fun onEnabled(ctx: Context) {
        Sync.schedule(ctx)
        Sync.now(ctx)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        ids.forEach { DataRepo.clearSelection(ctx, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            DataRepo.setLoading(ctx, true)
            updateAll(ctx)
            Sync.now(ctx)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.muraky.pricewidget.REFRESH"

        private val R_ROWS = intArrayOf(R.id.rowR1, R.id.rowR2, R.id.rowR3, R.id.rowR4)
        private val R_NAME = intArrayOf(R.id.nameR1, R.id.nameR2, R.id.nameR3, R.id.nameR4)
        private val R_VAL = intArrayOf(R.id.valR1, R.id.valR2, R.id.valR3, R.id.valR4)
        private val R_PCT = intArrayOf(R.id.pctR1, R.id.pctR2, R.id.pctR3, R.id.pctR4)
        private val S_ROWS = intArrayOf(R.id.rowS1, R.id.rowS2, R.id.rowS3, R.id.rowS4)
        private val S_NAME = intArrayOf(R.id.nameS1, R.id.nameS2, R.id.nameS3, R.id.nameS4)
        private val S_VAL = intArrayOf(R.id.valS1, R.id.valS2, R.id.valS3, R.id.valS4)
        private val S_PCT = intArrayOf(R.id.pctS1, R.id.pctS2, R.id.pctS3, R.id.pctS4)

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PriceWidget::class.java))
            ids.forEach { render(ctx, mgr, it) }
        }

        fun allWidgetIds(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx)
                .getAppWidgetIds(ComponentName(ctx, PriceWidget::class.java))

        fun render(ctx: Context, mgr: AppWidgetManager, widgetId: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            bindClicks(ctx, v, widgetId)

            val data = DataRepo.load(ctx)
            val assets = DataRepo.assets(ctx)
            if (data == null || assets.isEmpty()) {
                v.setTextViewText(R.id.tvSymbol, "가격 위젯")
                v.setTextViewText(R.id.tvName, "")
                v.setTextViewText(R.id.tvPrice, "불러오는 중…")
                v.setTextViewText(R.id.tvChange, "")
                v.setTextViewText(R.id.tvFooter, "인터넷 연결을 확인하세요 · ↻ 눌러 다시 시도")
                v.setViewVisibility(R.id.levels, View.GONE)
                mgr.updateAppWidget(widgetId, v)
                return
            }
            v.setViewVisibility(R.id.levels, View.VISIBLE)

            val sel = DataRepo.selected(ctx, widgetId)
            val a = assets.firstOrNull { DataRepo.str(it, "id") == sel } ?: assets[0]
            val price = DataRepo.num(a, "price") ?: 0.0
            val isCoin = DataRepo.str(a, "type") == "coin"

            v.setTextViewText(R.id.tvSymbol, DataRepo.str(a, "symbol"))
            val rank = if (isCoin && a.has("rank")) " · 시총 ${a.optInt("rank")}위" else ""
            v.setTextViewText(R.id.tvName, DataRepo.str(a, "name") + rank)
            v.setTextViewText(R.id.tvPrice, DataRepo.money(a, price))

            val chg = DataRepo.num(a, "change24h")
            if (chg != null) {
                v.setTextViewText(R.id.tvChange, DataRepo.fmtPct(chg) + if (isCoin) " 24h" else " 전일비")
                v.setTextColor(R.id.tvChange, if (chg >= 0) ctx.getColor(R.color.up) else ctx.getColor(R.color.down))
            } else {
                v.setTextViewText(R.id.tvChange, "")
            }

            val res = a.optJSONArray("resistance") ?: JSONArray()
            val sup = a.optJSONArray("support") ?: JSONArray()
            fillRows(v, a, res, R_ROWS, R_NAME, R_VAL, R_PCT)
            fillRows(v, a, sup, S_ROWS, S_NAME, S_VAL, S_PCT)

            v.setTextViewText(R.id.labelRes, label("▲ 저항", res.length(), "위로 저항선 없음"))
            v.setTextViewText(R.id.labelSup, label("▼ 지지", sup.length(), "아래로 지지선 없음"))
            v.setTextViewText(R.id.tvNow, "━  현재가 ${DataRepo.money(a, price)}  ━")

            val footer = buildList {
                add(DataRepo.str(data, "updated").replace(" KST", "") + " 기준")
                if (a.optBoolean("stale")) add("갱신 실패, 지난 값")
                if (DataRepo.isLoading(ctx)) add("갱신 중…")
                DataRepo.str(a, "note").takeIf { it.isNotEmpty() }?.let { add(it) }
            }.joinToString(" · ")
            v.setTextViewText(R.id.tvFooter, footer)

            mgr.updateAppWidget(widgetId, v)
        }

        private fun label(title: String, n: Int, empty: String): String = when {
            n == 0 -> "$title · $empty"
            n > 4 -> "$title (외 ${n - 4}개)"
            else -> title
        }

        /** rows[0] 이 현재가에 가장 가까운 선. */
        private fun fillRows(
            v: RemoteViews, asset: JSONObject, arr: JSONArray,
            rows: IntArray, names: IntArray, vals: IntArray, pcts: IntArray,
        ) {
            for (i in rows.indices) {
                if (i < arr.length()) {
                    val lv = arr.getJSONObject(i)
                    v.setViewVisibility(rows[i], View.VISIBLE)
                    v.setTextViewText(names[i], DataRepo.str(lv, "name"))
                    v.setTextViewText(vals[i], DataRepo.money(asset, lv.optDouble("value")))
                    v.setTextViewText(pcts[i], DataRepo.fmtPct(lv.optDouble("pct")))
                } else {
                    v.setViewVisibility(rows[i], View.GONE)
                }
            }
        }

        private fun bindClicks(ctx: Context, v: RemoteViews, widgetId: Int) {
            val pick = Intent(ctx, PickActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("pricewidget://pick/$widgetId"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pickPi = PendingIntent.getActivity(
                ctx, widgetId, pick,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.btnList, pickPi)
            v.setOnClickPendingIntent(R.id.header, pickPi)

            val refresh = Intent(ctx, PriceWidget::class.java).setAction(ACTION_REFRESH)
            val refreshPi = PendingIntent.getBroadcast(
                ctx, 0, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.btnRefresh, refreshPi)
        }
    }
}
