import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

FONT_CANDIDATES = [
    "arialbd.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "DejaVuSans-Bold.ttf",
]


def load_font(size):
    for path in FONT_CANDIDATES:
        try:
            return ImageFont.truetype(path, size)
        except Exception:
            continue
    return ImageFont.load_default(size=size)


def fit_font(draw, text, max_width, start_size, min_size=10):
    size = start_size
    while size > min_size:
        font = load_font(size)
        if draw.textlength(text, font=font) <= max_width:
            return font
        size -= 2
    return load_font(min_size)


def background(width, height):
    """Sfondo cinematografico a tutto campo (nessun bordo o trasparenza)."""
    bg = Image.new("RGBA", (width, height), (10, 14, 26, 255))
    accent = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    acc = ImageDraw.Draw(accent)
    acc.ellipse([-width * 0.15, -height * 0.3, width * 0.6, height * 1.3], fill=(0, 160, 255, 90))
    acc.ellipse([width * 0.45, -height * 0.2, width * 1.2, height * 1.2], fill=(120, 40, 240, 75))
    acc.polygon([(0, height), (int(width * 0.45), 0), (int(width * 0.65), 0), (int(width * 0.2), height)],
                fill=(0, 210, 255, 28))
    accent = accent.filter(ImageFilter.GaussianBlur(radius=max(width, height) * 0.06))
    return Image.alpha_composite(bg, accent)


def draw_play_logo(img, cx, cy, diameter):
    draw = ImageDraw.Draw(img)
    r = diameter / 2
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([cx - r * 1.1, cy - r * 1.1, cx + r * 1.1, cy + r * 1.1], fill=(0, 210, 255, 90))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=diameter * 0.12))
    img.alpha_composite(glow)
    draw = ImageDraw.Draw(img)
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(18, 24, 38, 245), outline=(0, 210, 255, 255),
                 width=max(2, int(diameter * 0.035)))
    p1 = (cx - r * 0.24, cy - r * 0.44)
    p2 = (cx + r * 0.48, cy)
    p3 = (cx - r * 0.24, cy + r * 0.44)
    draw.polygon([p1, p2, p3], fill=(0, 210, 255, 255))
    draw.line([(p1[0] + r * 0.08, p1[1] + r * 0.14), (p3[0] + r * 0.08, p3[1] - r * 0.14)],
              fill=(255, 255, 255, 220), width=max(2, int(diameter * 0.03)))


def generate_square_icon(size=512, shape="square"):
    """Icona launcher a tutto campo: niente angoli trasparenti, così il launcher non la rimpicciolisce."""
    img = background(size, size)
    draw_play_logo(img, size / 2, size / 2, size * 0.72)
    if shape == "round":
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size, size], fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(img, (0, 0), mask)
        return out
    if shape == "rounded":
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, size, size], radius=int(size * 0.22), fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(img, (0, 0), mask)
        return out
    return img


def generate_tv_banner(width=640, height=360):
    """Banner 16:9 full-bleed per Fire TV / Android TV (android:banner)."""
    img = background(width, height)
    draw = ImageDraw.Draw(img)

    logo_d = int(height * 0.56)
    logo_cx = int(width * 0.08) + logo_d / 2
    draw_play_logo(img, logo_cx, height / 2, logo_d)
    draw = ImageDraw.Draw(img)

    text_x = int(logo_cx + logo_d / 2 + width * 0.05)
    max_text_w = width - text_x - int(width * 0.06)

    title = "FreeStream"
    font_main = fit_font(draw, title, max_text_w, int(height * 0.22))
    tb = draw.textbbox((0, 0), title, font=font_main)
    title_h = tb[3] - tb[1]

    tag = "TV EDITION"
    font_tag = fit_font(draw, tag, max_text_w - 32, int(height * 0.075))
    gb = draw.textbbox((0, 0), tag, font=font_tag)
    tag_w, tag_h = gb[2] - gb[0], gb[3] - gb[1]
    pill_pad_x, pill_pad_y = 16, 10
    pill_h = tag_h + pill_pad_y * 2

    block_h = title_h + int(height * 0.06) + pill_h
    top = (height - block_h) // 2

    ty = top - tb[1]
    draw.text((text_x + 3, ty + 3), title, fill=(0, 0, 0, 170), font=font_main)
    draw.text((text_x, ty), title, fill=(255, 255, 255, 255), font=font_main)

    pill_y = top + title_h + int(height * 0.06)
    draw.rounded_rectangle([text_x, pill_y, text_x + tag_w + pill_pad_x * 2, pill_y + pill_h],
                           radius=pill_h // 2, fill=(8, 62, 88, 255), outline=(0, 210, 255, 255), width=2)
    draw.text((text_x + pill_pad_x - gb[0], pill_y + pill_pad_y - gb[1]), tag, fill=(0, 210, 255, 255), font=font_tag)

    return img.convert("RGB")


if __name__ == "__main__":
    res = "app/src/main/res"
    for folder in ["drawable", "drawable-xhdpi", "mipmap-xxxhdpi", "mipmap-xxhdpi", "mipmap-xhdpi", "mipmap-hdpi", "mipmap-mdpi"]:
        os.makedirs(f"{res}/{folder}", exist_ok=True)

    generate_square_icon(512, "rounded").save(f"{res}/drawable/app_icon.png")

    square = generate_square_icon(512, "square")
    round_icon = generate_square_icon(512, "round")
    for size, folder in [(192, "mipmap-xxxhdpi"), (144, "mipmap-xxhdpi"), (96, "mipmap-xhdpi"), (72, "mipmap-hdpi"), (48, "mipmap-mdpi")]:
        square.resize((size, size), Image.Resampling.LANCZOS).save(f"{res}/{folder}/ic_launcher.png")
        round_icon.resize((size, size), Image.Resampling.LANCZOS).save(f"{res}/{folder}/ic_launcher_round.png")

    # Il banner va SOLO in drawable-xhdpi (640x360 px = 320x180 dp), come richiesto da Fire TV / Android TV
    stale = f"{res}/drawable/tv_banner.png"
    if os.path.exists(stale):
        os.remove(stale)
    generate_tv_banner(640, 360).save(f"{res}/drawable-xhdpi/tv_banner.png")
    print("Icone launcher e banner TV 16:9 generati.")
