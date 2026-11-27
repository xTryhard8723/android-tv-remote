"""Mock Android TV Remote Service v2 using androidtvremote2's protobufs, for end-to-end testing."""
import hashlib, os, socket, threading, time, random, sys, datetime
from OpenSSL import SSL, crypto
from google.protobuf import text_format
from google.protobuf.internal.encoder import _EncodeVarint
from google.protobuf.internal.decoder import _DecodeVarint
from polo_pb2 import OuterMessage, Options
from remotemessage_pb2 import *

LOG = open('mocktv.log', 'a', buffering=1)
def log(*a): LOG.write(time.strftime('%H:%M:%S ') + ' '.join(str(x) for x in a) + '\n')
PAIRED = set()
if os.path.exists('paired.txt'): PAIRED = set(open('paired.txt').read().split())

key = crypto.PKey(); key.generate_key(crypto.TYPE_RSA, 2048)
cert = crypto.X509(); cert.get_subject().CN = "atvremote/MOCKSHIELD/AA:BB"; cert.set_serial_number(1)
cert.gmtime_adj_notBefore(0); cert.gmtime_adj_notAfter(10*365*86400); cert.set_issuer(cert.get_subject()); cert.set_pubkey(key); cert.sign(key, 'sha256')

def ctx():
    c = SSL.Context(SSL.TLS_SERVER_METHOD); c.use_privatekey(key); c.use_certificate(cert)
    c.set_verify(SSL.VERIFY_PEER, lambda *a: True)
    return c

def send(conn, m):
    out = bytearray(); _EncodeVarint(out.extend, m.ByteSize()); out += m.SerializeToString(); conn.sendall(bytes(out))

def reader(conn):
    buf = bytearray()
    while True:
        try: d = conn.recv(65536)
        except (SSL.ZeroReturnError, SSL.SysCallError, SSL.Error): return
        if not d: return
        buf += d
        while buf:
            try: n, p = _DecodeVarint(bytes(buf), 0)
            except IndexError: break
            if len(buf) < p + n: break
            yield bytes(buf[p:p+n]); del buf[:p+n]

def fp(x509): return x509.digest('sha256').decode()

def ne(x509):
    pk = x509.get_pubkey().to_cryptography_key().public_numbers(); return pk.n, pk.e

def pairing(conn):
    conn.do_handshake()
    client = conn.get_peer_certificate()
    log('PAIR connect, client cert', client.get_subject().CN if client else None)
    for raw in reader(conn):
        m = OuterMessage(); m.ParseFromString(raw)
        log('PAIR recv', text_format.MessageToString(m, as_one_line=True)[:200])
        r = OuterMessage(); r.protocol_version = 2; r.status = OuterMessage.STATUS_OK
        if m.HasField('pairing_request'):
            r.pairing_request_ack.server_name = "MOCKSHIELD"
        elif m.HasField('options'):
            e = r.options.input_encodings.add(); e.type = Options.Encoding.ENCODING_TYPE_HEXADECIMAL; e.symbol_length = 6
            r.options.preferred_role = Options.ROLE_TYPE_INPUT
        elif m.HasField('configuration'):
            r.configuration_ack.SetInParent()
            cn, ce = ne(client); sn, se = ne(cert)
            suffix = '%04X' % random.randint(0, 0xFFFF)
            h = hashlib.sha256(); h.update(bytes.fromhex(f"{cn:X}")); h.update(bytes.fromhex(f"0{ce:X}")); h.update(bytes.fromhex(f"{sn:X}")); h.update(bytes.fromhex(f"0{se:X}")); h.update(bytes.fromhex(suffix))
            conn.expected = h.digest(); code = '%02X' % conn.expected[0] + suffix
            open('code.txt', 'w').write(code); log('PAIR code on TV:', code)
        elif m.HasField('secret'):
            if m.secret.secret == conn.expected:
                PAIRED.add(fp(client)); open('paired.txt', 'w').write('\n'.join(PAIRED)); log('PAIR SUCCESS')
                r.secret_ack.secret = b'\x01'
            else:
                log('PAIR BAD SECRET'); r.status = OuterMessage.STATUS_BAD_SECRET
        send(conn, r)

STATE = {'conn': None}
# Shield-like text box, persists across connections (like the real TV's focused field).
FIELD = {'open': False, 'value': '', 'cursor': 0, 'counter': 0, 'app': 'com.google.android.tvlauncher'}
STATS = {'batch': 0, 'dels': 0, 'links': 0, 'errors': 0, 'counter_mismatch': 0}
KNOWN_SCHEMES = {'https': 'com.google.android.youtube.tv', 'wholphin': 'com.github.damontecres.wholphin', 'netflix': 'com.netflix.ninja'}
def dump():
    open('field.txt', 'w').write(FIELD['value'] if FIELD['open'] else '<closed>')
    open('stats.txt', 'w').write(' '.join(f'{k}={v}' for k, v in STATS.items()))
