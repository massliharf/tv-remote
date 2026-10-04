"""Fake LG webOS TV (ws :3000) and fake Android TV (TLS :6466/:6467) for the emulator test.

The emulator reaches the CI host at 10.0.2.2. Every message received is logged to stdout so the
test can check that the app really sent what it should.
"""
import asyncio, hashlib, json, os, socket, sys, threading, time

from OpenSSL import SSL, crypto
from androidtvremote2 import polo_pb2, remotemessage_pb2
from google.protobuf.internal.decoder import _DecodeVarint
from google.protobuf.internal.encoder import _VarintBytes
import websockets

OUT = sys.argv[1] if len(sys.argv) > 1 else "."

def log(*a):
    print(*a, flush=True)

# ---------------- LG webOS ----------------

async def lg_handler(ws, path=None):
    path = path or getattr(getattr(ws, "request", None), "path", "/")
    if path.startswith("/pointer"):
        async for frame in ws:
            log("LG POINTER", frame.replace("\n", "|"))
        return
    async for raw in ws:
        msg = json.loads(raw)
        log("LG <-", msg.get("type"), msg.get("uri", ""), json.dumps(msg.get("payload", {}))[:120] if msg.get("type") != "register" else "")
        mid = msg.get("id")
        if msg.get("type") == "register":
            if not msg["payload"].get("client-key"):
                await ws.send(json.dumps({"type": "response", "id": mid, "payload": {"pairingType": "PROMPT"}}))
                await asyncio.sleep(2)
            await ws.send(json.dumps({"type": "registered", "id": mid, "payload": {"client-key": "fake-key"}}))
            continue
        uri = msg.get("uri", "")
        payload = {"returnValue": True}
        if uri.endswith("getPointerInputSocket"):
            payload["socketPath"] = "ws://10.0.2.2:3000/pointer"
        elif uri.endswith("listLaunchPoints"):
            payload["launchPoints"] = [{"id": "netflix", "title": "Netflix"}, {"id": "youtube.leanback.v4", "title": "YouTube"}]
        elif uri.endswith("getExternalInputList"):
            payload["devices"] = [{"id": "HDMI_1", "label": "HDMI 1"}, {"id": "HDMI_2", "label": "HDMI 2"}]
        elif uri.endswith("audio/getStatus"):
            payload["mute"] = False
        await ws.send(json.dumps({"type": "response", "id": mid, "payload": payload}))

async def lg_main():
    async with websockets.serve(lg_handler, "0.0.0.0", 3000):
        await asyncio.Future()

# ---------------- Android TV ----------------

def make_cert():
    key = crypto.PKey(); key.generate_key(crypto.TYPE_RSA, 2048)
    cert = crypto.X509(); cert.get_subject().CN = "fakeatv"; cert.set_serial_number(1)
    cert.gmtime_adj_notBefore(0); cert.gmtime_adj_notAfter(86400)
    cert.set_issuer(cert.get_subject()); cert.set_pubkey(key); cert.sign(key, "sha256")
    return key, cert

KEY, CERT = make_cert()

def tls_server(port, handler):
    ctx = SSL.Context(SSL.TLS_SERVER_METHOD)
    ctx.use_privatekey(KEY); ctx.use_certificate(CERT)
    ctx.set_verify(SSL.VERIFY_PEER, lambda *a: True)  # accept the app's self-signed cert
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", port)); s.listen(5)
    while True:
        raw, _ = s.accept()
        def run(raw=raw):
            conn = SSL.Connection(ctx, raw); conn.set_accept_state()
            try:
                conn.do_handshake()
                handler(Conn(conn))
            except Exception as e:
                log(f"ATV {port} closed: {e!r}")
            finally:
                try: conn.close()
                except Exception: pass
        threading.Thread(target=run, daemon=True).start()

class Conn:
    def __init__(self, c): self.c, self.buf = c, b""
    def recv(self, cls):
        while True:
            try:
                n, p = _DecodeVarint(self.buf, 0)
                if len(self.buf) >= p + n:
                    raw, self.buf = self.buf[p:p+n], self.buf[p+n:]
                    m = cls(); m.ParseFromString(raw); return m
            except IndexError:
                pass
            d = self.c.recv(4096)
            if not d: raise EOFError
            self.buf += d
    def send(self, m):
        b = m.SerializeToString(); self.c.sendall(_VarintBytes(len(b)) + b)

PAIRED = set()

def outer():
    m = polo_pb2.OuterMessage(); m.protocol_version = 2; m.status = 200; return m

def pairing(c):
    m = c.recv(polo_pb2.OuterMessage); log("ATV PAIR <- request", m.pairing_request.client_name)
    r = outer(); r.pairing_request_ack.server_name = "Mi Box"; c.send(r)
    m = c.recv(polo_pb2.OuterMessage); r = outer(); r.options.CopyFrom(m.options); c.send(r)
    m = c.recv(polo_pb2.OuterMessage); r = outer(); r.configuration_ack.SetInParent(); c.send(r)
    client = c.c.get_peer_certificate().to_cryptography().public_key().public_numbers()
    server = CERT.to_cryptography().public_key().public_numbers()
    rnd = os.urandom(2)
    h = hashlib.sha256()
    h.update(bytes.fromhex(f"{client.n:X}")); h.update(bytes.fromhex(f"0{client.e:X}"))
    h.update(bytes.fromhex(f"{server.n:X}")); h.update(bytes.fromhex(f"0{server.e:X}")); h.update(rnd)
    d = h.digest()
    code = f"{d[0]:02X}{rnd.hex().upper()}"
    open(os.path.join(OUT, "atv_code.txt"), "w").write(code)
    log("ATV PAIR code", code)
    m = c.recv(polo_pb2.OuterMessage)
    if m.secret.secret != d:
        log("ATV PAIR secret MISMATCH"); r = outer(); r.status = 402; c.send(r); return
    log("ATV PAIR secret OK")
    PAIRED.add(c.c.get_peer_certificate().digest("sha256"))
    r = outer(); r.secret_ack.secret = d; c.send(r)

def remote(c):
    fp = c.c.get_peer_certificate().digest("sha256")
    if fp not in PAIRED:
        log("ATV REMOTE unknown cert -> closing"); return
    R = remotemessage_pb2.RemoteMessage
    m = R(); m.remote_configure.code1 = 622; m.remote_configure.device_info.model = "MIBOX4"; c.send(m)
    log("ATV REMOTE <-", str(c.recv(R)).replace("\n", " "))
    m = R(); m.remote_set_active.SetInParent(); c.send(m)
    m = R(); m.remote_ime_batch_edit.ime_counter = 1; m.remote_ime_batch_edit.field_counter = 1; c.send(m)
    import select
    raw = c.c.fileno()
    last_ping = time.time()
    while True:
        if c.buf or c.c.pending() or select.select([raw], [], [], 1.0)[0]:
            r = c.recv(R)
            if not r.HasField("remote_ping_response"):
                log("ATV REMOTE <-", str(r).replace("\n", " "))
        if time.time() - last_ping > 5:
            m = R(); m.remote_ping_request.val1 = 1; c.send(m); last_ping = time.time()

threading.Thread(target=tls_server, args=(6467, pairing), daemon=True).start()
threading.Thread(target=tls_server, args=(6466, remote), daemon=True).start()
log("fake TVs ready")
asyncio.run(lg_main())
