"""Builds app/src/main/assets/locals/dmas.json (see tools/locals/README.md for sources)."""
import re,csv,json,collections,sys,difflib
import os
HERE=os.path.dirname(os.path.abspath(__file__))
T=os.path.join(HERE,'inputs')
SRC=sys.argv[2] if len(sys.argv)>2 else os.path.join(HERE,'downloads')
OUT=sys.argv[1] if len(sys.argv)>1 else os.path.join(HERE,'..','..','app','src','main','assets','locals','dmas.json')
NAMES={'AL':'Alabama','AK':'Alaska','AZ':'Arizona','AR':'Arkansas','CA':'California','CO':'Colorado','CT':'Connecticut','DE':'Delaware','DC':'District of Columbia','FL':'Florida','GA':'Georgia','HI':'Hawaii','ID':'Idaho','IL':'Illinois','IN':'Indiana','IA':'Iowa','KS':'Kansas','KY':'Kentucky','LA':'Louisiana','ME':'Maine','MD':'Maryland','MA':'Massachusetts','MI':'Michigan','MN':'Minnesota','MS':'Mississippi','MO':'Missouri','MT':'Montana','NE':'Nebraska','NV':'Nevada','NH':'New Hampshire','NJ':'New Jersey','NM':'New Mexico','NY':'New York','NC':'North Carolina','ND':'North Dakota','OH':'Ohio','OK':'Oklahoma','OR':'Oregon','PA':'Pennsylvania','RI':'Rhode Island','SC':'South Carolina','SD':'South Dakota','TN':'Tennessee','TX':'Texas','UT':'Utah','VT':'Vermont','VA':'Virginia','WA':'Washington','WV':'West Virginia','WI':'Wisconsin','WY':'Wyoming'}
ranks=[(int(a),b.strip()) for a,b in (l.split('|',1) for l in open(f'{T}/dma_rank.txt').read().splitlines() if l.strip())]
def norm(n): return re.sub(r'\s+',' ',n.lower().replace('saint ','st. ').replace('ft. ','fort ').replace('ft ','fort ').replace('st ','st. ')).strip()
def first(n):
    n=norm(re.sub(r'\(.*?\)','',n)); return re.split(r'\s*(?:-|,|&| and )\s*',n)[0].strip()
def hints(n): return re.findall(r'\b([A-Z]{2})\b',n.replace('D.C.','DC'))
def pick(name,cands,getname,manual=None):
    f=first(name); hs=hints(name)
    hits=[c for c in cands if first(getname(c))==f]
    if len(hits)>1:
        h=[c for c in hits if hs and hs[0] in hints(getname(c))]
        if len(h)==1: return h[0]
        segs=[s.strip() for s in re.split(r'\s*(?:-|,|&)\s*',norm(re.sub(r'\(.*?\)','',name)))]
        h=[c for c in hits if len(segs)>1 and segs[1] and segs[1].split()[0] in norm(getname(c))]
        if len(h)==1: return h[0]
        return None
    return hits[0] if hits else None
radar=[(int(c),n) for c,n in json.load(open(f'{T}/radar.json'))]
gist=dict((int(c),n) for c,n in re.findall(r'"(\d{3})"=>"([^"]+)"',open(f'{T}/codes.rb').read()))
cty=list(csv.DictReader(open(f'{SRC}/county.csv',encoding='utf-8-sig')))
cdma=collections.defaultdict(collections.Counter)
for x in cty:
    if x['TVDMA'].strip()!='Unmeasured, AK DMA': cdma[x['TVDMA'].strip()][x['STATE_AB'].strip()]+=1
MAN_CODE={8:511,36:567,97:570,87:752,76:546,79:538,85:519}
MAN_CTY={8:'Washington, DC - MD - PA - VA - WV DMA',97:'Florence - Myrtle Beach, SC - NC DMA',129:'LaCrosse - Eau Claire, WI - MN DMA',36:'Greenville - Spartanburg, SC- Asheville - And., NC - GA  DMA'}
# home state = 1.3 rule (principal city), same manual corrections as 1.3's gen_dma.py
HOME_FIX={175:'IL',127:'GA',8:'DC',10:'MA',36:'SC',52:'RI',57:'AL',90:'KY',91:'LA',93:'VT',89:'TX',101:'TN',104:'IA',105:'FL',108:'GA',138:'MN',142:'LA',150:'TX',151:'MO',160:'TX',163:'WV',164:'AZ',193:'WY',200:'IA'}
# 1.3 hand-checked call signs
calls13={}
for l in open(f'{T}/dma_calls.txt').read().split('\n'):
    if '|' in l: r,c=l.split('|'); calls13[int(r)]=[kv.split('=') for kv in c.split()]
