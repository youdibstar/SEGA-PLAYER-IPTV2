"""Run: cd panel && python -m unittest discover -s tests -v"""
import base64
import importlib
import os
import tempfile
import unittest
import uuid

from fastapi.testclient import TestClient


class PanelApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        os.environ["DATABASE_PATH"] = os.path.join(cls.tmp.name, "app.db")
        os.environ["ADMIN_USERNAME"] = "qa-admin"
        os.environ["ADMIN_PASSWORD"] = "test-password-only"
        import main
        importlib.reload(main)
        cls.client = TestClient(main.app)
        cls.headers = {
            "Authorization": "Basic " + base64.b64encode(b"qa-admin:test-password-only").decode()
        }

    @classmethod
    def tearDownClass(cls):
        cls.client.close()
        cls.tmp.cleanup()

    def test_full_lifecycle(self):
        identity = {
            "device_id": str(uuid.uuid4()),
            "mac_alias": "12:23:34:45:56:67",
            "device_key": "sample-key-with-more-than-thirty-characters-00001"
        }
        self.assertEqual(self.client.post("/api/v1/register", json=identity).status_code, 200)
        self.assertEqual(self.client.post("/api/v1/register", json=identity).status_code, 200)
        query = {"device_id": identity["device_id"]}
        device_headers = {"X-Device-Key": identity["device_key"]}
        pending = self.client.get("/api/v1/config", params=query, headers=device_headers).json()
        self.assertFalse(pending["active"])
        self.assertEqual(pending["app_name"], "SEGA IPTV Player")
        self.assertEqual(self.client.get("/admin/api/state").status_code, 401)
        self.assertEqual(self.client.get("/api/v1/config", params=query,
                        headers={"X-Device-Key": "invalid"}).status_code, 403)

        response = self.client.post("/admin/api/playlists", headers=self.headers,
            json={"name": "Demo", "url": "https://media.example.org/lists/legal.m3u", "is_global": True})
        self.assertEqual(response.status_code, 200)
        playlist_id = response.json()["id"]
        response = self.client.put(f"/admin/api/devices/{identity['device_id']}", headers=self.headers,
            json={"active": True, "expires_at": None, "label": "Living room"})
        self.assertEqual(response.status_code, 200)
        active = self.client.get("/api/v1/config", params=query, headers=device_headers).json()
        self.assertTrue(active["active"])
        self.assertEqual(active["playlists"][0]["id"], playlist_id)

        self.assertEqual(self.client.put("/admin/api/settings", headers=self.headers, json={
            "app_name": "Brand Demo", "accent_color": "#12AFCC",
            "background_url": "https://media.example.org/background.jpg", "logo_url": ""}).status_code, 200)
        updated = self.client.get("/api/v1/config", params=query, headers=device_headers).json()
        self.assertEqual(updated["app_name"], "Brand Demo")
        self.assertEqual(updated["accent_color"], "#12AFCC")

        self.client.put(f"/admin/api/devices/{identity['device_id']}", headers=self.headers,
            json={"active": False, "expires_at": None, "label": "Living room"})
        revoked = self.client.get("/api/v1/config", params=query, headers=device_headers).json()
        self.assertFalse(revoked["active"])
        self.assertEqual(revoked["playlists"], [])

    def test_invalid_links(self):
        res = self.client.post("/admin/api/playlists", headers=self.headers,
            json={"name": "Bad", "url": "file:///secret.m3u", "is_global": True})
        self.assertEqual(res.status_code, 422)


if __name__ == "__main__":
    unittest.main()
