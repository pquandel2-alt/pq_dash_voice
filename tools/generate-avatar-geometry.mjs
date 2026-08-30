import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'app/src/main/assets/avatar-geometry.json');
const PARTICLE_COUNT = 11200;
const GEOMETRY_SEED = 0x25d5a11e;
let rngState = GEOMETRY_SEED;
const random = () => {
  rngState = (1664525 * rngState + 1013904223) >>> 0;
  return rngState / 0x100000000;
};
const clamp = (value, low, high) => Math.max(low, Math.min(high, value));
const lerp = (a, b, t) => a + (b - a) * t;
const smoothstep = t => {
  const v = clamp(t, 0, 1);
  return v * v * (3 - 2 * v);
};
const rgb = (r, g, b) => [
  clamp(Math.round(r), 0, 255),
  clamp(Math.round(g), 0, 255),
  clamp(Math.round(b), 0, 255),
];
const cyan = intensity => rgb(8 + intensity * 80, 150 + intensity * 100, 218 + intensity * 37);
const amber = intensity => rgb(255, 102 + intensity * 145, 8 + intensity * 145);
const faceAmber = intensity => {
  const hot = smoothstep((intensity - 0.86) / 0.14);
  return rgb(255, 72 + intensity * 120 + hot * 53, 4 + intensity * 48 + hot * 98);
};

const particles = [];
let particleSeed = 0;

function add({ x, y, z = 0, color, brightness, radius, region, bandId = -1, flowT = -1, drift = 0.7 }) {
  if (particles.length >= PARTICLE_COUNT) return;
  const seed = particleSeed++;
  particles.push({
    targetX: +x.toFixed(6), targetY: +y.toFixed(6), targetZ: +z.toFixed(6),
    x: +x.toFixed(6), y: +y.toFixed(6), z: +z.toFixed(6),
    color, baseColor: color,
    brightness: +clamp(brightness, 0.18, 1).toFixed(4),
    baseAlpha: +clamp(brightness, 0.18, 1).toFixed(4),
    radius: +radius.toFixed(4), baseSize: +radius.toFixed(4),
    region, bandId, flowT: +flowT.toFixed(5), path: -1,
    phase: +((seed * 0.61803398875) % 1 * Math.PI * 2).toFixed(5),
    drift: +(drift + ((seed * 37) % 101) / 101 * 0.55).toFixed(4), seed,
  });
}

// Hand-authored frontal crown/temple/cheek/jaw/chin silhouette. This is not an ellipse.
// The 10-90% keys are locked to the measured reference target-spline (see analyseReference()
// in tools/avatar-preview.html: 0.52 · 0.78 · 0.92 · 0.98 · 1.00 · 0.95 · 0.88 · 0.77 · 0.64).
// Only the crown apex (0.00) and chin tip (1.00) are authored here, softened to a rounded
// dome/chin instead of a mathematical zero-width point (which reads as a pointed egg/alien tip
// once every HEAD_RIM layer traces v=0..1 all the way to the ends).
const HEAD_PROFILE = [
  [0.00, 0.02], [0.035, 0.24], [0.10, 0.52], [0.20, 0.78],
  [0.30, 0.92], [0.40, 0.98], [0.50, 1.00], [0.62, 0.95],
  [0.72, 0.89], [0.82, 0.78], [0.90, 0.64], [0.96, 0.40],
  [1.00, 0.10],
];

function profileAt(v) {
  const t = clamp(v, 0, 1);
  for (let i = 0; i < HEAD_PROFILE.length - 1; i++) {
    const a = HEAD_PROFILE[i], b = HEAD_PROFILE[i + 1];
    if (t >= a[0] && t <= b[0]) {
      const local = smoothstep((t - a[0]) / (b[0] - a[0]));
      return lerp(a[1], b[1], local);
    }
  }
  return HEAD_PROFILE.at(-1)[1];
}

