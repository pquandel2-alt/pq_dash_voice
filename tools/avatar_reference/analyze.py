#!/usr/bin/env python3
"""Development-only reference analysis tool for the particle avatar.

Derives geometry (head profile, body profile, shoulder landmarks, rim zones,
color density maps, face-core distribution, brightness map) objectively from
a reference image via OpenCV/NumPy/SciPy — instead of hand-guessed constants
in particle-scene.js.

Usage:
    python analyze.py <reference_image> [--output-dir DIR] [--head-bbox x0,y0,x1,y1]

Output artifacts (all written to --output-dir, default ./output):
    avatar_silhouette.png    primary body silhouette mask (white=figure)
    side_trails_mask.png     secondary (disconnected) energy-trail components
    head_mask.png            head-only silhouette mask
    head_profile.json        101 normalized width samples across head height
    body_profile.json        101 normalized width samples across full bust height
    shoulder_profile.json    neck/shoulder/fade landmark rows
    head_rim_map.png         RIM_INNER / RIM_MAIN / RIM_HALO zone visualization
    avatar_distance_field.npy  signed distance-to-edge field (% of head width)
    cyan_density.png, orange_density.png, hot_density.png   HSV color maps
    face_core.json, face_core_density.png                   orange-core distribution
    brightness_map.png       normalized 0..1 brightness (stored as 8-bit)
    reference_analysis.png   8-panel debug overview
    analysis_summary.json    key measurements for generate_target.py / reporting

Runtime note: NONE of these artifacts are shipped to the Android app. Only
generate_target.py's output (avatar-target.json) is consumed at runtime.
"""
import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np
from scipy.signal import savgol_filter

# ---------------------------------------------------------------------------
# Silhouette extraction
# ---------------------------------------------------------------------------

def extract_silhouette(img_bgr):
    """Brightness/saturation driven mask, denoised, connected-component split
    into a primary (largest, central) body silhouette and secondary trail
    fragments."""
    hsv = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2HSV)
    h, s, v = cv2.split(hsv)
    blurred_v = cv2.GaussianBlur(v, (3, 3), 0)
    _, mask = cv2.threshold(blurred_v, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)

    close_kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (11, 11))
    open_kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    closed = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, close_kernel)
    denoised = cv2.morphologyEx(closed, cv2.MORPH_OPEN, open_kernel)

    n, labels, stats, centroids = cv2.connectedComponentsWithStats(denoised, connectivity=8)
    if n <= 1:
        raise RuntimeError("No foreground components found — check the reference image / threshold.")
    areas = stats[1:, cv2.CC_STAT_AREA]
    primary_label = 1 + int(np.argmax(areas))

    primary_mask = np.where(labels == primary_label, 255, 0).astype(np.uint8)
    secondary_mask = np.where((labels != primary_label) & (labels != 0), 255, 0).astype(np.uint8)

    trails = []
    for label in range(1, n):
        if label == primary_label:
            continue
        area = int(stats[label, cv2.CC_STAT_AREA])
        if area < 15:
            continue
        x, y, w, h_box = stats[label, cv2.CC_STAT_LEFT:cv2.CC_STAT_LEFT + 4]
        cx, cy = centroids[label]
        trails.append({
            "area": area,
            "bbox": [int(x), int(y), int(w), int(h_box)],
            "centroid": [float(cx), float(cy)],
        })

    return {
        "labels": labels,
        "primary_label": primary_label,
        "primary_mask": primary_mask,
        "secondary_mask": secondary_mask,
        "primary_bbox": tuple(int(v) for v in stats[primary_label, :4]),
        "trails": trails,
        "hsv": hsv,
    }


def row_profile(labels, primary_label, y0, y1, img_w):
    """Per-row (inclusive y0..y1) left/right/width of the primary label."""
    n_rows = y1 - y0 + 1
    left = np.full(n_rows, -1, dtype=np.float64)
    right = np.full(n_rows, -1, dtype=np.float64)
    row_mask = labels[y0:y1 + 1] == primary_label
    for i in range(n_rows):
        xs = np.where(row_mask[i])[0]
        if len(xs):
            left[i] = xs.min()
            right[i] = xs.max()
    width = np.where(left >= 0, right - left, 0)
    return left, right, width


