# Generates the launcher icon (adaptive + themed vector drawables) and design/app-icon-preview.svg. Run from the project root.
import math, os
dx=2.0   # centre the content (x 31.5..76.5) in the 108 dp canvas
x0,x1,y0,yb,yt=29.5+dx,55.5+dx,31.0,71.0,75.0
n=6; tw=(x1-x0)/n
pts=[f'M{x0:.2f},{y0}',f'H{x1:.2f}',f'V{yb}']
for i in range(n):
    pts+= [f'L{x1-tw*(i+0.5):.2f},{yt}', f'L{x1-tw*(i+1):.2f},{yb}']
pts.append('Z')
receipt=' '.join(pts)
bars=[(32.5+dx,36.5,49.0+dx),(32.5+dx,41.0,45.5+dx),(32.5+dx,45.5,47.5+dx)]
bar_path=' '.join(f'M{a:.2f},{y} H{b:.2f}' for a,y,b in bars)
cx,cy,r=42.5+dx,59.0,5.2
sx=cx+r*math.cos(math.radians(40)); sy=cy-r*math.sin(math.radians(40)); ey=cy+r*math.sin(math.radians(40))
euro=f'M{sx:.2f},{sy:.2f} A{r},{r} 0 1 0 {sx:.2f},{ey:.2f} M{cx-r-1.6:.2f},{cy-1.7:.2f} H{cx+r*0.6:.2f} M{cx-r-1.6:.2f},{cy+1.7:.2f} H{cx+r*0.6:.2f}'
wcx,wcy=57.5+dx,52.0
waves=' '.join(f'M{wcx+0.7071*rr:.2f},{wcy-0.7071*rr:.2f} a{rr},{rr} 0 0 1 0,{1.4142*rr:.2f}' for rr in (6,11.5,17))
ORANGE='#FF7043'; GREY='#B0BEC5'; BG='#263238'

fg=f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Launcher icon foreground: a ticket with a euro and sound waves (scan + speak). Content kept in the 66 dp safe zone. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFFFF" android:pathData="{receipt}" />
    <path android:strokeColor="{GREY}" android:strokeWidth="2.2" android:strokeLineCap="round" android:pathData="{bar_path}" />
    <path android:strokeColor="{ORANGE}" android:strokeWidth="2.2" android:strokeLineCap="round" android:pathData="{euro}" />
    <path android:strokeColor="{ORANGE}" android:strokeWidth="3" android:strokeLineCap="round" android:pathData="{waves}" />
</vector>
'''
mono=f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Themed (one colour) launcher icon: the same drawing as outlines. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:strokeColor="#FF000000" android:strokeWidth="2.4" android:strokeLineJoin="round" android:pathData="{receipt}" />
    <path android:strokeColor="#FF000000" android:strokeWidth="2.2" android:strokeLineCap="round" android:pathData="{bar_path}" />
    <path android:strokeColor="#FF000000" android:strokeWidth="2.2" android:strokeLineCap="round" android:pathData="{euro}" />
    <path android:strokeColor="#FF000000" android:strokeWidth="3" android:strokeLineCap="round" android:pathData="{waves}" />
</vector>
'''
adaptive='''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''
res='app/src/main/res'
os.makedirs(f'{res}/mipmap-anydpi-v26',exist_ok=True)
open(f'{res}/drawable/ic_launcher_foreground.xml','w').write(fg)
open(f'{res}/drawable/ic_launcher_monochrome.xml','w').write(mono)
open(f'{res}/values/ic_launcher_background.xml','w').write(f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">{BG}</color>\n</resources>\n')
open(f'{res}/mipmap-anydpi-v26/ic_launcher.xml','w').write(adaptive)
open(f'{res}/mipmap-anydpi-v26/ic_launcher_round.xml','w').write(adaptive)

# preview: the same paths with a circle mask, a rounded-square mask, and the themed version
def tile(x, y, mask, mono_style=False):
    s=3.0
    if mono_style:
        body=f'''<rect x="0" y="0" width="108" height="108" fill="#CFD8DC"/>
<g fill="none" stroke="#263238" stroke-linecap="round" stroke-linejoin="round">
<path d="{receipt}" stroke-width="2.4"/><path d="{bar_path}" stroke-width="2.2"/><path d="{euro}" stroke-width="2.2"/><path d="{waves}" stroke-width="3"/></g>'''
    else:
        body=f'''<rect x="0" y="0" width="108" height="108" fill="{BG}"/>
<path d="{receipt}" fill="#FFFFFF"/>
<g fill="none" stroke-linecap="round"><path d="{bar_path}" stroke="{GREY}" stroke-width="2.2"/><path d="{euro}" stroke="{ORANGE}" stroke-width="2.2"/><path d="{waves}" stroke="{ORANGE}" stroke-width="3"/></g>'''
    cid=f'c{int(x)}'
    shape=f'<circle cx="54" cy="54" r="36"/>' if mask=='circle' else f'<rect x="18" y="18" width="72" height="72" rx="20"/>'
    return f'<g transform="translate({x},{y}) scale({s})"><clipPath id="{cid}">{shape}</clipPath><g clip-path="url(#{cid})">{body}</g></g>'
svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="400" viewBox="0 0 1080 400">
<rect width="1080" height="400" fill="#F4F4F4"/>
{tile(20,38,'circle')}{tile(378,38,'squircle')}{tile(736,38,'circle',True)}
<g font-family="DejaVu Sans" font-size="20" fill="#333" text-anchor="middle">
<text x="182" y="380">cercle</text><text x="540" y="380">carré arrondi</text><text x="898" y="380">thème (monochrome)</text></g>
</svg>'''
os.makedirs('design',exist_ok=True)
open('design/app-icon-preview.svg','w').write(svg)
print('ok')
