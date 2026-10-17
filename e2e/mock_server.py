# -*- coding: utf-8 -*-
"""
YTmate TV 自動測試用的「測試伺服器」（只在 GitHub Actions 的模擬器測試裡使用）。

- 提供跟正式伺服器一樣的電視 API：配對（/tv/pair、/api/tv/pair/*）、裝置登入（/api/tv/login）、
  首頁（/api/tv/home）、頻道（/api/tv/channel/<id>?dec=…，依電視解碼能力排版本）、媒體檔案（需要登入 cookie）。
- 頁面直接用正式伺服器的 tv.html／tv_pair.html（e2e/www/，從伺服器專案複製過來）。
- 測試用影片（e2e/media/）：
    h264.mp4 藍底「H264 ORIGINAL」、av1.mp4 紅底「AV1 ORIGINAL」、low.mp4 綠底「480p COMPATIBLE」
  → 截圖顏色就看得出電視實際播的是哪個版本。
- /test/* 給測試腳本用：核准配對、讀取請求紀錄。
- 2026-10-17（v1.1）：📱 手機遙控（/api/remote/poll 長輪詢、state、ack、queue/take）、看到哪裡（/api/resume）、
  首頁各排（/test/rows 打開後 /api/tv/home 才帶 rows，舊的測試流程不受影響）、任意影片（/api/tv/videos）、
  影片資訊（/api/tv/info：H.264 那支有章節與摘要）。/test/cmd 模擬手機送指令、/test/remote 讀電視回報。
"""
import json
import os
import secrets
import threading
import time

from flask import Flask, jsonify, redirect, request, send_from_directory, abort

HERE = os.path.dirname(os.path.abspath(__file__))
WWW, MEDIA = os.path.join(HERE, "www"), os.path.join(HERE, "media")
app = Flask(__name__)
LOG, LOCK = [], threading.Lock()
COND = threading.Condition()
CMDS, ACKS, STATE, QUEUE, RESUME = [], {}, {}, [], {}
ROWS_ON = [False]
PAIRS, TOKENS, SESSIONS = {}, {}, set()

THUMBS = ["thumb_7c3aed.jpg", "thumb_db2777.jpg", "thumb_0891b2.jpg", "thumb_ea580c.jpg"]
# 頻道：12 個（首頁一定要能往下捲才看得到最後幾個）；第 1 個是「格式測試」
CHANNELS = [{"id": "fmt", "name": "🎬 格式測試", "description": "H.264／AV1／480p 相容版"}] + \
           [{"id": f"c{i}", "name": f"測試頻道 {i:02d}", "description": f"第 {i} 個測試頻道"} for i in range(1, 12)]
# 格式測試頻道的影片：
#   h264         原檔 H.264（所有電視都能播原檔）
#   av1          原檔 AV1，伺服器知道是 AV1 → 依電視回報挑版本（舊電視直接 480p）
#   av1_unknown  原檔 AV1，但伺服器「還不知道格式」→ 一律原檔優先，靠 App 播放時偵測後自動改 480p（保險機制）
#   music        純音樂
SONGS = [
    {"video_id": "h264", "title": "H.264 原檔影片", "codec": "h264", "file": "h264.mp4"},
    {"video_id": "av1", "title": "AV1 原檔影片（伺服器已知格式）", "codec": "av1", "file": "av1.mp4"},
    {"video_id": "av1_unknown", "title": "AV1 原檔影片（伺服器還不知道格式）", "codec": None, "file": "av1b.mp4"},
    {"video_id": "music", "title": "純音樂", "codec": None, "file": None},
]


def log(event, **kw):
    with LOCK:
        LOG.append(dict(t=round(time.time(), 2), kind=event, **kw))


def logged_in():
    return request.cookies.get("sid") in SESSIONS


@app.before_request
def _rec():
    log("req", method=request.method, path=request.path, qs=request.query_string.decode(), cookie=bool(logged_in()),
        range=request.headers.get("Range", ""), ua=request.headers.get("User-Agent", "")[:120])


# ── 頁面 ──
@app.route("/tv/pair")
def tv_pair():
    return send_from_directory(WWW, "tv_pair.html")


