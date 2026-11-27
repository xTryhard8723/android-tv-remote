import hashlib, random, sys
sys.path.insert(0,'.')
from polo_pb2 import OuterMessage, Options
from remotemessage_pb2 import *
from google.protobuf.internal.encoder import _EncodeVarint
def framed(m):
    out=bytearray(); _EncodeVarint(out.extend, m.ByteSize()); out+=m.SerializeToString(); return bytes(out).hex()
def cm():
    m=OuterMessage(); m.protocol_version=2; m.status=OuterMessage.Status.STATUS_OK; return m
V={}
m=cm(); m.pairing_request.client_name="Galaxy S25 Ultra"; m.pairing_request.service_name="atvremote"; V['pairing_request']=framed(m)
m=cm(); m.options.preferred_role=Options.RoleType.ROLE_TYPE_INPUT; e=m.options.input_encodings.add(); e.type=Options.Encoding.ENCODING_TYPE_HEXADECIMAL; e.symbol_length=6; V['options']=framed(m)
m=cm(); m.configuration.client_role=Options.RoleType.ROLE_TYPE_INPUT; m.configuration.encoding.type=Options.Encoding.ENCODING_TYPE_HEXADECIMAL; m.configuration.encoding.symbol_length=6; V['configuration']=framed(m)
m=cm(); m.secret.secret=bytes(range(32)); V['secret']=framed(m)
m=cm(); m.pairing_request_ack.server_name="SHIELD"; V['pairing_request_ack']=framed(m)
m=cm(); m.configuration_ack.SetInParent(); V['configuration_ack']=framed(m)
m=cm(); m.secret_ack.secret=b'\x01\x02'; V['secret_ack']=framed(m)
m=OuterMessage(); m.protocol_version=2; m.status=OuterMessage.Status.STATUS_BAD_SECRET; V['bad_secret']=framed(m)
F=1|2|4|32|64|512
m=RemoteMessage(); m.remote_configure.code1=F; d=m.remote_configure.device_info; d.unknown1=1; d.unknown2="1"; d.package_name="atvremote"; d.app_version="1.0.0"; V['configure']=framed(m)
m=RemoteMessage(); m.remote_set_active.active=F; V['set_active']=framed(m)
m=RemoteMessage(); m.remote_ping_response.val1=12345; V['ping_response']=framed(m)
m=RemoteMessage(); m.remote_ping_request.val1=12345; m.remote_ping_request.val2=7; V['ping_request']=framed(m)
m=RemoteMessage(); m.remote_key_inject.key_code=KEYCODE_DPAD_CENTER; m.remote_key_inject.direction=SHORT; V['key_short']=framed(m)
m=RemoteMessage(); m.remote_key_inject.key_code=KEYCODE_HOME; m.remote_key_inject.direction=START_LONG; V['key_start_long']=framed(m)
m=RemoteMessage(); m.remote_key_inject.key_code=KEYCODE_POWER; m.remote_key_inject.direction=END_LONG; V['key_end_long']=framed(m)
m=RemoteMessage(); m.remote_app_link_launch_request.app_link="https://www.youtube.com"; V['app_link']=framed(m)
m=RemoteMessage(); t="hello"; o=RemoteImeObject(start=len(t)-1,end=len(t)-1,value=t); ei=RemoteEditInfo(insert=1,text_field_status=o); m.remote_ime_batch_edit.CopyFrom(RemoteImeBatchEdit(ime_counter=3,field_counter=9,edit_info=[ei])); V['ime_text']=framed(m)
m=RemoteMessage(); c=m.remote_configure; c.code1=1023; c.device_info.model="SHIELD Android TV"; c.device_info.vendor="NVIDIA"; c.device_info.unknown1=1; c.device_info.unknown2="1"; c.device_info.package_name="com.google.android.tv.remote.service"; c.device_info.app_version="5.2.473254133"; V['srv_configure']=framed(m)
m=RemoteMessage(); m.remote_set_active.SetInParent(); V['srv_set_active']=framed(m)
m=RemoteMessage(); m.remote_start.started=True; V['srv_start']=framed(m)
m=RemoteMessage(); v=m.remote_set_volume_level; v.unknown1=4; v.unknown2=2; v.player_model="SHIELD"; v.unknown4=1; v.volume_max=100; v.volume_level=37; v.volume_muted=True; V['srv_volume']=framed(m)
m=RemoteMessage(); m.remote_ime_key_inject.app_info.app_package="com.netflix.ninja"; m.remote_ime_key_inject.app_info.counter=5; V['srv_ime_key_inject']=framed(m)
m=RemoteMessage(); m.remote_ime_batch_edit.ime_counter=42; m.remote_ime_batch_edit.field_counter=7; V['srv_batch_edit']=framed(m)
m=RemoteMessage(); m.remote_error.value=True; m.remote_error.message.remote_key_inject.key_code=KEYCODE_HOME; V['srv_error']=framed(m)
m=RemoteMessage(); m.remote_ping_request.val1=-5; V['ping_request_neg']=framed(m)
m=RemoteMessage(); m.remote_ping_response.val1=-5; V['ping_response_neg']=framed(m)
# big message for multi-byte varint length
m=RemoteMessage(); m.remote_app_link_launch_request.app_link="x"*300; V['app_link_long']=framed(m)
for k,v in V.items(): print(f'"{k}" to "{v}",')
# secret vectors
random.seed(1234)
def rnd(bits): return random.getrandbits(bits)|(1<<(bits-1))|1
for i,(code) in enumerate(["A1B2C3","00FF10","7e5d4c"]):
    cn,sn=rnd(2048),rnd(2048); ce,se=65537,65537 if i!=2 else 3
    h=hashlib.sha256(); h.update(bytes.fromhex(f"{cn:X}")); h.update(bytes.fromhex(f"0{ce:X}")); h.update(bytes.fromhex(f"{sn:X}")); h.update(bytes.fromhex(f"0{se:X}")); h.update(bytes.fromhex(code[2:]))
    d=h.digest()
    print(f'SECRET|{cn:x}|{ce}|{sn:x}|{se}|{code}|{d.hex()}')
