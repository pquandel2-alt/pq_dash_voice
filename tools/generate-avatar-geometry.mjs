import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'app/src/main/assets/avatar-geometry.json');
const PARTICLE_COUNT = 9800;
let seed = 0x51a7c0de;
const random = () => {
  seed = (1664525 * seed + 1013904223) >>> 0;
  return seed / 0x100000000;
};
const clamp = (value, low, high) => Math.max(low, Math.min(high, value));
const rgb = (r, g, b) => [clamp(Math.round(r), 0, 255), clamp(Math.round(g), 0, 255), clamp(Math.round(b), 0, 255)];
const cyan = brightness => rgb(8 + brightness * 32, 125 + brightness * 105, 210 + brightness * 45);
const particles = [];

function add(x, y, color, brightness, radius = 1.35, region = 'structure') {
  if (particles.length >= PARTICLE_COUNT) return;
  const freeParticle = region === 'aura' || region === 'core' || region === 'chestCore';
  particles.push({
    x: +x.toFixed(6), y: +y.toFixed(6), color,
    brightness: +clamp(brightness, 0.35, 1).toFixed(4),
    phase: +(random() * Math.PI * 2).toFixed(5),
    drift: +(freeParticle ? 1.4 + random() * 3.8 : .45 + random() * 1.7).toFixed(4),
    radius: +(radius + random() * 0.65).toFixed(4),
    region,
  });
}

const cx = 0.5;
const headCy = 0.335;
const headRy = 0.205;
const neckTopY = headCy + headRy;
const neckBaseY = 0.635;
const shoulderOuterY = 0.79;

function headHalfWidth(v) {
  const stops = [[-1,.18],[-.82,.68],[-.52,.96],[-.10,1],[.30,.92],[.60,.72],[.84,.43],[1,.16]];
  for (let i = 0; i < stops.length - 1; i++) {
    const [fromY, fromW] = stops[i], [toY, toW] = stops[i + 1];
    if (v >= fromY && v <= toY) {
      const t = (v - fromY) / (toY - fromY);
      return 0.096 * (fromW + (toW - fromW) * t);
    }
  }
  return 0.015;
}

// Translucent horizontal energy contours.
for (let line = 0; line < 30; line++) {
  const v = -1 + line / 29 * 2;
  const half = headHalfWidth(v);
  const profile = half / .096;
  const count = Math.round(30 + profile * 50);
  const y = headCy + v * headRy;
  for (let point = 0; point < count; point++) {
    const across = point / (count - 1);
    const edge = Math.abs(across - .5) * 2;
    const brightness = .34 + profile * .17 + edge * .22 + random() * .08;
    add(cx - half + half * 2 * across, y + Math.sin(across * Math.PI * 2 + line * .32) * .0015,
      cyan(brightness), brightness, .95, 'head');
  }
}

// Strong profile-following cyan halo.
for (let shell = 0; shell < 4; shell++) {
  const offsetX = shell * .00155;
  const offsetY = shell * .0019;
  for (let point = 0; point < 120; point++) {
    const v = -1 + point / 119 * 2;
    const half = headHalfWidth(v) + offsetX;
    const y = headCy + v * (headRy + offsetY);
    add(cx - half, y, cyan(.98), .9 + random() * .1, 1.55, 'halo');
    add(cx + half, y, cyan(.98), .9 + random() * .1, 1.55, 'halo');
  }
}

// Warm faceless energy core: coherent horizontal filaments, orange at the edge and almost
// white in the centre. It remains visibly made from particles rather than becoming a bitmap.
const coreCy = headCy + .018;
for (let line = 0; line < 24; line++) {
  const v = -1 + line / 23 * 2;
  const profile = Math.sqrt(Math.max(0, 1 - v * v));
  const half = .067 * profile;
  const points = Math.round(18 + profile * 48);
  for (let point = 0; point < points; point++) {
    const across = point / (points - 1);
    const radial = Math.sqrt(v * v + Math.pow((across - .5) * 2, 2));
    const centre = clamp(1 - radial, 0, 1);
    add(cx - half + half * 2 * across,
      coreCy + v * .105 + Math.sin(across * Math.PI * 3 + line * .35) * .0018,
      rgb(255, 112 + centre * 125, 8 + centre * 100),
      .72 + centre * .28, 1.38 + centre * .75, 'core');
  }
}
for (let point = 0; point < 180; point++) {
  const angle = random() * Math.PI * 2;
  const radius = Math.sqrt(random());
  add(cx + Math.cos(angle) * radius * .022, coreCy + Math.sin(angle) * radius * .034,
    rgb(255, 205 + random() * 45, 85 + random() * 90), .88 + random() * .12, 1.85, 'core');
}