@app.route("/tv")
def tv():
    if not logged_in():
        return redirect("/tv/pair")
    return send_from_directory(WWW, "tv.html")


@app.route("/assets/<path:p>")
def assets(p):
    return send_from_directory(WWW, p)


# ── 配對與登入 ──
@app.route("/api/tv/pair/start", methods=["POST"])
def pair_start():
    pid, code, ps = secrets.token_urlsafe(8), f"{secrets.randbelow(10**6):06d}", secrets.token_urlsafe(12)
    name = (request.get_json(silent=True) or {}).get("name", "")
    PAIRS[pid] = {"code": code, "ps": ps, "token": None}
    log("pair_start", code=code, name=name)
    return jsonify({"pair_id": pid, "code": code, "poll_secret": ps, "expires_in": 600})


@app.route("/api/tv/pair/poll", methods=["POST"])
def pair_poll():
    b = request.get_json(silent=True) or {}
    p = PAIRS.get(b.get("pair_id"))
    if not p or p["ps"] != b.get("poll_secret"):
        return jsonify({"status": "expired"})
    if p["token"]:
        tok = p["token"]; PAIRS.pop(b["pair_id"], None)
        return jsonify({"status": "approved", "token": tok, "device_id": "dvtest"})
    return jsonify({"status": "pending"})


@app.route("/api/tv/login", methods=["POST"])
def tv_login():
    tok = (request.get_json(silent=True) or {}).get("token") if request.is_json else request.form.get("token")
    if tok not in TOKENS:
        log("login_fail")
        return (jsonify({"ok": False}), 401) if request.is_json else redirect("/tv/pair?reason=revoked")
    sid = secrets.token_urlsafe(16); SESSIONS.add(sid)
    log("login_ok")
    resp = jsonify({"ok": True}) if request.is_json else redirect("/tv")
    resp.set_cookie("sid", sid, max_age=86400 * 365, httponly=True)
    return resp


# ── 資料 ──
@app.route("/api/tv/home")
def home():
    if not logged_in():
        return jsonify({"error": "需要登入"}), 401
    out = []
    for i, c in enumerate(CHANNELS):
        out.append({**c, "cover": None, "thumbs": [f"/api/files/thumb/{THUMBS[(i + k) % 4]}" for k in range(4 if i % 3 else 1)],
                    "song_count": len(SONGS) if c["id"] == "fmt" else 3, "playable": 3})
    resp = {"channels": out, "device_id": "dvtest"}
    if ROWS_ON[0]:
        card = lambda vid, t, **kw: {"video_id": vid, "title": t, "duration": 40, "thumb": f"/api/files/thumb/{THUMBS[len(vid) % 4]}",
                                     "channel_name": "🎬 格式測試", "resume": 0, "watched": False, "is_new": False, "description": "", **kw}
        resp["rows"] = {
            "hero": [card("h264", "最新節目：H.264", description="第一集的說明"), card("av1", "最新節目：AV1", description="第二集的說明")],
            "cont": [card("h264", "H.264 原檔影片", resume=12, resume_dur=40), card("av1", "AV1 原檔影片", resume=8, resume_dur=40)],
            "recent": [card("av1_unknown", "AV1（不知道格式）", is_new=True), card("music", "純音樂")],
            "shows": [card("h264", "最新節目：H.264"), card("av1", "最新節目：AV1")],
        }
    return jsonify(resp)


def sources_for(s, dec, prefer_low=False):
    if not s["file"]:
        return [{"url": "/api/files/audio/audio.mp3", "kind": "audio", "codec": None}]
    orig = {"url": f"/api/files/video/{s['file']}", "kind": "original", "codec": s["codec"]}
    low = {"url": "/api/files/video/low.mp4", "kind": "low", "codec": "h264"}
    aud = {"url": "/api/files/audio/audio.mp3", "kind": "audio", "codec": None}
    ok = s["codec"] is None or s["codec"] in dec
    if prefer_low and ok:                      # 使用者選了「480p 省流量」
        return [low, orig, aud]
    return ([orig, low] if ok else [low]) + [aud]


