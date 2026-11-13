import unittest
import app

class AlertTests(unittest.TestCase):
    def setUp(self):
        app._seen.clear()
        self.payload={"alert_id":"a1","symbol":"BTCUSDT","side":"LONG","setup":"Trend pullback",
          "horizon":"Intraday","status":"READY","created_at":1700000000,
          "entry":100.0,"stop":98.0,"target1":104.0,
          "risk_gates":{"liquidity":True,"funding":True,"net_rr":True}}
    def test_valid_ready_intraday(self):
        self.assertEqual(app.validate_alert(self.payload,1700000100),self.payload)
    def test_rejects_wait(self):
        self.payload["status"]="WAIT"
        with self.assertRaises(app.AlertError): app.validate_alert(self.payload,1700000100)
    def test_rejects_scalp(self):
        self.payload["horizon"]="Scalp"
        with self.assertRaises(app.AlertError): app.validate_alert(self.payload,1700000100)
    def test_rejects_failed_gate(self):
        self.payload["risk_gates"]["liquidity"]=False
        with self.assertRaises(app.AlertError): app.validate_alert(self.payload,1700000100)
    def test_rejects_stale(self):
        with self.assertRaises(app.AlertError): app.validate_alert(self.payload,1700000400)
    def test_rejects_nan_price(self):
        self.payload["entry"]=float("nan")
        with self.assertRaises(app.AlertError): app.validate_alert(self.payload,1700000100)
    def test_dedup_and_expiry(self):
        self.assertTrue(app.remember_once("x",10))
        self.assertFalse(app.remember_once("x",11))
        self.assertTrue(app.remember_once("x",90000))

if __name__=="__main__": unittest.main()