NET={'A':'ABC','N':'NBC','C':'CBS','F':'FOX','W':'CW','P':'PBS','M':'MNT','T':'TEL','U':'UNI'}
# FCC LMS facility.dat (full-power DTV + Class A), licensed
fcc=[]
with open(f'{SRC}/facility.dat',encoding='latin-1') as f:
    hdr=f.readline().rstrip('\n').split('|')
    for l in f:
        p=dict(zip(hdr,l.rstrip('\n').split('|')))
        if p.get('service_code') in ('DTV','DCA') and p.get('facility_status')=='LICEN' and p.get('active_ind')=='Y': fcc.append(p)
def fnet(a):
    u=' '+re.sub(r'[^A-Z0-9]+',' ',a.upper())+' '
    found=[]
    for k,ws in [('ABC',['ABC']),('NBC',['NBC']),('CBS',['CBS']),('FOX',['FOX']),('CW',['CW','THE CW']),('PBS',['PBS']),('MNT',['MYNETWORK','MYNET','MY NETWORK','MNT','MYNETWORKTV','MY NETWORK TV']),('TEL',['TELEMUNDO']),('UNI',['UNIVISION']),('IND',['INDEPENDENT','IND'])]:
        pos=[u.find(' '+w+' ') for w in ws if ' '+w+' ' in u]
        if pos: found.append((min(pos),k))
    found.sort(); return [k for _,k in found]
out=[]; notes=[]
dmatext={}
for r,raw in ranks:
    code=MAN_CODE.get(r) or (pick(raw,radar,lambda c:c[1]) or (None,))[0]
    rn=dict(radar).get(code); gn=gist.get(code)
    cn=MAN_CTY.get(r) or pick(raw,list(cdma),lambda c:c)
    st=cdma.get(cn,collections.Counter())
    suf=[s for s in hints(raw.split(',',1)[1] if ',' in raw else '') if s in NAMES]
    home=HOME_FIX.get(r) or (suf[0] if suf else None)
    states=sorted(set(st)|set(suf)|({home} if home else set()))
    verify=[]
    if code is None: verify.append('code')
    elif gn and not (set(re.findall(r'[a-z]{4,}',norm(gn)))&set(re.findall(r'[a-z]{4,}',norm(rn)))): verify.append('code differs between sources')
    if not st: verify.append('states: no county list')
    miss=[s for s in suf if s not in st and not (r==8 and s=='DC')]
    if st and miss: verify.append('states '+','.join(miss)+' only from the market name')
    out.append(dict(code=code,rank=r,raw=raw,home=home,states=states,verify=verify,countyName=cn,counties=dict(st)))
# map FCC market text -> rank
byfirst=collections.defaultdict(list)
for o in out: byfirst[first(o['raw'])[:5]].append(o)
unm=collections.Counter()
for p in fcc:
    t=p['nielsen_dma_rank'].strip()
    if not t or t.upper() in ('NA','PUERTO RICO','N/A'): continue
    if t not in dmatext:
        c=byfirst.get(first(re.sub(r'\s+[A-Z]{2}$','',t))[:5],[])
        if len(c)>1:
            h=[o for o in c if any(s in hints(t.upper()) for s in [o['home']])] or [o for o in c if p['community_served_state'] in o['states']]
            c=h if len(h)==1 else [o for o in c if difflib.SequenceMatcher(None,norm(t),norm(o['raw'])).ratio()>0.6][:1] if len(h)!=1 else h
        dmatext[t]=c[0]['rank'] if len(c)==1 else None
    r=dmatext[t]
    if r is None: unm[t]+=1; continue
    call=p['callsign'].strip().upper(); base=re.sub(r'-(TV|DT|CD|CA|LD|HD)\d*$','',call)
    nets=fnet(p['network_affiliation'])
    if not nets: continue
    if not re.fullmatch(r'[KW][A-Z]{2,3}',base): continue   # translators (W36EX-D) and odd records
    o=next(x for x in out if x['rank']==r)
    o.setdefault('fcc',[]).append((base,nets,p['community_served_state'],p['service_code']))
print('unmapped FCC market texts:',sum(unm.values()),unm.most_common(15),file=sys.stderr)
# aliases (same rules as 1.3)
amb={'washington','columbus','portland','springfield','rochester','charleston','greenville','jackson','lafayette','albany','houston','austin','decatur','florence','lincoln','monroe','columbia','augusta','salisbury','wilmington','anderson','burlington','harrisburg','bloomington','marion','jacksonville','victoria','alexandria','bend','helena','eureka','laredo','lima','mankato','meridian','sherman','tyler','temple','troy','york','lebanon','richland','auburn','kearney','hastings','superior','austin','aiken','houston','west point','dayton','salem','washington'}
EXTRA={4:['DFW'],1:['NYC','New York City'],2:['LA'],9:['Bay Area','SF'],5:['Philly'],8:['DC','Washington DC'],16:['Twin Cities','St Paul'],24:['Saint Louis','STL'],18:['Ft Lauderdale','South Florida'],11:['Tampa Bay'],63:['Lexington KY'],49:['Louisville KY'],53:['Ft Myers'],39:['Palm Beach'],110:['Ft Wayne'],96:['Ft Smith'],19:['Akron']}
def disp(n):
    n=re.sub(r',\s*(D\.C\.|[A-Z]{2})(-[A-Z]{2})*(?=$|\s|-)','',n); n=re.sub(r'\s*\(.*?\)','',n).strip()
    n=re.sub(r',\s*[A-Z]{2}-',' – ',n); n=re.sub(r'\s*-\s*','–',n)
    return n.replace('Washington, D.C.','Washington, D.C.')
