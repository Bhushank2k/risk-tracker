"""Database-free Telegram alert relay. It does not generate trading signals."""
from __future__ import annotations
import html, json, os, threading, time, urllib.error, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

_seen: dict[str, float] = {}
_alerts: dict[str, dict[str, Any]] = {}
_lock = threading.Lock()

class AlertError(ValueError): pass

def number(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise AlertError(f"{name} must be numeric")
    result = float(value)
    if not result == result or result in (float("inf"), float("-inf")):
        raise AlertError(f"{name} must be finite")
    return result

def validate_alert(payload: Any, now: float | None = None) -> dict[str, Any]:
    if not isinstance(payload, dict): raise AlertError("JSON object required")
    required = ("alert_id","symbol","side","setup","horizon","status")
    if any(not isinstance(payload.get(k), str) or not payload[k].strip() for k in required):
        raise AlertError("missing or invalid required string field")
    if payload["status"] != "READY": raise AlertError("only READY alerts are accepted")
    if payload["horizon"].lower() != "intraday": raise AlertError("only Intraday alerts are accepted")
    if payload["side"] not in ("LONG","SHORT"): raise AlertError("side must be LONG or SHORT")
    created = number(payload["created_at"], "created_at")
    current = time.time() if now is None else now
    if created > current + 30 or current - created > int(os.getenv("ALERT_MAX_AGE_SECONDS","300")):
        raise AlertError("alert is stale or timestamp is in the future")
    gates = payload.get("risk_gates")
    if not isinstance(gates, dict) or not gates or any(v is not True for v in gates.values()):
        raise AlertError("every hard risk gate must be explicitly true")
    for field in ("entry","stop","target1"): number(payload.get(field), field)
    return payload

def remember_once(alert_id: str, now: float | None = None) -> bool:
    current = time.time() if now is None else now
    ttl, limit = int(os.getenv("DEDUP_TTL_SECONDS","86400")), int(os.getenv("MAX_DEDUP_ENTRIES","2000"))
    with _lock:
        for key in [k for k,v in _seen.items() if current-v > ttl]: _seen.pop(key,None)
        if alert_id in _seen: return False
        if len(_seen) >= limit: _seen.pop(min(_seen,key=_seen.get),None)
        _seen[alert_id] = current
        return True

def send_telegram(alert: dict[str, Any]) -> None:
    token, chat_id = os.environ["TELEGRAM_BOT_TOKEN"], os.environ["TELEGRAM_CHAT_ID"]
    esc = lambda x: html.escape(str(x))
    message = (f"<b>Risk Tracker · READY {esc(alert['horizon'])}</b>\n"
        f"<b>{esc(alert['symbol'])} · {esc(alert['side'])}</b>\n"
        f"Setup: {esc(alert['setup'])}\nEntry: {esc(alert['entry'])} · Stop: {esc(alert['stop'])}\n"
        f"Target 1: {esc(alert['target1'])}\nAlert ID: <code>{esc(alert['alert_id'])}</code>\n"
        "<i>Informational signal only. Verify live market conditions.</i>")
    data = urllib.parse.urlencode({"chat_id":chat_id,"text":message,"parse_mode":"HTML","disable_web_page_preview":"true"}).encode()
    req = urllib.request.Request(f"https://api.telegram.org/bot{token}/sendMessage",data=data,
        headers={"Content-Type":"application/x-www-form-urlencoded"},method="POST")
    with urllib.request.urlopen(req,timeout=10) as response:
        result = json.loads(response.read().decode())
        if response.status != 200 or not result.get("ok"): raise RuntimeError("Telegram rejected alert")

class Handler(BaseHTTPRequestHandler):
    server_version = "RiskTrackerCloudAlerts/0.1"
    def reply(self,status:int,body:dict[str,Any])->None:
        data=json.dumps(body,separators=(",",":")).encode()
        self.send_response(status); self.send_header("Content-Type","application/json")
        self.send_header("Content-Length",str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_GET(self)->None:
        if self.path=="/healthz": return self.reply(200,{"ok":True,"service":"telegram-alert-relay"})
        if self.path=="/alerts":
            expected=os.getenv("ALERT_API_TOKEN","")
            if not expected or self.headers.get("Authorization","")!=f"Bearer {expected}":
                return self.reply(401,{"error":"unauthorized"})
            with _lock:
                rows=sorted(_alerts.values(),key=lambda item:item["created_at"],reverse=True)
            return self.reply(200,{"alerts":rows[:100],"updated_at":time.time()})
        self.reply(404,{"error":"not_found"})
    def do_POST(self)->None:
        if self.path!="/alerts": return self.reply(404,{"error":"not_found"})
        expected=os.getenv("ALERT_API_TOKEN","")
        if not expected or self.headers.get("Authorization","")!=f"Bearer {expected}":
            return self.reply(401,{"error":"unauthorized"})
        try:
            size=int(self.headers.get("Content-Length","0"))
            if size<=0 or size>32768: raise AlertError("invalid request body size")
            payload=validate_alert(json.loads(self.rfile.read(size)))
            if not remember_once(payload["alert_id"]): return self.reply(200,{"ok":True,"duplicate":True})
            send_telegram(payload)
            with _lock:
                _alerts[payload["alert_id"]] = payload
                if len(_alerts) > 100:
                    oldest = min(_alerts, key=lambda key: _alerts[key]["created_at"])
                    _alerts.pop(oldest, None)
            self.reply(202,{"ok":True,"delivered":True})
        except (AlertError,ValueError,json.JSONDecodeError) as exc: self.reply(400,{"error":str(exc)})
        except (KeyError,urllib.error.URLError,TimeoutError,RuntimeError): self.reply(502,{"error":"telegram_delivery_failed"})
    def log_message(self,_format:str,*_args:Any)->None: return

if __name__=="__main__":
    ThreadingHTTPServer(("0.0.0.0",int(os.getenv("PORT","8080"))),Handler).serve_forever()
