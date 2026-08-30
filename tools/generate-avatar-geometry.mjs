import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'app/src/main/assets/avatar-geometry.json');
const PARTICLE_COUNT = 9400;
let seed = 0x51a7c0de;
const random = () => {
  seed = (1664525 * seed + 1013904223) >>> 0;
  return seed / 0x100000000;
};
const clamp = (value, low, high) => Math.max(low, Math.min(high, value));
const rgb = (r, g, b) => [clamp(Math.round(r), 0, 255), clamp(Math.round(g), 0, 255), clamp(Math.round(b), 0, 255)];
const cyan = brightness => rgb(8 + brightness * 32, 125 + brightness * 105, 210 + brightness * 45);
const particles = [];
let pathSequence = 0;
const nextPath = () => pathSequence++;
const pathOrders = new Map();

function add(x, y, color, brightness, radius = 1.35, region = 'structure', pathId = -1) {
  if (particles.length >= PARTICLE_COUNT) return;
  const freeParticle = region === 'aura' || region === 'chestCore' || (region === 'core' && pathId < 0);
  const pathOrder = pathId >= 0 ? (pathOrders.get(pathId) ?? 0) : 0;
  if (pathId >= 0) pathOrders.set(pathId, pathOrder + 1);
  const phase = pathId >= 0
    ? (pathId * .618 + pathOrder * .024) % (Math.PI * 2)
    : random() * Math.PI * 2;
  const jitter = pathId < 0 ? 0 : region === 'head' ? .0011
    : region === 'core' ? .0018
      : region === 'neck' || region === 'neckEnergy' || region === 'flow' ? .0022
        : region === 'shoulder' || region === 'torso' ? .0032 : .0015;
  const jitterX = (random() - .5) * jitter * 2;
  const jitterY = (random() - .5) * jitter * 2;
  particles.push({
    x: +(x + jitterX).toFixed(6), y: +(y + jitterY).toFixed(6), color,
    brightness: +clamp(brightness, 0.35, 1).toFixed(4),
    phase: +phase.toFixed(5),
    drift: +(freeParticle ? 1.4 + random() * 3.8 : .55 + (pathId * 37 % 100) / 100 * 1.1).toFixed(4),
    radius: +(freeParticle ? radius + random() * .65 : radius * .58 + random() * .28).toFixed(4),
    // Native Canvas draws every energy filament as point samples. Keeping path=-1 prevents
    // the renderer from reconnecting them into CAD-like solid strokes.
    region, path: -1,
  });
}

const cx = 0.5;
const headCy = 0.36;
const headRy = 0.18;
const neckTopY = headCy + headRy;
const neckBaseY = 0.61;
const shoulderOuterY = 0.79;

function headHalfWidth(v) {
  const ellipse = Math.sqrt(Math.max(0, 1 - v * v));
  const jawTaper = 1 - Math.max(0, v) * .18;
  return .105 * Math.pow(ellipse, .86) * jawTaper;
}

// Translucent horizontal energy contours.
for (let line = 0; line < 44; line++) {
  const pathId = nextPath();
  const v = -1 + line / 43 * 2 + Math.sin(line * 1.71) * .003;
  const half = headHalfWidth(v);
  const profile = half / .096;
  const count = Math.round(24 + profile * 42);
  const y = headCy + v * headRy;
  for (let point = 0; point < count; point++) {
    const across = point / (count - 1);
    const edge = Math.abs(across - .5) * 2;
    const brightness = .34 + profile * .17 + edge * .22 + random() * .08;
    add(cx - half + half * 2 * across, y + Math.sin(across * Math.PI * 2 + line * .32) * .0015,
      cyan(brightness), brightness, .95, 'head', pathId);
  }
}

// No explicit profile halo: silhouette is produced by flow density and surface particles.
for (let shell = 0; shell < 0; shell++) {
  const offsetX = shell * .00155;
  const offsetY = shell * .0019;
  const pathId = nextPath();
  for (let point = 0; point < 90; point++) {
    const v = -1 + point / 89 * 2;
    const half = headHalfWidth(v) + offsetX;
    const y = headCy + v * (headRy + offsetY);
    add(cx - half, y, cyan(.98), .9 + random() * .1, 1.55, 'halo', pathId);
  }
  for (let point = 89; point >= 0; point--) {
    const v = -1 + point / 89 * 2;
    const half = headHalfWidth(v) + offsetX;
    const y = headCy + v * (headRy + offsetY);
    add(cx + half, y, cyan(.98), .9 + random() * .1, 1.55, 'halo', pathId);
  }
  add(cx - offsetX, headCy - headRy - offsetY, cyan(.98), .96, 1.55, 'halo', pathId);
}

