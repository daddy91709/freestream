import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

def generate_square_icon(size=512):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bg = Image.new("RGBA", (size, size), (11, 15, 25, 255))
    
    # Glow central
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse([size*0.15, size*0.15, size*0.85, size*0.85], fill=(0, 210, 255, 75))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=size*0.12))
    bg = Image.alpha_composite(bg, glow)
    
    draw = ImageDraw.Draw(bg)
    
    # Outer Tech Rings
    draw.arc([size*0.12, size*0.12, size*0.88, size*0.88], start=-140, end=40, fill=(88, 166, 255, 140), width=int(size*0.03))
    draw.arc([size*0.06, size*0.06, size*0.94, size*0.94], start=-150, end=50, fill=(0, 210, 255, 90), width=int(size*0.015))
    
    # Cyan Play Triangle
    tri_glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    tg_draw = ImageDraw.Draw(tri_glow)
    
    p1 = (int(size * 0.38), int(size * 0.28))
    p2 = (int(size * 0.74), int(size * 0.50))
    p3 = (int(size * 0.38), int(size * 0.72))
    
    tg_draw.polygon([p1, p2, p3], fill=(0, 210, 255, 220))
    tri_glow = tri_glow.filter(ImageFilter.GaussianBlur(radius=size*0.05))
    bg = Image.alpha_composite(bg, tri_glow)
    
    draw = ImageDraw.Draw(bg)
    draw.polygon([p1, p2, p3], fill=(0, 210, 255, 255))
    draw.line([(p1[0]+size*0.04, p1[1]+size*0.06), (p3[0]+size*0.04, p3[1]-size*0.06)], fill=(255, 255, 255, 230), width=int(size*0.025))

    # Rounded mask for icon
    mask = Image.new("L", (size, size), 0)
    mask_draw = ImageDraw.Draw(mask)
    corner_radius = int(size * 0.22)
    mask_draw.rounded_rectangle([0, 0, size, size], radius=corner_radius, fill=255)
    
    final_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    final_img.paste(bg, (0, 0), mask)
    return final_img

def generate_tv_banner(width=640, height=360):
    """
    Crea un banner 16:9 full-bleed per Android TV, riempiendo tutto lo spazio
    in stile Netflix / Prime Video con grafica cinematografica e tipografia in evidenza.
    """
    # 1. Base gradient cinematografico
    bg = Image.new("RGBA", (width, height), (10, 14, 26, 255))
    
    # 2. Raggi diagonali di luce e gradienti ad alto contrasto
    accent_layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    acc_draw = ImageDraw.Draw(accent_layer)
    
    # Bagliore diffuso sul lato sinistro (dietro il logo)
    acc_draw.ellipse([-width*0.1, -height*0.2, width*0.6, height*1.2], fill=(0, 160, 255, 80))
    # Bagliore violaceo su lato destro
    acc_draw.ellipse([width*0.45, -height*0.1, width*1.15, height*1.1], fill=(120, 40, 240, 65))
    
    # Fascio diagonale di luce streaming
    acc_draw.polygon([
        (0, height),
        (int(width * 0.45), 0),
        (int(width * 0.65), 0),
        (int(width * 0.2), height)
    ], fill=(0, 210, 255, 25))
    
    accent_layer = accent_layer.filter(ImageFilter.GaussianBlur(radius=40))
    bg = Image.alpha_composite(bg, accent_layer)
    
    draw = ImageDraw.Draw(bg)
    
    # 3. Logo Play 3D sulla sinistra (grande e d'impatto)
    logo_size = int(height * 0.58)
    offset_x = int(width * 0.08)
    offset_y = (height - logo_size) // 2
    
    # Cerchio logo esterno
    circle_box = [offset_x, offset_y, offset_x + logo_size, offset_y + logo_size]
    draw.ellipse(circle_box, fill=(18, 24, 38, 240), outline=(0, 210, 255, 240), width=5)
    
    # Triangolo Play
    p1 = (offset_x + int(logo_size * 0.38), offset_y + int(logo_size * 0.28))
    p2 = (offset_x + int(logo_size * 0.74), offset_y + int(logo_size * 0.50))
    p3 = (offset_x + int(logo_size * 0.38), offset_y + int(logo_size * 0.72))
    draw.polygon([p1, p2, p3], fill=(0, 210, 255, 255))
    
    # Riflesso lucido interno
    draw.line([(p1[0]+8, p1[1]+12), (p3[0]+8, p3[1]-12)], fill=(255, 255, 255, 220), width=4)
    
    # 4. Tipografia grande e decisa
    text_x = offset_x + logo_size + int(width * 0.06)
    
    try:
        font_main = ImageFont.truetype("arialbd.ttf", int(height * 0.22)) # Bold
        font_tag = ImageFont.truetype("arialbd.ttf", int(height * 0.085))
    except Exception:
        try:
            font_main = ImageFont.truetype("arial.ttf", int(height * 0.22))
            font_tag = ImageFont.truetype("arial.ttf", int(height * 0.085))
        except Exception:
            font_main = ImageFont.load_default()
            font_tag = ImageFont.load_default()
            
    # Titolo "FreeStream" con ombra
    draw.text((text_x + 3, int(height * 0.27) + 3), "FreeStream", fill=(0, 0, 0, 180), font=font_main)
    draw.text((text_x, int(height * 0.27)), "FreeStream", fill=(255, 255, 255, 255), font=font_main)
    
    # Sottotitolo / Badge TV
    badge_y = int(height * 0.58)
    badge_w = int(width * 0.28)
    badge_h = int(height * 0.13)
    
    # Pill background
    draw.rounded_rectangle([text_x, badge_y, text_x + badge_w, badge_y + badge_h], radius=10, fill=(0, 210, 255, 45), outline=(0, 210, 255, 180), width=2)
    draw.text((text_x + 16, badge_y + 8), "SMART TV EDITION", fill=(0, 210, 255, 255), font=font_tag)
    
    # Bordo elegante 16:9 attorno al banner
    draw.rectangle([0, 0, width - 1, height - 1], outline=(0, 210, 255, 90), width=2)
    
    return bg

if __name__ == "__main__":
    os.makedirs("app/src/main/res/drawable", exist_ok=True)
    os.makedirs("app/src/main/res/drawable-xhdpi", exist_ok=True)
    os.makedirs("app/src/main/res/mipmap-xxxhdpi", exist_ok=True)
    os.makedirs("app/src/main/res/mipmap-xxhdpi", exist_ok=True)
    os.makedirs("app/src/main/res/mipmap-xhdpi", exist_ok=True)
    os.makedirs("app/src/main/res/mipmap-hdpi", exist_ok=True)
    os.makedirs("app/src/main/res/mipmap-mdpi", exist_ok=True)
    
    icon_512 = generate_square_icon(512)
    icon_512.save("app/src/main/res/drawable/app_icon.png")
    icon_512.save("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
    icon_512.save("app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png")
    
    for size, folder in [(144, "mipmap-xxhdpi"), (96, "mipmap-xhdpi"), (72, "mipmap-hdpi"), (48, "mipmap-mdpi")]:
        scaled = icon_512.resize((size, size), Image.Resampling.LANCZOS)
        scaled.save(f"app/src/main/res/{folder}/ic_launcher.png")
        scaled.save(f"app/src/main/res/{folder}/ic_launcher_round.png")
        
    banner = generate_tv_banner(640, 360)
    banner.save("app/src/main/res/drawable/tv_banner.png")
    banner.save("app/src/main/res/drawable-xhdpi/tv_banner.png")
    print("New edge-to-edge TV banner and icons generated successfully in drawable and drawable-xhdpi!")
