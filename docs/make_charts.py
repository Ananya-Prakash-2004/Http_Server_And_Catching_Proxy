#!/usr/bin/env python3
"""Draws the three benchmark charts from the CSV written by httpserver.benchmark.CacheBenchmark.

Usage:  python docs/make_charts.py cache_results.csv docs
Needs:  pip install matplotlib
If the CSV holds several runs (it is appended to), the LAST row of each setting is used.
"""
import csv
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HIT, REVAL, MISS, BASE, IDEAL = "#2a6fdb", "#f28e2b", "#aab0b8", "#d62728", "#444444"


def load(path):
    rows = {}
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            key = (r["sweep"], r["capacity"], r["zipf_s"], r["ttl_s"])
            rows[key] = r  # a later row replaces an earlier one with the same setting
    return list(rows.values())


def num(r, name, default=0.0):
    return float(r[name]) if name in r and r[name] != "" else default


def ideal_hit_pct(capacity, objects, s):
    """Hit ratio of a cache that permanently holds the `capacity` most popular objects."""
    weights = [1.0 / (k ** s) for k in range(1, objects + 1)]
    return 100.0 * sum(weights[:capacity]) / sum(weights)


def style(ax, title, xlabel, ylabel):
    ax.set_title(title, fontsize=11, loc="left", fontweight="bold")
    ax.set_xlabel(xlabel)
    ax.set_ylabel(ylabel)
    ax.grid(axis="y", alpha=0.3)
    ax.spines[["top", "right"]].set_visible(False)