const CX = 0.5;
const HEAD_TOP = 0.165;
const HEAD_HEIGHT = 0.32;
const HEAD_BOTTOM = HEAD_TOP + HEAD_HEIGHT;
const VIEW_ASPECT = 1.6;
const HEAD_MAX_WIDTH = 0.142;
const HEAD_HALF = HEAD_MAX_WIDTH / 2;
// The warm field sits inside the facial shell, slightly below geometric centre like the
// reference. Its soft falloff is intentionally larger than the high-energy white centre.
const CORE_CY = 0.340;
const CORE_WIDTH = 0.086;
const CORE_HEIGHT = 0.18;
// Short energy connection instead of a separate anatomical neck segment (was 0.07 head-bottom
// to neck-base gap; now ~0.045, roughly a third shorter).
const NECK_BASE_Y = 0.530;
const SHOULDER_WIDTH = 0.3432;
const SHOULDER_HALF = SHOULDER_WIDTH / 2;
const SHOULDER_OUTER_Y = 0.675;
const CHEST_CORE_Y = 0.720;
// Torso fades out earlier so the figure never reads as a long body (was 0.835).
const BODY_FADE_Y = 0.80;

// Shoulder bands are emitted first, so head bands and rim composite visibly above them.
// Cubic (not quadratic) Bezier per band: neckBase -> trapeziusRise -> shoulderPeak ->
// smooth outer drop. The extra control point is what turns the previous single quadratic
// sweep (which read as a flat triangular wedge) into a rounded, human trapezius silhouette.
function cubic(u, t, a, b, c, d) {
  return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d;
}
function shoulderControls(layer) {
  return {
    p0x: 0.006 + layer * 0.040,
    p0y: 0.700 + layer * 0.120,
    p1x: 0.025 + layer * 0.025,
    p1y: 0.540 + layer * 0.120,
    p2x: 0.100 + layer * 0.025,
    p2y: 0.570 + layer * 0.100,
    p3x: SHOULDER_HALF - layer * 0.050,
    p3y: SHOULDER_OUTER_Y + layer * 0.090,
  };
}
for (const side of [-1, 1]) {
  for (let band = 0; band < 28; band++) {
    const layer = band / 27;
    const { p0x, p0y, p1x, p1y, p2x, p2y, p3x, p3y } = shoulderControls(layer);
    for (let point = 0; point < 33; point++) {
      const t = point / 32;
      const u = 1 - t;
      const xOffset = cubic(u, t, p0x, p1x, p2x, p3x);
      const y = cubic(u, t, p0y, p1y, p2y, p3y) +
        Math.sin((point + band * 1.7) * 0.43) * 0.0008;
      const edgeFade = 1 - smoothstep(Math.max(0, (t - 0.86) / 0.14)) * (0.42 + layer * 0.42);
      add({
        x: CX + side * xOffset, y,
        z: lerp(0.012, -0.014, layer) + Math.sin(band * 1.3) * 0.003,
        color: cyan(0.50 + (1 - layer) * 0.22),
        brightness: (0.48 + (1 - layer) * 0.25) * edgeFade,
        radius: 0.66 + (1 - layer) * 0.20,
        region: 'shoulderBand', bandId: side * (100 + band), flowT: t, drift: 0.42,
      });
    }
  }
}

// Stable body surface. Only the separately generated ambient region may fray outward.
for (let i = 0; i < 180; i++) {
  const side = i % 2 === 0 ? -1 : 1;
  const layer = random();
  const t = random();
  const u = 1 - t;
  const { p0x, p0y, p1x, p1y, p2x, p2y, p3x, p3y } = shoulderControls(layer);
  const xOffset = cubic(u, t, p0x, p1x, p2x, p3x);
  const centerY = cubic(u, t, p0y, p1y, p2y, p3y);
  add({
    x: CX + side * xOffset,
    y: centerY + (random() - 0.5) * 0.012,
    z: (layer - 0.5) * 0.035,
    color: cyan(0.50 + random() * 0.18), brightness: 0.44 + random() * 0.22,
    radius: 0.58 + random() * 0.27, region: 'shoulderSurface', drift: 0.38,
  });
}

