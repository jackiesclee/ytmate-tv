# -*- coding: utf-8 -*-
"""
YTmate TV 模擬器自動測試（GitHub Actions 執行）。

在 Android 電視模擬器上安裝「自動測試版」APK（連到 e2e/mock_server.py），全程用 adb 送出真的遙控器按鍵：
  1. 第一次開啟 → 配對畫面（配對碼＋QR）→ 測試伺服器核准 → 自動登入首頁
  2. 首頁：頻道卡片大小一致、16:9、每排 4 張、可以往下捲到最後一排、沒有橫向超出
  3. 設定頁：顯示裝置資訊、解碼能力
  4. 頻道頁 → 播放：
       H.264 原檔 → 播原檔（截圖是藍色）
       AV1（伺服器已知格式）→ 這台模擬器沒有 AV1 硬體解碼 → 直接播 480p（綠色），不下載 AV1 原檔
       AV1（伺服器不知道格式）→ 先試原檔 → App 偵測畫面解不了 → 自動改 480p（綠色）
       純音樂 → 只播聲音
     媒體檔請求都要帶登入 cookie
  5. 返回鍵：播放器 → 頻道頁 → 首頁；首頁連按兩次離開 App
  6. 重新開啟 App → 不用重新配對，自動登入
結果（report.md、results.json、截圖）輸出到 --out 資料夾。任何一項失敗 → 結束代碼 1。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

import websocket
from PIL import Image

PKG = "net.iucat.ytmate.tv"
SRV = "http://127.0.0.1:8765"
ap = argparse.ArgumentParser()
ap.add_argument("--apk", required=True)
ap.add_argument("--out", required=True)
ap.add_argument("--label", default="emulator")
args = ap.parse_args()
os.makedirs(args.out, exist_ok=True)
RESULTS, INFO = [], {}


def adb(*a, timeout=90, binary=False):
    r = subprocess.run(["adb", *a], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", "ignore")


def sh(*a):
    return adb("shell", *a)


def key(name, n=1, delay=0.8):
    for _ in range(n):
        sh("input", "keyevent", name)
        time.sleep(delay)


def shot(name):
    data = adb("exec-out", "screencap", "-p", binary=True)
    p = os.path.join(args.out, f"{args.label}_{name}.png")
    with open(p, "wb") as f:
        f.write(data)
    return p


def srvlog():
    return json.load(urllib.request.urlopen(SRV + "/test/log", timeout=10))


def check(name, ok, detail=""):
    RESULTS.append({"name": name, "ok": bool(ok), "detail": str(detail)[:400]})
    print(("✔ " if ok else "✘ ") + name + (f"  —  {detail}" if detail else ""), flush=True)
    return ok


def wait_for(fn, timeout, step=1.0):
    t0 = time.time()
    last = None
    while time.time() - t0 < timeout:
        try:
            last = fn()
            if last:
                return last
        except Exception as e:
            last = e
        time.sleep(step)
    return None


def resumed():
    """目前在最上層的畫面（Activity）名稱。"""
    out = sh("dumpsys", "activity", "activities")
    m = re.search(r"(?:mResumedActivity|ResumedActivity|topResumedActivity)[^\n]*?\{[^\n]*?(\S+/\S+)", out)
    if m:
        return m.group(1)
    m = re.search(r"mCurrentFocus=[^\n]*?(\S+/\S+)\}", sh("dumpsys", "window"))
    return m.group(1) if m else ""


def logcat():
    return adb("logcat", "-d", "-s", "YTmateTV:*")


# ── WebView DevTools（自動測試版才有開）──
class Page:
    def __init__(self):
        self.ws, self.n = None, 0

    def connect(self, url_part):
        # 同一個 WebView 換頁後，DevTools 連線仍然有效：能用就沿用（重新連線在舊版 WebView 上不穩定）
        if self.ws:
            try:
                href = self.ev("location.href") or ""
                if url_part in href:
                    return href
                t0 = time.time()
                while time.time() - t0 < 30:
                    time.sleep(1)
                    href = self.ev("location.href") or ""
                    if url_part in href:
                        return href
            except Exception:
                pass
        # 連線斷了才重新連（先關掉舊連線：頁面已經有人連著時，DevTools 不會給出 webSocketDebuggerUrl）
        if self.ws:
            try:
                self.ws.close()
            except Exception:
                pass
            self.ws = None
            time.sleep(1)

        def find():
            names = re.findall(r"@(webview_devtools_remote_\d+)", sh("cat", "/proc/net/unix"))
            if not names:
                return None
            adb("forward", "tcp:9222", "localabstract:" + names[-1])
            pages = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=5))
            return next((p for p in pages if p.get("type") == "page" and url_part in p.get("url", "")
                         and p.get("webSocketDebuggerUrl")), None)
        page = wait_for(find, 120, 2)          # 模擬器剛開機時 WebView 偵錯通道可能較慢出現
        if not page:
            raise RuntimeError("找不到 WebView 頁面 " + url_part)
        self.ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=20, suppress_origin=True)
        return page["url"]

    def ev(self, expr):
        self.n += 1
        self.ws.send(json.dumps({"id": self.n, "method": "Runtime.evaluate", "params": {"expression": expr, "returnByValue": True}}))
        while True:
            m = json.loads(self.ws.recv())
            if m.get("id") == self.n:
                res = m.get("result", {}).get("result", {})
                return res.get("value")

    def state(self):
        v = self.ev("window.tvState ? tvState() : ''")
        return json.loads(v) if v else {}


# 測試影片的底色（e2e/media/）：H.264 原檔藍、AV1 原檔紅、480p 相容版綠
REF = {"blue": (29, 78, 216), "red": (185, 28, 28), "green": (21, 128, 61)}


def color_at(png, fx=0.5, fy=0.28):        # 取在中間文字上方的位置（避開白色字）
    """取畫面某一點的顏色，跟三支測試影片的底色比對；都不像（例如音樂封面、黑畫面）就回傳 other。"""
    im = Image.open(png).convert("RGB")
    w, h = im.size
    pts = [(int(w * fx + dx), int(h * fy + dy)) for dx in (-10, 0, 10) for dy in (-10, 0, 10)]
    c = tuple(sum(im.getpixel(p)[k] for p in pts) / 9 for k in range(3))
    name, dist = min(((n, sum((a - b) ** 2 for a, b in zip(c, ref)) ** 0.5) for n, ref in REF.items()), key=lambda x: x[1])
    return (name if dist < 70 else "other"), tuple(int(x) for x in c)


def ctl_lines():
    """播放器的控制列顯示／隱藏紀錄（App 每次變化都會寫 log：controller visible=true/false）。"""
    return [l.split("YTmateTV:")[-1].strip() for l in logcat().splitlines() if "controller visible=" in l]


def ctl_hidden_now():
    ls = ctl_lines()
    return bool(ls) and "visible=false" in ls[-1]


def ctl_shown_now():
    ls = ctl_lines()
    return bool(ls) and "visible=true" in ls[-1]


def player_log(pat):
    return [l.split("YTmateTV:")[-1].strip() for l in logcat().splitlines() if re.search(pat, l)]


def idle(pg):
    """從播放器回來後頁面會重新整理頻道：等它完成再選卡片（不然選好的焦點會被重新整理蓋掉）。"""
    time.sleep(0.5)
    wait_for(lambda: not pg.state().get("busy"), 10, 0.5)


def seek_subtitle_cases(pg):
    """2026-10-16 使用者需求：
    - 播放中（控制列收起）按「下」＝字幕開／關；
    - 按左右快轉／倒轉時，畫面下方出現時間軸；放開按鍵、恢復正常播放後時間軸消失（控制列不會跑出來）。"""
    idle(pg)
    adb("logcat", "-c")
    pg.ev("setFocus(document.querySelector('.grid.songs .card[data-i=\"0\"]'))")   # H.264 那支（有中英字幕）
    key("KEYCODE_ENTER", 1, 1)
    wait_for(lambda: "PlayerActivity" in resumed(), 15, 1)
    wait_for(lambda: player_log(r"playing=true"), 15, 0.5)
    wait_for(ctl_hidden_now, 10, 0.5)
    sub0 = player_log(r"subtitle item=0 ")
    check("[字幕] 播放時字幕預設開啟（英文）", sub0 and "selected=en" in sub0[-1], sub0[-1:])

    n0 = len(logcat().splitlines())
    key("KEYCODE_DPAD_DOWN", 1, 2.5)
    new = [l.split("YTmateTV:")[-1].strip() for l in logcat().splitlines()[n0:]]
    tog = [l for l in new if "subtitle toggle" in l]
    sel = [l for l in new if l.startswith("subtitle item=0")]
    saved_off = " ".join(l for l in new if "saved=" in l)
    check("[字幕] 按「下」→ 關閉字幕（控制列沒有跑出來）", tog and "-> off" in tog[-1] and (not sel or "selected=none" in sel[-1])
          and not any("key KEYCODE_DPAD_DOWN shown=true" in l for l in new), (tog + sel)[-3:])
    shot("10_sub_off")
    n0 = len(logcat().splitlines())
    key("KEYCODE_DPAD_DOWN", 1, 2.5)
    new = [l.split("YTmateTV:")[-1].strip() for l in logcat().splitlines()[n0:]]
    tog = [l for l in new if "subtitle toggle" in l]
    sel = [l for l in new if l.startswith("subtitle item=0")]
    check("[字幕] 再按「下」→ 打開字幕（回到英文）", tog and "-> en" in tog[-1] and sel and "selected=en" in sel[-1], (tog + sel)[-3:])
    check("[字幕] 字幕開關會記住（存在 App 設定）", tog and "-> en" in tog[-1] and "saved=en" in "".join(new) and "saved=off" in saved_off, saved_off)

    # 按住「右」快轉：時間軸出現 → 放開、恢復播放後消失；控制列不出現
    n0 = len(logcat().splitlines())
    sh("input", "keyevent", "--longpress", "KEYCODE_DPAD_RIGHT")
    time.sleep(0.4)
    mid = [l.split("YTmateTV:")[-1].strip() for l in logcat().splitlines()[n0:]]
    shot("11_seekbar")
    check("[時間軸] 快轉時畫面下方出現時間軸", any("seekbar visible=true" in l for l in mid), [l for l in mid if "seek" in l][-3:])
    seeks = [int(re.search(r"to=(\d+)", l).group(1)) for l in mid if l.startswith("seek to=")]
    check("[時間軸] 按住會連續快轉", len(seeks) >= 2 and seeks[-1] > seeks[0], seeks)
    gone = wait_for(lambda: any("seekbar visible=false" in l.split("YTmateTV:")[-1] for l in logcat().splitlines()[n0:]), 8, 0.5)
    allnew = [l.split("YTmateTV:")[-1].strip() for l in logcat().splitlines()[n0:]]
    check("[時間軸] 放開後恢復播放 → 時間軸自動消失", gone, [l for l in allnew if "seekbar" in l or "playing=" in l][-4:])
    check("[時間軸] 快轉時控制列不會跑出來", not any("controller visible=true" in l for l in allnew), [l for l in allnew if "controller" in l][-3:])
    # 倒轉一下（單按）：一樣出現、消失
    n0 = len(logcat().splitlines())
    key("KEYCODE_DPAD_LEFT", 1, 0.3)
    gone = wait_for(lambda: any("seekbar visible=false" in l for l in logcat().splitlines()[n0:]), 8, 0.5)
    lines = [l.split("YTmateTV:")[-1].strip() for l in logcat().splitlines()[n0:] if "seekbar" in l]
    check("[時間軸] 按「左」倒轉：時間軸出現，放開後消失", gone and any("visible=true" in l for l in lines), lines)
    for _ in range(3):
        key("KEYCODE_BACK", 1, 1.5)
        if "MainActivity" in resumed():
            break
    wait_for(lambda: "MainActivity" in resumed(), 10, 1)


def controller_cases(pg):
    """使用者回報：在小米電視上用控制列按「下一支」→ 下一支開始播了，但控制列一直不收起來，要按返回才會收。
    這裡完全照著遙控器操作重現，並檢查各種情況下控制列都會自動收起。
    模擬器預設關掉動畫；真的電視有動畫（控制列收起／出現有動畫），這段測試打開動畫，跟電視一樣。"""
    for k in ("animator_duration_scale", "transition_animation_scale", "window_animation_scale"):
        sh("settings", "put", "global", k, "1")
    adb("logcat", "-c")
    idle(pg)
    pg.ev("setFocus(document.querySelector('.grid.songs .card[data-i=\"0\"]'))")
    key("KEYCODE_ENTER", 1, 1)
    top = wait_for(lambda: "PlayerActivity" in resumed(), 15, 1)
    check("[控制列] 開啟播放器", top)
    ok = wait_for(ctl_hidden_now, 12, 0.5)
    check("[控制列] 開始播放後控制列自動收起", ok, " / ".join(ctl_lines())[-300:])

    # 按「上」叫出控制列 → 焦點在播放／暫停 → 按「右」兩下移到「下一支」→ 按確認。
    # 控制列 3.5 秒沒操作就會收起，模擬器每送一次 adb 指令要 1 秒左右，所以這幾個鍵用一次指令連續送出
    n_before = len(logcat().splitlines())
    sh("input", "keyevent", "KEYCODE_DPAD_UP", "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_RIGHT")
    time.sleep(0.3)
    sh("input", "keyevent", "KEYCODE_ENTER")              # 按下「下一支」
    time.sleep(0.5)
    keys = [l.split("YTmateTV:")[-1].strip() for l in logcat().splitlines()[n_before:] if "key KEYCODE" in l]
    check("[控制列] 按「上」叫出控制列（之後的按鍵都在控制列上）", len(keys) >= 4 and all("shown=true" in k for k in keys[1:4]), keys)
    ent = [l.split("YTmateTV:")[-1].strip() for l in logcat().splitlines()[n_before:] if "key KEYCODE_ENTER" in l]
    check("[控制列] 焦點在「下一支」按鈕上按確認", ent and "focus=exo_next" in ent[0], ent)
    moved = wait_for(lambda: player_log(r"transition item=1"), 8, 0.5)
    check("[控制列] 換到下一支（第 2 支）", moved, " / ".join(player_log("transition|use src"))[-200:])
    hidden = wait_for(ctl_hidden_now, 9, 0.5)
    check("[控制列] 按「下一支」後：下一支開始播放，控制列會自動收起（不再一直顯示）",
          hidden, " / ".join(ctl_lines() + player_log(r"key KEYCODE_ENTER"))[-380:])
    shot("08_after_next")

    # 暫停 → 控制列保持顯示（正常）；再按確認繼續播放 → 控制列自動收起
    adb("logcat", "-c")
    key("KEYCODE_DPAD_CENTER", 1, 1.0)                      # 控制列已收起：確認鍵＝暫停（會叫出控制列）
    check("[控制列] 確認鍵暫停 → 控制列顯示", ctl_shown_now(), " / ".join(ctl_lines()))
    time.sleep(5)
    check("[控制列] 暫停中控制列保持顯示（方便看進度）", ctl_shown_now(), " / ".join(ctl_lines()))
    adb("logcat", "-c")
    key("KEYCODE_DPAD_CENTER", 1, 0.5)                      # 焦點在播放鍵：繼續播放
    hidden = wait_for(ctl_hidden_now, 9, 0.5)
    check("[控制列] 按確認繼續播放 → 控制列自動收起", hidden, " / ".join(ctl_lines() + player_log("key KEYCODE"))[-300:])

    # 返回頻道頁 → 焦點停在最後播放的那一支（第 2 支）
    for _ in range(3):
        key("KEYCODE_BACK", 1, 1.5)
        if "MainActivity" in resumed():
            break
    back = wait_for(lambda: "MainActivity" in resumed(), 10, 1)
    st = wait_for(lambda: pg.state() if pg.state().get("focusI") == "1" else None, 8, 1)
    check("[返回] 回到頻道頁、焦點停在最後播放的那一支", back and st, pg.state())

    # 長影片記住看到哪裡（測試版門檻 20 秒；正式版 5 分鐘以上的影片）
    def open_card(i):
        idle(pg)
        pg.ev(f"setFocus(document.querySelector('.grid.songs .card[data-i=\"{i}\"]'))")
        key("KEYCODE_ENTER", 1, 1)
        return wait_for(lambda: "PlayerActivity" in resumed(), 15, 1)

    def leave_player():
        for _ in range(3):
            key("KEYCODE_BACK", 1, 1.5)
            if "MainActivity" in resumed():
                break
        return wait_for(lambda: "MainActivity" in resumed(), 10, 1)

    adb("logcat", "-c")
    open_card(0)
    wait_for(lambda: player_log(r"playing=true"), 15, 0.5)
    wait_for(ctl_hidden_now, 10, 0.5)             # 控制列收起後，左鍵才是「倒轉」
    key("KEYCODE_DPAD_LEFT", 4, 0.3)              # 先倒轉回開頭（這支前面可能已經記過位置），再看 13 秒
    time.sleep(13)
    adb("logcat", "-c")
    leave_player()
    # 播放器的 onStop（存位置）在頻道頁回到前景之後才執行：等一下再讀紀錄
    saved = wait_for(lambda: player_log(r"resume save vid=h264 pos=(\d+)"), 8, 0.5)
    check("[續播] 看到一半離開 → 記住位置", saved, saved)
    adb("logcat", "-c")
    open_card(0)
    time.sleep(3)
    rs = player_log(r"resume from=(\d+)")
    pos = int(re.search(r"from=(\d+)", rs[0]).group(1)) if rs else 0
    check("[續播] 再開同一支 → 從上次的位置繼續（不是從頭）", pos >= 10000, rs)
    shot("09_resume")
    leave_player()

    for k in ("animator_duration_scale", "transition_animation_scale", "window_animation_scale"):
        sh("settings", "put", "global", k, "0")


def main():
    pg = Page()
    INFO["device"] = sh("getprop", "ro.product.model").strip() + " / Android " + sh("getprop", "ro.build.version.release").strip()
    wvpkg = sh("dumpsys", "webviewupdate")
    m = re.search(r"Current WebView package.*?\(([^,]+),\s*([\d.]+)\)", wvpkg)
    INFO["webview"] = m.group(2) if m else ""
    print("裝置：", INFO, flush=True)

    # ① 安裝、第一次開啟 → 配對畫面
    print(adb("install", "-r", "-g", args.apk, timeout=240).strip())
    adb("logcat", "-c")
    sh("am", "start", "-n", f"{PKG}/.MainActivity")
    got = wait_for(lambda: [x for x in srvlog() if x["kind"] == "pair_start"], 120, 2)
    check("第一次開啟顯示配對畫面（向伺服器要配對碼）", got, got[-1] if got else "沒有收到配對請求")
    time.sleep(3)
    pg.connect("/tv/pair")
    digits = pg.ev("document.querySelectorAll('#code span').length")
    qr = pg.ev("!!document.querySelector('#qr svg')")
    check("配對畫面：6 位數配對碼＋QR code", digits == 6 and qr, f"digits={digits} qr={qr}")
    shot("01_pair")

    # ② 核准 → 自動登入首頁
    urllib.request.urlopen(urllib.request.Request(SRV + "/test/approve", method="POST"), timeout=10)
    ok = wait_for(lambda: [x for x in srvlog() if x["kind"] == "login_ok"], 40, 1)
    check("手機核准後電視自動登入", ok)
    time.sleep(3)
    pg.connect("/tv")
    st = wait_for(lambda: pg.state() if pg.state().get("view") == "home" and pg.ev("document.querySelectorAll('.grid .card').length") == 12 else None, 40, 1)
    check("首頁載入 12 個頻道", st, st)
    INFO["chrome"] = (st or {}).get("chrome")
    INFO["decoders"] = (st or {}).get("decoders")
    INFO["lite"] = (st or {}).get("lite")
    check(f"網頁核心 Chrome {INFO['chrome']}：{'自動開啟輕量模式' if INFO['lite'] else '一般模式'}",
          (INFO["chrome"] or 0) >= 80 or INFO["lite"], INFO)
    check("App 回報解碼能力（一定含 H.264）", "h264" in (INFO["decoders"] or ""), INFO["decoders"])

    # ③ 卡片大小一致、16:9、每排 4 張、沒有橫向超出
    # 用版面尺寸（offsetWidth）量：選中的卡片會刻意放大 8%，畫面上的外框不能拿來比
    rects = json.loads(pg.ev("JSON.stringify(Array.prototype.map.call(document.querySelectorAll('.grid .card'),function(c){var e=c.querySelector('.art');return [e.offsetWidth,e.offsetHeight,c.offsetTop]}))"))
    ws_, hs_ = [r[0] for r in rects], [r[1] for r in rects]
    check("頻道卡片大小全部一致", max(ws_) - min(ws_) < 1.5 and max(hs_) - min(hs_) < 1.5, f"寬 {min(ws_):.1f}–{max(ws_):.1f}、高 {min(hs_):.1f}–{max(hs_):.1f}")
    check("頻道卡片是 16:9", all(abs(h / w - 0.5625) < 0.02 for w, h in zip(ws_, hs_)), f"{hs_[0] / ws_[0]:.3f}")
    rows = sorted(set(r[2] for r in rects))
    check("每排 4 張、共 3 排", len(rows) == 3 and all(sum(1 for r in rects if r[2] == y) == 4 for y in rows), f"rows={rows}")
    sw = pg.ev("document.documentElement.scrollWidth + ',' + window.innerWidth")
    a, b = map(float, sw.split(","))
    check("沒有橫向超出畫面", a <= b + 1, sw)
    need_scroll = pg.ev("document.getElementById('scroller').scrollHeight > window.innerHeight")
    check("首頁內容超過一個畫面（需要往下捲）", need_scroll)
    shot("02_home_top")

    # ④ 遙控器往下 → 捲到最後一排
    key("KEYCODE_DPAD_DOWN", 2)
    time.sleep(1)
    st = pg.state()
    vis = json.loads(pg.ev("(function(){var r=document.querySelector('.card.focus').getBoundingClientRect();return JSON.stringify([r.top,r.bottom,window.innerHeight])})()"))
    check("按「下」兩次 → 選到最後一排、畫面跟著往下捲", st.get("scrollTop", 0) > 0 and vis[0] >= 0 and vis[1] <= vis[2] + 1,
          f"scrollTop={st.get('scrollTop')} focus={vis}")
    shot("03_home_scrolled")
    key("KEYCODE_DPAD_RIGHT", 3)
    check("按「右」移到最後一個頻道", pg.state().get("focusId") == "c11", pg.state().get("focusId"))
    key("KEYCODE_DPAD_UP", 2)
    time.sleep(0.5)
    check("按「上」回到第一排、捲回頂端", pg.state().get("scrollTop", 1) == 0, pg.state())

    # ⑤ 設定頁（裝置資訊）
    key("KEYCODE_DPAD_UP", 1)
    check("第一排再按「上」→ 選到 ⚙", pg.state().get("focusAct") == "settings", pg.state())
    key("KEYCODE_ENTER", 1, 1.5)
    opened = pg.ev("document.getElementById('settings').className.indexOf('on')>=0")
    txt = pg.ev("document.getElementById('decs').textContent + ' | ' + document.getElementById('wv').textContent + ' | ' + document.getElementById('model').textContent")
    check("設定頁顯示裝置資訊與解碼能力", opened and "H264" in (txt or ""), txt)
    shot("04_settings")
    key("KEYCODE_BACK", 1, 1.5)
    check("返回鍵關閉設定頁", not pg.ev("document.getElementById('settings').className.indexOf('on')>=0"))
    key("KEYCODE_MENU", 1, 1.5)
    check("遙控器「選單」鍵 → 開啟設定", pg.ev("document.getElementById('settings').className.indexOf('on')>=0"))
    key("KEYCODE_MENU", 1, 1.5)
    check("再按「選單」鍵 → 關閉設定", not pg.ev("document.getElementById('settings').className.indexOf('on')>=0"))

    # ⑥ 進入「格式測試」頻道
    key("KEYCODE_DPAD_DOWN", 1)
    pg.ev("setFocus(document.querySelector('[data-id=fmt]'))")
    key("KEYCODE_ENTER", 1, 3)
    st = wait_for(lambda: pg.state() if pg.state().get("view") == "channel" else None, 20, 1)
    check("確認鍵開啟頻道頁", st, st)
    chreq = [x for x in srvlog() if x["kind"] == "channel"]
    check("頻道清單請求帶著這台電視的解碼能力", chreq and chreq[-1]["dec"], chreq[-1] if chreq else "")
    badge = pg.ev("(function(){var c=document.querySelectorAll('.grid.songs .card');return c[1].innerText+' || '+c[3].innerText})()")
    av1_hw = "av1" in (INFO["decoders"] or "")
    check("AV1 影片卡片標示 480p（這台不支援 AV1）；純音樂標示音樂", (av1_hw or "480p" in badge) and "音樂" in badge, badge)
    shot("05_channel")

    # ⑦ 播放測試
    def play_case(idx, label, expect_kind, expect_color, expect_files, forbid_files=(), fallback=False, expect_sub=None):
        adb("logcat", "-c")
        n0 = len(srvlog())
        idle(pg)
        pg.ev(f"setFocus(document.querySelector('.grid.songs .card[data-i=\"{idx}\"]'))")
        key("KEYCODE_ENTER", 1, 1)
        top = wait_for(lambda: "PlayerActivity" in resumed(), 15, 1)
        check(f"[{label}] 開啟原生播放器", top)
        time.sleep(10 if fallback else 8)          # 控制列 3.5 秒後自動隱藏；測試影片 40 秒，截圖時還在同一支
        lc = logcat()
        with open(os.path.join(args.out, f"{args.label}_play_{label}_logcat.txt"), "w", encoding="utf-8") as f:
            f.write(lc)
        reqs = [x for x in srvlog()[n0:] if x["kind"] in ("file", "file_denied")]
        names = [x["name"] for x in reqs if x["kind"] == "file"]
        check(f"[{label}] 媒體檔請求都有帶登入 cookie", reqs and not [x for x in reqs if x["kind"] == "file_denied"], names)
        final_kind = None                      # 這支影片最後實際用的版本（看「use src」與「fallback」紀錄）
        for l in lc.splitlines():
            m1 = re.search(rf"item={idx} use src=(\w+)", l)
            m2 = re.search(rf"fallback item={idx} -> (\w+)", l)
            if m1 or m2:
                final_kind = (m1 or m2).group(1)
        check(f"[{label}] 使用 {expect_kind} 版本", final_kind == expect_kind,
              " / ".join(l.split("YTmateTV")[-1].strip() for l in lc.splitlines() if "src=" in l or "->" in l or "tracks" in l)[:300])
        for f in expect_files:
            check(f"[{label}] 有下載 {f}", f in names, names)
        for f in forbid_files:
            check(f"[{label}] 沒有去下載 {f}（省流量）", f not in names, names)
        if fallback:
            fb = [l.split("YTmateTV:")[-1].strip() for l in lc.splitlines() if f"item={idx}" in l and ("fallback" in l or "tracks" in l)]
            check(f"[{label}] App 偵測到畫面解不了 → 自動改用 480p（而且沒有再誤判掉到純聲音）",
                  any("-> low" in l for l in fb) and not any("-> audio" in l for l in fb), " / ".join(fb)[:380])
        if expect_sub:
            subl = [l.split("YTmateTV:")[-1].strip() for l in lc.splitlines() if f"subtitle item={idx}" in l]
            check(f"[{label}] 預設自動開字幕：{ {'en': '英文', 'zh': '中文'}[expect_sub] }", subl and f"selected={expect_sub}" in subl[-1], subl[-2:])
        p = shot(f"06_play_{label}")
        if expect_color:
            col, rgb = color_at(p)
            check(f"[{label}] 畫面確實有影像（{ {'blue': 'H.264 原檔藍色', 'green': '480p 相容版綠色'}[expect_color] }）", col == expect_color, f"{col} {rgb}")
        for _ in range(3):                  # 控制列顯示中：第一次收起、第二次離開；已隱藏：一次就離開
            key("KEYCODE_BACK", 1, 1.5)
            if "MainActivity" in resumed():
                break
        back = wait_for(lambda: "MainActivity" in resumed(), 10, 1)
        check(f"[{label}] 返回鍵回到頻道頁", back and pg.state().get("view") == "channel", pg.state().get("view"))

    play_case(0, "h264", "original", "blue", ["h264.mp4", "en.vtt"], expect_sub="en")
    if av1_hw:
        play_case(1, "av1_known", "original", None, ["av1.mp4"])
    else:
        play_case(1, "av1_known", "low", "green", ["low.mp4"], forbid_files=["av1.mp4"])
        play_case(2, "av1_unknown", "low", "green", ["av1b.mp4", "low.mp4"], fallback=True)
    play_case(3, "music", "audio", None, ["audio.mp3"])
    controller_cases(pg)
    # 設定頁：畫質「480p 省流量」、字幕「中文優先」→ 記住 → 播放照新設定
    key("KEYCODE_MENU", 1, 1.5)
    labels = lambda: pg.ev("document.getElementById('qbtn').textContent + ' | ' + document.getElementById('sbtn').textContent")
    check("[設定] 預設：畫質自動（最佳）、字幕英文優先", labels() == "畫質：自動（最佳） | 字幕：英文優先", labels())
    pg.ev("setFocus(document.getElementById('qbtn'))")
    key("KEYCODE_ENTER", 1, 1)
    pg.ev("setFocus(document.getElementById('sbtn'))")
    key("KEYCODE_ENTER", 1, 1)
    check("[設定] 切換成 480p 省流量、中文字幕優先", labels() == "畫質：480p 省流量 | 字幕：中文優先", labels())
    check("[設定] 設定存在 App 裡", pg.ev("YTmateTV.getPref('quality') + ',' + YTmateTV.getPref('subs')") == "low,zh")
    n0 = len(srvlog())
    key("KEYCODE_BACK", 1, 2.5)
    idle(pg)
    chq = [x for x in srvlog()[n0:] if x["kind"] == "channel"]
    check("[設定] 關閉設定 → 頻道用 480p 重新排播放順序（q=low）", chq and chq[-1].get("q") == "low", chq[-1:] if chq else "")
    badge = pg.ev("document.querySelector('.grid.songs .card[data-i=\"0\"]').innerText")
    check("[設定] H.264 影片卡片改標 480p", "480p" in (badge or ""), badge)
    play_case(0, "h264_pref_low", "low", "green", ["low.mp4", "zh.vtt"], forbid_files=["h264.mp4"], expect_sub="zh")
    # 在播放器字幕選單以外的方式關字幕：設定頁再按兩下（中文 → 關閉 → 英文），回到預設；畫質保留 480p（測試重開 App 後仍記得）
    key("KEYCODE_MENU", 1, 1.5)
    pg.ev("setFocus(document.getElementById('sbtn'))")
    key("KEYCODE_ENTER", 1, 1)
    check("[設定] 字幕可以關閉", "字幕：關閉" in labels(), labels())
    key("KEYCODE_ENTER", 1, 1)
    key("KEYCODE_BACK", 1, 2)
    seek_subtitle_cases(pg)

    # ⑧ 返回鍵：頻道頁 → 首頁 → 連按兩次離開
    key("KEYCODE_BACK", 1, 1.5)
    check("頻道頁按返回 → 回到首頁、停在原本的頻道", pg.state().get("view") == "home" and pg.state().get("focusId") == "fmt", pg.state())

    # ⑧b 登入過期（伺服器 session 清掉、裝置金鑰仍有效）→ App 自動用金鑰重新登入，不用重新配對
    n_login = len([x for x in srvlog() if x["kind"] == "login_ok"])
    n_pair = len([x for x in srvlog() if x["kind"] == "pair_start"])
    urllib.request.urlopen(urllib.request.Request(SRV + "/test/expire", method="POST"), timeout=10)
    pg.ev("openChannel('fmt')")
    relog = wait_for(lambda: len([x for x in srvlog() if x["kind"] == "login_ok"]) > n_login, 30, 1)
    time.sleep(3)
    pg.connect("/tv")
    st = wait_for(lambda: pg.state() if pg.state().get("view") == "home" else None, 30, 1)
    check("登入過期 → 自動用裝置金鑰重新登入、回到首頁（不用重新配對）",
          relog and st and len([x for x in srvlog() if x["kind"] == "pair_start"]) == n_pair, st)

    key("KEYCODE_BACK", 1, 0.4)
    key("KEYCODE_BACK", 1, 2)
    gone = PKG not in resumed()
    check("首頁連按兩次返回 → 離開 App", gone)

    # ⑨ 重新開啟 → 不用重新配對
    n_pair = len([x for x in srvlog() if x["kind"] == "pair_start"])
    n_login = len([x for x in srvlog() if x["kind"] == "login_ok"])
    sh("am", "start", "-n", f"{PKG}/.MainActivity")
    again = wait_for(lambda: len([x for x in srvlog() if x["kind"] == "login_ok"]) > n_login, 40, 1)
    check("重新開啟 App → 自動登入（不用重新配對）", again and len([x for x in srvlog() if x["kind"] == "pair_start"]) == n_pair)
    time.sleep(4)
    shot("07_reopen")
    n0 = len(srvlog())
    key("KEYCODE_ENTER", 1, 3)                    # 首頁第一個頻道（格式測試）
    chq = wait_for(lambda: [x for x in srvlog()[n0:] if x["kind"] == "channel"], 15, 1)
    check("重新開啟 App → 畫質設定（480p）仍記得（頻道請求帶 q=low）", chq and chq[-1].get("q") == "low", chq[-1:] if chq else "")


try:
    main()
except Exception as e:
    import traceback
    traceback.print_exc()
    check("測試流程執行完成（沒有中途出錯）", False, repr(e))
    try:
        shot("99_error")
    except Exception:
        pass

passed = sum(1 for r in RESULTS if r["ok"])
with open(os.path.join(args.out, f"{args.label}_results.json"), "w", encoding="utf-8") as f:
    json.dump({"label": args.label, "info": INFO, "passed": passed, "total": len(RESULTS), "results": RESULTS}, f, ensure_ascii=False, indent=1)
with open(os.path.join(args.out, f"{args.label}_report.md"), "w", encoding="utf-8") as f:
    f.write(f"# YTmate TV 模擬器測試：{args.label}\n\n裝置：{INFO.get('device')}　WebView：{INFO.get('webview')}　"
            f"Chrome {INFO.get('chrome')}　解碼：{INFO.get('decoders')}\n\n**{passed}/{len(RESULTS)} 通過**\n\n")
    for r in RESULTS:
        f.write(f"- {'✅' if r['ok'] else '❌'} {r['name']}" + (f" — `{r['detail']}`" if r["detail"] else "") + "\n")
with open(os.path.join(args.out, f"{args.label}_logcat.txt"), "w", encoding="utf-8") as f:
    f.write(adb("logcat", "-d", timeout=60)[-200000:])
print(f"\n{passed}/{len(RESULTS)} 通過")
sys.exit(0 if passed == len(RESULTS) and RESULTS else 1)