// Shallow head-surface volume breaks the perfect scan-line regularity without drawing a rim.
for (let point = 0; point < 900; point++) {
  const v = -1 + random() * 2;
  const half = headHalfWidth(v);
  const across = (random() * 2 - 1) * (.72 + Math.sqrt(random()) * .28);
  const edge = Math.abs(across);
  const brightness = .38 + edge * .24 + random() * .13;
  add(cx + across * half, headCy + v * headRy + (random() - .5) * .004,
    cyan(brightness), brightness, .78, 'head');
}

// Warm faceless energy core: coherent horizontal filaments, orange at the edge and almost
// white in the centre. It remains visibly made from particles rather than becoming a bitmap.
const coreCy = headCy + .018;
for (let line = 0; line < 24; line++) {
  const pathId = nextPath();
  const v = -1 + line / 23 * 2;
  const profile = Math.sqrt(Math.max(0, 1 - v * v));
  const half = .048 * profile * (.86 + (1 - Math.abs(v)) * .14);
  const points = Math.round(14 + profile * 36);
  for (let point = 0; point < points; point++) {
    const across = point / (points - 1);
    const radial = Math.sqrt(v * v + Math.pow((across - .5) * 2, 2));
    const centre = clamp(1 - radial, 0, 1);
    add(cx - half + half * 2 * across,
      coreCy + v * .092 + Math.sin(across * Math.PI * 3 + line * .35) * .0018,
      rgb(255, 112 + centre * 125, 8 + centre * 100),
      .72 + centre * .28, 1.38 + centre * .75, 'core', pathId);
  }
}
for (let point = 0; point < 80; point++) {
  const angle = random() * Math.PI * 2;
  const radius = Math.sqrt(random());
  add(cx + Math.cos(angle) * radius * .022, coreCy + Math.sin(angle) * radius * .034,
    rgb(255, 205 + random() * 45, 85 + random() * 90), .88 + random() * .12, 1.85, 'core');
}

// Long, narrow neural neck with gold central fibres.
for (let stream = -10; stream <= 10; stream++) {
  const pathId = nextPath();
  const lane = stream / 10;
  for (let point = 0; point < 45; point++) {
    const t = point / 44;
    const spread = .018 + t * .037;
    const curve = Math.sin(t * Math.PI) * Math.sin(stream * 1.35) * .006;
    const gold = Math.abs(stream) <= 3;
    add(cx + lane * spread + curve, neckTopY + (neckBaseY - neckTopY) * t,
      gold ? rgb(240,176,70) : cyan(.78), gold ? .88 : .7, 1.15,
      gold ? 'neckEnergy' : 'neck', pathId);
  }
}

// Bright outer neck shell connects the head silhouette organically to the shoulders.
for (const side of [-1, 1]) {
  for (let shell = 0; shell < 0; shell++) {
    const pathId = nextPath();
    for (let point = 0; point < 40; point++) {
      const t = point / 39;
      const chinWidth = .018 + shell * .0018;
      const baseWidth = .044 + shell * .0032;
      const x = cx + side * (chinWidth + (baseWidth - chinWidth) * t) +
        side * Math.sin(t * Math.PI) * .004;
      const y = neckTopY + (neckBaseY - neckTopY) * t;
      add(x, y, cyan(.86), .76 + shell * .025, 1.22, 'neckShell', pathId);
    }
  }
}

// Bright continuous silhouette from the jaw around the upper shoulder.
for (const side of [-1, 1]) {
  for (let shell = 0; shell < 0; shell++) {
    const pathId = nextPath();
    for (let point = 0; point < 55; point++) {
      const t = point / 54;
      const eased = 1 - Math.pow(1 - t, 2.15);
      const startWidth = .006 + shell * .0017;
      const endWidth = .23 + shell * .002;
      const width = startWidth + (endWidth - startWidth) * eased;
      const y = neckTopY + (.72 - neckTopY) * Math.pow(t, 1.45) - Math.sin(t * Math.PI) * .012;
      add(cx + side * width, y, cyan(.98), .86 + shell * .025, 1.4, 'silhouette', pathId);
    }
  }
}

// Separate anatomical shoulder filaments: neck base → trapezius → shoulder → soft outer drop.
// They never join into a full-width arch or a closed chest loop.
const shoulderHalf = .195;
for (const side of [-1, 1]) {
  for (let layer = 0; layer < 17; layer++) {
    const pathId = nextPath();
    const depth = layer / 16;
    const startWidth = .020 + depth * .030;
    const endWidth = shoulderHalf - depth * .018 + Math.sin(layer * 1.9) * .004;
    const startY = .605 + depth * .016 + Math.sin(layer * 1.37) * .002;
    const endY = .700 + depth * .058 + Math.sin(layer * 1.71) * .004;
    for (let point = 0; point < 45; point++) {
      if ((point + layer * 5) % 41 === 0) continue;
      const t = point / 44;
      const eased = 1 - Math.pow(1 - t, 1.42);
      const x = cx + side * (startWidth + (endWidth - startWidth) * eased);
      const y = startY + (endY - startY) * Math.pow(t, 1.18) -
        Math.sin(t * Math.PI) * (.021 - depth * .006) +
        Math.sin(t * Math.PI * 2 + layer * .51) * .0015;
      const brightness = .48 + (1 - depth) * .27 + random() * .08;
      add(x, y, cyan(brightness), brightness, 1.02, 'shoulder', pathId);
    }
  }
}