// Particle-density shoulder rim; two near-coincident layers, never a connected stroke.
for (const side of [-1, 1]) {
  for (let layer = 0; layer < 2; layer++) {
    for (let point = 0; point < 70; point++) {
      const t = point / 69;
      const u = 1 - t;
      const xOffset = u * u * u * 0.022 + 3 * u * u * t * 0.032 +
        3 * u * t * t * 0.104 + t * t * t * SHOULDER_HALF;
      const y = u * u * u * (HEAD_BOTTOM + 0.003) + 3 * u * u * t * 0.570 +
        3 * u * t * t * 0.600 + t * t * t * SHOULDER_OUTER_Y + layer * 0.0025;
      const fade = 1 - smoothstep(Math.max(0, (t - 0.84) / 0.16)) * 0.68;
      add({
        x: CX + side * xOffset, y, z: 0.014 - layer * 0.004,
        color: cyan(0.88), brightness: (0.76 - layer * 0.16) * fade,
        radius: layer ? 0.78 : 0.60, region: 'shoulderRim',
        bandId: side * (200 + layer), flowT: t, drift: 0.34,
      });
    }
  }
}

// Upper-bust-only chest filaments; the stable form ends quickly below the chest node.
for (const side of [-1, 1]) {
  for (let band = 0; band < 13; band++) {
    const layer = band / 12;
    for (let point = 0; point < 25; point++) {
      const t = point / 24;
      const u = 1 - t;
      const p0x = 0.004 + layer * 0.008, p0y = CHEST_CORE_Y + layer * 0.004;
      const p1x = 0.042 + layer * 0.016, p1y = 0.625 + layer * 0.040;
      const p2x = 0.103 + layer * 0.020, p2y = 0.635 + layer * 0.072;
      const p3x = 0.151 - layer * 0.010, p3y = 0.680 + layer * 0.125;
      const xOffset = u * u * u * p0x + 3 * u * u * t * p1x +
        3 * u * t * t * p2x + t * t * t * p3x;
      const y = u * u * u * p0y + 3 * u * u * t * p1y +
        3 * u * t * t * p2y + t * t * t * p3y;
      add({
        x: CX + side * xOffset, y, z: 0.006 - layer * 0.020,
        color: cyan(0.52 + (1 - layer) * 0.17), brightness: 0.48 + (1 - layer) * 0.19,
        radius: 0.66 + (1 - layer) * 0.18, region: 'chestBand',
        bandId: side * (300 + band), flowT: t, drift: 0.38,
      });
    }
  }
}
for (let i = 0; i < 100; i++) {
  const t = random();
  const width = lerp(0.067, 0.026, t);
  add({
    x: CX + (random() - 0.5) * width * 2,
    y: lerp(0.700, BODY_FADE_Y, t), z: (random() - 0.5) * 0.025,
    color: cyan(0.48 + random() * 0.12), brightness: 0.56 - t * 0.34,
    radius: 0.58 + random() * 0.20, region: 'chestBand', drift: 0.32,
  });
}

// Short central energy axis: curved filaments connect chin, sternum node and upper-bust fade.
// Their opacity falls before the lower screen, preventing the former long-torso appearance.
for (let strand = -7; strand <= 7; strand++) {
  const nx = strand / 7;
  for (let point = 0; point < 24; point++) {
    const t = point / 23;
    const spread = t < 0.55 ? lerp(0.020, 0.058, t / 0.55) : lerp(0.058, 0.035, (t - 0.55) / 0.45);
    const y = lerp(0.550, 0.825, t) + Math.sin(strand * 1.31 + t * Math.PI * 1.7) * 0.0028;
    const fade = 1 - smoothstep(Math.max(0, (t - 0.68) / 0.32)) * 0.72;
    const warm = Math.abs(strand) <= 1 && t < 0.62;
    add({
      x: CX + nx * spread + Math.sin(t * Math.PI * 2 + strand * 0.72) * 0.0018,
      y, z: 0.012 - Math.abs(nx) * 0.018,
      color: warm ? amber(0.56 + (1 - Math.abs(nx)) * 0.18) : cyan(0.58),
      brightness: (warm ? 0.64 : 0.55) * fade,
      radius: 0.62 + (1 - Math.abs(nx)) * 0.16,
      region: 'chestBand', bandId: 350 + strand, flowT: t, drift: 0.34,
    });
  }
}

