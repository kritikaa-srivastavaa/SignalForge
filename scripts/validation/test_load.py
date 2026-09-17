import unittest
from unittest.mock import patch
from load import percentile, request, summarize, run
from urllib.error import URLError


class LoadTests(unittest.TestCase):
    def test_percentiles_and_edges(self):
        self.assertIsNone(percentile([], 95))
        self.assertEqual(percentile([7], 99), 7)
        self.assertEqual(percentile([4, 1, 3, 2], 50), 2.5)
        self.assertAlmostEqual(percentile([0, 100], 95), 95)
        self.assertEqual(percentile([2, 9], 100), 9)

    def test_aggregation_keeps_transport_and_http_failures_separate(self):
        rows = [{"status": status, "latency_ms": latency}
                for status, latency in [(201, 10), (201, 30), (429, 2), (500, 50), (None, 100)]]
        result = summarize(rows, 2)
        self.assertEqual(result["attempted"], 5)
        self.assertEqual(result["status_counts"], {"201": 2, "429": 1, "500": 1})
        self.assertEqual(result["request_failures"], 1)
        self.assertEqual(result["accepted_per_second"], 1)
        self.assertEqual(result["accepted_latency_ms"]["average"], 20)

    def test_empty_aggregation(self):
        self.assertEqual(summarize([], 0)["accepted_per_second"], 0)
        self.assertIsNone(summarize([], 0)["latency_ms"]["p50"])

    @patch("load.urlopen", side_effect=URLError("unreachable"))
    def test_transport_error_is_recorded(self, _):
        result = request("http://localhost:8080/events")
        self.assertIsNone(result["status"])
        self.assertGreaterEqual(result["latency_ms"], 0)

    def test_invalid_configuration_is_rejected(self):
        for kwargs in ({"total": 0}, {"concurrency": 0}, {"timeout": 0}):
            with self.assertRaises(ValueError):
                run(**kwargs)


if __name__ == "__main__":
    unittest.main()