def find_neck_row(labels, primary_label, bbox):
    """Locate the neck as the width-profile local minimum between 10% and
    60% of the primary component's height (crown -> jaw -> neck -> shoulder
    flare is a widen-narrow-widen pattern; the neck is the narrow point)."""
    x0, y0, w0, h0 = bbox
    _, _, width = row_profile(labels, primary_label, y0, y0 + h0 - 1, x0 + w0)
    k = 7
    smoothed = np.convolve(width, np.ones(k) / k, mode="same")
    lo = int(h0 * 0.10)
    hi = int(h0 * 0.60)
    neck_rel = lo + int(np.argmin(smoothed[lo:hi]))
    return y0 + neck_rel, width


def sample_profile(labels, primary_label, y_top, y_bottom, n_samples=101):
    """Sample left/right/width at n_samples evenly spaced rows in [y_top,
    y_bottom], normalize by max width, center on the region's pixel centroid,
    and return both raw and Savitzky-Golay smoothed curves."""
    ys = np.linspace(y_top, y_bottom, n_samples)
    left_px = np.full(n_samples, np.nan)
    right_px = np.full(n_samples, np.nan)
    for i, yf in enumerate(ys):
        y = int(round(yf))
        xs = np.where(labels[y] == primary_label)[0]
        if len(xs) == 0:
            for dy in range(1, 5):
                for cand in (y - dy, y + dy):
                    if 0 <= cand < labels.shape[0]:
                        xs = np.where(labels[cand] == primary_label)[0]
                        if len(xs):
                            break
                if len(xs):
                    break
        if len(xs):
            left_px[i] = xs.min()
            right_px[i] = xs.max()

    valid = ~np.isnan(left_px)
    idx = np.arange(n_samples)
    left_px = np.interp(idx, idx[valid], left_px[valid])
    right_px = np.interp(idx, idx[valid], right_px[valid])
    width_px = right_px - left_px

    max_width_px = max(float(np.max(width_px)), 1.0)
    center_px = float(np.mean((left_px + right_px) / 2.0))

    def to_samples(l, r):
        out = []
        for i in range(n_samples):
            ln = (l[i] - center_px) / max_width_px
            rn = (r[i] - center_px) / max_width_px
            out.append({
                "y": round(i / (n_samples - 1), 4),
                "left": round(float(ln), 4),
                "right": round(float(rn), 4),
                "width": round(float(rn - ln), 4),
                "centerX": round(float((ln + rn) / 2.0), 4),
            })
        return out

    raw_samples = to_samples(left_px, right_px)

    window = min(15, n_samples if n_samples % 2 == 1 else n_samples - 1)
    if window >= 5:
        left_sm = savgol_filter(left_px, window_length=window, polyorder=3)
        right_sm = savgol_filter(right_px, window_length=window, polyorder=3)
    else:
        left_sm, right_sm = left_px, right_px
    smoothed_samples = to_samples(left_sm, right_sm)

    return {
        "maxWidthPx": max_width_px,
        "centerPx": center_px,
        "yTopPx": int(y_top),
        "yBottomPx": int(y_bottom),
        "raw": {"samples": raw_samples},
        "smoothed": {"samples": smoothed_samples},
    }


# ---------------------------------------------------------------------------
# Rim / distance field
# ---------------------------------------------------------------------------

def compute_distance_field(primary_mask, head_max_width_px):
    dist_inside = cv2.distanceTransform(primary_mask, cv2.DIST_L2, 5)
    dist_outside = cv2.distanceTransform(255 - primary_mask, cv2.DIST_L2, 5)
    signed_px = np.where(primary_mask > 0, dist_inside, -dist_outside)
    scale = max(head_max_width_px, 1.0)
    return (signed_px / scale * 100.0).astype(np.float32)


