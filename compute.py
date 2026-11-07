"""
지지/저항 가격선 계산기
- 시총 상위 코인 10개(스테이블코인 제외, 바이낸스 상장 코인) + 지정 주식
- 50/100/200일 이평선, 50/100/200주 이평선, 불마켓 서포트 밴드(20주 SMA / 21주 EMA)
- BTC: 실현가격(Realized Price, 무료 데이터는 약 7일 지연)
결과를 data.json 으로 저장한다. 외부 패키지 없이 표준 라이브러리만 사용.
"""
import json
import time
import urllib.request
from datetime import datetime, timezone, timedelta
from pathlib import Path

KST = timezone(timedelta(hours=9))
UA = {"User-Agent": "Mozilla/5.0 (price-widget)"}

STOCKS = [
    ("^GSPC", "S&P 500"),
    ("NVDA", "엔비디아"),
    ("IONQ", "아이온큐"),
    ("QQQ", "인베스코 QQQ"),
    ("TSLA", "테슬라"),
    ("XLE", "SPDR 에너지"),
    ("XLU", "SPDR 유틸리티"),
]

# 스테이블코인, 래핑/스테이킹 토큰은 제외
EXCLUDE = {
    "usdt", "usdc", "usds", "dai", "fdusd", "tusd", "usde", "pyusd", "usd1", "busd",
    "usdd", "frax", "usdp", "gusd", "rlusd", "usdtb", "susde", "eurc", "xaut", "paxg",
    "wbtc", "weth", "steth", "wsteth", "weeth", "cbbtc", "reth", "wbeth", "bsc-usd",
    "figr_heloc",  # 토큰화된 대출 상품(코인 아님)
}
COIN_COUNT = 10
# api.binance.com 은 미국 IP(GitHub 서버)를 차단하므로 공개 시세 전용 주소를 사용
BINANCE = "https://data-api.binance.vision"


def get_json(url, retries=3):
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(2 * (i + 1))
    raise RuntimeError(f"{url} -> {last}")


def sma(values, n):
    if len(values) < n:
        return None
    return sum(values[-n:]) / n


def ema(values, n):
    if len(values) < n:
        return None
    k = 2 / (n + 1)
    e = sum(values[:n]) / n
    for v in values[n:]:
        e = v * k + e * (1 - k)
    return e


def build_levels(daily, weekly):
    """일봉/주봉 종가 리스트로 가격선 계산."""
    levels = {
        "50일 이평": sma(daily, 50),
        "100일 이평": sma(daily, 100),
        "200일 이평": sma(daily, 200),
        "50주 이평": sma(weekly, 50),
        "100주 이평": sma(weekly, 100),
        "200주 이평": sma(weekly, 200),
    }
    s20, e21 = sma(weekly, 20), ema(weekly, 21)
    if s20 is not None and e21 is not None:
        levels["BMSB 상단"] = max(s20, e21)
        levels["BMSB 중앙"] = (s20 + e21) / 2
        levels["BMSB 하단"] = min(s20, e21)
    return levels


def split_levels(price, levels):
    items = [{"name": k, "value": round(v, 6)} for k, v in levels.items() if v is not None]
    res = sorted([x for x in items if x["value"] > price], key=lambda x: x["value"])
    sup = sorted([x for x in items if x["value"] <= price], key=lambda x: -x["value"])
    for x in res + sup:
        x["pct"] = round((x["value"] / price - 1) * 100, 2)
    return res, sup


# ---------- 코인 ----------
def binance_closes(symbol, interval, limit=1000):
    k = get_json(f"{BINANCE}/api/v3/klines?symbol={symbol}&interval={interval}&limit={limit}")
    # 상장폐지된 페어는 오래된 가격이 남아있으므로 최근 3일 내 캔들이 없으면 무효
    if not k or time.time() * 1000 - k[-1][0] > 3 * 86400 * 1000:
        return []
    return [float(c[4]) for c in k]


def coingecko_closes(cg_id):
    """바이낸스 데이터가 없거나 짧을 때: 코인게코 무료 1년치 일봉 + 그걸로 만든 주봉."""
    d = get_json(f"https://api.coingecko.com/api/v3/coins/{cg_id}/market_chart?vs_currency=usd&days=365&interval=daily")
    pts = [(int(t), float(p)) for t, p in d["prices"]]
    daily = [p for _, p in pts]
    weeks = {}
    for t, p in pts:
        y, w, _ = datetime.fromtimestamp(t / 1000, timezone.utc).isocalendar()
        weeks[(y, w)] = p  # 주의 마지막 종가
    weekly = [weeks[k] for k in sorted(weeks)]
    return daily, weekly