@app.route("/api/tv/channel/<cid>")
def channel(cid):
    if not logged_in():
        return jsonify({"error": "需要登入"}), 401
    dec = {x for x in (request.args.get("dec") or "h264,vp9,av1").split(",") if x} | {"h264"}
    q = request.args.get("q") or "auto"
    log("channel", cid=cid, dec=sorted(dec), q=q)
    songs = SONGS if cid == "fmt" else SONGS[:1] * 3
    out = []
    for i, s in enumerate(songs):
        src = sources_for(s, dec, q == "low")
        out.append({"id": f"s{i}", "video_id": s["video_id"], "title": s["title"], "duration": 12,
                    "thumb_file": f"/api/files/thumb/{THUMBS[i % 4]}", "sources": src,
                    # H.264 那支附中英字幕（測試「預設開字幕、英文優先」）
                    "subtitles": {"zh-Hant": "/api/files/sub/zh.vtt", "en": "/api/files/sub/en.vtt"} if s["video_id"] == "h264" else {},
                    "codec": s["codec"], "need_low": False, "audio_only": src[0]["kind"] == "audio",
                    "note": "這台電視不支援原檔格式（av1），改播 480p 相容版" if src[0]["kind"] == "low" else ""})
    return jsonify({"id": cid, "name": cid, "songs": out, "decoders": sorted(dec), "quality": q})


def song_obj(i, s, dec, q):
    src = sources_for(s, dec, q == "low")
    r = RESUME.get(s["video_id"]) or {}
    return {"id": f"s{i}", "video_id": s["video_id"], "title": s["title"], "duration": 40,
            "thumb_file": f"/api/files/thumb/{THUMBS[i % 4]}", "sources": src, "channel_name": "🎬 格式測試",
            "subtitles": {"zh-Hant": "/api/files/sub/zh.vtt", "en": "/api/files/sub/en.vtt"} if s["video_id"] == "h264" else {},
            "codec": s["codec"], "need_low": False, "audio_only": src[0]["kind"] == "audio", "note": "",
            "resume": 0 if r.get("done") else r.get("pos", 0), "resume_at": r.get("at", 0), "watched": bool(r.get("done"))}


@app.route("/api/tv/videos", methods=["POST"])
def tv_videos():
    if not logged_in():
        return jsonify({"error": "需要登入"}), 401
    b = request.get_json(silent=True) or {}
    dec = {x for x in (b.get("dec") or "h264,vp9,av1").split(",") if x} | {"h264"}
    log("videos", vids=b.get("video_ids"), q=b.get("q"))
    by = {s["video_id"]: (i, s) for i, s in enumerate(SONGS)}
    return jsonify({"songs": [song_obj(*by[v], dec, b.get("q")) for v in b.get("video_ids") or [] if v in by]})


@app.route("/api/tv/info/<vid>")
def tv_info(vid):
    if not logged_in():
        return jsonify({"error": "需要登入"}), 401
    log("info", vid=vid)
    if vid == "h264":
        return jsonify({"video_id": vid, "summary": "這是 H.264 測試影片的中文摘要：藍色畫面、中英字幕。",
                        "chapters": [{"start": 0, "end": 15, "title": "開場"}, {"start": 15, "end": 40, "title": "重點段落"}], "resume": RESUME.get(vid) or {}})
    return jsonify({"video_id": vid, "summary": "", "chapters": [], "resume": {}})


@app.route("/api/resume/<vid>", methods=["GET", "POST"])
def resume(vid):
    if not logged_in():
        return jsonify({"error": "需要登入"}), 401
    if request.method == "POST":
        b = request.get_json(silent=True) or {}
        RESUME[vid] = {"pos": 0 if b.get("done") else b.get("pos", 0), "dur": b.get("dur", 0), "done": bool(b.get("done")), "at": time.time()}
        log("resume", vid=vid, pos=b.get("pos"), done=bool(b.get("done")))
    return jsonify(RESUME.get(vid) or {})


# ── 📱 手機遙控（電視端）──
@app.route("/api/remote/poll")
def remote_poll():
    if not logged_in():
        return jsonify({"error": "需要登入"}), 401
    wait = min(float(request.args.get("wait") or 20), 25)
    deadline = time.time() + wait
    with COND:
        while True:
            ready = [c for c in CMDS if c["status"] == "pending"]
            if ready or time.time() >= deadline:
                for c in ready:
                    c["status"] = "delivered"
                break
            COND.wait(timeout=max(0.05, deadline - time.time()))
    if ready:
        log("poll_cmds", ids=[c["id"] for c in ready])
    return jsonify({"cmds": [{"id": c["id"], "type": c["type"], "args": c["args"]} for c in ready]})


