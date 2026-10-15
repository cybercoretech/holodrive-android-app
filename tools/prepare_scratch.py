#!/usr/bin/env python3
"""Create isolated Holodrive fixtures; never reads the production config."""
import json
import math
import os
from pathlib import Path
import secrets
import tempfile

from argon2 import PasswordHasher
from PIL import Image, ImageDraw

base = Path(tempfile.mkdtemp(prefix="holodrive-photos-"))
os.chmod(base, 0o700)
photos = base / "photos"
(photos / "Weekend escapes").mkdir(parents=True)
(photos / "Empty album").mkdir()
colors = [(12, 126, 164), (181, 56, 118), (130, 88, 188), (25, 135, 108), (210, 137, 66), (58, 99, 182)]
for index, tint in enumerate(colors):
    image = Image.new("RGB", (900, 1100))
    pixels = image.load()
    for y in range(1100):
        for x in range(900):
            glow = max(0, 1 - math.hypot(x - 580, y - 320) / 1000)
            pixels[x, y] = tuple(int(c * (.2 + .65 * glow)) for c in tint)
    draw = ImageDraw.Draw(image)
    draw.ellipse((460, 190, 720, 450), fill=tuple(min(255, c + 65) for c in tint))
    draw.polygon([(0, 830), (250, 480 + index * 12), (520, 840), (760, 590), (900, 820), (900, 1100), (0, 1100)], fill=(15 + index * 2, 25, 42))
    draw.polygon([(0, 950), (430, 770), (900, 1030), (900, 1100), (0, 1100)], fill=(8, 14, 25))
    image.save(photos / f"{index + 1:02d} - Horizon étude.jpg", quality=92)
    if index < 2:
        image.save(photos / "Weekend escapes" / f"Coast & sky {index}.jpg", quality=92)
(photos / "notes.txt").write_text("This must not appear in the photo gallery.\n")
(photos / ".hidden.jpg").write_bytes(b"hidden")
# Keep one private local-test credential so rebuilding the disposable fixture
# server does not force the developer to fetch a new password each time.
credential = Path.home() / ".local/share/holodrive-photos/scratch-password"
credential.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(credential.parent, 0o700)
try:
    password = credential.read_text().strip()
except FileNotFoundError:
    password = secrets.token_urlsafe(24)
    fd = os.open(credential, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        stream.write(password)
credential.chmod(0o600)
# Keep the fixture-local copy for the emulator helper; do not print it.
(base / "password").write_text(password)
os.chmod(base / "password", 0o600)
config = f'''listen = "127.0.0.1:18768"
hosts = ["blackbox.tail9311b5.ts.net:8768"]
password_hash = "{PasswordHasher().hash(password)}"

[[roots]]
id = "pictures"
name = "Photo library"
path = {json.dumps(str(photos))}
icon = "🖼️"
writable = true
'''
(base / "config.toml").write_text(config)
os.chmod(base / "config.toml", 0o600)
Path("/tmp/holodrive-photos-scratch-path").write_text(str(base))
print(base)