// Fragmented background trails: six per side, well behind the body.
for (const side of [-1, 1]) {
  for (let trail = 0; trail < 6; trail++) {
    for (let point = 0; point < 30; point++) {
      if ((point + trail * 3) % 11 === 0) continue;
      const t = point / 29;
      add({
        x: CX + side * lerp(0.145, 0.31, t),
        y: 0.39 + trail * 0.040 + Math.sin(t * Math.PI * 1.6 + trail * 0.63 + (side > 0 ? 0.37 : 0)) * (0.018 + trail * 0.0015),
        z: -0.035 - trail * 0.002, color: cyan(0.42 + trail * 0.025),
        brightness: 0.30 + trail * 0.025, radius: 0.48 + trail * 0.035,
        region: 'sideTrail', bandId: side * (400 + trail), flowT: t, drift: 0.85,
      });
    }
  }
}

// Interwoven energy connection from the jaw through the short neck into the sternum node.
// It occupies the negative space without creating a cylindrical anatomical neck.
for (let strand = 0; strand < 18; strand++) {
  const nx = (strand / 17) * 2 - 1;
  const warm = Math.abs(nx) < 0.48;
  for (let point = 0; point < 25; point++) {
    const t = point / 24;
    const waist = Math.sin(t * Math.PI);
    add({
      x: CX + nx * lerp(0.023, 0.008, t) + nx * waist * 0.018 +
        Math.sin(strand * 1.7 + t * Math.PI * 2) * 0.0024 * waist,
      y: lerp(HEAD_BOTTOM - 0.004, CHEST_CORE_Y, t),
      z: 0.018 + Math.cos(strand * 1.1) * 0.007,
      color: warm ? amber(0.58 + (1 - Math.abs(nx)) * 0.20) : cyan(0.78),
      brightness: warm ? 0.72 + (1 - Math.abs(nx)) * 0.17 : 0.62 + waist * 0.12,
      radius: warm ? 0.80 : 0.70, region: 'neckEnergy', bandId: 500 + strand,
      flowT: t, drift: 0.36,
    });
  }
}

// Three deterministic depth fields turn the interior into a volume instead of a rim plus void.
for (const field of [
  { region: 'headBack', count: 260, z: -0.030, intensity: 0.32, alpha: 0.24, radius: 0.46 },
  { region: 'headMid', count: 320, z: -0.006, intensity: 0.50, alpha: 0.40, radius: 0.50 },
  { region: 'headFront', count: 260, z: 0.020, intensity: 0.68, alpha: 0.56, radius: 0.54 },
]) {
  for (let i = 0; i < field.count; i++) {
    const v = 0.025 + random() * 0.95;
    const boundary = HEAD_HALF * profileAt(v);
    const xNorm = (random() * 2 - 1) * (0.30 + Math.sqrt(random()) * 0.66);
    const frontLift = (1 - Math.abs(xNorm)) * 0.010;
    add({
      x: CX + boundary * xNorm,
      y: HEAD_TOP + v * HEAD_HEIGHT + (random() - 0.5) * 0.0022,
      z: field.z + frontLift + (random() - 0.5) * 0.006,
      color: cyan(field.intensity + Math.abs(xNorm) * 0.08),
      brightness: field.alpha + Math.abs(xNorm) * 0.10,
      radius: field.radius + random() * 0.12, region: field.region, drift: 0.25,
    });
  }
}

