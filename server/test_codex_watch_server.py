from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from urllib.error import HTTPError
from urllib.request import Request, urlopen


SERVER_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SERVER_DIR))

from codex_watch_server import CodexWatchHandler, ThreadingHTTPServer, make_update_metadata


class UpdateMetadataTests(unittest.TestCase):
    def test_metadata_uses_real_apk_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "watch.apk"
            apk.write_bytes(b"test-apk")
            server = SimpleNamespace(
                update_apk_path=str(apk),
                update_apk_url="https://watch.example/downloads/codex-pet-watch.apk",
                update_version_code=2,
                update_version_name="0.2.0",
                update_required=False,
                update_notes="Test release",
            )

            payload = make_update_metadata(server)

            self.assertIsNotNone(payload)
            self.assertEqual(2, payload["version_code"])
            self.assertEqual(hashlib.sha256(b"test-apk").hexdigest(), payload["sha256"])

    def test_metadata_rejects_non_https_url(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "watch.apk"
            apk.write_bytes(b"test-apk")
            server = SimpleNamespace(
                update_apk_path=str(apk),
                update_apk_url="http://watch.example/watch.apk",
                update_version_code=2,
                update_version_name="0.2.0",
                update_required=False,
                update_notes="",
            )

            with self.assertRaises(ValueError):
                make_update_metadata(server)


class UpdateEndpointTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.apk_bytes = b"fake-apk-payload"
        self.apk = Path(self.temp_dir.name) / "watch.apk"
        self.apk.write_bytes(self.apk_bytes)

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), CodexWatchHandler)
        self.httpd.token = "test-token"
        self.httpd.quiet = True
        self.httpd.update_apk_path = str(self.apk)
        self.httpd.update_apk_url = "https://watch.example/downloads/codex-pet-watch.apk"
        self.httpd.update_version_code = 2
        self.httpd.update_version_name = "0.2.0"
        self.httpd.update_required = False
        self.httpd.update_notes = "Test release"
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.httpd.server_port}"

    def tearDown(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=5)
        self.temp_dir.cleanup()

    def request(self, path: str):
        request = Request(
            self.base_url + path,
            headers={"X-Codex-Watch-Token": "test-token"},
        )
        return urlopen(request, timeout=5)

    def test_update_endpoints_require_header_token(self) -> None:
        with self.assertRaises(HTTPError) as error:
            urlopen(self.base_url + "/update", timeout=5)
        self.assertEqual(401, error.exception.code)

    def test_metadata_and_apk_download(self) -> None:
        with self.request("/update") as response:
            metadata = json.load(response)
            self.assertEqual(2, metadata["version_code"])
            self.assertEqual(hashlib.sha256(self.apk_bytes).hexdigest(), metadata["sha256"])

        with self.request("/downloads/codex-pet-watch.apk") as response:
            self.assertEqual("application/vnd.android.package-archive", response.headers.get_content_type())
            self.assertEqual(str(len(self.apk_bytes)), response.headers["Content-Length"])
            self.assertEqual(self.apk_bytes, response.read())

    def test_unconfigured_endpoint_returns_404(self) -> None:
        self.httpd.update_apk_path = ""
        with self.assertRaises(HTTPError) as error:
            self.request("/update")
        self.assertEqual(404, error.exception.code)


if __name__ == "__main__":
    unittest.main()
