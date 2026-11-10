import json, time, sys
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
CATS = [("1","USA General Sports"),("2","USA News"),("3","USA Locals Chicago"),("4","USA Entertainment")]
CH = []
sid = 100
def add(name, cat, epg):
    global sid; sid += 1; CH.append(dict(num=len(CH)+1, name=name, stream_type="live", stream_id=sid, stream_icon="", epg_channel_id=epg, added="0", category_id=cat, custom_sid="", tv_archive=0, direct_source="", tv_archive_duration=0))
for i,n in enumerate(["ESPN","ESPN 2","FS1","NBC Sports Network","CBS Sports Network","NFL Network","MLB Network","NBA TV","Golf Channel","Tennis Channel","ACC Network","SEC Network"]): add("US: "+n, "1", "sports%d.us"%i)
for i,n in enumerate(["CNN","FOX News","MSNBC","CNBC","Newsmax"]): add("US: "+n, "2", "news%d.us"%i)
for n,e in [("US: ABC 7 CHICAGO","wls.us"),("US: NBC 5 Chicago WMAQ","wmaq.us"),("CHICAGO CBS 2 HD","wbbm.us"),("US: FOX 32 Chicago WFLD","wfld.us"),("WGN 9 Chicago","wgn.us")]: add(n,"3",e)
for n,e in [("US: ABC 7 New York WABC","wabc.us"),("US: NBC 4 New York WNBC","wnbc.us"),("US: CBS 2 New York WCBS","wcbs.us"),("US: FOX 5 New York WNYW","wnyw.us"),("US: ABC 7 Los Angeles KABC","kabc.us"),("US: NBC 4 Los Angeles KNBC","knbc.us"),("US: WFAA ABC 8 Dallas","wfaa.us"),("US: WSB ABC 2 Atlanta","wsb.us")]: add(n,"4",e)
for i in range(40): add("US: Entertainment %02d"%i, "4", "ent%d.us"%i)
# Locals fixture (several Nielsen markets + look-alike national feeds), shared with LocalsFlowTest
import os
_L = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "locals_channels.json")))
CATS += [(k, v) for k, v in _L["categories"].items()]
for i, (cat, name) in enumerate(_L["channels"]): add(name, cat, "loc%d.us" % i)
EPG_DELAY = float(sys.argv[2]) if len(sys.argv)>2 else 10.0
def xmltv():
    now = int(time.time())//1800*1800
    out = ['<?xml version="1.0" encoding="UTF-8"?><tv>']
    for c in CH: out.append('<channel id="%s"><display-name>%s</display-name></channel>' % (c["epg_channel_id"], c["name"]))
    for c in CH:
        for k in range(60):
            s = now + (k-2)*1800; e = s+1800
            f = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
            out.append('<programme start="%s" stop="%s" channel="%s"><title>Show %d on %s</title><desc>%s</desc></programme>' % (f(s), f(e), c["epg_channel_id"], k, c["name"].replace("&","and"), "Lorem ipsum dolor sit amet. "*8))
    out.append('</tv>')
    return "\n".join(out).encode()
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): sys.stderr.write("%s %s\n" % (time.strftime("%H:%M:%S"), self.path))
    def send(self, body, ctype="application/json"):
        self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query); a = q.get("action",[None])[0]
        if u.path.endswith("player_api.php"):
            if a is None:
                return self.send(json.dumps({"user_info":{"username":q.get("username",[""])[0],"password":q.get("password",[""])[0],"auth":1,"status":"Active","exp_date":str(int(time.time())+86400*300),"is_trial":"0","active_cons":"0","max_connections":"2","allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"10.0.2.2","port":"8088","server_protocol":"http","timezone":"UTC","timestamp_now":int(time.time())}}).encode())
            if a == "get_live_categories": return self.send(json.dumps([{"category_id":i,"category_name":n,"parent_id":0} for i,n in CATS]).encode())
            if a == "get_live_streams": return self.send(json.dumps(CH).encode())
            if a in ("get_vod_categories","get_series_categories"): return self.send(json.dumps([{"category_id":"9","category_name":"Movies","parent_id":0}]).encode())
            if a == "get_vod_streams": return self.send(json.dumps([{"num":1,"name":"Test Movie (2020)","stream_type":"movie","stream_id":9001,"stream_icon":"","rating":"","added":"0","category_id":"9","container_extension":"mp4"}]).encode())
            if a == "get_series": return self.send(b"[]")
            if a == "get_short_epg": return self.send(b'{"epg_listings":[]}')
            return self.send(b"[]")
        if u.path.endswith("xmltv.php"):
            body = xmltv(); self.send_response(200); self.send_header("Content-Type","application/xml"); self.send_header("Content-Length", str(len(body))); self.end_headers()
            n = 40; step = len(body)//n + 1
            for i in range(0, len(body), step):
                self.wfile.write(body[i:i+step]); self.wfile.flush(); time.sleep(EPG_DELAY/n)
            return
        self.send_response(404); self.send_header("Content-Length","0"); self.end_headers()
ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1]) if len(sys.argv)>1 else 8088), H).serve_forever()
