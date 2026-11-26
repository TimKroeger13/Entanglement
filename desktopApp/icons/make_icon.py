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
