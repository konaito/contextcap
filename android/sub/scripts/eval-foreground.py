#!/usr/bin/env python3
"""ResumedActivities の数え方を、インスタンス ID つきの正解と比べる。

UsageStats の公開 API ではインスタンス ID が取れないので、sub はパッケージ名とクラス名ごとに数える。
この数え方を Kotlin（ResumedActivities.kt）と同じ規則で写し、端末のダンプにある instanceId で
組んだ正解と、イベント 1 件ごとに「RESUMED のまま残っているパッケージの集合」を比べる。

    adb shell dumpsys usagestats file 0 daily > /tmp/us-daily.txt   # 端末に残る約 10 日分
    python3 android/sub/scripts/eval-foreground.py /tmp/us-daily.txt

ダンプは利用履歴（PII）を含む。リポジトリに入れない。
"""
import collections
import re
import sys

PATTERN = re.compile(
    r"\s*time=(\d+) type=ACTIVITY_(RESUMED|PAUSED|STOPPED) package=(\S+) class=(\S+) instanceId=(\d+)"
)


def load(path):
    events, seen = [], set()
    for line in open(path, encoding="utf-8", errors="replace"):
        m = PATTERN.match(line)
        if not m:
            continue
        e = (int(m[1]), m[2], m[3], m[4], m[5])
        if e in seen:
            continue
        seen.add(e)
        events.append(e)
    events.sort(key=lambda e: e[0])
    return events


def truth(events):
    resumed, out = {}, []
    for _t, ty, pkg, _cls, iid in events:
        if ty == "RESUMED":
            resumed[iid] = pkg
        else:
            resumed.pop(iid, None)
        out.append(frozenset(resumed.values()))
    return out


def counted(events, window_ms):
    """ResumedActivities.apply と同じ規則"""
    resumed = collections.Counter()
    pending = collections.defaultdict(collections.deque)
    out = []
    for t, ty, pkg, cls, _iid in events:
        key = (pkg, cls)
        q = pending[key]
        while window_ms is not None and q and t - q[0] > window_ms:
            q.popleft()
        if ty == "RESUMED":
            resumed[key] += 1
        elif ty == "PAUSED":
            if resumed[key] > 0:
                resumed[key] -= 1
            q.append(t)
        elif q:
            q.popleft()
        elif resumed[key] > 0:
            resumed[key] -= 1
        out.append(frozenset(k[0] for k, v in resumed.items() if v > 0))
    return out


def main():
    events = load(sys.argv[1])
    print(f"events: {len(events)}")
    expected = truth(events)
    for window in (None, 3_000, 10_000, 30_000):
        got = counted(events, window)
        extra, missed = collections.Counter(), collections.Counter()
        for a, b in zip(expected, got):
            for p in b - a:
                extra[p] += 1
            for p in a - b:
                missed[p] += 1
        label = "no window" if window is None else f"{window} ms"
        print(
            f"{label:>10}: 正解に無いのに RESUMED {sum(extra.values())} {extra.most_common(3)}"
            f" / 見落とし {sum(missed.values())} {missed.most_common(3)}"
        )


if __name__ == "__main__":
    main()