// Long, narrow neural neck with gold central fibres.
for (let stream = -7; stream <= 7; stream++) {
  const lane = stream / 7;
  for (let point = 0; point < 45; point++) {
    const t = point / 44;
    const spread = .012 + t * .036;
    const curve = Math.sin(t * Math.PI) * Math.sin(stream * 1.35) * .006;
    const gold = Math.abs(stream) <= 2;
    add(cx + lane * spread + curve, neckTopY + (neckBaseY - neckTopY) * t,
      gold ? rgb(240,176,70) : cyan(.78), gold ? .88 : .7, 1.15, gold ? 'neckEnergy' : 'neck');
  }
}

// Bright outer neck shell connects the head silhouette organically to the shoulders.
for (const side of [-1, 1]) {
  for (let shell = 0; shell < 5; shell++) {
    for (let point = 0; point < 50; point++) {
      const t = point / 49;
      const chinWidth = .018 + shell * .0018;
      const baseWidth = .044 + shell * .0032;
      const x = cx + side * (chinWidth + (baseWidth - chinWidth) * t) +
        side * Math.sin(t * Math.PI) * .004;
      const y = neckTopY + (neckBaseY - neckTopY) * t;
      add(x, y, cyan(.86), .76 + shell * .025, 1.22, 'neckShell');
    }
  }
}

// Continuous shoulder-to-torso streams. Each contour starts at the neck, rounds over the
// shoulder and returns toward the sternum, so the bust reads as one living form.
const neckHalf = .036;
const shoulderHalf = .23;
for (const side of [-1, 1]) {
  for (let layer = 0; layer < 21; layer++) {
    const depth = layer / 20;
    const widest = shoulderHalf - depth * .135;
    const endWidth = .040 + depth * .065;
    const endY = .952 - depth * .075;
    for (let point = 0; point < 80; point++) {
      const t = point / 79;
      let width;
      let y;
      if (t < .34) {
        const u = t / .34;
        const eased = 1 - Math.pow(1 - u, 2.25);
        width = neckHalf + depth * .010 + (widest - neckHalf - depth * .010) * eased;
        y = neckBaseY + (.720 + depth * .006 - neckBaseY) * Math.pow(u, 1.45);
      } else {
        const u = (t - .34) / .66;
        width = widest + (endWidth - widest) * Math.pow(u, 1.72);
        y = .720 + depth * .006 + (endY - .720 - depth * .006) * Math.pow(u, .92) +
          Math.sin(u * Math.PI) * (.018 - depth * .004);
      }
      const fade = 1 - Math.pow(Math.max(0, (t - .64) / .36), 1.3) * .58;
      const brightness = (.45 + (1 - depth) * .29 + (1 - t) * .09) * fade;
      add(cx + side * width, y, cyan(brightness), brightness, 1.04, 'shoulder');
    }
  }
}

// Fine inner ribs fill the chest without forming a flat horizontal shelf.
for (const side of [-1, 1]) {
  for (let rib = 0; rib < 7; rib++) {
    for (let point = 0; point < 55; point++) {
      const t = point / 54;
      const startWidth = .050 + rib * .022;
      const endWidth = .020 + rib * .006;
      const x = cx + side * (startWidth + (endWidth - startWidth) * Math.pow(t, 1.08));
      const y = neckBaseY + .025 + (.93 - neckBaseY - .025) * t +
        Math.sin(t * Math.PI) * (.014 + rib * .0015);
      add(x, y, cyan(.61 + (1 - t) * .15), .54 + (1 - t) * .22, 1.02, 'rib');
    }
  }
}

// Central living energy fibres and chest beacon.
for (let stream = -6; stream <= 6; stream++) {
  for (let point = 0; point < 31; point++) {
    const t = point / 30;
    const x = cx + stream * .0052 * (1 - t * .55) + Math.sin(t * Math.PI * 2 + stream) * .0025;
    const y = neckBaseY + (.925 - neckBaseY) * t;
    add(x, y, Math.abs(stream) <= 1 ? rgb(225,177,92) : cyan(.65), .62, .95, 'flow');
  }
}
for (let point = 0; point < 280; point++) {
  const angle = random() * Math.PI * 2;
  const radius = Math.sqrt(random());
  add(cx + Math.cos(angle) * radius * .018, .79 + Math.sin(angle) * radius * .045,
    rgb(110,245,255), .78 + random() * .22, 1.55, 'chestCore');
}

// Sparse crown/side aura only.
while (particles.length < PARTICLE_COUNT) {
  const crown = random() < .62;
  const x = crown ? cx + (random() - .5) * .24 : cx + (random() - .5) * .78;
  const y = crown ? headCy - headRy - Math.pow(random(), 1.7) * .13 : shoulderOuterY + (random() - .72) * .13;
  add(x, y, cyan(.48), .38 + random() * .3, .82, 'aura');
}

fs.writeFileSync(output, JSON.stringify({ version: 1, particles }, null, 0) + '\n');
console.log(`${particles.length} particles -> ${output}`);