final=[]
firsts=collections.Counter(first(o['raw']) for o in out)
for o in out:
    r=o['rank']; base=re.sub(r',\s*(D\.C\.|[A-Z]{2})(-[A-Z]{2})*','',o['raw']).replace('Washington, D.C.','Washington DC')
    parts=[re.sub(r'\s+Plus$','',p.strip().replace('St.','St')) for p in re.split(r'[-&(),]|\band\b',base) if p.strip()]
    cities=[];ambig=[]
    for p in parts:
        if len(p)<4 and p.upper()!='DC': continue
        if p in ('Plus','Oak Hill') and False: pass
        (ambig if p.lower() in amb else cities).append(p)
    cities+=EXTRA.get(r,[])
    st=[]; seen=set()
    for k,v in calls13.get(r,[]):
        st.append(dict(call=v,net=NET[k],src='hand')); seen.add(v)
    fccv=[]
    for call,nets,s,svc in sorted(o.get('fcc',[]),key=lambda x:(x[3]!='DTV',x[0])):
        if call in seen:
            prev=[x for x in st if x['call']==call]
            if prev and prev[0]['src']=='hand' and nets[0]!=prev[0]['net'] and prev[0]['net'] not in nets:
                prev[0]['verify']=True; prev[0]['note']='FCC lists '+'/'.join(nets)
            continue
        e=dict(call=call,net=nets[0],src='fcc')
        if len(nets)>1 and not (nets[0] in('CW','MNT') and set(nets)<= {'CW','MNT','IND'}): e['verify']=True; e['note']='FCC lists '+'/'.join(nets)
        if svc=='DCA': e['classA']=True
        st.append(e); seen.add(call)
    m=dict(code=o['code'],rank=r,name=(disp(o['raw'])+(', '+o['home'] if firsts[first(o['raw'])]>1 and '–' not in disp(o['raw']) else '')) if r!=8 else 'Washington, D.C.',states=o['states'],home=o['home'],cities=cities,ambiguousCities=ambig,stations=st)
    if r==8 and 'DC' not in m['states']: m['states']=sorted(set(m['states'])|{'DC'})
    if o['verify']: m['verify']=True; m['verifyReason']='; '.join(o['verify'])
    final.append(m)
doc=dict(version=1,schema='ipvio.locals.dmas/1',updated='2026-11-09',
  sources=['Nielsen 2025-26 Local TV market (DMA) ranks, as published by methodshop.com (Sept 2025)',
           'DMA codes: docs.radar.com/regions/dmas (cross-checked with a public code list)',
           'States per DMA: county-to-DMA list github.com/alex-patton/US-TVDMA-BY-COUNTY (DC added by hand to Washington, D.C.)',
           'Station call signs: hand-checked list from an earlier app version (src hand) and FCC LMS facility data 2026-11-03 (src fcc; network_affiliation and nielsen market as filed by stations)'],
  states=NAMES,markets=final)
txt=json.dumps(doc,indent=1,ensure_ascii=False)
# one station per line keeps the file reviewable and diff-friendly
txt=re.sub(r'\{\n\s+"call": ([^\n]+),\n\s+"net": ([^\n]+?)(,\n\s+[^\n]+?)*\n\s+\}',lambda m: '{'+re.sub(r'\n\s+',' ',m.group(0)[1:-1]).strip()+'}',txt)
txt=re.sub(r'\[\n(\s+"[^"\n]*",?\n)+\s+\]',lambda m:'['+', '.join(x.strip().rstrip(',') for x in m.group(0)[1:-1].strip().split('\n'))+']',txt)
open(OUT,'w').write(txt+'\n')
print('markets',len(final),'verify',sum(1 for m in final if m.get('verify')),'stations',sum(len(m['stations']) for m in final),'st-verify',sum(1 for m in final for s in m['stations'] if s.get('verify')),'no stations',[m['rank'] for m in final if not m['stations']],file=sys.stderr)
for s in NAMES:
    if not any(s in m['states'] for m in final): print('STATE WITHOUT MARKET',s,file=sys.stderr)
print('codes dup',[c for c,k in collections.Counter(m['code'] for m in final).items() if k>1 or c is None],file=sys.stderr)
