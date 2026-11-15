import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

def create_gradient_radial(width, height, center_color, edge_color):
    base = Image.new("RGBA", (width, height), edge_color)
    center_x, center_y = width // 2, height // 2
    max_radius = (center_x**2 + center_y**2)**0.5
    
    # Simple radial gradient via image
    gradient = Image.new("RGBA", (width, height))
    draw = ImageDraw.Draw(gradient)
    
    steps = 100
    for i in range(steps, 0, -1):
        radius = max_radius * (i / steps)
        factor = i / steps
        r = int(center_color[0] * (1 - factor) + edge_color[0] * factor)
        g = int(center_color[1] * (1 - factor) + edge_color[1] * factor)
        b = int(center_color[2] * (1 - factor) + edge_color[2] * factor)
        draw.ellipse(
            [center_x - radius, center_y - radius, center_x + radius, center_y + radius],
            fill=(r, g, b, 255)
        )
    return gradient

def generate_square_icon(size=512):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    
    # Background: Rounded rectangle with dark sleek gradient
    bg = Image.new("RGBA", (size, size), (11, 15, 25, 255))
    draw = ImageDraw.Draw(bg)
    
    # Add subtle glowing circle in center
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse([size*0.2, size*0.2, size*0.8, size*0.8], fill=(0, 210, 255, 60))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=size*0.1))
    bg = Image.alpha_composite(bg, glow)
    
    draw = ImageDraw.Draw(bg)
    
    # Draw Stream Wave / Antenna rings
    ring_color = (88, 166, 255, 120)
    draw.arc([size*0.15, size*0.15, size*0.85, size*0.85], start=-140, end=40, fill=ring_color, width=int(size*0.025))
    draw.arc([size*0.08, size*0.08, size*0.92, size*0.92], start=-150, end=50, fill=(0, 210, 255, 80), width=int(size*0.015))
    
    # Draw Modern Play Triangle in center
    # Cyan to Purple gradient play button
    tri_glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    tg_draw = ImageDraw.Draw(tri_glow)
    
    p1 = (int(size * 0.40), int(size * 0.30))
    p2 = (int(size * 0.72), int(size * 0.50))
    p3 = (int(size * 0.40), int(size * 0.70))
    
    # Glow for triangle
    tg_draw.polygon([p1, p2, p3], fill=(0, 210, 255, 200))
    tri_glow = tri_glow.filter(ImageFilter.GaussianBlur(radius=size*0.04))
    bg = Image.alpha_composite(bg, tri_glow)
    
    # Solid triangle on top with rounded corner look
    draw = ImageDraw.Draw(bg)
    draw.polygon([p1, p2, p3], fill=(0, 210, 255, 255))
    
    # High-tech inner accent line
    draw.line([(p1[0]+size*0.04, p1[1]+size*0.06), (p3[0]+size*0.04, p3[1]-size*0.06)], fill=(255, 255, 255, 220), width=int(size*0.02))

    # Rounded mask for app icon
    mask = Image.new("L", (size, size), 0)
    mask_draw = ImageDraw.Draw(mask)
    corner_radius = int(size * 0.22)
    mask_draw.rounded_rectangle([0, 0, size, size], radius=corner_radius, fill=255)
    
    final_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    final_img.paste(bg, (0, 0), mask)
    return final_img

def generate_tv_banner(width=640, height=360):
    # Android TV 16:9 banner
    bg = Image.new("RGBA", (width, height), (13, 17, 23, 255))
    
    # Gradient radial background
    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse([width*0.05, height*0.1, width*0.55, height*0.9], fill=(0, 180, 255, 50))
    glow_draw.ellipse([width*0.5, height*0.2, width*0.95, height*0.8], fill=(120, 50, 255, 40))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=50))
    bg = Image.alpha_composite(bg, glow)
    
    draw = ImageDraw.Draw(bg)
    
    # Draw Play Logo on the left
    logo_size = int(height * 0.55)
    offset_x = int(width * 0.12)
    offset_y = (height - logo_size) // 2
    
    # Play Icon Circle
    circle_box = [offset_x, offset_y, offset_x + logo_size, offset_y + logo_size]
    draw.ellipse(circle_box, fill=(22, 27, 34, 230), outline=(0, 210, 255, 200), width=4)
    
    # Play triangle inside circle
    p1 = (offset_x + int(logo_size * 0.38), offset_y + int(logo_size * 0.28))
    p2 = (offset_x + int(logo_size * 0.72), offset_y + int(logo_size * 0.50))
    p3 = (offset_x + int(logo_size * 0.38), offset_y + int(logo_size * 0.72))
    draw.polygon([p1, p2, p3], fill=(0, 210, 255, 255))
    
    # Typography: "FreeStream" & "Android TV"
    text_x = offset_x + logo_size + int(width * 0.06)
    
    # Fallback to default or load basic font
    try:
        font_title = ImageFont.truetype("arial.ttf", int(height * 0.18))
        font_sub = ImageFont.truetype("arial.ttf", int(height * 0.08))
    except Exception:
        font_title = ImageFont.load_default()
        font_sub = ImageFont.load_default()
        
    draw.text((text_x, int(height * 0.34)), "FreeStream", fill=(255, 255, 255, 255), font=font_title)
    
    # Badge "ANDROID TV"
    badge_x = text_x
    badge_y = int(height * 0.58)
    draw.rounded_rectangle([badge_x, badge_y, badge_x + 180, badge_y + 36], radius=8, fill=(0, 210, 255, 30), outline=(0, 210, 255, 120), width=1)
    draw.text((badge_x + 16, badge_y + 8), "ANDROID TV", fill=(0, 210, 255, 255), font=font_sub)
    
    return bg

if __name__ == "__main__":
    os.makedirs("app/src/main/res/drawable", exist_ok=True)
    os.makedirs("app/src/main/res/mipmap-xxxhdpi", exist_ok=True)
    os.makedirs("app/src/main/res/mipmap-xxhdpi", exist_ok=True)
    os.makedirs("app/src/main/res/mipmap-xhdpi", exist_ok=True)
    os.makedirs("app/src/main/res/mipmap-hdpi", exist_ok=True)
    os.makedirs("app/src/main/res/mipmap-mdpi", exist_ok=True)
    
    icon_512 = generate_square_icon(512)
    icon_512.save("app/src/main/res/drawable/app_icon.png")
    icon_512.save("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
    icon_512.save("app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png")
    
    # Generate smaller mipmaps
    for size, folder in [(144, "mipmap-xxhdpi"), (96, "mipmap-xhdpi"), (72, "mipmap-hdpi"), (48, "mipmap-mdpi")]:
        scaled = icon_512.resize((size, size), Image.Resampling.LANCZOS)
        scaled.save(f"app/src/main/res/{folder}/ic_launcher.png")
        scaled.save(f"app/src/main/res/{folder}/ic_launcher_round.png")
        
    banner = generate_tv_banner(640, 360)
    banner.save("app/src/main/res/drawable/tv_banner.png")
    print("Icons and banner generated successfully!")
