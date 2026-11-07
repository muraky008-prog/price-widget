package com.muraky.pricewidget

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * 종목 관리: 바이낸스 Spot(USDT) 종목을 검색해 추가하고, 목록에서 빼거나 다시 넣기.
 * - 기본 종목(코인 시총 상위 10 + 주식)은 "빼기" 하면 숨김 처리, "숨긴 종목"에서 다시 넣을 수 있음
 * - 추가한 바이낸스 종목은 폰에서 직접 지지/저항선을 계산
 */
class ManageActivity : Activity() {

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class Item(
            val symbol: String,
            val name: String,
            val action: String,
            val enabled: Boolean,
            val onClick: () -> Unit,
        ) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val adapter = RowAdapter()
    private lateinit var search: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage)
        search = findViewById(R.id.search)
        findViewById<ListView>(R.id.list).adapter = adapter
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = rebuild()
        })
        rebuild()
    }

    private fun rebuild() {
        val q = search.text.toString().trim().uppercase()
        rows.clear()
        if (q.isEmpty()) buildMyList() else buildSearch(q)
        adapter.notifyDataSetChanged()
    }

    private fun buildMyList() {
        val hidden = DataRepo.hidden(this)
        val server = DataRepo.serverAssets(this)
        val data = DataRepo.customData(this)
        val custom = DataRepo.customList(this)

        rows += Row.Header("내 종목 · 위젯 목록에 보이는 종목")
        server.filter { DataRepo.str(it, "type") == "coin" && DataRepo.str(it, "id") !in hidden }
            .forEach { a -> rows += item(a, "빼기") { hide(DataRepo.str(a, "id")) } }
        custom.forEach { pair ->
            val base = pair.removeSuffix("USDT")
            val ready = data.has(pair)
            rows += Row.Item(base, if (ready) Calc.displayName(base) else "계산 중…", "빼기", true) {
                removeCustom(pair)
            }
        }
        server.filter { DataRepo.str(it, "type") != "coin" && DataRepo.str(it, "id") !in hidden }
            .forEach { a -> rows += item(a, "빼기") { hide(DataRepo.str(a, "id")) } }

        val hiddenItems = server.filter { DataRepo.str(it, "id") in hidden }
        if (hiddenItems.isNotEmpty()) {
            rows += Row.Header("숨긴 종목")
            hiddenItems.forEach { a -> rows += item(a, "다시 넣기") { unhide(DataRepo.str(a, "id")) } }
        }
        rows += Row.Header("위 검색창에 바이낸스 종목을 입력하면 추가할 수 있어요")
    }

    private fun buildSearch(q: String) {
        val bases = DataRepo.spotBases(this)
        if (bases.isEmpty()) {
            rows += Row.Header("바이낸스 종목 목록이 아직 없어요. 종목 선택 화면에서 ↻ 를 눌러주세요")
            return
        }
        val custom = DataRepo.customList(this)
        val matches = bases.filter { it.contains(q) }
            .sortedWith(compareBy({ !it.startsWith(q) }, { it.length }, { it }))
            .take(60)
        rows += Row.Header("바이낸스 Spot 검색 결과 ${matches.size}개 (USDT 마켓)")
        matches.forEach { base ->
            val pair = base + "USDT"
            val added = pair in custom
            rows += Row.Item(base, Calc.displayName(base), if (added) "추가됨" else "추가", !added) {
                addCustom(pair)
            }
        }
    }

    private fun item(a: org.json.JSONObject, action: String, onClick: () -> Unit) =
        Row.Item(DataRepo.str(a, "symbol"), DataRepo.str(a, "name"), action, true, onClick)

    private fun hide(id: String) {
        DataRepo.setHidden(this, DataRepo.hidden(this).apply { add(id) })
        changed()
    }

    private fun unhide(id: String) {
        DataRepo.setHidden(this, DataRepo.hidden(this).apply { remove(id) })
        changed()
    }

    private fun removeCustom(pair: String) {
        DataRepo.setCustomList(this, DataRepo.customList(this).apply { remove(pair) })
        DataRepo.putCustomData(this, pair, null)
        changed()
    }

    private fun addCustom(pair: String) {
        val list = DataRepo.customList(this)
        if (pair in list) return
        list.add(pair)
        DataRepo.setCustomList(this, list)
        Toast.makeText(this, "${pair.removeSuffix("USDT")} 추가됨 · 지지/저항 계산 중…", Toast.LENGTH_SHORT).show()
        changed()
        val app = applicationContext
        Thread {
            val ok = try {
                DataRepo.putCustomData(app, pair, Calc.compute(pair))
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (!ok) Toast.makeText(app, "${pair.removeSuffix("USDT")} 계산 실패. 나중에 자동으로 다시 시도해요.", Toast.LENGTH_SHORT).show()
                if (!isFinishing) rebuild()
                PriceWidget.updateAll(app)
            }
        }.start()
    }

    private fun changed() {
        rebuild()
        PriceWidget.updateAll(this)
    }

    inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val r = rows[position]) {
                is Row.Header -> {
                    val v = convertView ?: layoutInflater.inflate(R.layout.item_manage_header, parent, false)
                    (v as TextView).text = r.text
                    v
                }
                is Row.Item -> {
                    val v = convertView ?: layoutInflater.inflate(R.layout.item_manage, parent, false)
                    v.findViewById<TextView>(R.id.tvSym).text = r.symbol
                    v.findViewById<TextView>(R.id.tvName).text = r.name
                    val btn = v.findViewById<TextView>(R.id.tvAction)
                    btn.text = r.action
                    btn.alpha = if (r.enabled) 1f else 0.4f
                    btn.setOnClickListener(if (r.enabled) View.OnClickListener { r.onClick() } else null)
                    v
                }
            }
        }
    }
}