def classify_rim_zones(distance_pct):
    """Returns an int8 zone map: 0=background/far-halo, 1=RIM_HALO_OUTER,
    2=RIM_INNER, 3=RIM_MAIN, 4=RIM_HALO(inner)/FLOW, 5=HEAD_SURFACE."""
    zones = np.zeros(distance_pct.shape, dtype=np.uint8)
    zones[(distance_pct < 0) & (distance_pct >= -8)] = 1
    zones[(distance_pct >= 0) & (distance_pct < 2)] = 2
    zones[(distance_pct >= 2) & (distance_pct < 5)] = 3
    zones[(distance_pct >= 5) & (distance_pct < 10)] = 4
    zones[distance_pct >= 10] = 5
    return zones


def rim_zone_colors():
    return {
        0: (0, 0, 0),
        1: (120, 40, 0),
        2: (255, 255, 255),
        3: (255, 220, 0),
        4: (0, 160, 255),
        5: (60, 30, 10),
    }


# ---------------------------------------------------------------------------
# Color segmentation
# ---------------------------------------------------------------------------

def color_density_maps(hsv):
    h, s, v = cv2.split(hsv)
    cyan = ((h >= 80) & (h <= 105) & (s > 60) & (v > 40))
    # Saturation drops toward the bright peak of the warm core (JPEG-compressed
    # near-white highlight) while hue stays stable (~15-32) — a strict s>80 cut
    # left a black hole at the exact core center. s>30 keeps the true orange
    # ring while including that washed-out peak; hot/cyan stay disjoint from it.
    orange = ((h >= 5) & (h <= 35) & (s > 30) & (v > 60))
    hot = ((s < 50) & (v > 200))
    cyan_density = np.where(cyan, v, 0).astype(np.uint8)
    orange_density = np.where(orange, v, 0).astype(np.uint8)
    hot_density = np.where(hot, v, 0).astype(np.uint8)
    return cyan_density, orange_density, hot_density


def face_core_from_density(orange_density, img_w, img_h):
    ys, xs = np.nonzero(orange_density)
    if len(xs) == 0:
        return None
    weights = orange_density[ys, xs].astype(np.float64)
    total = weights.sum()
    center_x = float((xs * weights).sum() / total)
    center_y = float((ys * weights).sum() / total)
    var_x = float(((xs - center_x) ** 2 * weights).sum() / total)
    var_y = float(((ys - center_y) ** 2 * weights).sum() / total)
    std_x, std_y = np.sqrt(var_x), np.sqrt(var_y)

    radii = np.sqrt((xs - center_x) ** 2 + (ys - center_y) ** 2)
    max_r = max(float(radii.max()), 1.0)
    bins = 20
    falloff = []
    for i in range(bins):
        r0, r1 = max_r * i / bins, max_r * (i + 1) / bins
        sel = (radii >= r0) & (radii < r1)
        density = float(weights[sel].mean()) / 255.0 if sel.any() else 0.0
        falloff.append(round(density, 4))
    peak = max(falloff) or 1.0
    falloff = [round(f / peak, 4) for f in falloff]

    return {
        "centerX": round(center_x / img_w, 4),
        "centerY": round(center_y / img_h, 4),
        "width": round(std_x * 2.0 / img_w, 4),
        "height": round(std_y * 2.0 / img_h, 4),
        "falloff": falloff,
    }


# ---------------------------------------------------------------------------
# Shoulder landmarks
# ---------------------------------------------------------------------------