@app.route("/api/remote/state", methods=["POST"])
def remote_state():
    if not logged_in():
        return jsonify({"error": "需要登入"}), 401
    b = request.get_json(silent=True) or {}
    with COND:
        STATE.clear(); STATE.update(b.get("state") or {}); STATE["at"] = time.time()
        for a in b.get("acks") or []:
            ACKS[a.get("id")] = a
    if b.get("acks"):
        log("acks", acks=b["acks"])
    return jsonify({"ok": True})


@app.route("/api/remote/ack", methods=["POST"])
def remote_ack():
    for a in (request.get_json(silent=True) or {}).get("results") or []:
        ACKS[a.get("id")] = a
    return jsonify({"ok": True})


@app.route("/api/remote/queue/take", methods=["POST"])
def remote_queue_take():
    if not logged_in():
        return jsonify({"songs": []})
    b = request.get_json(silent=True) or {}
    dec = {x for x in (b.get("dec") or "h264,vp9,av1").split(",") if x} | {"h264"}
    vids = list(QUEUE); QUEUE.clear()
    log("queue_take", vids=vids)
    by = {s["video_id"]: (i, s) for i, s in enumerate(SONGS)}
    return jsonify({"songs": [song_obj(*by[v], dec, b.get("q")) for v in vids if v in by]})


@app.route("/api/tv/prepare", methods=["POST"])
def prepare():
    return jsonify({"queued": []})


@app.route("/api/tv/devices/<did>", methods=["DELETE"])
def dev_del(did):
    return jsonify({"revoked": did})


@app.route("/api/logout", methods=["POST"])
def logout():
    return jsonify({"ok": True})


@app.route("/api/files/<kind>/<name>")
def files(kind, name):
    if not logged_in():                       # 正式伺服器的媒體檔也要登入：驗證 App 播放器有帶 cookie
        log("file_denied", ftype=kind, name=name)
        abort(401)
    log("file", ftype=kind, name=name)
    return send_from_directory(MEDIA, os.path.basename(name), conditional=True)


# ── 測試腳本用 ──
@app.route("/test/approve", methods=["POST"])
def t_approve():
    for p in PAIRS.values():
        if not p["token"]:
            tok = secrets.token_urlsafe(24); TOKENS[tok] = True; p["token"] = tok
            log("approved")
            return jsonify({"ok": True})
    return jsonify({"ok": False}), 404


@app.route("/test/expire", methods=["POST"])
def t_expire():
    """模擬登入過期：清掉所有 session（裝置金鑰仍有效）→ 電視應該自動用金鑰重新登入。"""
    SESSIONS.clear()
    log("expired")
    return jsonify({"ok": True})


@app.route("/test/cmd", methods=["POST"])
def t_cmd():
    """模擬手機送指令：{type, args} → 回傳指令 id。"""
    b = request.get_json(silent=True) or {}
    c = {"id": secrets.token_hex(5), "type": b.get("type"), "args": b.get("args") or {}, "status": "pending"}
    with COND:
        CMDS.append(c); COND.notify_all()
    log("test_cmd", id=c["id"], type=c["type"], args=c["args"])
    return jsonify({"id": c["id"]})


@app.route("/test/remote")
def t_remote():
    with COND:
        return jsonify({"state": dict(STATE), "acks": dict(ACKS), "cmds": [dict(c) for c in CMDS]})


@app.route("/test/queue", methods=["POST"])
def t_queue():
    QUEUE[:] = (request.get_json(silent=True) or {}).get("video_ids") or []
    return jsonify({"queue": QUEUE})


@app.route("/test/rows", methods=["POST"])
def t_rows():
    ROWS_ON[0] = bool((request.get_json(silent=True) or {}).get("on", True))
    return jsonify({"rows": ROWS_ON[0]})


@app.route("/test/log")
def t_log():
    with LOCK:
        return jsonify(LOG)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 8765)), threaded=True)
