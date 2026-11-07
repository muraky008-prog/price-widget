package com.muraky.pricewidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/** 위젯의 목록 아이콘을 누르면 열리는 종목 선택 화면 (앱 아이콘으로 열어도 같은 화면). */
class PickActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var items: List<JSONObject> = emptyList()
    private lateinit var list: ListView
    private lateinit var tvSub: TextView
    private val adapter = AssetAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pick)
        widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        list = findViewById(R.id.list)
        tvSub = findViewById(R.id.tvSub)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> choose(items[pos]) }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnManage).setOnClickListener {
            startActivity(android.content.Intent(this, ManageActivity::class.java))
        }

        showData()
        if (items.isEmpty()) refresh()
    }

    override fun onResume() {
        super.onResume()
        showData() // 종목 관리 화면에서 돌아왔을 때 목록 갱신
    }

    private fun showData() {
        items = DataRepo.assets(this)
        val data = DataRepo.load(this)
        tvSub.text = when {
            data == null -> "데이터를 불러오는 중…"
            widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ->
                "${DataRepo.str(data, "updated")} 기준 · 고르면 모든 위젯에 적용"
            else -> "${DataRepo.str(data, "updated")} 기준 · 위젯에 표시할 종목을 고르세요"
        }
        adapter.notifyDataSetChanged()
    }

    private fun refresh() {
        tvSub.text = "새로 불러오는 중…"
        val app = applicationContext
        Thread {
            val ok = DataRepo.fetch(app)
            Calc.refreshAll(app)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                showData()
                PriceWidget.updateAll(app)
                if (!ok) Toast.makeText(app, "불러오기 실패. 인터넷 연결을 확인하세요.", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun choose(a: JSONObject) {
        val id = DataRepo.str(a, "id")
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            DataRepo.select(this, widgetId, id)
            PriceWidget.updateAll(this)
            finish()
        } else {
            val ids = PriceWidget.allWidgetIds(this)
            ids.forEach { DataRepo.select(this, it, id) }
            PriceWidget.updateAll(this)
            val msg = if (ids.isEmpty()) "홈 화면에 위젯을 먼저 추가하세요" else "${DataRepo.str(a, "symbol")} 적용됨"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            adapter.notifyDataSetChanged()
        }
    }

    private fun currentSelection(): String? = when {
        widgetId != AppWidgetManager.INVALID_APPWIDGET_ID -> DataRepo.selected(this, widgetId)
        else -> PriceWidget.allWidgetIds(this).firstOrNull()?.let { DataRepo.selected(this, it) }
    }

    inner class AssetAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_asset, parent, false)
            val a = items[position]
            val price = DataRepo.num(a, "price") ?: 0.0
            val isCoin = DataRepo.str(a, "type") == "coin"

            view.findViewById<TextView>(R.id.tvSym).text = DataRepo.str(a, "symbol")
            view.findViewById<TextView>(R.id.tvName).text = DataRepo.str(a, "name")
            view.findViewById<TextView>(R.id.tvPrice).text = DataRepo.money(a, price)

            val chgView = view.findViewById<TextView>(R.id.tvChg)
            val chg = DataRepo.num(a, "change24h")
            chgView.text = chg?.let { DataRepo.fmtPct(it) } ?: ""
            chgView.setTextColor(getColor(if ((chg ?: 0.0) >= 0) R.color.up else R.color.down))

            val r = a.optJSONArray("resistance")?.optJSONObject(0)
            val s = a.optJSONArray("support")?.optJSONObject(0)
            val parts = mutableListOf<String>()
            if (r != null) parts += "▲ ${DataRepo.str(r, "name")} ${DataRepo.fmtPct(r.optDouble("pct"))}"
            if (s != null) parts += "▼ ${DataRepo.str(s, "name")} ${DataRepo.fmtPct(s.optDouble("pct"))}"
            view.findViewById<TextView>(R.id.tvLevels).text = parts.joinToString("    ")

            val header = view.findViewById<TextView>(R.id.tvGroup)
            val prevType = if (position > 0) DataRepo.str(items[position - 1], "type") else ""
            val type = DataRepo.str(a, "type")
            if (type != prevType) {
                header.visibility = View.VISIBLE
                header.text = when (type) {
                    "coin" -> "코인 (시총 상위)"
                    "custom" -> "내가 추가한 바이낸스 종목"
                    else -> "주식 · ETF"
                }
            } else {
                header.visibility = View.GONE
            }

            val selected = DataRepo.str(a, "id") == currentSelection()
            view.findViewById<View>(R.id.itemBody).setBackgroundColor(
                getColor(if (selected) R.color.selected else android.R.color.transparent),
            )
            return view
        }
    }
}