def shoulder_landmarks(labels, primary_label, neck_y, bbox_bottom, img_w, img_h):
    y_top, y_bottom = neck_y, bbox_bottom
    _, _, width = row_profile(labels, primary_label, y_top, y_bottom, img_w)
    k = 7
    smoothed = np.convolve(width, np.ones(k) / k, mode="same") if len(width) > k else width

    neck_width = float(smoothed[0])
    peak_idx = int(np.argmax(smoothed))
    peak_width = float(smoothed[peak_idx])

    inner_idx = 0
    threshold = neck_width + 0.15 * (peak_width - neck_width)
    for i in range(peak_idx):
        if smoothed[i] >= threshold:
            inner_idx = i
            break

    fade_idx = len(smoothed) - 1
    fade_threshold = 0.4 * peak_width
    for i in range(peak_idx, len(smoothed)):
        if smoothed[i] < fade_threshold:
            fade_idx = i
            break

    def yfrac(idx):
        return round((y_top + idx) / img_h, 4)

    return {
        "neckBaseY": yfrac(0),
        "innerShoulderY": yfrac(inner_idx),
        "shoulderPeakY": yfrac(peak_idx),
        "outerShoulderY": yfrac(peak_idx),
        "fadeStartY": yfrac(fade_idx),
        "fadeEndY": yfrac(len(smoothed) - 1),
        "neckWidthPx": round(neck_width, 2),
        "shoulderPeakWidthPx": round(peak_width, 2),
        "samples": [
            {"y": round((y_top + i) / img_h, 4), "width": round(float(smoothed[i]), 2)}
            for i in range(0, len(smoothed), max(1, len(smoothed) // 60))
        ],
    }


# ---------------------------------------------------------------------------
# Debug montage
# ---------------------------------------------------------------------------

def make_panel(img_gray_or_bgr, label, panel_w=200, panel_h=356):
    if img_gray_or_bgr.ndim == 2:
        panel = cv2.cvtColor(img_gray_or_bgr, cv2.COLOR_GRAY2BGR)
    else:
        panel = img_gray_or_bgr
    panel = cv2.resize(panel, (panel_w, panel_h), interpolation=cv2.INTER_AREA)
    bar = np.zeros((22, panel_w, 3), dtype=np.uint8)
    cv2.putText(bar, label, (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (180, 255, 210), 1, cv2.LINE_AA)
    return np.vstack([bar, panel])


def build_debug_montage(panels):
    rows = []
    for i in range(0, len(panels), 4):
        row_panels = panels[i:i + 4]
        while len(row_panels) < 4:
            row_panels.append(np.zeros_like(row_panels[0]))
        rows.append(np.hstack(row_panels))
    return np.vstack(rows)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Analyze the particle-avatar reference image.")
    parser.add_argument("image", help="Path to the reference image")
    parser.add_argument("--output-dir", default=None, help="Output directory (default: ./output next to this script)")
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    out_dir = Path(args.output_dir) if args.output_dir else script_dir / "output"
    out_dir.mkdir(parents=True, exist_ok=True)

    img_path = Path(args.image)
    img = cv2.imread(str(img_path))
    if img is None:
        print(f"ERROR: could not read image {img_path}", file=sys.stderr)
        sys.exit(1)
    img_h, img_w = img.shape[:2]

    print(f"[1/9] Loaded {img_path} ({img_w}x{img_h})")

    sil = extract_silhouette(img)
    cv2.imwrite(str(out_dir / "avatar_silhouette.png"), sil["primary_mask"])
    cv2.imwrite(str(out_dir / "side_trails_mask.png"), sil["secondary_mask"])
    print(f"[2/9] Silhouette extracted. Primary bbox={sil['primary_bbox']}, "
          f"{len(sil['trails'])} side-trail fragments")

    labels, primary_label = sil["labels"], sil["primary_label"]
    bbox = sil["primary_bbox"]
    x0, y0, w0, h0 = bbox

    neck_y, _head_width_probe = find_neck_row(labels, primary_label, bbox)
    head_top_y = y0
    head_bottom_y = neck_y
    print(f"[3/9] Head region detected: y={head_top_y}..{head_bottom_y} "
          f"({head_bottom_y - head_top_y}px, neck at y={neck_y})")

    head_mask = np.where(
        (labels == primary_label) & (np.arange(img_h)[:, None] <= head_bottom_y),
        255, 0
    ).astype(np.uint8)
    cv2.imwrite(str(out_dir / "head_mask.png"), head_mask)

    head_profile = sample_profile(labels, primary_label, head_top_y, head_bottom_y, n_samples=101)
    with open(out_dir / "head_profile.json", "w") as f:
        json.dump(head_profile, f, indent=2)
    print(f"[4/9] head_profile.json written. maxHeadWidthPx={head_profile['maxWidthPx']:.1f}")

    body_bottom_y = y0 + h0 - 1
    body_profile = sample_profile(labels, primary_label, head_top_y, body_bottom_y, n_samples=101)
    with open(out_dir / "body_profile.json", "w") as f:
        json.dump(body_profile, f, indent=2)
    print(f"[5/9] body_profile.json written. maxBodyWidthPx={body_profile['maxWidthPx']:.1f}")

    shoulders = shoulder_landmarks(labels, primary_label, neck_y, body_bottom_y, img_w, img_h)
    with open(out_dir / "shoulder_profile.json", "w") as f:
        json.dump(shoulders, f, indent=2)
    print(f"[6/9] shoulder_profile.json written. peakWidthPx={shoulders['shoulderPeakWidthPx']:.1f}")

    distance_field = compute_distance_field(sil["primary_mask"], head_profile["maxWidthPx"])
    np.save(out_dir / "avatar_distance_field.npy", distance_field)
    zones = classify_rim_zones(distance_field)
    zone_colors = rim_zone_colors()
    rim_vis = np.zeros((img_h, img_w, 3), dtype=np.uint8)
    for zone_id, color in zone_colors.items():
        rim_vis[zones == zone_id] = color
    rim_vis[head_bottom_y + 1:, :, :] = 0
    cv2.imwrite(str(out_dir / "head_rim_map.png"), rim_vis)
    print("[7/9] head_rim_map.png + avatar_distance_field.npy written")

    hsv = sil["hsv"]
    cyan_density, orange_density, hot_density = color_density_maps(hsv)
    cv2.imwrite(str(out_dir / "cyan_density.png"), cyan_density)
    cv2.imwrite(str(out_dir / "orange_density.png"), orange_density)
    cv2.imwrite(str(out_dir / "hot_density.png"), hot_density)

    face_core = face_core_from_density(orange_density, img_w, img_h)
    if face_core:
        with open(out_dir / "face_core.json", "w") as f:
            json.dump(face_core, f, indent=2)
    cv2.imwrite(str(out_dir / "face_core_density.png"), orange_density)

    v_channel = hsv[:, :, 2].astype(np.float32) / 255.0
    cv2.imwrite(str(out_dir / "brightness_map.png"), (v_channel * 255).astype(np.uint8))
    print(f"[8/9] Color density + face_core.json written. "
          f"faceCore center=({face_core['centerX']:.3f},{face_core['centerY']:.3f})" if face_core else "[8/9] No orange core found")

    body_mask_vis = np.where(
        (labels == primary_label) & (np.arange(img_h)[:, None] > head_bottom_y),
        255, 0
    ).astype(np.uint8)

    panels = [
        make_panel(cv2.cvtColor(img, cv2.COLOR_BGR2RGB)[:, :, ::-1], "REFERENCE"),
        make_panel(sil["primary_mask"], "SILHOUETTE"),
        make_panel(head_mask, "HEAD MASK"),
        make_panel(rim_vis, "RIM ZONES"),
        make_panel(cyan_density, "CYAN MAP"),
        make_panel(orange_density, "ORANGE CORE"),
        make_panel((v_channel * 255).astype(np.uint8), "BRIGHTNESS"),
        make_panel(body_mask_vis, "BODY MASK"),
    ]
    montage = build_debug_montage(panels)
    cv2.imwrite(str(out_dir / "reference_analysis.png"), montage)
    print("[9/9] reference_analysis.png debug montage written")

    summary = {
        "sourceImage": str(img_path),
        "imageWidth": img_w,
        "imageHeight": img_h,
        "primaryBBoxPx": list(bbox),
        "headTopYPx": int(head_top_y),
        "neckYPx": int(neck_y),
        "bodyBottomYPx": int(body_bottom_y),
        "headMaxWidthPx": head_profile["maxWidthPx"],
        "bodyMaxWidthPx": body_profile["maxWidthPx"],
        "shoulderPeakWidthPx": shoulders["shoulderPeakWidthPx"],
        "headHeightToWidth": round((head_bottom_y - head_top_y) / head_profile["maxWidthPx"], 3),
        "shoulderToHeadWidth": round(shoulders["shoulderPeakWidthPx"] / head_profile["maxWidthPx"], 3),
        "sideTrailCount": len(sil["trails"]),
        "faceCore": face_core,
    }
    with open(out_dir / "analysis_summary.json", "w") as f:
        json.dump(summary, f, indent=2)

    print(f"\nDone. Artifacts written to {out_dir}")


if __name__ == "__main__":
    main()
