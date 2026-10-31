"""Adapt qqbot's edge-tts output to phone MP3 playback without FFmpeg or SILK."""
import asyncio
import json
import sys

import edge_tts


async def main():
    value = json.load(sys.stdin)
    communicate = edge_tts.Communicate(
        value["text"], value.get("voice", "zh-CN-XiaoyiNeural"),
        rate=f'{int(value.get("rate", 8)):+d}%',
        pitch=f'{int(value.get("pitch", 0)):+d}Hz',
    )
    async for part in communicate.stream():
        if part["type"] == "audio":
            sys.stdout.buffer.write(part["data"])
    sys.stdout.buffer.flush()


if __name__ == "__main__":
    asyncio.run(main())
