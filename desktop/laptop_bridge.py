#!/usr/bin/env python3
"""Vorlen laptop audio bridge.

Receives 48 kHz mono PCM16 caller audio from the Android gateway and exposes it
through the selected laptop output device (choose a virtual cable/loopback as
ChatGPT's microphone). Captures the selected laptop input (choose ChatGPT's
virtual-cable output) and streams it back to the phone.

Usage:
  python laptop_bridge.py --list-devices
  python laptop_bridge.py --bind 0.0.0.0 --port 28761 --input DEVICE --output DEVICE
"""
import argparse, socket, struct, threading, time
try:
    import sounddevice as sd
except ImportError:
    raise SystemExit("Install dependency first: python -m pip install sounddevice")

RATE=48000
CHANNELS=1
DTYPE="int16"
FRAMES=960  # 20 ms
MAGIC=b"VOR1"

def recvall(sock,n):
    b=bytearray()
    while len(b)<n:
        x=sock.recv(n-len(b))
        if not x: raise ConnectionError("phone disconnected")
        b.extend(x)
    return bytes(b)

def send_frame(sock,payload,lock):
    with lock:
        sock.sendall(struct.pack("!I",len(payload))+payload)

def handle(conn,input_dev,output_dev):
    print("Phone connected:",conn.getpeername())
    if recvall(conn,4)!=MAGIC: raise ConnectionError("bad handshake")
    conn.sendall(MAGIC)
    lock=threading.Lock()
    stop=threading.Event()
    out=sd.RawOutputStream(samplerate=RATE,channels=CHANNELS,dtype=DTYPE,
                           blocksize=FRAMES,device=output_dev)
    inp=sd.RawInputStream(samplerate=RATE,channels=CHANNELS,dtype=DTYPE,
                          blocksize=FRAMES,device=input_dev)
    out.start(); inp.start()\n    print(f"Audio ready: input={input_dev}, output={output_dev}; full-duplex bridge active")
    def rx():
        try:
            while not stop.is_set():
                n=struct.unpack("!I",recvall(conn,4))[0]
                if n>192000: raise ConnectionError("invalid frame")
                if n: out.write(recvall(conn,n))
        except Exception as e:\n            print("Phone -> laptop audio failed:", repr(e))\n        finally: stop.set()
    t=threading.Thread(target=rx,daemon=True); t.start()
    try:
        while not stop.is_set():
            data,overflow=inp.read(FRAMES)
            if data: send_frame(conn,bytes(data),lock)
    finally:
        stop.set(); inp.stop(); out.stop(); inp.close(); out.close(); conn.close()

def resolve_device(value, kind):
    """Treat an all-digit CLI value as a PortAudio device index; otherwise use its name."""
    if isinstance(value, str) and value.strip().isdigit():
        index = int(value.strip())
        devices = sd.query_devices()
        if index < 0 or index >= len(devices):
            raise ValueError(f"{kind} device index {index} is out of range (0..{len(devices)-1})")
        info = devices[index]
        channels_key = "max_input_channels" if kind == "input" else "max_output_channels"
        if info[channels_key] < 1:
            raise ValueError(f"device {index} ({info['name']}) has no {kind} channels")
        return index
    return value

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--bind",default="0.0.0.0")
    p.add_argument("--port",type=int,default=28761)
    p.add_argument("--input")
    p.add_argument("--output")
    p.add_argument("--list-devices",action="store_true")
    a=p.parse_args()
    if a.list_devices:
        print(sd.query_devices()); return
    if a.input is None or a.output is None:
        p.error("--input and --output are required (use --list-devices first)")
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        s.bind((a.bind,a.port)); s.listen(1)
        print(f"Vorlen laptop bridge listening on {a.bind}:{a.port}")
        while True:
            c,_=s.accept()
            try:
                input_dev = resolve_device(a.input, "input")
                output_dev = resolve_device(a.output, "output")
                handle(c,input_dev,output_dev)
            except Exception as e:\n                print("Bridge disconnected:", repr(e))\n                time.sleep(.5)

if __name__=="__main__":
    main()