def show_request(conn):
    r = RemoteMessage(); t = r.remote_ime_show_request.remote_text_field_status
    t.counter_field = FIELD['counter']; t.value = FIELD['value']; t.start = FIELD['cursor']; t.end = FIELD['cursor']
    send(conn, r)
def key_inject_status(conn, with_field=True):
    r = RemoteMessage(); k = r.remote_ime_key_inject; k.app_info.counter = 1; k.app_info.app_package = FIELD['app']
    if with_field and FIELD['open']:
        t = k.text_field_status; t.counter_field = FIELD['counter']; t.value = FIELD['value']; t.start = FIELD['cursor']; t.end = FIELD['cursor']
    send(conn, r)
def remote(conn):
    conn.do_handshake()
    client = conn.get_peer_certificate()
    if client is None or fp(client) not in PAIRED:
        log('REMOTE reject unpaired client'); conn.shutdown(); conn.close(); return
    log('REMOTE connect')
    STATE['conn'] = conn
    m = RemoteMessage(); c = m.remote_configure; c.code1 = 1023; c.device_info.model = "SHIELD Android TV"; c.device_info.vendor = "NVIDIA"; c.device_info.unknown1 = 1; c.device_info.unknown2 = "1"; c.device_info.package_name = "com.google.android.tv.remote.service"; c.device_info.app_version = "5.2"
    send(conn, m)
    alive = {'v': True, 'pings': 0, 'pongs': 0}
    def pinger():
        i = 0
        while alive['v']:
            time.sleep(5)
            if os.path.exists('silent'): continue
            i += 1; p = RemoteMessage(); p.remote_ping_request.val1 = i; p.remote_ping_request.val2 = 0
            try: send(conn, p); alive['pings'] += 1
            except Exception: return
    threading.Thread(target=pinger, daemon=True).start()
    try:
        for raw in reader(conn):
            m = RemoteMessage(); m.ParseFromString(raw)
            kind = m.WhichOneof('remote_configure') if False else [f.name for f, _ in m.ListFields()]
            if 'remote_ping_response' in kind: alive['pongs'] += 1; log('REMOTE pong', m.remote_ping_response.val1, f"({alive['pongs']}/{alive['pings']})"); continue
            if 'remote_voice_payload' in kind: log('REMOTE voice payload', len(m.remote_voice_payload.samples), 'bytes'); continue
            log('REMOTE recv', text_format.MessageToString(m, as_one_line=True)[:300])
            r = RemoteMessage()
            if m.HasField('remote_configure'):
                r.remote_set_active.SetInParent()
            elif m.HasField('remote_set_active'):
                r.remote_start.started = True; send(conn, r)
                if FIELD['open'] and os.path.exists('shieldmode'):
                    key_inject_status(conn)
                    b = RemoteMessage(); b.remote_ime_batch_edit.ime_counter = 1; b.remote_ime_batch_edit.field_counter = 1; send(conn, b)
                r = RemoteMessage(); v = r.remote_set_volume_level; v.volume_max = 100; v.volume_level = 25; v.player_model = "SHIELD"
            elif m.HasField('remote_ime_batch_edit') and os.path.exists('shieldmode'):
                be = m.remote_ime_batch_edit
                STATS['batch'] += 1
                if be.field_counter != FIELD['counter']: STATS['counter_mismatch'] += 1; log('SHIELD stale field counter', be.field_counter, 'expected', FIELD['counter'])
                for e in be.edit_info:
                    txt = e.text_field_status.value  # the real Shield INSERTS this at its cursor
                    v, c = FIELD['value'], FIELD['cursor']
                    FIELD['value'] = v[:c] + txt + v[c:]; FIELD['cursor'] = c + len(txt)
                FIELD['counter'] += 1
                log('SHIELD field now', repr(FIELD['value'])); dump()
                show_request(conn)
            elif m.HasField('remote_app_link_launch_request') and os.path.exists('shieldmode'):
                link = m.remote_app_link_launch_request.app_link; STATS['links'] += 1
                scheme = link.split(':', 1)[0]
                if scheme in KNOWN_SCHEMES and ' ' not in link:
                    FIELD['open'] = False; FIELD['app'] = KNOWN_SCHEMES[scheme]; dump(); key_inject_status(conn, False)
                else:
                    STATS['errors'] += 1; dump(); log('SHIELD rejects', link, '-> error + close')
                    r.remote_error.value = True; send(conn, r)
                    time.sleep(0.05); conn.sock_shutdown(socket.SHUT_RDWR); return
            elif m.HasField('remote_key_inject'):
                k = m.remote_key_inject.key_code
                if os.path.exists('shieldmode') and m.remote_key_inject.direction == SHORT:
                    if k == KEYCODE_SEARCH and os.path.exists('mute_search'): continue  # TV doesn't answer at all
                    if k == KEYCODE_SEARCH and os.path.exists('noack'):  # no voice session: the TV opens its search box instead
                        FIELD['open'] = True; FIELD['value'] = ''; FIELD['cursor'] = 0; FIELD['counter'] += 10; dump(); show_request(conn); continue
                    if k == KEYCODE_INFO:  # focus a text box (prefill from prefill.txt)
                        FIELD['open'] = True; FIELD['value'] = open('prefill.txt').read() if os.path.exists('prefill.txt') else ''
                        FIELD['cursor'] = len(FIELD['value']); FIELD['counter'] += 10; dump(); show_request(conn); continue
                    if k == KEYCODE_DEL and FIELD['open']:
                        STATS['dels'] += 1
                        c = FIELD['cursor']
                        if c > 0: FIELD['value'] = FIELD['value'][:c-1] + FIELD['value'][c:]; FIELD['cursor'] = c - 1
                        FIELD['counter'] += 1; dump(); show_request(conn); continue
                    if k == KEYCODE_HOME:
                        FIELD['open'] = False; FIELD['app'] = 'com.google.android.tvlauncher'; dump(); continue
                    if k == KEYCODE_DPAD_CENTER and FIELD['app'] == 'com.google.android.tvlauncher':
                        FIELD['app'] = 'com.teamsmart.videomanager.tv'; dump(); key_inject_status(conn, False); continue
                if k == KEYCODE_SEARCH and m.remote_key_inject.direction == SHORT:
                    if os.path.exists('novoice'):
                        r.remote_ime_show_request.remote_text_field_status.counter_field = 3; r.remote_ime_show_request.remote_text_field_status.label = "Search"
                    else:
                        r.remote_voice_begin.session_id = 42; r.remote_voice_begin.package_name = "com.google.android.katniss"
                elif k == KEYCODE_INFO:
                    r.remote_ime_show_request.remote_text_field_status.counter_field = 7; r.remote_ime_show_request.remote_text_field_status.value = "star"; r.remote_ime_show_request.remote_text_field_status.label = "Search movies"
                    send(conn, r); r = RemoteMessage(); r.remote_ime_batch_edit.ime_counter = 1; r.remote_ime_batch_edit.field_counter = 7
                elif k == KEYCODE_ENTER:
                    r.remote_ime_show_request.SetInParent()  # keyboard closes
                elif k in (KEYCODE_VOLUME_UP, KEYCODE_VOLUME_DOWN):
                    v = r.remote_set_volume_level; v.volume_max = 100; v.volume_level = 30 if k == KEYCODE_VOLUME_UP else 20; v.player_model = "SHIELD"
            if r.ListFields(): send(conn, r)
    finally:
        alive['v'] = False; log('REMOTE disconnected'); STATE['conn'] = None

def serve(port, handler):
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(('0.0.0.0', port)); s.listen(5)
    while True:
        c, a = s.accept()
        conn = SSL.Connection(ctx(), c); conn.set_accept_state()
        def run(conn=conn):
            try: handler(conn)
            except Exception as e: log(f'{port} error', repr(e))
        threading.Thread(target=run, daemon=True).start()

def control():
    # touch drop to kill the current remote connection
    while True:
        time.sleep(0.5)
        if os.path.exists('voicebegin'):  # the Shield's Assistant opens a voice session on its own
            os.remove('voicebegin'); c = STATE['conn']
            if c:
                r = RemoteMessage(); r.remote_voice_begin.session_id = 7; r.remote_voice_begin.package_name = "com.google.android.katniss"
                log('CONTROL unprompted voice_begin 7'); send(c, r)
        if os.path.exists('drop'):
            os.remove('drop'); c = STATE['conn']
            if c:
                log('CONTROL dropping connection')
                try: c.sock_shutdown(socket.SHUT_RDWR)
                except Exception as e: log('drop err', e)

threading.Thread(target=serve, args=(6467, pairing), daemon=True).start()
threading.Thread(target=control, daemon=True).start()
log('mock TV up')
serve(6466, remote)
