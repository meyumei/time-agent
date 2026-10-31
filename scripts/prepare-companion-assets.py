"""Slice generated character boards, align feet, and export native atlases and animations."""
import hashlib
import json
from pathlib import Path
import zipfile

import cv2
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "design/fish-companion"
RES = ROOT / "app/src/main/res/drawable-nodpi"
NAMES = [
    "idle-open", "idle-half-blink", "idle-blink", "idle-look",
    "wave-start", "wave-high", "wave-side", "delighted",
    "thinking", "curious", "work-read", "work-write",
    "sleepy", "sleeping", "concerned", "apologetic",
]
SEQUENCES = {
    "idle": ([0, 0, 0, 0, 1, 2, 1, 0, 3, 0], [600, 600, 600, 600, 90, 110, 90, 800, 700, 1000]),
    "happy": ([4, 5, 6, 5, 4, 7], [220, 300, 300, 300, 260, 700]),
    "thinking": ([8, 9, 8], [900, 800, 900]),
    "working": ([10, 11, 10, 11], [650, 450, 650, 450]),
    "concerned": ([14, 15, 14], [1100, 800, 1100]),
    "rest": ([12, 13], [1400, 2200]),
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    manifest = {
        "schema": 1, "generator": "built-in image_gen, reference-guided",
        "references": json.loads((ROOT / "evidence/fish-companion-20261029/reference-hashes.json").read_text("utf-8")),
        "cellSize": [320, 320], "columns": 4, "rows": 4, "styles": {},
        "review": {"transparent": True, "textFree": True, "nativeVisualVerified": False},
        "sequences": SEQUENCES,
    }
    RES.mkdir(parents=True, exist_ok=True)
    for style, source_name in [("chibi", "chibi-v2.png"), ("elegant", "elegant-v1.png")]:
        source = DESIGN / "atlases" / source_name
        original = Image.open(source).convert("RGBA")
        rgba = np.array(original)
        h, w = rgba.shape[:2]
        pieces = []
        records = []
        for col in range(4):
            x1, x2 = round(col * w / 4), round((col + 1) * w / 4)
            counts = (rgba[:, x1:x2, 3] > 32).sum(axis=1)
            cuts = [0] + [
                min(range(round(k * h / 4) - 20, round(k * h / 4) + 21),
                    key=lambda y: (counts[y], abs(y - k * h / 4)))
                for k in range(1, 4)
            ] + [h]
            for row in range(4):
                crop = rgba[cuts[row]:cuts[row + 1], x1:x2].copy()
                count, labels, stats, _ = cv2.connectedComponentsWithStats((crop[:, :, 3] > 16).astype("uint8"), 8)
                # Keep the complete connected character; discard stray pixels and neighboring tail slivers.
                main_label = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
                keep = [main_label]
                mask = np.isin(labels, keep).astype("uint8")
                mask = cv2.dilate(mask, np.ones((3, 3), dtype="uint8"))
                crop[:, :, 3] *= mask
                image = Image.fromarray(crop)
                box = image.getchannel("A").getbbox()
                assert box, (style, row, col)
                image = image.crop(box)
                pieces.append((row * 4 + col, image))
                records.append({"index": row * 4 + col, "name": NAMES[row * 4 + col],
                                "sourceRect": [x1, cuts[row], x2, cuts[row + 1]], "alphaBounds": list(box)})
        scale = min(280 / max(im.height for _, im in pieces), 294 / max(im.width for _, im in pieces))
        out = DESIGN / "frames" / style
        out.mkdir(parents=True, exist_ok=True)
        atlas = Image.new("RGBA", (1280, 1280))
        frames = {}
        for index, image in sorted(pieces):
            image = image.resize((round(image.width * scale), round(image.height * scale)), Image.Resampling.LANCZOS)
            frame = Image.new("RGBA", (320, 320))
            frame.alpha_composite(image, ((320 - image.width) // 2, 302 - image.height))
            target = out / (NAMES[index] + ".png")
            frame.save(target, optimize=True)
            frames[index] = frame
            atlas.alpha_composite(frame, ((index % 4) * 320, (index // 4) * 320))
            record = next(r for r in records if r["index"] == index)
            record.update(path=target.relative_to(ROOT).as_posix(), sha256=sha(target), outputSize=[320, 320])
            alpha = np.array(frame.getchannel("A"))
            assert not alpha[:8].any() and not alpha[-8:].any() and not alpha[:, :8].any() and not alpha[:, -8:].any()
        native = RES / f"fish_{style}_atlas.png"
        atlas.save(native, optimize=True)
        animations = DESIGN / "animations" / style
        animations.mkdir(parents=True, exist_ok=True)
        for name, (indexes, durations) in SEQUENCES.items():
            selected = [frames[i] for i in indexes]
            selected[0].save(animations / f"{name}.webp", save_all=True, append_images=selected[1:],
                             duration=durations, loop=0, lossless=True)
        # A light/dark edge proof sheet makes white halos and clipped silhouettes visible.
        proof = Image.new("RGB", (1280, 640))
        for i, color in enumerate(["#f3f0e8", "#182330"]):
            background = Image.new("RGBA", (1280, 320), color)
            for col, frame_index in enumerate([0, 5, 11, 13]):
                background.alpha_composite(frames[frame_index], (col * 320, 0))
            proof.paste(background.convert("RGB"), (0, i * 320))
        proof.save(DESIGN / f"{style}-edge-proof.jpg", quality=94)
        manifest["styles"][style] = {
            "source": source.relative_to(ROOT).as_posix(), "sourceSha256": sha(source),
            "nativeAtlas": native.relative_to(ROOT).as_posix(), "nativeSha256": sha(native),
            "frames": sorted(records, key=lambda r: r["index"]),
            "separateFrames": 16, "animations": list(SEQUENCES),
        }
    path = DESIGN / "asset-manifest.json"
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", "utf-8")
    target = ROOT / "outputs/fish-companion-assets-0.14.0.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for folder in ["frames", "animations", "atlases"]:
            for file in (DESIGN / folder).rglob("*"):
                if file.is_file():
                    archive.write(file, file.relative_to(DESIGN))
        archive.write(path, path.name)
        for style in ["chibi", "elegant"]:
            archive.write(RES / f"fish_{style}_atlas.png", f"native/fish_{style}_atlas.png")
        archive.write(DESIGN / "generation-prompts.md", "generation-prompts.md")
        for file in DESIGN.glob("*-edge-proof.jpg"):
            archive.write(file, file.name)
    print(json.dumps({"styles": 2, "pngFrames": 32, "animatedWebp": 12, "package": str(target)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