print("#VOICE")
W={}
F2=1|2|4|8|32|64|512
m=RemoteMessage(); m.remote_configure.code1=F2; d=m.remote_configure.device_info; d.unknown1=1; d.unknown2="1"; d.package_name="atvremote"; d.app_version="1.0.0"; W['configure_voice']=framed(m)
m=RemoteMessage(); m.remote_voice_begin.session_id=17; W['voice_begin']=framed(m)
m=RemoteMessage(); m.remote_voice_begin.session_id=17; m.remote_voice_begin.package_name="com.google.android.katniss"; W['srv_voice_begin']=framed(m)
m=RemoteMessage(); m.remote_voice_payload.session_id=17; m.remote_voice_payload.samples=bytes(8192); W['voice_payload']=framed(m)
m=RemoteMessage(); m.remote_voice_end.session_id=17; W['voice_end']=framed(m)
m=RemoteMessage(); m.remote_ime_show_request.remote_text_field_status.counter_field=9; m.remote_ime_show_request.remote_text_field_status.value="star"; m.remote_ime_show_request.remote_text_field_status.start=3; m.remote_ime_show_request.remote_text_field_status.end=3; m.remote_ime_show_request.remote_text_field_status.label="Search"; W['srv_ime_show']=framed(m)
m=RemoteMessage(); m.remote_ime_show_request.SetInParent(); W['srv_ime_hide']=framed(m)
m=RemoteMessage(); m.remote_ime_batch_edit.ime_counter=2; m.remote_ime_batch_edit.field_counter=9; ei=m.remote_ime_batch_edit.edit_info.add(); ei.insert=1; ei.text_field_status.start=2; ei.text_field_status.end=2; ei.text_field_status.value="abc"; W['srv_batch_edit_text']=framed(m)
for k,v in W.items(): print(f'"{k}" to "{v}",')
