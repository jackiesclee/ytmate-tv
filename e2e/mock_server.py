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
    {"video_id": "av1_unknown", "title": "AV1 原檔影片（伺服器還不知道格式）", "codec": None, "file": "av1.mp4"},
    {"video_id": "music", "title": "純音樂", "codec": None, "file": None},
]


def log(kind, **kw):
    with LOCK:
        LOG.append(dict(t=round(time.time(), 2), kind=kind, **kw))


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
    return jsonify({"channels": out, "device_id": "dvtest"})


def sources_for(s, dec):
    if not s["file"]:
        return [{"url": "/api/files/audio/audio.mp3", "kind": "audio", "codec": None}]
    orig = {"url": f"/api/files/video/{s['file']}", "kind": "original", "codec": s["codec"]}
    low = {"url": "/api/files/video/low.mp4", "kind": "low", "codec": "h264"}
    aud = {"url": "/api/files/audio/audio.mp3", "kind": "audio", "codec": None}
    ok = s["codec"] is None or s["codec"] in dec
    return ([orig, low] if ok else [low]) + [aud]


@app.route("/api/tv/channel/<cid>")
def channel(cid):
    if not logged_in():
        return jsonify({"error": "需要登入"}), 401
    dec = {x for x in (request.args.get("dec") or "h264,vp9,av1").split(",") if x} | {"h264"}
    log("channel", cid=cid, dec=sorted(dec))
    songs = SONGS if cid == "fmt" else SONGS[:1] * 3
    out = []
    for i, s in enumerate(songs):
        src = sources_for(s, dec)
        out.append({"id": f"s{i}", "video_id": s["video_id"], "title": s["title"], "duration": 12,
                    "thumb_file": f"/api/files/thumb/{THUMBS[i % 4]}", "subtitles": {}, "sources": src,
                    "codec": s["codec"], "need_low": False, "audio_only": src[0]["kind"] == "audio",
                    "note": "這台電視不支援原檔格式（av1），改播 480p 相容版" if src[0]["kind"] == "low" else ""})
    return jsonify({"id": cid, "name": cid, "songs": out, "decoders": sorted(dec)})


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
        log("file_denied", kind=kind, name=name)
        abort(401)
    log("file", kind=kind, name=name)
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


@app.route("/test/log")
def t_log():
    with LOCK:
        return jsonify(LOG)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 8765)), threaded=True)
