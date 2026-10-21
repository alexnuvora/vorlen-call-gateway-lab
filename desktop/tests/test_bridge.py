import math
import multiprocessing
import socket
import struct
import sys
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import laptop_bridge as bridge


def frozen_worker(conn, *args):
    # Represents a PortAudio native call that never returns.
    threading.Event().wait()


def one_way_worker(conn, *args):
    progress = args[-1]
    while True:
        progress[0] = time.monotonic()
        time.sleep(0.02)


def probe_worker(conn, *args):
    try:
        if bridge.recvall(conn, 4) == b"VORP":
            conn.sendall(b"PONG")
    finally:
        conn.close()


def tcp_pair():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        peer = socket.create_connection(listener.getsockname())
        conn, _ = listener.accept()
    return conn, peer


class BridgeTests(unittest.TestCase):
    def test_fragmented_frames_and_eof(self):
        conn, peer = tcp_pair()
        try:
            peer.sendall(b"VO")
            peer.sendall(b"R1")
            self.assertEqual(bridge.recvall(conn, 4), b"VOR1")
            peer.close()
            with self.assertRaises(ConnectionError):
                bridge.recvall(conn, 1)
        finally:
            conn.close()
            peer.close()

    def test_silence_and_level(self):
        self.assertEqual(bridge.rms_dbfs(bytes(960)), -120)
        self.assertAlmostEqual(bridge.rms_dbfs(struct.pack("<h", 16384) * 480),
                               20 * math.log10(0.5), places=4)

    def test_stereo_downmix(self):
        data = struct.pack("<hh", 1000, -500) * 480
        self.assertEqual(bridge.input_to_mono_pcm16(data, 2),
                         struct.pack("<h", 250) * 480)

    def test_hung_and_one_way_workers_release_socket_then_reconnect(self):
        for worker in (frozen_worker, one_way_worker):
            with self.subTest(worker=worker.__name__):
                conn, peer = tcp_pair()
                start = time.monotonic()
                bridge.supervise_session(conn, None, None, 1, stall_seconds=1,
                                         worker=worker)
                self.assertLess(time.monotonic() - start, 5)
                peer.settimeout(1)
                self.assertEqual(peer.recv(1), b"")
                peer.close()
                self.assertEqual(multiprocessing.active_children(), [])
                conn, peer = tcp_pair()
                peer.sendall(b"VORP")
                bridge.supervise_session(conn, None, None, 1, stall_seconds=2,
                                         worker=probe_worker)
                self.assertEqual(peer.recv(4), b"PONG")
                peer.close()


if __name__ == "__main__":
    unittest.main()