// Gaussian-energy face field: positions fill a soft facial volume while brightness falls
// continuously from its centre. There are no rings, contour points or flame/egg boundary.
// Higher density + larger particle radius than before so the cluster reads as one continuous
// energy surface at normal viewing distance instead of grainy/isolated dots.
for (let i = 0; i < 1180; i++) {
  const angle = random() * Math.PI * 2;
  const radial = Math.sqrt(random());
  const ny = Math.sin(angle) * radial;
  const upperTaper = ny < -0.08 ? lerp(1.0, 0.64, (-ny - 0.08) / 0.92) : 1.0;
  const lowerTaper = ny > 0.18 ? lerp(1.0, 0.72, (ny - 0.18) / 0.82) : 1.0;
  const shape = upperTaper * lowerTaper * (0.92 + 0.08 * (1 - Math.abs(ny)));
  const intensity = Math.exp(-2.15 * radial * radial);
  add({
    x: CX + Math.cos(angle) * radial * CORE_WIDTH * 0.5 * shape,
    y: CORE_CY + ny * CORE_HEIGHT * 0.5,
    z: 0.008 + intensity * 0.020 + (random() - 0.5) * 0.008,
    color: faceAmber(intensity), brightness: 0.34 + intensity * 0.60,
    radius: 0.62 + (1 - intensity) * 0.22 + intensity * 0.30,
    region: 'faceCore', drift: 0.33,
  });
}

// Dense head bands terminate at the exact rim boundary and render in front of the warm core.
for (let band = 0; band < 72; band++) {
  const v = 0.018 + band / 71 * 0.964;
  const profile = profileAt(v);
  const boundary = HEAD_HALF * profile;
  const count = Math.max(20, Math.round(24 + profile * 36));
  for (let point = 0; point < count; point++) {
    if ((point + band * 7) % 173 === 0) continue;
    const t = point / (count - 1);
    const xNorm = -1 + t * 2;
    const edge = Math.pow(Math.abs(xNorm), 2.4);
    const yWave = Math.cos(xNorm * Math.PI * 0.72) * (0.0009 + (band % 7) * 0.00005) +
      Math.sin(band * 1.31 + point * 0.57) * 0.00028;
    add({
      x: CX + xNorm * boundary,
      y: HEAD_TOP + v * HEAD_HEIGHT + yWave,
      z: 0.010 + Math.sqrt(Math.max(0, 1 - xNorm * xNorm)) * 0.025,
      color: cyan(0.70 + edge * 0.27), brightness: 0.64 + edge * 0.31,
      radius: 0.54 + edge * 0.11, region: 'headBand', bandId: 600 + band,
      flowT: t, drift: 0.30,
    });
  }
}

// Three coincident particle rim layers: hot inner core, electric-cyan main layer, soft halo.
// The density creates the readable neon silhouette; individual dots stay fine enough that the
// three layers do not merge into the previous solid helmet/tube.
const rimLayers = [
  { region: 'headRimHalo', scale: 1.018, brightness: 0.32, radius: 2.60, z: -0.010 },
  { region: 'headRimMain', scale: 1.000, brightness: 0.92, radius: 0.72, z: 0.020 },
  { region: 'headRimInner', scale: 0.988, brightness: 1.00, radius: 0.34, z: 0.030 },
];
for (let layer = 0; layer < rimLayers.length; layer++) {
  const rim = rimLayers[layer];
  for (const side of [-1, 1]) {
    for (let point = 0; point < 142; point++) {
      if (layer === 2 && point % 2 === 1) continue;
      if (side > 0 && (point === 0 || point === 141)) continue;
      const v = point / 141;
      const cluster = 0.5 + 0.5 * Math.sin(v * Math.PI * 10.4 + layer * 1.7 + side * 0.4);
      if (layer > 0 && cluster < 0.025 && point % 3 !== 0) continue;
      const organic = Math.sin(v * Math.PI * 5.3 + layer) * 0.0045 +
        Math.sin(v * Math.PI * 13.1 + side) * 0.0025;
      const emphasis = 0.72 + 0.28 * cluster;
      add({
        x: CX + side * HEAD_HALF * profileAt(v) * (rim.scale + organic),
        y: HEAD_TOP + v * HEAD_HEIGHT + Math.sin(v * Math.PI * 17 + layer) * 0.00045,
        z: rim.z + organic * 0.4,
        color: layer === 2 ? rgb(205, 252, 255) :
          (layer === 1 ? rgb(0, 220, 255) : rgb(0, 168, 255)),
        brightness: rim.brightness * emphasis,
        radius: rim.radius * (0.78 + cluster * 0.32),
        region: rim.region, bandId: 700 + layer * 2 + (side > 0 ? 1 : 0),
        flowT: v, drift: 0.24,
      });
    }
  }
}

