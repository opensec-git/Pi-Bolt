"""Local unpaced SSE model for measuring client work without Linux delayed-ACK jitter."""
from http.server import ThreadingHTTPServer
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fake_model_stress import Handler


class BurstHandler(Handler):
    # BaseHTTPRequestHandler flushes at the end of each response. Keep the small
    # unpaced Markdown stream in one burst instead of issuing a send for each SSE frame.
    wbufsize = 256 * 1024
    disable_nagle_algorithm = True


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), BurstHandler).serve_forever()
