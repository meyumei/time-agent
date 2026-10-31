"""Project the selected upstream CLI's real feed into linkable source items."""
import re

def parse_feed(stdout: str) -> list[dict]:
    result = []
    seen = set()
    for line in stdout.splitlines():
        match = re.search(r'^\s*\d+\.\s+(BV[0-9A-Za-z]+).*?播放\s+\d+\s+(.+?)(?:\s+@|$)', line)
        if not match or match[1] in seen:
            continue
        seen.add(match[1])
        result.append({'title':match[2].strip()[:120], 'bvid':match[1], 'url':'https://www.bilibili.com/video/'+match[1]})
    return result
