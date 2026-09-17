import os
import unittest
from unittest.mock import patch
from load import SessionClient, run


class AuthenticationToolTests(unittest.TestCase):
    @patch.dict(os.environ, {}, clear=True)
    def test_credentials_are_required_without_a_default_password(self):
        with self.assertRaisesRegex(ValueError, "SIGNALFORGE_AUTH_EMAIL"):
            SessionClient.from_environment()

    @patch("load.request")
    def test_login_refreshes_csrf_after_session_rotation(self, request):
        request.side_effect = [
            {"status": 200, "response": {"headerName": "X-CSRF-TOKEN", "token": "before"}},
            {"status": 200, "response": {"id": "test-user"}},
            {"status": 200, "response": {"headerName": "X-CSRF-TOKEN", "token": "after"}},
        ]
        client = SessionClient("http://localhost:8080", "test@example.com", "test-only-password")
        client.login()
        self.assertEqual(request.call_args_list[1].kwargs["headers"], {"X-CSRF-TOKEN": "before"})
        self.assertEqual(client.csrf_headers, {"X-CSRF-TOKEN": "after"})
        self.assertIsNotNone(request.call_args_list[1].kwargs["opener"])

    @patch("load.request", return_value={"status": 200, "response": {}})
    def test_only_mutations_include_csrf_and_other_origins_are_rejected(self, request):
        client = SessionClient("http://localhost:8080", "test@example.com", "test-only-password")
        client.csrf_headers = {"X-CSRF-TOKEN": "test-token"}
        client.request("http://localhost:8080/events")
        self.assertEqual(request.call_args.kwargs["headers"], {})
        client.request("http://localhost:8080/events", "POST", {})
        self.assertEqual(request.call_args.kwargs["headers"], client.csrf_headers)
        with self.assertRaises(ValueError):
            client.request("http://localhost:8080.evil.example/events")

    @patch("load.request")
    def test_failed_login_does_not_expose_credentials(self, request):
        request.side_effect = [
            {"status": 200, "response": {"headerName": "X-CSRF-TOKEN", "token": "test"}},
            {"status": 401, "response": {}},
        ]
        client = SessionClient("http://localhost:8080", "test@example.com", "test-only-password")
        with self.assertRaises(ValueError) as raised:
            client.login()
        self.assertNotIn(client.password, str(raised.exception))
        self.assertNotIn(client.email, str(raised.exception))

    def test_probe_uses_supplied_authenticated_transport(self):
        from unittest.mock import Mock
        transport = Mock(return_value={"status": 201, "latency_ms": 1, "response": {"id": "event-id"}})
        result = run(total=1, concurrency=1, request_fn=transport)
        self.assertEqual(result["accepted_201"], 1)
        self.assertEqual(transport.call_args.args[1], "POST")


if __name__ == "__main__":
    unittest.main()