// Curved pectoral energy bands connect the sternum to each shoulder without radial spokes.
for (const side of [-1, 1]) {
  for (let layer = 0; layer < 12; layer++) {
    const pathId = nextPath();
    const n = layer / 11;
    const p0x = .006 + n * .034, p0y = .732 + n * .018;
    const p1x = .072 + n * .030, p1y = .618 + n * .050;
    const p2x = .184 - n * .022, p2y = .700 + n * .050;
    for (let point = 0; point < 40; point++) {
      if ((point + layer * 2) % 37 === 0) continue;
      const t = point / 39, u = 1 - t;
      const x = u * u * p0x + 2 * u * t * p1x + t * t * p2x;
      const y = u * u * p0y + 2 * u * t * p1y + t * t * p2y +
        Math.sin(t * Math.PI * 2 + layer * .7) * .0015;
      const brightness = .48 + (1 - n) * .25 + random() * .07;
      add(cx + side * x, y, cyan(brightness), brightness, .98, 'shoulder', pathId);
    }
  }
}

// Open torso filaments follow the sternum downward and taper independently into black.
for (let strand = -9; strand <= 9; strand++) {
  const pathId = nextPath();
  const xNorm = strand / 9;
  const asym = Math.sin(strand * 1.77) * .004;
  for (let point = 0; point < 45; point++) {
    const t = point / 44;
    if (random() < .035 + t * .10) continue;
    const width = .122 - t * .064;
    const x = cx + xNorm * width + asym * Math.sin(t * Math.PI) +
      Math.sin(t * Math.PI * 2 + strand * .63) * .0017;
    const y = .695 + t * (.255 + Math.sin(strand * 1.2) * .008);
    const brightness = .58 - t * .22 + (1 - Math.abs(xNorm)) * .08;
    add(x, y, cyan(brightness), brightness, .92, 'torso', pathId);
  }
}

// Central living energy fibres and chest beacon.
for (let stream = -6; stream <= 6; stream++) {
  const pathId = nextPath();
  for (let point = 0; point < 40; point++) {
    const t = point / 39;
    const x = cx + stream * .0042 * (.45 + t * .95) + Math.sin(t * Math.PI * 2 + stream) * .0025;
    const y = neckBaseY + (.94 - neckBaseY) * t;
    const gold = Math.abs(stream) <= 2 || (Math.abs(stream) <= 5 && t < .48);
    add(x, y, gold ? rgb(225,177,92) : cyan(.68), .66, .95, 'flow', pathId);
  }
}
for (let point = 0; point < 72; point++) {
  const angle = random() * Math.PI * 2;
  const radius = Math.sqrt(random());
  add(cx + Math.cos(angle) * radius * .012, .735 + Math.sin(angle) * radius * .028,
    rgb(110,245,255), .78 + random() * .22, 1.0, 'chestCore');
}

// Electric side ribbons breathe behind the figure like the reference's loose particle waves.
for (const side of [-1, 1]) {
  for (let wave = 0; wave < 0; wave++) {
    const pathId = nextPath();
    for (let point = 0; point < 50; point++) {
      const t = point / 49;
      const x = cx + side * (.20 + t * .25);
      const y = .34 + wave * .047 + (t - .5) * (.03 + wave * .006) +
        Math.sin(t * Math.PI * 2.4 + wave * .92) * (.018 + wave * .002);
      add(x, y, cyan(.48 + wave * .035), .42 + wave * .035, .72, 'sideWave', pathId);
    }
  }
}

// Sparse crown/side aura only.
while (particles.length < PARTICLE_COUNT) {
  const kind = random();
  const crown = kind < .46;
  const torsoFade = kind >= .46 && kind < .80;
  const x = crown
    ? cx + (random() - .5) * .24
    : torsoFade
      ? cx + (random() - .5) * (.16 + random() * .18)
      : cx + (random() - .5) * .78;
  const y = crown
    ? headCy - headRy - Math.pow(random(), 1.7) * .13
    : torsoFade
      ? .82 + Math.pow(random(), .72) * .18
      : shoulderOuterY + (random() - .72) * .13;
  add(x, y, cyan(.48), .38 + random() * .3, .82, 'aura');
}

// Preserve the tall humanoid head on a landscape tablet; only the shoulders use the wide canvas.
for (const particle of particles) {
  if (particle.region === 'head' || particle.region === 'halo' || particle.region === 'core') {
    particle.x = +(cx + (particle.x - cx) * .82).toFixed(6);
  }
}

fs.writeFileSync(output, JSON.stringify({ version: 1, particles }, null, 0) + '\n');
console.log(`${particles.length} particles -> ${output}`);
