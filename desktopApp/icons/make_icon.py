"""Draws the Entanglement icon (two interlocked rings) and writes entanglement.ico / .png.

Run: python make_icon.py
"""
from PIL import Image, ImageDraw

S = 1024
VIOLET = (124, 92, 255, 255)
TEAL = (0, 184, 190, 255)
R = S * 0.27          # ring radius
W = int(S * 0.095)    # ring width
DX = S * 0.15         # horizontal offset of each ring from the centre


def ring(cx: float, color) -> Image.Image:
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse([cx - R, S / 2 - R, cx + R, S / 2 + R], outline=color, width=W)
    return layer


left = ring(S / 2 - DX, VIOLET)
right = ring(S / 2 + DX, TEAL)

# Teal over violet everywhere, then violet back on top at the lower crossing: the rings interlock.
img = Image.alpha_composite(Image.alpha_composite(Image.new("RGBA", (S, S)), left), right)
crossing = Image.new("L", (S, S), 0)
ImageDraw.Draw(crossing).rectangle([S / 2 - S * 0.12, S / 2, S / 2 + S * 0.12, S], fill=255)
img.paste(left, (0, 0), Image.composite(left, Image.new("RGBA", (S, S)), crossing))

img.save("entanglement.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
img.resize((256, 256), Image.LANCZOS).save("entanglement.png")


# --- Explorer overlay badge: small rings on a white disc in the lower-left corner -------------------
def overlay() -> Image.Image:
    badge = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(badge)
    r = S * 0.25
    cx, cy = S * 0.27, S * 0.73
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255, 255), outline=(170, 170, 185, 255),
              width=int(S * 0.02))
    rings = img.resize((int(r * 1.75), int(r * 1.75)), Image.LANCZOS)
    badge.alpha_composite(rings, (int(cx - rings.width / 2), int(cy - rings.height / 2)))
    return badge


import os

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "resources", "windows")
os.makedirs(out, exist_ok=True)
badge = overlay()
badge.save(os.path.join(out, "EntanglementOverlay.ico"), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (256, 256)])
badge.resize((256, 256), Image.LANCZOS).save("overlay-preview.png")
