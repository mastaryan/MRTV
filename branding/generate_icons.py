# MR TV 2: regenerates launcher icons, adaptive-icon foreground, in-app brand mark and the TV banners
# from branding/logo-app.jpg. Needs Python 3 with Pillow, numpy and scipy:  python3 branding/generate_icons.py
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import numpy as np
from scipy import ndimage
import os
HERE=os.path.dirname(os.path.abspath(__file__))
R=os.path.join(HERE,'..','app','src','main','res')+'/'
src=Image.open(os.path.join(HERE,'logo-app.jpg')).convert('RGB').resize((1080,1080),Image.LANCZOS)
a=np.asarray(src).astype(np.int32)
L=a.max(axis=2)
# transparent cut-out: dark pixels connected to the border become transparent (soft edge)
fgm=L>=40
yy,xx=np.ogrid[-25:26,-25:26]; disk=(xx*xx+yy*yy)<=625
closed=ndimage.binary_closing(np.pad(fgm,30),structure=disk)[30:-30,30:-30]
filled=ndimage.binary_fill_holes(closed)
filled=ndimage.binary_opening(filled,structure=np.ones((5,5)))
alpha=np.where(filled,255,0).astype(np.uint8)
alpha=np.asarray(Image.fromarray(alpha).filter(ImageFilter.GaussianBlur(1.2)))
rgba=np.dstack([a.astype(np.uint8),alpha])
cut=Image.fromarray(rgba,'RGBA')
bb=Image.fromarray(np.where(alpha>40,255,0).astype(np.uint8)).getbbox(); cut=cut.crop(bb)
print('cut bbox',bb,cut.size)
def square(img,size,frac,bg=(0,0,0,0)):
    c=Image.new('RGBA',(size,size),bg)
    s=frac*size/max(img.size)
    r=img.resize((max(1,round(img.width*s)),max(1,round(img.height*s))),Image.LANCZOS)
    c.alpha_composite(r,((size-r.width)//2,(size-r.height)//2))
    return c

# MR TV 2 (2.0.6): red "2" badge top right (as approved in logo-2-compare.png): red circle, white ring, bold white 2
import math
RED=(0xE5,0x39,0x35,255)
def make_badge(D):
    over=4; S=D*over
    img=Image.new('RGBA',(S,S),(0,0,0,0))
    sh=Image.new('RGBA',(S,S),(0,0,0,0)); inset=int(S*0.03)
    ImageDraw.Draw(sh).ellipse((inset,inset+int(S*0.04),S-inset,S-inset+int(S*0.04)),fill=(0,0,0,180))
    img=Image.alpha_composite(img,sh.filter(ImageFilter.GaussianBlur(int(S*0.04))))
    d=ImageDraw.Draw(img)
    d.ellipse((0,0,S-1,S-1),fill=(255,255,255,255))
    ring=max(2*over,int(S*0.055)); d.ellipse((ring,ring,S-1-ring,S-1-ring),fill=RED)
    font=ImageFont.truetype(R+'font/outfit_bold.ttf',int(S*0.72))
    l,t,r,b=d.textbbox((0,0),'2',font=font)
    d.text(((S-(r-l))/2-l,(S-(b-t))/2-t-S*0.03),'2',font=font,fill=(255,255,255,255))
    return img.resize((D,D),Image.LANCZOS)
def badge_icon(icon,bfrac=118/512,safe=0.97):
    """Whole badge inside the inscribed circle (safe = radius share), so a round mask never cuts the 2."""
    out=icon.copy(); S=out.size[0]
    bsz=int(round(S*bfrac)); b=make_badge(bsz); br=bsz/2
    d=max(0.0,(S/2)*safe-br); ang=math.radians(42)
    out.alpha_composite(b,(int(round(S/2+d*math.cos(ang)-br)),int(round(S/2-d*math.sin(ang)-br))))
    return out
# brand mark used in-app (sidebar, loading) 256px transparent
square(cut,256,0.98).save(R+'drawable/ic_brand_mark.png')
# adaptive foreground 432x432 (108dp @xxxhdpi), content inside 66dp safe circle -> ~0.60
fg=square(cut,432,0.60)
# badge inside the 66dp safe circle of the 108dp canvas, same size relative to the logo as on the launcher icon
fg=badge_icon(fg,bfrac=(118/512)*0.60/0.86,safe=(66/108)*0.97)
fg.save(R+'drawable/ic_logo_fg.png')
BG=(0x0D,0x0E,0x10,255)
dens={'mdpi':48,'hdpi':72,'xhdpi':96,'xxhdpi':144,'xxxhdpi':192}
for d,s in dens.items():
    sq=badge_icon(square(cut,s*4,0.86,BG))
    sq.resize((s,s),Image.LANCZOS).save(R+f'mipmap-{d}/ic_launcher.webp','WEBP',lossless=True)
    # round
    rd=badge_icon(square(cut,s*4,0.80,BG))
    m=Image.new('L',(s*4,s*4),0); ImageDraw.Draw(m).ellipse((0,0,s*4-1,s*4-1),fill=255)
    rd.putalpha(m)
    rd.resize((s,s),Image.LANCZOS).save(R+f'mipmap-{d}/ic_launcher_round.webp','WEBP',lossless=True)
# banner 320x180 (mdpi) and 640x360 (xhdpi)
def banner(W,H):
    k=W/320
    c=Image.new('RGBA',(W,H),BG)
    logo=square(cut,int(150*k),1.0)
    c.alpha_composite(logo,(int(14*k),(H-logo.height)//2))
    txt='MR TV 2'
    x0=int(14*k)+logo.width+int(14*k); avail=W-x0-int(14*k)
    fs=int(60*k)
    while True:
        font=ImageFont.truetype(R+'font/outfit_bold.ttf',fs)
        l,t,r,b=ImageDraw.Draw(c).textbbox((0,0),txt,font=font)
        if r-l<=avail: break
        fs-=1
    tw,th=r,b; x=x0+(avail-(r-l))//2-l; y=(H-th)//2-int(6*k)
    m=Image.new('L',(W,H),0); ImageDraw.Draw(m).text((x,y),txt,font=font,fill=255)
    grad=Image.new('RGBA',(W,H))
    g=np.zeros((H,W,4),np.uint8)
    top=np.array([0xF2,0xF4,0xF7]); bot=np.array([0xB9,0xBF,0xC8])
    for yy in range(H):
        t=min(max((yy-y)/max(th,1),0),1); g[yy,:,:3]=(top*(1-t)+bot*t).astype(np.uint8)
    g[:,:,3]=np.asarray(m); c.alpha_composite(Image.fromarray(g,'RGBA'))
    # underline like site
    d=ImageDraw.Draw(c); d.line((x,y+th+int(10*k),x+tw,y+th+int(10*k)),fill=(0xC9,0xCE,0xD6,255),width=max(1,int(2*k)))
    # small banner: badge scaled with the banner (pads/sizes from the 640x360 design)
    bsz=int(H*0.20); pad=int(H*0.045)
    c.alpha_composite(make_badge(bsz),(W-pad-bsz,pad))
    return c.convert('RGB')
import os
os.makedirs(R+'drawable-mdpi',exist_ok=True); os.makedirs(R+'drawable-xhdpi',exist_ok=True)
banner(320,180).save(R+'drawable-mdpi/banner.png')
banner(640,360).save(R+'drawable-xhdpi/banner.png')
if os.path.exists(R+'drawable/banner.png'): os.remove(R+'drawable/banner.png')