def coin_history(pair, cg_id, binance_pairs):
    """(일봉, 주봉, 출처) 반환. 바이낸스 우선, 부족하면 코인게코."""
    daily = weekly = []
    if pair in binance_pairs:
        daily = binance_closes(pair, "1d")
        if daily:
            weekly = binance_closes(pair, "1w")
    if len(daily) >= 200 and weekly:
        return daily, weekly, "binance"
    time.sleep(2)  # 코인게코 무료 호출 제한 대비
    cd, cw = coingecko_closes(cg_id)
    if len(cd) > len(daily):
        return cd, cw, "coingecko"
    return daily, weekly, "binance"


def btc_realized_price():
    try:
        d = get_json("https://bitcoin-data.com/v1/realized-price/last")
        return float(d["realizedPrice"]), d.get("d")
    except Exception as e:  # noqa: BLE001
        print("realized price 실패:", e)
        return None, None


def coins():
    markets = get_json(
        "https://api.coingecko.com/api/v3/coins/markets"
        "?vs_currency=usd&order=market_cap_desc&per_page=60&page=1"
    )
    pairs = {p["symbol"] for p in get_json(f"{BINANCE}/api/v3/ticker/price")}
    out = []
    for m in markets:
        sym = m["symbol"].lower()
        pair = sym.upper() + "USDT"
        if sym in EXCLUDE:
            continue
        # 바이낸스에 없으면서 가격이 1달러 근처면 스테이블/RWA 토큰으로 보고 제외
        if pair not in pairs and abs(float(m["current_price"] or 0) - 1) < 0.05:
            continue
        try:
            daily, weekly, src = coin_history(pair, m["id"], pairs)
        except Exception as e:  # noqa: BLE001
            print("skip", pair, e)
            continue
        if len(daily) < 50:
            print("skip (데이터 부족)", pair)
            continue
        price = float(m["current_price"]) if src == "coingecko" else daily[-1]
        daily[-1] = price
        weekly[-1] = price
        levels = build_levels(daily, weekly)
        note = "1년치 데이터만 있어 장기 주봉 이평 일부 없음" if src == "coingecko" else None
        if sym == "btc":
            rp, rp_date = btc_realized_price()
            if rp:
                levels["실현가격"] = rp
                note = f"실현가격 기준일 {rp_date} (무료 데이터 약 7일 지연)"
        res, sup = split_levels(price, levels)
        out.append({
            "id": sym, "symbol": sym.upper(), "name": m["name"], "type": "coin",
            "rank": len(out) + 1, "price": price,
            "change24h": m.get("price_change_percentage_24h"),
            "resistance": res, "support": sup, "note": note,
        })
        if len(out) >= COIN_COUNT:
            break
    return out


# ---------- 주식 ----------
def yahoo(symbol, interval, rng):
    s = urllib.request.quote(symbol)
    d = get_json(f"https://query1.finance.yahoo.com/v8/finance/chart/{s}?range={rng}&interval={interval}")
    r = d["chart"]["result"][0]
    closes = [c for c in r["indicators"]["quote"][0]["close"] if c is not None]
    return closes, r["meta"]


def stocks():
    out = []
    for sym, name in STOCKS:
        try:
            daily, meta = yahoo(sym, "1d", "2y")
            weekly, _ = yahoo(sym, "1wk", "10y")
        except Exception as e:  # noqa: BLE001
            print("skip", sym, e)
            continue
        price = meta.get("regularMarketPrice") or daily[-1]
        prev = meta.get("chartPreviousClose") or meta.get("previousClose")
        # 마지막 일봉 종가를 현재가로 맞춰 이평선이 최신 가격을 반영하도록
        daily[-1] = price
        weekly[-1] = price
        levels = build_levels(daily, weekly)
        res, sup = split_levels(price, levels)
        chg = None
        if len(daily) >= 2:
            chg = round((price / daily[-2] - 1) * 100, 2)
        out.append({
            "id": sym.lower().replace("^", ""), "symbol": sym.replace("^", ""), "name": name,
            "type": "stock", "price": price, "change24h": chg,
            "resistance": res, "support": sup, "note": None,
        })
        time.sleep(0.5)
    return out


def main():
    now = datetime.now(KST)
    data = {
        "updated": now.strftime("%Y-%m-%d %H:%M KST"),
        "updated_unix": int(now.timestamp()),
        "assets": coins() + stocks(),
    }
    path = Path(__file__).with_name("data.json")
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"saved {len(data['assets'])} assets -> {path}")
    for a in data["assets"]:
        r = a["resistance"][0] if a["resistance"] else None
        s = a["support"][0] if a["support"] else None
        print(f"{a['symbol']:>6} {a['price']:>12,.4f} | 저항 {r['name'] + ' ' + format(r['value'], ',.2f') if r else '-':<24}"
              f" | 지지 {s['name'] + ' ' + format(s['value'], ',.2f') if s else '-'}")


if __name__ == "__main__":
    main()