def main(csv_path, out_dir):
    rows = load(csv_path)
    base = next(r for r in rows if r["sweep"] == "baseline")
    base_mean = num(base, "mean_ms")
    objects = int(num(base, "objects", 500))
    delay = int(num(base, "origin_delay_ms", 20))
    note = f"{objects} objects, {delay} ms origin delay, 16 clients"

    # ---------- 1. cache capacity ----------
    cap = sorted((r for r in rows if r["sweep"] == "capacity"), key=lambda r: int(r["capacity"]))
    xs = [int(r["capacity"]) for r in cap]
    fig, (a, b) = plt.subplots(1, 2, figsize=(11, 4.6))
    a.plot(range(len(xs)), [num(r, "hit_pct") for r in cap], "o-", color=HIT, lw=2, label="LRU cache (measured)")
    ideal = [num(r, "ideal_static_hit_pct", ideal_hit_pct(int(r["capacity"]), objects, 1.0)) for r in cap]
    a.plot(range(len(xs)), ideal, "s--", color=IDEAL, lw=1.4, label="ideal: holds the most popular objects")
    for i, r in enumerate(cap):
        a.annotate(f"{num(r, 'hit_pct'):.0f}%", (i, num(r, "hit_pct")), textcoords="offset points",
                   xytext=(0, -14), ha="center", fontsize=8, color=HIT)
    a.set_xticks(range(len(xs)), [str(x) for x in xs])
    a.set_ylim(0, 105)
    a.legend(loc="lower right", fontsize=8, frameon=False)
    style(a, "Hit ratio grows with cache size", f"cache capacity (entries, of {objects} objects)", "requests served from cache (%)")
    b.bar(range(len(xs)), [num(r, "mean_ms") for r in cap], color=HIT, width=0.6)
    b.axhline(base_mean, color=BASE, ls="--", lw=1.5, label=f"no cache ({base_mean:.1f} ms)")
    for i, r in enumerate(cap):
        b.annotate(f"{num(r, 'mean_ms'):.1f}", (i, num(r, "mean_ms")), textcoords="offset points",
                   xytext=(0, 3), ha="center", fontsize=8)
    b.set_xticks(range(len(xs)), [str(x) for x in xs])
    b.set_ylim(0, base_mean * 1.3)
    b.legend(loc="upper right", fontsize=8, frameon=False)
    style(b, "Mean latency falls with it", f"cache capacity (entries, of {objects} objects)", "mean latency (ms)")
    fig.text(0.5, 0.012, f"Sweep 1: cache capacity  (Zipf s = 1.0; {note})", fontsize=9, ha="center", color="#555")
    fig.tight_layout(rect=(0, 0.06, 1, 1))
    fig.savefig(f"{out_dir}/cache-capacity.png", dpi=150)
    plt.close(fig)

    # ---------- 2. popularity skew ----------
    sk = sorted((r for r in rows if r["sweep"] == "skew"), key=lambda r: float(r["zipf_s"]))
    labels = [f"{float(r['zipf_s']):.1f}" for r in sk]
    cap_used = int(sk[0]["capacity"])
    fig, (a, b) = plt.subplots(1, 2, figsize=(11, 4.6))
    a.plot(range(len(sk)), [num(r, "hit_pct") for r in sk], "o-", color=HIT, lw=2, label="LRU cache (measured)")
    a.plot(range(len(sk)), [ideal_hit_pct(cap_used, objects, float(r["zipf_s"])) for r in sk], "s--",
           color=IDEAL, lw=1.4, label="ideal: holds the most popular objects")
    for i, r in enumerate(sk):
        a.annotate(f"{num(r, 'hit_pct'):.0f}%", (i, num(r, "hit_pct")), textcoords="offset points",
                   xytext=(0, -14), ha="center", fontsize=8, color=HIT)
    a.set_xticks(range(len(sk)), labels)
    a.set_ylim(0, 105)
    a.legend(loc="upper left", fontsize=8, frameon=False)
    style(a, "More skewed traffic means more hits", "Zipf exponent s  (0 = uniform, higher = more skewed)", "requests served from cache (%)")
    b.bar(range(len(sk)), [num(r, "mean_ms") for r in sk], color=HIT, width=0.6)
    b.axhline(base_mean, color=BASE, ls="--", lw=1.5, label=f"no cache ({base_mean:.1f} ms)")
    for i, r in enumerate(sk):
        b.annotate(f"{num(r, 'mean_ms'):.1f}", (i, num(r, "mean_ms")), textcoords="offset points",
                   xytext=(0, 3), ha="center", fontsize=8)
    b.set_xticks(range(len(sk)), labels)
    b.set_ylim(0, base_mean * 1.3)
    b.legend(loc="upper right", fontsize=8, frameon=False)
    style(b, "...and lower latency", "Zipf exponent s", "mean latency (ms)")
    fig.text(0.5, 0.012, f"Sweep 2: popularity skew  (cache holds {cap_used} of {objects} objects; {note})", fontsize=9, ha="center", color="#555")
    fig.tight_layout(rect=(0, 0.06, 1, 1))
    fig.savefig(f"{out_dir}/cache-skew.png", dpi=150)
    plt.close(fig)

    # ---------- 3. freshness lifetime ----------
    tt = sorted((r for r in rows if r["sweep"] == "ttl"), key=lambda r: int(r["ttl_s"]))
    labels = [f"{int(r['ttl_s'])} s" for r in tt]
    fig, (a, b) = plt.subplots(1, 2, figsize=(11, 4.6))
    hits = [num(r, "hit_pct") for r in tt]
    revs = [num(r, "revalidated_pct") for r in tt]
    miss = [num(r, "miss_pct") for r in tt]
    idx = range(len(tt))
    a.bar(idx, hits, color=HIT, width=0.6, label="HIT (fresh, origin not contacted)")
    a.bar(idx, revs, bottom=hits, color=REVAL, width=0.6, label="REVALIDATED (304, body reused)")
    a.bar(idx, miss, bottom=[h + v for h, v in zip(hits, revs)], color=MISS, width=0.6, label="MISS (full fetch)")
    for i in idx:
        a.annotate(f"{hits[i]:.0f}%", (i, hits[i] / 2), ha="center", va="center", color="white", fontsize=9, fontweight="bold")
        if revs[i] >= 3:
            a.annotate(f"{revs[i]:.0f}%", (i, hits[i] + revs[i] / 2), ha="center", va="center", fontsize=8)
    a.set_xticks(list(idx), labels)
    a.set_ylim(0, 100)
    a.legend(loc="upper center", bbox_to_anchor=(0.5, -0.18), fontsize=8, frameon=False, ncol=1)
    style(a, "Short lifetimes turn hits into revalidations", "how long a response stays fresh (max-age)", "share of requests (%)")
    reqs = [num(r, "origin_requests") for r in tt]
    b.bar(idx, reqs, color=REVAL, width=0.6)
    for i in idx:
        b.annotate(f"{int(reqs[i])}", (i, reqs[i]), textcoords="offset points", xytext=(0, 3), ha="center", fontsize=9)
    b.set_xticks(list(idx), labels)
    meas = int(num(base, "requests", 10000))
    b.axhline(meas, color=BASE, ls="--", lw=1.5, label=f"no cache ({meas} requests)")
    b.set_ylim(0, meas * 1.3)
    b.legend(loc="upper right", fontsize=8, frameon=False)
    style(b, "...and send more requests to the origin", "how long a response stays fresh (max-age)", f"requests reaching the origin (per {meas})")
    fig.text(0.5, 0.012, f"Sweep 3: freshness lifetime  (cache holds all {objects} objects, Zipf s = 1.0; {note})", fontsize=9, ha="center", color="#555")
    fig.tight_layout(rect=(0, 0.06, 1, 1))
    fig.savefig(f"{out_dir}/cache-ttl.png", dpi=150)
    plt.close(fig)
    print("wrote cache-capacity.png, cache-skew.png, cache-ttl.png to", out_dir)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
