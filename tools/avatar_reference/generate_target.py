#!/usr/bin/env python3
"""Development-only target-field generator for the particle avatar.

Consumes the maps produced by analyze.py and emits avatar-target.json in the
SAME schema as the existing procedural tools/generate-avatar-geometry.mjs
output, so particle-scene.js's generateSharedTargetGeometry() needs no
changes to consume it. Positions (x/y) come directly from the reference
measurements; only Z (depth) is synthesized, and never alters the silhouette.

Usage:
    python generate_target.py [--analysis-dir DIR] [--output PATH] [--reference IMAGE]
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from skimage.morphology import skeletonize

FIELD_ASPECT = 1.6  # worldWidth = worldHeight * FIELD_ASPECT, see particle-scene.js
TOTAL_BUDGET = 18000
SEED = 0x7efd0a1e

rng_state = SEED


def random():
    global rng_state
    rng_state = (1664525 * rng_state + 1013904223) & 0xFFFFFFFF
    return rng_state / 0x100000000


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def lerp(a, b, t):
    return a + (b - a) * t


def rgb(r, g, b):
    return [int(clamp(round(r), 0, 255)), int(clamp(round(g), 0, 255)), int(clamp(round(b), 0, 255))]


def cyan(intensity):
    return rgb(8 + intensity * 80, 150 + intensity * 100, 218 + intensity * 37)


def amber(intensity):
    return rgb(255, 102 + intensity * 145, 8 + intensity * 145)


def face_amber(intensity):
    hot = clamp((intensity - 0.86) / 0.14, 0, 1)
    hot = hot * hot * (3 - 2 * hot)
    return rgb(255, 72 + intensity * 120 + hot * 53, 4 + intensity * 48 + hot * 98)


particles = []


def add(x, y, z, color, brightness, radius, region, band_id=-1, flow_t=-1.0, drift=0.7):
    seed = len(particles)
    particles.append({
        "targetX": round(x, 6), "targetY": round(y, 6), "targetZ": round(z, 6),
        "x": round(x, 6), "y": round(y, 6), "z": round(z, 6),
        "color": color, "baseColor": color,
        "brightness": round(clamp(brightness, 0.18, 1.0), 4),
        "baseAlpha": round(clamp(brightness, 0.18, 1.0), 4),
        "radius": round(radius, 4), "baseSize": round(radius, 4),
        "region": region, "bandId": band_id, "flowT": round(flow_t, 5), "path": -1,
        "phase": round((seed * 0.61803398875) % 1 * np.pi * 2, 5),
        "drift": round(drift + ((seed * 37) % 101) / 101 * 0.55, 4), "seed": seed,
    })


# ---------------------------------------------------------------------------
# Analysis-derived helpers
# ---------------------------------------------------------------------------

def load_analysis(analysis_dir: Path):
    def j(name):
        with open(analysis_dir / name) as f:
            return json.load(f)

    return {
        "summary": j("analysis_summary.json"),
        "head_profile": j("head_profile.json"),
        "body_profile": j("body_profile.json"),
        "shoulder_profile": j("shoulder_profile.json"),
        "face_core": j("face_core.json"),
        "cyan_density": cv2.imread(str(analysis_dir / "cyan_density.png"), cv2.IMREAD_GRAYSCALE),
        "orange_density": cv2.imread(str(analysis_dir / "orange_density.png"), cv2.IMREAD_GRAYSCALE),
        "hot_density": cv2.imread(str(analysis_dir / "hot_density.png"), cv2.IMREAD_GRAYSCALE),
        "brightness": cv2.imread(str(analysis_dir / "brightness_map.png"), cv2.IMREAD_GRAYSCALE),
        "side_trails_mask": cv2.imread(str(analysis_dir / "side_trails_mask.png"), cv2.IMREAD_GRAYSCALE),
    }


def make_boundary_fn(profile):
    samples = profile["smoothed"]["samples"]
    ts = np.array([s["y"] for s in samples])
    lefts = np.array([s["left"] for s in samples])
    rights = np.array([s["right"] for s in samples])
    center_px, max_w = profile["centerPx"], profile["maxWidthPx"]
    y_top, y_bottom = profile["yTopPx"], profile["yBottomPx"]

    def boundary(img_y):
        t = clamp((img_y - y_top) / max(1.0, (y_bottom - y_top)), 0, 1)
        l = float(np.interp(t, ts, lefts)) * max_w + center_px
        r = float(np.interp(t, ts, rights)) * max_w + center_px
        return l, r

    return boundary, y_top, y_bottom


def make_field_mapper(center_x_px, img_h):
    def to_field(img_x, img_y):
        fx = 0.5 + (img_x - center_x_px) / (img_h * FIELD_ASPECT)
        fy = img_y / img_h
        return fx, fy
    return to_field


def brightness_at(brightness_map, x, y):
    h, w = brightness_map.shape
    xi = int(clamp(x, 0, w - 1))
    yi = int(clamp(y, 0, h - 1))
    return brightness_map[yi, xi] / 255.0


def weighted_sample(density, n):
    """Rejection-free weighted pixel sampling from a density map."""
    ys, xs = np.nonzero(density > 4)
    if len(xs) == 0:
        return []
    weights = density[ys, xs].astype(np.float64)
    weights /= weights.sum()
    idx = np.random.choice(len(xs), size=min(n, len(xs) * 4), p=weights, replace=True)
    return [(float(xs[i]), float(ys[i]), float(density[ys[i], xs[i]]) / 255.0) for i in idx]


def trace_skeleton_paths(skel_mask, min_len=6):
    """Order a binary skeleton's pixels into polylines by walking connected
    8-neighbourhoods, preferring the neighbour that continues the current
    direction at branch points. Gives real, ordered flow-line curves instead
    of an unordered point cloud."""
    ys, xs = np.nonzero(skel_mask)
    pixels = set(zip(xs.tolist(), ys.tolist()))
    visited = set()
    nbrs8 = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]

    def neighbors(p):
        return [(p[0] + dx, p[1] + dy) for dx, dy in nbrs8 if (p[0] + dx, p[1] + dy) in pixels]

    deg = {p: len(neighbors(p)) for p in pixels}
    endpoints = [p for p, d in deg.items() if d == 1]
    order = endpoints + [p for p in pixels if p not in endpoints]

    paths = []
    for start in order:
        if start in visited:
            continue
        path = [start]
        visited.add(start)
        current = start
        while True:
            cand = [n for n in neighbors(current) if n not in visited]
            if not cand:
                break
            if len(cand) > 1 and len(path) >= 2:
                prev = path[-2]
                dirv = (current[0] - prev[0], current[1] - prev[1])
                cand.sort(key=lambda n: -((n[0] - current[0]) * dirv[0] + (n[1] - current[1]) * dirv[1]))
            nxt = cand[0]
            path.append(nxt)
            visited.add(nxt)
            current = nxt
        if len(path) >= min_len:
            paths.append(path)
    return paths


def extract_flow_paths(density, y_min, y_max, threshold=40):
    masked = mask_y_range(density, y_min, y_max)
    binary = masked > threshold
    skeleton = skeletonize(binary)
    return trace_skeleton_paths(skeleton)


def resample_path(path, n):
    pts = np.array(path, dtype=np.float64)
    if len(pts) < 2:
        return [(pts[0][0], pts[0][1], 0.0)] * n
    seg = np.sqrt(((pts[1:] - pts[:-1]) ** 2).sum(axis=1))
    cum = np.concatenate([[0.0], np.cumsum(seg)])
    total = cum[-1]
    if total == 0:
        return [(pts[0][0], pts[0][1], 0.0)] * n
    targets = np.linspace(0, total, n)
    xs = np.interp(targets, cum, pts[:, 0])
    ys = np.interp(targets, cum, pts[:, 1])
    ts = targets / total
    return list(zip(xs.tolist(), ys.tolist(), ts.tolist()))


def distribute_along_paths(paths, count):
    """Split a particle budget across traced polylines proportional to their
    pixel length, then resample each to an even arc-length spacing."""
    total_len = sum(len(p) for p in paths) or 1
    out = []
    for path in paths:
        n_i = max(2, round(count * len(path) / total_len))
        out.extend(resample_path(path, n_i))
    return out


def mask_y_range(density, y_min, y_max):
    masked = density.copy()
    y_min_i = max(0, int(y_min))
    y_max_i = min(density.shape[0], int(y_max))
    masked[:y_min_i, :] = 0
    masked[y_max_i:, :] = 0
    return masked


def detect_bright_centroid(density, y_min, y_max):
    region = density[int(y_min):int(y_max), :]
    ys, xs = np.nonzero(region > 200)
    if len(xs) == 0:
        return None
    weights = region[ys, xs].astype(np.float64)
    cx = float((xs * weights).sum() / weights.sum())
    cy = float((ys * weights).sum() / weights.sum()) + y_min
    return cx, cy


# ---------------------------------------------------------------------------
# Region generators
# ---------------------------------------------------------------------------

def gen_head_rim(head_boundary, head_top, head_bottom, to_field, max_head_width_px):
    layers = [
        {"region": "headRimHalo", "offset_pct": 1.8, "brightness": 0.32, "radius": 2.60, "z": -0.010, "color": lambda: rgb(0, 168, 255)},
        {"region": "headRimMain", "offset_pct": 0.0, "brightness": 0.92, "radius": 0.72, "z": 0.020, "color": lambda: rgb(0, 220, 255)},
        {"region": "headRimInner", "offset_pct": -1.2, "brightness": 1.00, "radius": 0.34, "z": 0.030, "color": lambda: rgb(205, 252, 255)},
    ]
    n_points = 170
    for layer_idx, layer in enumerate(layers):
        offset_px = max_head_width_px * layer["offset_pct"] / 100.0
        for side, sign in ((0, -1), (1, 1)):
            for i in range(n_points):
                if side == 1 and (i == 0 or i == n_points - 1):
                    continue
                v = i / (n_points - 1)
                img_y = head_top + v * (head_bottom - head_top)
                left_px, right_px = head_boundary(img_y)
                edge_px = left_px if sign < 0 else right_px
                center_px = (left_px + right_px) / 2.0
                cluster = 0.5 + 0.5 * np.sin(v * np.pi * 10.4 + layer_idx * 1.7 + sign * 0.4)
                organic = np.sin(v * np.pi * 5.3 + layer_idx) * max_head_width_px * 0.006
                emphasis = 0.72 + 0.28 * cluster
                edge_px_off = edge_px + sign * offset_px + organic
                fx, fy = to_field(edge_px_off, img_y + np.sin(v * np.pi * 17 + layer_idx) * 0.4)
                add(fx, fy, layer["z"] + organic * 0.0004,
                    layer["color"](), layer["brightness"] * emphasis,
                    layer["radius"] * (0.78 + cluster * 0.32),
                    layer["region"], band_id=700 + layer_idx * 2 + side, flow_t=v, drift=0.24)

        # crown cap
        cap_points = 58 if layer_idx == 2 else 112
        for i in range(cap_points):
            x_norm = -1 + i / (cap_points - 1) * 2
            left_px, right_px = head_boundary(head_top)
            center_px = (left_px + right_px) / 2.0
            half_w = (right_px - left_px) / 2.0
            cluster = 0.72 + 0.28 * np.sin(i * 0.41 + layer_idx * 1.7)
            px = center_px + x_norm * half_w * (1 + layer["offset_pct"] / 100.0)
            fx, fy = to_field(px, head_top)
            add(fx, fy, layer["z"], layer["color"](), layer["brightness"] * cluster,
                layer["radius"] * (0.78 + cluster * 0.28), layer["region"],
                band_id=710 + layer_idx, flow_t=i / (cap_points - 1), drift=0.24)


def gen_head_bands(head_boundary, head_top, head_bottom, to_field, max_head_width_px, n_bands=62):
    for band in range(n_bands):
        v = 0.010 + band / (n_bands - 1) * 0.980
        img_y = head_top + v * (head_bottom - head_top)
        left_px, right_px = head_boundary(img_y)
        width_frac = (right_px - left_px) / max_head_width_px
        count = max(48, round(52 + width_frac * 34))
        for point in range(count):
            if (point + band * 7) % 173 == 0:
                continue
            t = point / (count - 1)
            x_norm = -1 + t * 2
            edge = abs(x_norm) ** 2.4
            px = left_px + t * (right_px - left_px)
            y_wave = np.cos(x_norm * np.pi * 0.72) * (0.7 + (band % 7) * 0.04) + \
                np.sin(band * 1.31 + point * 0.57) * 0.25
            fx, fy = to_field(px, img_y + y_wave)
            add(fx, fy, 0.010 + np.sqrt(max(0, 1 - x_norm * x_norm)) * 0.025,
                cyan(0.70 + edge * 0.27), 0.64 + edge * 0.31, 0.54 + edge * 0.11,
                "headBand", band_id=600 + band, flow_t=t, drift=0.30)


def gen_head_interior(head_boundary, head_top, head_bottom, to_field, brightness_map):
    fields = [
        {"region": "headBack", "count": 500, "z": -0.030, "min_scale": 0.30},
        {"region": "headMid", "count": 650, "z": -0.006, "min_scale": 0.30},
        {"region": "headFront", "count": 500, "z": 0.020, "min_scale": 0.30},
    ]
    for field in fields:
        for _ in range(field["count"]):
            v = 0.03 + random() * 0.94
            img_y = head_top + v * (head_bottom - head_top)
            left_px, right_px = head_boundary(img_y)
            x_norm = (random() * 2 - 1) * (field["min_scale"] + np.sqrt(random()) * 0.66)
            px = (left_px + right_px) / 2.0 + x_norm * (right_px - left_px) / 2.0
            front_lift = (1 - abs(x_norm)) * 0.010
            intensity = 0.5 + brightness_at(brightness_map, px, img_y) * 0.5
            fx, fy = to_field(px, img_y)
            add(fx, fy, field["z"] + front_lift + (random() - 0.5) * 0.006,
                cyan(intensity), 0.30 + intensity * 0.35, 0.44 + random() * 0.14 + (0.04 if field["region"] == "headFront" else 0),
                field["region"], drift=0.25)


def gen_face_core(orange_density, face_core, to_field, head_top, head_bottom, count=1180):
    masked = orange_density.copy()
    masked[: int(head_top), :] = 0
    masked[int(head_bottom):, :] = 0
    samples = weighted_sample(masked, count)
    for px, py, intensity in samples[:count]:
        add(*to_field(px, py), 0.008 + intensity * 0.020 + (random() - 0.5) * 0.008,
            face_amber(intensity), 0.34 + intensity * 0.60,
            0.62 + (1 - intensity) * 0.22 + intensity * 0.30, "faceCore", drift=0.33)


def gen_shoulder_band(cyan_density, body_boundary, neck_y, peak_y, to_field, n_bands=28, points_per_band=33):
    """Traces the reference's own cyan flow-line skeleton (cyan_density,
    masked to the shoulder Y-range, thinned to 1px curves) instead of a
    formulaic pull-in/wobble band, so the real curved ridge pattern — not an
    approximation of it — carries over. flow_t follows position along the
    real traced curve."""
    count = n_bands * points_per_band * 2
    paths = extract_flow_paths(cyan_density, neck_y, peak_y)
    for px, py, t in distribute_along_paths(paths, count):
        left_px, right_px = body_boundary(py)
        center_px = (left_px + right_px) / 2.0
        half = max(1.0, (right_px - left_px) / 2.0)
        side = -1 if px < center_px else 1
        layer = 1.0 - clamp(abs(px - center_px) / half, 0, 1)  # 0 at edge, 1 near centerline
        y_t = clamp((py - neck_y) / max(1.0, (peak_y - neck_y)), 0, 1)
        edge_fade = 1 - (0.42 + layer * 0.42) * max(0, (y_t - 0.86) / 0.14)
        fx, fy = to_field(px, py)
        add(fx, fy, lerp(0.012, -0.014, layer),
            cyan(0.50 + (1 - layer) * 0.22), (0.48 + (1 - layer) * 0.25) * max(0.15, edge_fade),
            0.66 + (1 - layer) * 0.20, "shoulderBand", band_id=side * 100, flow_t=t, drift=0.42)


def gen_shoulder_surface(cyan_density, neck_y, peak_y, to_field, count=180):
    masked = mask_y_range(cyan_density, neck_y, peak_y)
    samples = weighted_sample(masked, count)
    for px, py, intensity in samples[:count]:
        t = clamp((py - neck_y) / max(1.0, (peak_y - neck_y)), 0, 1)
        fx, fy = to_field(px, py)
        add(fx, fy, (intensity - 0.5) * 0.035, cyan(0.38 + intensity * 0.40), 0.34 + intensity * 0.36,
            0.50 + intensity * 0.28, "shoulderSurface", flow_t=t, drift=0.38)


def taper_neck_width(left_px, right_px, t, ease_end=0.30, narrow_factor=0.62):
    """The reference's cyan tendrils flare to near-full body width immediately
    below the jaw; reproduced literally this reads as 'neck already as wide as
    the shoulders'. User-requested stylization: pull the boundary inward by
    narrow_factor right at the neck (t=0) and ease back to the true traced
    boundary by t=ease_end, where the real shoulder flare takes over."""
    if t >= ease_end:
        return left_px, right_px
    center = (left_px + right_px) / 2.0
    blend = t / ease_end
    factor = narrow_factor + (1 - narrow_factor) * blend
    return center + (left_px - center) * factor, center + (right_px - center) * factor


def gen_shoulder_rim(body_boundary, neck_y, peak_y, to_field, points=70):
    for side, sign in ((0, -1), (1, 1)):
        for layer in range(2):
            for point in range(points):
                t = point / (points - 1)
                img_y = lerp(neck_y, peak_y, t)
                left_px, right_px = body_boundary(img_y)
                left_px, right_px = taper_neck_width(left_px, right_px, t)
                edge_px = left_px if sign < 0 else right_px
                fade = 1 - 0.68 * max(0, (t - 0.84) / 0.16)
                fx, fy = to_field(edge_px, img_y + layer * 1.5)
                add(fx, fy, 0.014 - layer * 0.004, cyan(0.88), (0.76 - layer * 0.16) * fade,
                    0.78 if layer else 0.60, "shoulderRim", band_id=sign * (200 + layer), flow_t=t, drift=0.34)


def gen_chest_band(cyan_density, orange_density, peak_y, fade_end_y, to_field,
                    count_bands=13, points=25, extra=100, strands=15):
    """Cyan ridge lines and the amber chest filament both sampled straight from
    their reference density maps (masked to the chest Y-range), replacing the
    previous hand-tuned pull-in/spread curves."""
    cyan_count = count_bands * points * 2 + extra
    paths = extract_flow_paths(cyan_density, peak_y, fade_end_y)
    for px, py, path_t in distribute_along_paths(paths, cyan_count):
        y_t = clamp((py - peak_y) / max(1.0, (fade_end_y - peak_y)), 0, 1)
        fx, fy = to_field(px, py)
        add(fx, fy, lerp(0.006, -0.020, y_t),
            cyan(0.52 + (1 - y_t) * 0.17), (0.48 + (1 - y_t) * 0.19) * (1 - 0.35 * y_t),
            0.66 + (1 - y_t) * 0.18, "chestBand", flow_t=path_t, drift=0.38)

    amber_count = strands * 24
    masked_amber = mask_y_range(orange_density, peak_y, fade_end_y)
    for px, py, intensity in weighted_sample(masked_amber, amber_count)[:amber_count]:
        t = clamp((py - peak_y) / max(1.0, (fade_end_y - peak_y)), 0, 1)
        fade = 1 - 0.72 * max(0, (t - 0.68) / 0.32)
        fx, fy = to_field(px, py)
        add(fx, fy, 0.012 + intensity * 0.010, amber(0.50 + intensity * 0.40),
            (0.50 + intensity * 0.40) * fade, 0.58 + intensity * 0.22, "chestBand", flow_t=t, drift=0.34)


def gen_chest_core(center_xy, to_field, count=80):
    if center_xy is None:
        return
    cx, cy = center_xy
    for _ in range(count):
        angle = random() * np.pi * 2
        r = np.sqrt(random())
        px = cx + np.cos(angle) * r * 9
        py = cy + np.sin(angle) * r * 16
        fx, fy = to_field(px, py)
        add(fx, fy, 0.022 + (1 - r) * 0.012, rgb(160 + (1 - r) * 95, 245, 255),
            0.72 + (1 - r) * 0.28, 0.72 + (1 - r) * 0.62, "chestCore", drift=0.28)


def gen_neck_energy(head_boundary, head_bottom, chest_core_xy, to_field, strands=18):
    if chest_core_xy is None:
        return
    cx_end, cy_end = chest_core_xy
    left_px, right_px = head_boundary(head_bottom)
    cx_start = (left_px + right_px) / 2.0
    for strand in range(strands):
        n_x = (strand / (strands - 1)) * 2 - 1
        warm = abs(n_x) < 0.48
        for point in range(25):
            t = point / 24
            waist = np.sin(t * np.pi)
            width_px = (right_px - left_px) * 0.5
            px = lerp(cx_start, cx_end, t) + n_x * lerp(width_px * 0.16, width_px * 0.05, t) + \
                n_x * waist * width_px * 0.12 + np.sin(strand * 1.7 + t * np.pi * 2) * 1.5 * waist
            py = lerp(head_bottom - 3, cy_end, t)
            fx, fy = to_field(px, py)
            add(fx, fy, 0.018 + np.cos(strand * 1.1) * 0.007,
                amber(0.58 + (1 - abs(n_x)) * 0.20) if warm else cyan(0.78),
                (0.72 + (1 - abs(n_x)) * 0.17) if warm else (0.62 + waist * 0.12),
                0.80 if warm else 0.70, "neckEnergy", band_id=500 + strand, flow_t=t, drift=0.36)


def gen_side_trails(side_trails_mask, brightness_map, to_field, count=350):
    ys, xs = np.nonzero(side_trails_mask > 0)
    if len(xs) == 0:
        return
    n = min(count, len(xs))
    idx = np.random.choice(len(xs), size=n, replace=False)
    for i in idx:
        px, py = float(xs[i]), float(ys[i])
        intensity = brightness_at(brightness_map, px, py)
        fx, fy = to_field(px, py)
        add(fx, fy, -0.035 - random() * 0.010, cyan(0.42 + intensity * 0.30),
            0.30 + intensity * 0.30, 0.48 + random() * 0.20, "sideTrail", drift=0.85)


def gen_ambient(head_top, head_max_width_px, to_field, img_h, count):
    for _ in range(count):
        zone = random()
        if zone < 0.58:
            angle = random() * np.pi
            distance = 18 + (random() ** 1.8) * 120
            px = 0.5 * img_h * FIELD_ASPECT + np.cos(angle) * (head_max_width_px / 2 + distance) * (0.65 + random() * 0.55)
            # place in field space directly around head top
            fx = 0.5 + (np.cos(angle) * (head_max_width_px / 2 + distance) * (0.65 + random() * 0.55)) / (img_h * FIELD_ASPECT)
            fy = (head_top - np.sin(angle) * (35 + distance * 0.8) + (random() - 0.5) * 25) / img_h
        else:
            side = -1 if random() < 0.5 else 1
            fx = 0.5 + side * (0.18 + random() * 0.23)
            fy = 0.46 + random() * 0.22
        add(fx, fy, -0.045 + (random() - 0.5) * 0.03, cyan(0.42 + random() * 0.15),
            0.22 + random() * 0.28, 0.42 + random() * 0.38, "ambient", drift=1.15)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Generate avatar-target.json from analyze.py output.")
    script_dir = Path(__file__).resolve().parent
    parser.add_argument("--analysis-dir", default=str(script_dir / "output"))
    parser.add_argument("--output", default=str(script_dir.parent.parent / "app/src/main/assets/avatar-target.json"))
    args = parser.parse_args()

    analysis_dir = Path(args.analysis_dir)
    data = load_analysis(analysis_dir)
    summary = data["summary"]
    img_h = summary["imageHeight"]

    head_boundary, head_top, head_bottom = make_boundary_fn(data["head_profile"])
    body_boundary, body_top, body_bottom = make_boundary_fn(data["body_profile"])
    center_x_px = data["body_profile"]["centerPx"]
    to_field = make_field_mapper(center_x_px, img_h)
    max_head_width_px = data["head_profile"]["maxWidthPx"]

    shoulders = data["shoulder_profile"]
    neck_y_px = shoulders["neckBaseY"] * img_h
    peak_y_px = shoulders["shoulderPeakY"] * img_h
    fade_end_y_px = shoulders["fadeEndY"] * img_h

    chest_core_xy = detect_bright_centroid(data["hot_density"], peak_y_px, fade_end_y_px)
    if chest_core_xy is None:
        chest_core_xy = detect_bright_centroid(data["cyan_density"], peak_y_px, fade_end_y_px)

    print(f"Head: y={head_top:.0f}..{head_bottom:.0f}px, width={max_head_width_px:.0f}px")
    print(f"Shoulders: neck={neck_y_px:.0f}px peak={peak_y_px:.0f}px fadeEnd={fade_end_y_px:.0f}px")
    print(f"Chest core detected at: {chest_core_xy}")

    gen_head_interior(head_boundary, head_top, head_bottom, to_field, data["brightness"])
    gen_head_rim(head_boundary, head_top, head_bottom, to_field, max_head_width_px)
    gen_head_bands(head_boundary, head_top, head_bottom, to_field, max_head_width_px)
    gen_face_core(data["orange_density"], data["face_core"], to_field, head_top, head_bottom)
    gen_neck_energy(head_boundary, head_bottom, chest_core_xy, to_field)
    gen_shoulder_band(data["cyan_density"], body_boundary, neck_y_px, peak_y_px, to_field,
                       n_bands=40, points_per_band=50)
    gen_shoulder_surface(data["cyan_density"], neck_y_px, peak_y_px, to_field, count=600)
    gen_shoulder_rim(body_boundary, neck_y_px, peak_y_px, to_field)
    gen_chest_band(data["cyan_density"], data["orange_density"], peak_y_px, fade_end_y_px, to_field,
                    count_bands=24, points=40, extra=200)
    gen_chest_core(chest_core_xy, to_field)
    gen_side_trails(data["side_trails_mask"], data["brightness"], to_field)

    used = len(particles)
    ambient_count = max(0, TOTAL_BUDGET - used)
    gen_ambient(head_top, max_head_width_px, to_field, img_h, ambient_count)

    # pad/trim to exact budget
    while len(particles) < TOTAL_BUDGET:
        src = particles[int(random() * len(particles))]
        clone = dict(src)
        clone["x"] += (random() - 0.5) * 0.003
        clone["y"] += (random() - 0.5) * 0.003
        clone["targetX"], clone["targetY"] = clone["x"], clone["y"]
        particles.append(clone)
    del particles[TOTAL_BUDGET:]

    counts = {}
    for p in particles:
        counts[p["region"]] = counts.get(p["region"], 0) + 1

    head_height_px = head_bottom - head_top
    shoulder_width_px = shoulders["shoulderPeakWidthPx"]
    metrics = {
        "coordinateSystem": "normalized-16:10-front-view",
        "source": "reference-analysis",
        "headWidth": round(max_head_width_px / (img_h * FIELD_ASPECT), 4),
        "headHeight": round(head_height_px / img_h, 4),
        "headHeightToWidth": round(head_height_px / max_head_width_px, 3),
        "shoulderWidth": round(shoulder_width_px / (img_h * FIELD_ASPECT), 4),
        "shoulderToHead": round(shoulder_width_px / max_head_width_px, 3),
        "neckToHeadWidth": round((neck_y_px - head_bottom) / max_head_width_px, 3) if neck_y_px > head_bottom else 0,
        "faceCoreToHeadWidth": round(data["face_core"]["width"] * img_h / max_head_width_px, 3) if data["face_core"] else None,
        "headBands": 62,
        "shoulderBandsPerSide": 28,
        "sideTrailsPerSide": counts.get("sideTrail", 0) // 2,
    }

    output = {
        "version": 3, "seed": SEED, "particleCount": len(particles),
        "metrics": metrics, "counts": counts, "particles": particles,
    }
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(output, f)

    size_kb = out_path.stat().st_size / 1024
    print(f"\n{len(particles)} reference-derived particles -> {out_path} ({size_kb:.0f} KB)")
    print(json.dumps({"metrics": metrics, "counts": counts}, indent=2))


if __name__ == "__main__":
    main()
