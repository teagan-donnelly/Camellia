"""Make the Camellia logo PNG transparent: remove the white background, white flower center, speckle and the
blue screenshot-selection lines, then trim to the artwork with padding."""
import sys
from PIL import Image

src, *dests = sys.argv[1:]
im = Image.open(src).convert('RGB')
w, h = im.size
px = im.load()
LO, HI = 8, 110          # deviation from white: <= LO is background noise, >= HI is fully opaque artwork

def is_art(r, g, b):     # pink/red artwork, never the blue selection lines
    return r > b + 40 and r > g + 40

xs, ys = [], []
for y in range(h):
    for x in range(w):
        if is_art(*px[x, y]):
            xs.append(x); ys.append(y)
pad = 24
box = (max(min(xs) - pad, 0), max(min(ys) - pad, 0), min(max(xs) + pad + 1, w), min(max(ys) + pad + 1, h))
crop = im.crop(box)
out = Image.new('RGBA', crop.size)
cp, op = crop.load(), out.load()
for y in range(crop.size[1]):
    for x in range(crop.size[0]):
        r, g, b = cp[x, y]
        d = 255 - min(r, g, b)
        if not is_art(r, g, b) and d > LO and (b > r):   # stray blue pixels
            op[x, y] = (0, 0, 0, 0); continue
        a = min(max((d - LO) / (HI - LO), 0.0), 1.0)
        if a == 0:
            op[x, y] = (0, 0, 0, 0); continue
        # Un-premultiply against white so anti-aliased edges keep the artwork color (no white halo).
        un = lambda c: int(round(min(max((c - (1 - a) * 255) / a, 0), 255)))
        op[x, y] = (un(r), un(g), un(b), int(round(a * 255)))
for d in dests:
    out.save(d)
print('crop', box, '->', out.size)