// Compact white-cyan sternum node.
for (let i = 0; i < 80; i++) {
  const angle = random() * Math.PI * 2;
  const r = Math.sqrt(random());
  add({
    x: CX + Math.cos(angle) * r * 0.010,
    y: CHEST_CORE_Y + Math.sin(angle) * r * 0.018,
    z: 0.022 + (1 - r) * 0.012,
    color: rgb(160 + (1 - r) * 95, 245, 255), brightness: 0.72 + (1 - r) * 0.28,
    radius: 0.72 + (1 - r) * 0.62, region: 'chestCore', drift: 0.28,
  });
}

// Controlled aura, concentrated above the head and behind the shoulders.
while (particles.length < PARTICLE_COUNT) {
  const zone = random();
  let x, y;
  if (zone < 0.58) {
    const angle = random() * Math.PI;
    const distance = 0.018 + Math.pow(random(), 1.8) * 0.12;
    x = CX + Math.cos(angle) * (HEAD_HALF + distance) * (0.65 + random() * 0.55);
    y = HEAD_TOP - Math.sin(angle) * (0.035 + distance * 0.8) + (random() - 0.5) * 0.025;
  } else {
    const side = random() < 0.5 ? -1 : 1;
    x = CX + side * (0.18 + random() * 0.23);
    y = 0.46 + random() * 0.22;
  }
  add({
    x, y, z: -0.045 + (random() - 0.5) * 0.03,
    color: cyan(0.42 + random() * 0.15), brightness: 0.22 + random() * 0.28,
    radius: 0.42 + random() * 0.38, region: 'ambient', drift: 1.15,
  });
}

const counts = particles.reduce((acc, particle) => {
  acc[particle.region] = (acc[particle.region] || 0) + 1;
  return acc;
}, {});
const metrics = {
  coordinateSystem: 'normalized-16:10-front-view',
  headWidth: HEAD_MAX_WIDTH,
  headHeight: HEAD_HEIGHT,
  headHeightToWidth: +(HEAD_HEIGHT / (HEAD_MAX_WIDTH * VIEW_ASPECT)).toFixed(3),
  shoulderWidth: SHOULDER_WIDTH,
  shoulderToHead: +(SHOULDER_WIDTH / HEAD_MAX_WIDTH).toFixed(3),
  visibleNeckHeight: +(NECK_BASE_Y - HEAD_BOTTOM).toFixed(3),
  neckToHeadWidth: +((NECK_BASE_Y - HEAD_BOTTOM) / (HEAD_MAX_WIDTH * VIEW_ASPECT)).toFixed(3),
  stableBustHeightBelowHead: +(0.72 - HEAD_BOTTOM).toFixed(3),
  bustToHeadWidth: +((0.72 - HEAD_BOTTOM) / (HEAD_MAX_WIDTH * VIEW_ASPECT)).toFixed(3),
  faceCoreWidth: CORE_WIDTH,
  faceCoreHeight: CORE_HEIGHT,
  faceCoreToHeadWidth: +(CORE_WIDTH / HEAD_MAX_WIDTH).toFixed(3),
  headBands: 72,
  shoulderBandsPerSide: 28,
  sideTrailsPerSide: 6,
};

fs.writeFileSync(output, `${JSON.stringify({
  version: 2, seed: GEOMETRY_SEED, particleCount: particles.length,
  metrics, counts, particles,
})}\n`);
console.log(`${particles.length} deterministic 2.5D particles -> ${output}`);
console.log(JSON.stringify({ metrics, counts }, null, 2));
