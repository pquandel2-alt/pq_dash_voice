/**
 * ParticleScene: WebGL-rendered 3D particle system forming an abstract holographic
 * humanoid AI bust (head + neck + shoulders + upper chest).
 *
 * Architecture (see REGION below):
 * - HEAD / FACE / NECK / SHOULDER_L / SHOULDER_R / CHEST  → structural silhouette
 * - FLOW                                                  → glowing energy lines across
 *                                                            forehead→temple→cheek→neck and
 *                                                            chest→shoulders, rendered as
 *                                                            densely-sampled point chains
 * - FACE_CORE / CHEST_CORE                                → soft volumetric energy clusters
 * - AMBIENT                                                → free-floating particles around
 *                                                            the figure
 *
 * All regions share ONE THREE.Points draw call with a custom ShaderMaterial (radial-falloff
 * "glow dot" fragment shader, additive blending). Per-particle region/path/seed data is
 * static (uploaded once); only the position buffer and a handful of uniforms change per
 * frame, which keeps this cheap enough for the target device (Huawei MatePad DBY-W09) even
 * at 18k particles — no per-particle object allocation happens in the render loop.
 *
 * States: IDLE, ASSEMBLING, LISTENING, THINKING, SPEAKING, SUCCESS, ERROR
 * (state machine contract unchanged — see setState()/setAudioLevel() and
 * ParticleSceneInterface.kt / ParticleAssistantController.kt on the Kotlin side).
 */

const REGION = {
    HEAD: 0,
    FACE: 1,
    NECK: 2,
    SHOULDER_L: 3,
    SHOULDER_R: 4,
    CHEST: 5,
    FLOW: 6,
    FACE_CORE: 7,
    CHEST_CORE: 8,
    AMBIENT: 9,
};

// Assembly is hierarchical: each region starts/finishes moving into place within its own
// window (fraction of the total assembly duration), so the figure builds up in stages
// (ambient haze → rough silhouette → face/neck detail → energy lines → glowing cores)
// instead of every particle moving in lockstep.
const REGION_WINDOW = {
    [REGION.AMBIENT]: [0.00, 0.32],
    [REGION.HEAD]: [0.08, 0.55],
    [REGION.SHOULDER_L]: [0.10, 0.55],
    [REGION.SHOULDER_R]: [0.10, 0.55],
    [REGION.CHEST]: [0.12, 0.58],
    [REGION.NECK]: [0.40, 0.75],
    [REGION.FACE]: [0.42, 0.78],
    [REGION.FLOW]: [0.55, 0.88],
    [REGION.FACE_CORE]: [0.78, 1.00],
    [REGION.CHEST_CORE]: [0.80, 1.00],
};

// Head profile: half-width (x) / half-depth (z) as a fraction of the maximum, keyed by
// normalized height h (1 = crown, -1 = chin). This is what replaces the old constant-radius
// sphere — width/depth now genuinely depend on height, giving a skull → temple → cheekbone →
// jaw → chin taper instead of a ball.
const HEAD_PROFILE = [
    { h: 1.00, rx: 0.24, rz: 0.30 },
    { h: 0.86, rx: 0.66, rz: 0.68 },
    { h: 0.58, rx: 0.91, rz: 0.92 },
    { h: 0.15, rx: 1.00, rz: 1.00 },
    { h: -0.30, rx: 0.92, rz: 0.90 },
    { h: -0.67, rx: 0.70, rz: 0.70 },
    { h: -0.90, rx: 0.40, rz: 0.43 },
    { h: -1.00, rx: 0.20, rz: 0.28 },
];

function headProfileAt(h) {
    if (h >= HEAD_PROFILE[0].h) return HEAD_PROFILE[0];
    const last = HEAD_PROFILE[HEAD_PROFILE.length - 1];
    if (h <= last.h) return last;
    for (let k = 0; k < HEAD_PROFILE.length - 1; k++) {
        const a = HEAD_PROFILE[k], b = HEAD_PROFILE[k + 1];
        if (h <= a.h && h >= b.h) {
            const t = (a.h - h) / (a.h - b.h);
            return { rx: a.rx + (b.rx - a.rx) * t, rz: a.rz + (b.rz - a.rz) * t };
        }
    }
    return last;
}

function easeInOutCubic(t) {
    return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
}

// ──────── Color palette ────────
// Outer structure/flow: cyan / electric blue, shifting toward white-cyan for high-energy
// flow lines. Face core: warm amber/orange. Chest core: cyan-white. Ambient: darker blue-cyan.
function colorCyanStructure() {
    const c = new THREE.Color();
    c.setHSL(0.54 + (Math.random() - 0.5) * 0.035, 0.84 + Math.random() * 0.14, 0.43 + Math.random() * 0.12);
    return c;
}
function colorFlow() {
    const c = new THREE.Color();
    c.setHSL(0.52 + (Math.random() - 0.5) * 0.025, 0.82 + Math.random() * 0.16, 0.50 + Math.random() * 0.12);
    return c;
}
function colorFaceCore(intensity = Math.random()) {
    const c = new THREE.Color();
    c.setHSL(0.065 + intensity * 0.035, 0.96 - intensity * 0.10, 0.43 + intensity * 0.23);
    return c;
}
function colorNeckEnergy() {
    const c = new THREE.Color();
    c.setHSL(0.09 + Math.random() * 0.04, 0.68 + Math.random() * 0.2, 0.52 + Math.random() * 0.18);
    return c;
}
function colorChestCore() {
    const c = new THREE.Color();
    c.setHSL(0.52 + (Math.random() - 0.5) * 0.025, 0.78 + Math.random() * 0.18, 0.60 + Math.random() * 0.16);
    return c;
}
function colorAmbient() {
    const c = new THREE.Color();
    c.setHSL(0.55 + (Math.random() - 0.5) * 0.06, 0.68 + Math.random() * 0.20, 0.30 + Math.random() * 0.18);
    return c;
}

// ──────── Shaders ────────
// Region/energy modulation lives in the vertex shader (per-particle size & alpha); the
// fragment shader turns every point into a soft radial "glow dot" instead of a hard square,
// which is what gives the multi-layer glow look without a separate bloom post-process pass.
const VERTEX_SHADER = `
attribute vec3 aColor;
attribute float aSize;
attribute vec3 aMeta; // x = regionId, y = pathT (flow lines only, else -1), z = noiseSeed 0..1

uniform float uTime;
uniform float uPixelRatio;
uniform float uSizeScale;
uniform float uHeadEnergy;
uniform float uFaceCoreEnergy;
uniform float uChestCoreEnergy;
uniform float uAudioLevel;
uniform float uGlowPass;

varying vec3 vColor;
varying float vAlpha;
varying float vRegion;

void main() {
    vRegion = aMeta.x;
    float pathT = aMeta.y;
    float seed = aMeta.z;

    float twinkle = 0.85 + 0.15 * sin(uTime * (0.6 + seed) + seed * 6.2831);
    float size = aSize;
    float alpha = 1.0;

    if (vRegion == 7.0) { // FACE_CORE
        float pulse = 0.7 + 0.3 * sin(uTime * 1.3 + seed * 6.2831);
        size *= (1.0 + uFaceCoreEnergy * 0.9) * pulse;
        alpha *= 0.5 + 0.5 * pulse;
    } else if (vRegion == 8.0) { // CHEST_CORE
        float pulse = 0.7 + 0.3 * sin(uTime * 1.0 + seed * 6.2831 + 1.5);
        size *= (1.0 + uChestCoreEnergy * 0.8) * pulse;
        alpha *= 0.45 + 0.45 * pulse;
    } else if (vRegion == 6.0) { // FLOW — traveling glow pulse along the path
        float wave = sin(pathT * 12.0 - uTime * 1.8 + seed * 6.2831);
        float glow = smoothstep(0.5, 1.0, wave);
        alpha *= 0.30 + 0.70 * glow;
        size *= 0.75 + 0.7 * glow;
    } else if (vRegion == 9.0) { // AMBIENT
        alpha *= 0.35 * twinkle;
    } else { // HEAD / FACE / NECK / SHOULDER / CHEST structure
        size *= (1.0 + uHeadEnergy * 0.25) * twinkle;
        alpha *= 0.72 + 0.28 * twinkle;
    }

    alpha *= 0.75 + 0.25 * uAudioLevel;
    if (uGlowPass > 0.5) {
        float glowScale = vRegion == 9.0 ? 1.8 : (vRegion == 7.0 || vRegion == 8.0 ? 2.8 : 2.5);
        size *= glowScale;
        float glowAlpha = 0.055;
        if (vRegion == 0.0 || vRegion == 3.0 || vRegion == 4.0) glowAlpha = 0.095;
        if (vRegion == 7.0 || vRegion == 8.0) glowAlpha = 0.060;
        if (vRegion == 9.0) glowAlpha = 0.040;
        alpha *= glowAlpha;
    }
    vColor = aColor;
    vAlpha = clamp(alpha, 0.0, 1.0);

    vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
    gl_PointSize = size * uPixelRatio * uSizeScale / -mvPosition.z;
    gl_Position = projectionMatrix * mvPosition;
}
`;

const FRAGMENT_SHADER = `
uniform float uErrorFlash;
uniform float uGlowPass;
varying vec3 vColor;
varying float vAlpha;
varying float vRegion;

void main() {
    vec2 uv = gl_PointCoord - vec2(0.5);
    float d = length(uv) * 2.0;
    if (uGlowPass > 0.5) {
        float halo = exp(-d * d * 2.8) * vAlpha;
        if (halo < 0.008) discard;
        gl_FragColor = vec4(vColor, halo);
        return;
    }
    float core = smoothstep(1.0, 0.0, d);
    float hot = smoothstep(0.35, 0.0, d) * 0.55;
    float alpha = core * vAlpha;
    if (alpha < 0.015) discard;

    float hotStrength = vRegion == 7.0 ? 0.32 : 0.75;
    vec3 col = vColor + hot * hotStrength;
    if (vRegion == 0.0 || vRegion == 1.0) {
        col = mix(col, vec3(1.0, 0.28, 0.16), uErrorFlash * 0.55);
    }
    gl_FragColor = vec4(col, alpha);
}
`;

class ParticleScene {
    static QUALITY_PRESETS = { LOW: 6000, MEDIUM: 12000, HIGH: 18000 };

    constructor(canvasElement) {
        this.canvas = canvasElement;
        this.isRunning = false;
        this.isPaused = false;

        this.scene = new THREE.Scene();
        this.scene.background = new THREE.Color(0x000000);

        const w = window.innerWidth;
        const h = window.innerHeight;
        this.camera = new THREE.PerspectiveCamera(45, w / h, 0.1, 10000);
        this.camera.position.z = 500;

        this.renderer = new THREE.WebGLRenderer({ canvas: canvasElement, antialias: true, alpha: false });
        this.renderer.setSize(w, h);
        this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));

        this.renderer.domElement.addEventListener('webglcontextlost', (e) => {
            e.preventDefault();
            this.isRunning = false;
            this.log('WebGL context lost');
        }, false);
        this.renderer.domElement.addEventListener('webglcontextrestored', () => {
            this.log('WebGL context restored');
            this.start();
        }, false);

        this.currentState = 'IDLE';
        this.audioLevel = 0;
        this.elapsedTime = 0;
        this.frameCount = 0;
        this.burstT = 0; // SUCCESS outward pulse envelope (1 → 0)
        this.errorFlash = 0; // ERROR warm-flash envelope (1 → 0)
        this.headEnergyCur = 0;
        this.faceCoreEnergyCur = 0.4;
        this.chestCoreEnergyCur = 0.35;

        // Typed-array particle store — avoids per-particle objects/GC pressure in the
        // render loop. Populated by initializeParticles().
        this.particleCountActual = 0;
        this.positions = null;
        this.startPositions = null;
        this.targetPositions = null;
        this.meta = null; // [region, pathT, noiseSeed] per particle
        this.particleGeometry = null;
        this.particleSystem = null;
        this.glowSystem = null;
        this.layout = null; // anatomical constants from computeLayout()
        this.referenceImage = null;

        this.config = {
            quality: 'AUTO',
            particleCount: ParticleScene.QUALITY_PRESETS.MEDIUM,
            assemblyDurationMs: 4200,
            animationSpeedMultiplier: 1.0,
            assemblyEnabled: true,
            idleScale: 1.0,
            headRadius: 80,
            debug: false,
        };

        this.perfHistory = [];
        this.perfLastTime = performance.now();
        this.lastUpdateTime = performance.now();
        this.autoDowngraded = false;
        this.lastFrameTime = performance.now();

        window.addEventListener('resize', () => this.onWindowResize());
    }

    /** Muss vor start() aufgerufen werden. opts: {quality, animationSpeedMultiplier, assemblyEnabled, debug} */
    configure(opts = {}) {
        const quality = opts.quality || 'AUTO';
        this.config.quality = quality;
        this.config.particleCount = quality === 'AUTO'
            ? this.autoDetectParticleCount()
            : (ParticleScene.QUALITY_PRESETS[quality] || ParticleScene.QUALITY_PRESETS.MEDIUM);
        this.config.animationSpeedMultiplier = opts.animationSpeedMultiplier > 0 ? opts.animationSpeedMultiplier : 1.0;
        this.config.assemblyEnabled = opts.assemblyEnabled !== false;
        this.config.debug = opts.debug === true;
        this.log(`Particle quality: ${quality} (${this.config.particleCount} particles), ` +
            `speed=${this.config.animationSpeedMultiplier}x, assembly=${this.config.assemblyEnabled}`);
    }

    setReferenceImage(imageElement) {
        this.referenceImage = imageElement;
    }

    syncReferenceVisual() {
        // The reference image is input data only. The visible result always stays WebGL,
        // so it remains animated and cannot disappear when WebView evicts an image layer.
        if (this.referenceImage) this.referenceImage.style.display = 'none';
        this.canvas.style.opacity = '1';
        if (this.glowSystem) this.glowSystem.visible = true;
    }

    /** Grobe Geräte-Heuristik für AUTO-Qualität: CPU-Kerne + effektive Pixelzahl. */
    autoDetectParticleCount() {
        const cores = navigator.hardwareConcurrency || 4;
        const pixelRatio = Math.min(window.devicePixelRatio || 1, 1.5);
        const pixels = window.innerWidth * window.innerHeight * pixelRatio;
        if (cores <= 2 || pixels > 3000000) return ParticleScene.QUALITY_PRESETS.LOW;
        if (cores <= 4 || pixels > 1500000) return ParticleScene.QUALITY_PRESETS.MEDIUM;
        return ParticleScene.QUALITY_PRESETS.HIGH;
    }

    start() {
        if (this.isRunning) return;
        this.isRunning = true;
        this.isPaused = false;
        this.log('ParticleScene initialized');
        this.layout = this.computeLayout();
        this.frameCamera();
        this.generateTargetGeometry();
        this.initializeParticles();
        this.lastUpdateTime = performance.now();
        this.animate();
    }

    pause() {
        this.isPaused = true;
        this.log('ParticleScene paused');
    }

    resume() {
        if (!this.isRunning) return;
        this.isPaused = false;
        // Do not count time spent paused as animation time.
        this.lastUpdateTime = performance.now();
        this.log('ParticleScene resumed');
        this.animate();
    }

    reset() {
        this.log('Screensaver state → ASSEMBLING (scene reset)');
        this.elapsedTime = 0;
        this.frameCount = 0;
        this.audioLevel = 0;
        this.burstT = 0;
        this.errorFlash = 0;
        this.assemblyCompleteLogged = false;
        if (!this.particleSystem) {
            this.initializeParticles();
            return;
        }
        // Fresh spawn positions every time — never just re-fade an already-assembled figure.
        for (let i = 0; i < this.particleCountActual; i++) {
            const s = this.randomStartPosition();
            const idx = i * 3;
            this.startPositions[idx] = s.x;
            this.startPositions[idx + 1] = s.y;
            this.startPositions[idx + 2] = s.z;
            this.positions[idx] = s.x;
            this.positions[idx + 1] = s.y;
            this.positions[idx + 2] = s.z;
        }
        this.particleGeometry.attributes.position.needsUpdate = true;
    }

    setState(stateName) {
        if (this.currentState === stateName) return;
        this.currentState = stateName;
        this.log(`Particle state: ${stateName}`);

        if (stateName === 'ASSEMBLING' && !this.config.assemblyEnabled) {
            // Assembly-animation disabled in settings → snap straight to the finished figure.
            this.positions.set(this.targetPositions);
            if (this.particleGeometry) this.particleGeometry.attributes.position.needsUpdate = true;
            this.currentState = 'IDLE';
            this.syncReferenceVisual();
            this.log('Assembly completed (instant, animation disabled)');
            window.particleInterface?.setState('IDLE');
            return;
        }

        this.syncReferenceVisual();

        switch (stateName) {
            case 'SUCCESS':
                this.playSuccessAnimation();
                break;
            case 'ERROR':
                this.playErrorAnimation();
                break;
            default:
                break; // LISTENING/THINKING/SPEAKING/ASSEMBLING/IDLE handled per-frame in update()
        }
    }

    setAudioLevel(level) {
        this.audioLevel = Math.max(0, Math.min(1, level));
    }

    // ──────── Private: Layout & Geometry ────────

    /**
     * Anatomical layout constants for the bust, in world units, scaled by
     * s = headRadius / 80 so config.headRadius stays the single "figure size" knob.
     * Stacked bottom-up: chest → neck → head, with shoulders branching off the chest top.
     */
    computeLayout() {
        const s = this.config.headRadius / 80;
        const chestTopY = 32 * s;
        const chestBottomY = -88 * s;
        const neckBottomY = 28 * s;
        const neckTopY = 102 * s;
        const headHalfHeight = 78 * s;
        const headBottomY = neckTopY - 2 * s;
        const headCenterY = headBottomY + headHalfHeight;
        const headTopY = headCenterY + headHalfHeight;
        const shoulderCenterY = chestTopY;

        return {
            s,
            headCenterY,
            headHalfHeight,
            headHalfWidthMax: 62 * s,
            headHalfDepthMax: 52 * s,
            headTopY,
            headBottomY,
            neckTopY,
            neckBottomY,
            neckHalfWidth: 25 * s,
            neckHalfDepth: 20 * s,
            shoulderCenterY,
            shoulderReachX: 190 * s,
            shoulderDropY: 38 * s,
            chestTopY,
            chestBottomY,
            chestHalfWidthTop: 150 * s,
            chestHalfWidthBottom: 90 * s,
            chestHalfDepth: 55 * s,
            chestCoreY: -5 * s,
            chestCoreZ: 48 * s,
            faceCoreY: headCenterY - headHalfHeight * 0.20,
            faceCoreZ: 53 * s,
        };
    }

    /** Positions/sizes the camera so the whole bust fills ~80% of the screen height. */
    frameCamera() {
        const L = this.layout;
        const figureHeight = L.headTopY - L.chestBottomY;
        const figureCenterY = (L.headTopY + L.chestBottomY) / 2;
        const fillFraction = 0.82;
        const fovRad = THREE.MathUtils.degToRad(this.camera.fov);
        const distance = figureHeight / (2 * Math.tan(fovRad / 2) * fillFraction);
        this.camera.position.set(0, figureCenterY, THREE.MathUtils.clamp(distance, 260, 1400));
        this.camera.updateProjectionMatrix();
    }

    /**
     * Builds the full point cloud (plain array, setup-time only) across all regions, then
     * hands off to initializeParticles() to pack it into typed arrays / GPU buffers.
     */
    generateTargetGeometry() {
        const L = this.layout;
        const budget = this.config.particleCount;

        if (this.referenceImage?.complete && this.referenceImage.naturalWidth > 0) {
            this.targetPoints = this.generateReferenceParticles(budget, L);
            return;
        }

        const pts = [];

        const faceCoreBudget = Math.round(budget * 0.075);
        const chestCoreBudget = Math.round(budget * 0.004);
        const coreBudget = faceCoreBudget + chestCoreBudget;
        const remaining = budget - coreBudget;
        const structuralBudget = Math.round(remaining * 0.56);
        const flowBudget = Math.round(remaining * 0.29);
        const ambientBudget = remaining - structuralBudget - flowBudget;

        const headBudget = Math.round(structuralBudget * 0.45);
        const neckBudget = Math.round(structuralBudget * 0.10);
        const shoulderBudget = Math.round(structuralBudget * 0.20);
        const chestBudget = structuralBudget - headBudget - neckBudget - shoulderBudget;

        this.genHeadSurface(pts, headBudget, L);
        this.genNeck(pts, neckBudget, L);
        this.genShoulder(pts, Math.round(shoulderBudget / 2), L, -1);
        this.genShoulder(pts, shoulderBudget - Math.round(shoulderBudget / 2), L, 1);
        this.genChest(pts, chestBudget, L);
        this.genFlowPaths(pts, flowBudget, L);
        this.genFaceCore(pts, faceCoreBudget, L);
        this.genVolumetricCluster(pts, chestCoreBudget, REGION.CHEST_CORE, 0, L.chestCoreY, L.chestCoreZ, 4 * L.s, 6 * L.s, 3 * L.s, colorChestCore, 3.0);
        this.genAmbient(pts, ambientBudget, L);

        // Pad/trim to the exact configured budget (rounding remainders only).
        while (pts.length < budget) {
            const src = pts[Math.floor(Math.random() * pts.length)];
            pts.push({ ...src, x: src.x + (Math.random() - 0.5) * 3, y: src.y + (Math.random() - 0.5) * 3, z: src.z + (Math.random() - 0.5) * 3 });
        }
        this.targetPoints = pts.slice(0, budget);
    }

    /**
     * Samples the supplied artwork itself, so assembly particles converge on the exact
     * silhouette and palette instead of on a hand-authored anatomical approximation.
     */
    generateReferenceParticles(budget, L) {
        const sampleCanvas = document.createElement('canvas');
        sampleCanvas.width = 360;
        sampleCanvas.height = 640;
        const ctx = sampleCanvas.getContext('2d', { willReadFrequently: true });
        ctx.drawImage(this.referenceImage, 0, 0, sampleCanvas.width, sampleCanvas.height);
        const pixels = ctx.getImageData(0, 0, sampleCanvas.width, sampleCanvas.height).data;
        const candidates = [];

        for (let y = 0; y < sampleCanvas.height; y++) {
            for (let x = 0; x < sampleCanvas.width; x++) {
                const offset = (y * sampleCanvas.width + x) * 4;
                const r = pixels[offset], g = pixels[offset + 1], b = pixels[offset + 2];
                const peak = Math.max(r, g, b);
                const chroma = peak - Math.min(r, g, b);
                // Reject the JPEG-black background but retain the dim blue particle haze.
                if (peak >= 18 && (chroma >= 8 || peak >= 70)) candidates.push({ x, y, r, g, b, peak });
            }
        }

        const worldHeight = (L.headTopY - L.chestBottomY) * 1.18;
        const centerY = (L.headTopY + L.chestBottomY) * 0.5;
        const worldWidth = worldHeight * (this.referenceImage.naturalWidth / this.referenceImage.naturalHeight) * 1.48;
        const pts = [];
        const goldenStep = 0.6180339887498949;

        for (let i = 0; i < budget; i++) {
            const candidateIndex = Math.floor(((i * goldenStep) % 1) * candidates.length);
            const p = candidates[candidateIndex];
            const u = p.x / (sampleCanvas.width - 1);
            const v = p.y / (sampleCanvas.height - 1);
            const intensity = p.peak / 255;
            const warm = p.r > p.b * 1.18 && p.r > p.g * 1.04;
            const region = warm
                ? REGION.FACE_CORE
                : (intensity < 0.20 ? REGION.AMBIENT : (v < 0.57 ? REGION.HEAD : REGION.CHEST));

            pts.push({
                x: (u - 0.5) * worldWidth,
                y: centerY + (0.5 - v) * worldHeight,
                z: (24 + intensity * 30 + (Math.random() - 0.5) * 4) * L.s,
                region,
                color: new THREE.Color(p.r / 255, p.g / 255, p.b / 255),
                size: (1.15 + intensity * 1.9) * L.s,
                pathT: v,
            });
        }
        return pts;
    }

    genHeadSurface(pts, budget, L) {
        // Stacked camera-facing scan lines keep the faceless oval readable instead of
        // turning it into a transparent wireframe globe.
        const haloBudget = Math.round(budget * 0.20);
        const bandBudget = budget - haloBudget;
        const rings = Math.max(26, Math.round(Math.sqrt(bandBudget * 0.55)));
        const segments = Math.max(20, Math.round(bandBudget / rings));
        for (let i = 0; i < rings; i++) {
            const u = i / (rings - 1);
            const h = 1 - 2 * u;
            const prof = headProfileAt(h);
            const y = L.headCenterY + h * L.headHalfHeight;
            for (let j = 0; j < segments; j++) {
                const xNorm = (j / (segments - 1)) * 2 - 1;
                const x = xNorm * prof.rx * L.headHalfWidthMax;
                const z = Math.sqrt(Math.max(0, 1 - xNorm * xNorm)) * prof.rz * L.headHalfDepthMax;
                const color = colorCyanStructure();
                // Keep the cyan scan lines visible across the warm core without bleaching it.
                if (Math.abs(h + 0.08) < 0.58 && Math.abs(xNorm) < 0.72) color.multiplyScalar(0.42);
                pts.push({ x, y, z, region: REGION.HEAD, color, size: 1.65 * L.s, pathT: -1 });
            }
        }

        // Several slightly offset silhouette passes create the bright electric-cyan rim.
        const passes = 4;
        const perPass = Math.max(40, Math.floor(haloBudget / passes));
        for (let pass = 0; pass < passes; pass++) {
            for (const side of [-1, 1]) {
                for (let i = 0; i < Math.floor(perPass / 2); i++) {
                    const h = 1 - 2 * (i / (Math.floor(perPass / 2) - 1));
                    const prof = headProfileAt(h);
                    const passDistance = Math.abs(pass - (passes - 1) / 2);
                    const spread = (pass - (passes - 1) / 2) * 1.05 * L.s;
                    pts.push({
                        x: side * (prof.rx * L.headHalfWidthMax + spread),
                        y: L.headCenterY + h * L.headHalfHeight,
                        z: 14 * L.s + pass * 0.35 * L.s,
                        region: REGION.HEAD,
                        color: colorFlow(),
                        size: (2.9 - passDistance * 0.18) * L.s,
                        pathT: -1,
                    });
                }
            }
        }
    }

    /** Large warm energy field made from horizontal contour strands; no facial features. */
    genFaceCore(pts, budget, L) {
        const rings = 34;
        const lineBudget = Math.round(budget * 0.82);
        const perRing = Math.max(12, Math.floor(lineBudget / rings));
        const halfH = 34 * L.s;
        const halfW = 37 * L.s;
        for (let row = 0; row < rings; row++) {
            const h = 1 - (row / (rings - 1)) * 2;
            const rowWidth = halfW * Math.sqrt(Math.max(0, 1 - h * h));
            const y = L.faceCoreY + h * halfH;
            for (let j = 0; j < perRing; j++) {
                const xNorm = (j / (perRing - 1)) * 2 - 1;
                const radial = Math.sqrt(Math.max(0, 1 - xNorm * xNorm - h * h * 0.45));
                const intensity = radial * (0.72 + Math.random() * 0.28);
                pts.push({
                    x: xNorm * rowWidth,
                    y: y + Math.sin(xNorm * Math.PI) * 1.4 * L.s,
                    z: L.faceCoreZ + radial * 2 * L.s,
                    region: REGION.FACE_CORE,
                    color: colorFaceCore(intensity),
                    size: (1.75 + intensity * 0.75) * L.s,
                    pathT: row / (rings - 1),
                });
            }
        }
        const cloudBudget = Math.max(0, budget - rings * perRing - 2);
        this.genVolumetricCluster(
            pts, cloudBudget, REGION.FACE_CORE,
            0, L.faceCoreY, L.faceCoreZ - 2 * L.s,
            28 * L.s, 30 * L.s, 5 * L.s,
            colorFaceCore, 2.2 * L.s
        );
        // A restrained central spark keeps the core orange instead of turning into a
        // white lamp on lower-density/mobile displays.
        for (const size of [24, 15]) {
            pts.push({
                x: 0,
                y: L.faceCoreY,
                z: L.faceCoreZ + 5 * L.s,
                region: REGION.FACE_CORE,
                color: colorFaceCore(0.72),
                size: size * L.s,
                pathT: 0.5,
            });
        }
    }

    /** Subtle front-facing feature lines: brow, nose ridge, cheeks, jawline, mouth line. */
    genFaceStructure(pts, budget, L) {
        const s = L.s;
        const addChain = (points3d, count, sizeMul = 1) => {
            const curve = new THREE.CatmullRomCurve3(points3d.map(p => new THREE.Vector3(p[0], p[1], p[2])));
            const sampled = curve.getSpacedPoints(Math.max(2, count - 1));
            for (const p of sampled) {
                pts.push({
                    x: p.x + (Math.random() - 0.5) * 2.5 * s,
                    y: p.y + (Math.random() - 0.5) * 2.5 * s,
                    z: p.z + (Math.random() - 0.5) * 2.5 * s,
                    region: REGION.FACE,
                    color: colorCyanStructure(),
                    size: 1.9 * s * sizeMul,
                    pathT: -1,
                });
            }
        };

        const featureCount = 6; // brow(L/R), cheek(L/R), jaw(L/R) share budget; nose+mouth fixed small
        const perFeature = Math.max(4, Math.round((budget * 0.8) / featureCount));

        for (const side of [-1, 1]) {
            const browZ = headProfileAt(0.35).rz * L.headHalfDepthMax * 0.95;
            addChain([
                [side * 14 * s, L.headCenterY + L.headHalfHeight * 0.38, browZ],
                [side * 32 * s, L.headCenterY + L.headHalfHeight * 0.34, browZ * 0.96],
                [side * 46 * s, L.headCenterY + L.headHalfHeight * 0.26, browZ * 0.88],
            ], perFeature);

            const cheekZ = headProfileAt(-0.15).rz * L.headHalfDepthMax * 0.9;
            addChain([
                [side * 30 * s, L.headCenterY - L.headHalfHeight * 0.02, cheekZ],
                [side * 44 * s, L.headCenterY - L.headHalfHeight * 0.18, cheekZ * 0.92],
                [side * 36 * s, L.headCenterY - L.headHalfHeight * 0.34, cheekZ * 0.8],
            ], perFeature);

            const jawPts = [];
            for (let t = 0; t <= 1; t += 0.34) {
                const h = -0.35 - t * 0.6;
                const prof = headProfileAt(h);
                jawPts.push([side * prof.rx * L.headHalfWidthMax * 0.9, L.headCenterY + h * L.headHalfHeight, prof.rz * L.headHalfDepthMax * 0.95]);
            }
            addChain(jawPts, perFeature, 0.85);
        }

        // Nose ridge: centered, proud of the base surface, brow → tip → philtrum.
        const noseZ = headProfileAt(0.1).rz * L.headHalfDepthMax;
        addChain([
            [0, L.headCenterY + L.headHalfHeight * 0.32, noseZ * 0.9],
            [0, L.headCenterY + L.headHalfHeight * 0.10, noseZ * 1.18],
            [0, L.headCenterY - L.headHalfHeight * 0.05, noseZ * 1.22],
            [0, L.headCenterY - L.headHalfHeight * 0.14, noseZ * 1.05],
        ], Math.max(4, Math.round(budget * 0.1)), 0.9);

        // Mouth line: short, subtle, no eye-ring equivalent.
        const mouthZ = headProfileAt(-0.55).rz * L.headHalfDepthMax * 0.98;
        addChain([
            [-16 * s, L.headCenterY - L.headHalfHeight * 0.55, mouthZ],
            [0, L.headCenterY - L.headHalfHeight * 0.57, mouthZ * 1.03],
            [16 * s, L.headCenterY - L.headHalfHeight * 0.55, mouthZ],
        ], Math.max(4, Math.round(budget * 0.1)), 0.8);

        // Eye-hollow hint: sparse, slightly recessed points — deliberately NOT a ring/circle.
        for (const side of [-1, 1]) {
            for (let i = 0; i < 6; i++) {
                const z = headProfileAt(0.22).rz * L.headHalfDepthMax * (0.7 + Math.random() * 0.15);
                pts.push({
                    x: side * (26 + Math.random() * 16) * s,
                    y: L.headCenterY + L.headHalfHeight * (0.20 + Math.random() * 0.08),
                    z,
                    region: REGION.FACE,
                    color: colorCyanStructure(),
                    size: 1.5 * s,
                    pathT: -1,
                });
            }
        }
    }

    genNeck(pts, budget, L) {
        const strands = 18;
        const perStrand = Math.max(8, Math.round(budget / strands));
        for (let strand = 0; strand < strands; strand++) {
            const xNorm = (strand / (strands - 1)) * 2 - 1;
            const absX = Math.abs(xNorm);
            const curve = new THREE.CatmullRomCurve3([
                new THREE.Vector3(xNorm * 13 * L.s, L.neckTopY + 3 * L.s, 42 * L.s),
                new THREE.Vector3(xNorm * (11 + absX * 4) * L.s, L.neckTopY - 29 * L.s, 40 * L.s),
                new THREE.Vector3(xNorm * (16 + absX * 7) * L.s, L.neckBottomY + 18 * L.s, 43 * L.s),
                new THREE.Vector3(xNorm * (30 + absX * 8) * L.s, L.neckBottomY, 44 * L.s),
            ]);
            const sampled = curve.getSpacedPoints(perStrand - 1);
            sampled.forEach((p, i) => {
                const warm = absX < 0.34;
                pts.push({
                    x: p.x + Math.sin(i * 0.24 + xNorm * 2) * 0.8 * L.s,
                    y: p.y + Math.sin(strand * 1.71 + i * 0.31) * 1.1 * L.s,
                    z: p.z,
                    region: REGION.NECK,
                    color: warm ? colorNeckEnergy() : colorCyanStructure(),
                    size: (warm ? 1.35 : 1.95) * L.s,
                    pathT: i / (perStrand - 1),
                });
            });
        }
    }

    genShoulder(pts, budget, L, side) {
        const region = side < 0 ? REGION.SHOULDER_L : REGION.SHOULDER_R;
        const layers = 5;
        const perLayer = Math.max(12, Math.round(budget / layers));
        for (let layer = 0; layer < layers; layer++) {
            const depth = layer / (layers - 1);
            const offset = (depth - 0.5) * 5 * L.s;
            const curve = new THREE.CatmullRomCurve3([
                new THREE.Vector3(side * 18 * L.s, L.headBottomY - 9 * L.s + offset, 38 * L.s),
                new THREE.Vector3(side * 31 * L.s, L.neckTopY - 37 * L.s + offset, 42 * L.s),
                new THREE.Vector3(side * 76 * L.s, L.shoulderCenterY + 24 * L.s + offset, 36 * L.s),
                new THREE.Vector3(side * 141 * L.s, L.shoulderCenterY + 3 * L.s + offset, 22 * L.s),
                new THREE.Vector3(side * L.shoulderReachX, L.shoulderCenterY - 38 * L.s + offset * 0.25, 7 * L.s),
            ]);
            const sampled = curve.getSpacedPoints(perLayer - 1);
            sampled.forEach((p, i) => {
                pts.push({
                    x: p.x,
                    y: p.y,
                    z: p.z,
                    region,
                    color: colorCyanStructure(),
                    size: (layer === 0 || layer === layers - 1 ? 2.8 : 1.9) * L.s,
                    pathT: i / (perLayer - 1),
                });
            });
        }
    }

    genChest(pts, budget, L) {
        const fansPerSide = 25;
        const perFan = Math.max(12, Math.floor(budget / (fansPerSide * 2)));
        for (const side of [-1, 1]) {
            for (let lane = 0; lane < fansPerSide; lane++) {
                const n = lane / (fansPerSide - 1);
                const curve = new THREE.CatmullRomCurve3([
                    new THREE.Vector3(side * (3 + n * 78) * L.s, L.chestBottomY + n * 43 * L.s, 18 * L.s),
                    new THREE.Vector3(side * (4 + n * 67) * L.s, L.chestCoreY - 49 * L.s, 32 * L.s),
                    new THREE.Vector3(side * (9 + n * 53) * L.s, L.chestCoreY - 8 * L.s, 48 * L.s),
                    new THREE.Vector3(side * (40 + n * 78) * L.s, L.shoulderCenterY + (8 + n * 10) * L.s, 38 * L.s),
                    new THREE.Vector3(side * (76 + n * 103) * L.s, L.shoulderCenterY - (8 + n * 36) * L.s, 20 * L.s),
                ]);
                const phase = (lane * 0.37) % 1;
                const sampled = [];
                for (let i = 0; i < perFan; i++) {
                    sampled.push(curve.getPointAt(Math.min(1, (i + phase) / (perFan - 1))));
                }
                sampled.forEach((p, i) => pts.push({
                    x: p.x + Math.sin(lane * 1.9 + i * 0.7) * 0.28 * L.s,
                    y: p.y + Math.cos(lane * 1.4 + i * 0.6) * 0.28 * L.s,
                    z: p.z,
                    region: REGION.CHEST,
                    color: colorFlow(),
                    size: (1.85 + (1 - n) * 0.42) * L.s,
                    pathT: i / (perFan - 1),
                }));
            }
        }
    }

    addFlowPath(pts, keypoints, count, L, colorFn = colorFlow, size = 1.9) {
        const curve = new THREE.CatmullRomCurve3(keypoints.map(p => new THREE.Vector3(p[0], p[1], p[2])));
        const sampled = curve.getSpacedPoints(Math.max(2, count - 1));
        sampled.forEach((p, i) => {
            pts.push({
                x: p.x,
                y: p.y,
                z: p.z,
                region: REGION.FLOW,
                color: colorFn(),
                size: size * L.s,
                pathT: i / Math.max(1, sampled.length - 1),
                });
        });
    }

    genFlowPaths(pts, budget, L) {
        const s = L.s;
        const pathSpecs = [];
        for (const side of [-1, 1]) {
            // Luminous filaments sweep down the face and converge into the warm neck.
            for (let lane = 0; lane < 4; lane++) {
                pathSpecs.push({ points: [
                    [side * (5 + lane * 4) * s, L.headCenterY + L.headHalfHeight * 0.62, 55 * s],
                    [side * (28 + lane * 5) * s, L.headCenterY + L.headHalfHeight * 0.16, 56 * s],
                    [side * (48 + lane * 2) * s, L.headCenterY - L.headHalfHeight * 0.30, 44 * s],
                    [side * (20 + lane) * s, L.neckTopY - 10 * s, 40 * s],
                    [side * (6 + lane * 2) * s, L.chestCoreY + 4 * s, L.chestCoreZ],
                ], color: lane < 2 ? colorNeckEnergy : colorFlow, size: lane < 2 ? 2.1 : 1.9 });
            }

            // A few nested chest streams merge with the fan instead of forming a
            // detached second shoulder wing.
            for (let lane = 0; lane < 5; lane++) {
                const n = lane / 4;
                pathSpecs.push({ points: [
                    [side * (3 + lane) * s, L.chestCoreY, L.chestCoreZ + 2 * s],
                    [side * (8 + lane * 3) * s, L.chestCoreY + (12 + lane * 2) * s, 47 * s],
                    [side * (42 + lane * 12) * s, L.shoulderCenterY + (17 - lane) * s, 39 * s],
                    [side * (105 + n * 68) * s, L.shoulderCenterY - (4 + lane * 5) * s, 16 * s],
                ], color: colorFlow, size: lane === 0 ? 2.35 : 1.75 });
            }
        }

        const perPath = Math.max(8, Math.round(budget / pathSpecs.length));
        for (const spec of pathSpecs) {
            this.addFlowPath(pts, spec.points, perPath, L, spec.color, spec.size);
        }
    }

    /** Soft volumetric particle blob (multiple depth layers) — not a solid sphere/circle. */
    genVolumetricCluster(pts, budget, region, cx, cy, cz, rx, ry, rz, colorFn, baseSize) {
        for (let i = 0; i < budget; i++) {
            // Uniform-in-volume-ish sampling: cube root of a random radius fraction + random
            // direction gives more points toward the center falling off softly outward.
            const dir = new THREE.Vector3(Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5).normalize();
            const r = Math.cbrt(Math.random());
            const x = cx + dir.x * rx * r;
            const y = cy + dir.y * ry * r;
            const z = cz + dir.z * rz * r;
            pts.push({ x, y, z, region, color: colorFn(1 - r), size: baseSize * (1.3 - 0.5 * r), pathT: -1 });
        }
    }

    genAmbient(pts, budget, L) {
        const s = L.s;
        const waveBudget = Math.round(budget * 0.52);
        const scatterBudget = budget - waveBudget;
        const waveCount = 8;
        const perWave = Math.max(8, Math.floor(waveBudget / waveCount));

        for (const side of [-1, 1]) {
            for (let lane = 0; lane < waveCount / 2; lane++) {
                for (let i = 0; i < perWave; i++) {
                    const t = i / (perWave - 1);
                    const x = side * (92 + t * 205) * s;
                    const y = (L.headCenterY + 8 * s) - t * 105 * s +
                        Math.sin(t * Math.PI * (2.4 + lane * 0.18) + lane * 0.8) * (8 + lane * 2) * s;
                    pts.push({
                        x: x + (Math.random() - 0.5) * 5 * s,
                        y: y + (Math.random() - 0.5) * 5 * s,
                        z: (-15 + Math.random() * 22) * s,
                        region: REGION.AMBIENT,
                        color: colorAmbient(),
                        size: (1.4 + Math.random() * 1.1) * s,
                        pathT: t,
                    });
                }
            }
        }

        for (let i = 0; i < scatterBudget; i++) {
            const crownParticle = Math.random() < 0.62;
            const angle = Math.random() * Math.PI * 2;
            const radius = Math.pow(Math.random(), 0.72);
            const x = crownParticle
                ? Math.cos(angle) * radius * 95 * s
                : (Math.random() - 0.5) * 430 * s;
            const y = crownParticle
                ? L.headTopY + (Math.random() * 105 - 18) * s
                : L.shoulderCenterY + (Math.random() - 0.15) * 115 * s;
            pts.push({
                x,
                y,
                z: (Math.random() - 0.5) * 100 * s,
                region: REGION.AMBIENT,
                color: colorAmbient(),
                size: (1.0 + Math.random() * 1.7) * s,
                pathT: -1,
            });
        }
    }

    // ──────── Private: Initialization ────────

    initializeParticles(settled = false) {
        if (this.particleSystem) {
            this.scene.remove(this.particleSystem);
            this.particleSystem.geometry.dispose();
            this.particleSystem.material.dispose();
        }
        if (this.glowSystem) {
            this.scene.remove(this.glowSystem);
            this.glowSystem.material.dispose();
        }

        const n = this.targetPoints.length;
        this.particleCountActual = n;
        this.positions = new Float32Array(n * 3);
        this.startPositions = new Float32Array(n * 3);
        this.targetPositions = new Float32Array(n * 3);
        this.meta = new Float32Array(n * 3);
        const colors = new Float32Array(n * 3);
        const sizes = new Float32Array(n);

        for (let i = 0; i < n; i++) {
            const p = this.targetPoints[i];
            const idx = i * 3;

            const startPos = this.randomStartPosition();
            this.startPositions[idx] = startPos.x;
            this.startPositions[idx + 1] = startPos.y;
            this.startPositions[idx + 2] = startPos.z;
            this.positions[idx] = startPos.x;
            this.positions[idx + 1] = startPos.y;
            this.positions[idx + 2] = startPos.z;

            this.targetPositions[idx] = p.x;
            this.targetPositions[idx + 1] = p.y;
            this.targetPositions[idx + 2] = p.z;

            this.meta[idx] = p.region;
            this.meta[idx + 1] = p.pathT;
            this.meta[idx + 2] = Math.random();

            colors[idx] = p.color.r;
            colors[idx + 1] = p.color.g;
            colors[idx + 2] = p.color.b;
            sizes[i] = p.size;
        }

        if (settled) {
            // A performance-tier rebuild happens while the figure is already visible.
            // Start at the new targets instead of scattering everything back into darkness.
            this.positions.set(this.targetPositions);
            this.startPositions.set(this.targetPositions);
        }

        const geometry = new THREE.BufferGeometry();
        const posAttr = new THREE.BufferAttribute(this.positions, 3);
        posAttr.setUsage(THREE.DynamicDrawUsage);
        geometry.setAttribute('position', posAttr);
        geometry.setAttribute('aColor', new THREE.BufferAttribute(colors, 3));
        geometry.setAttribute('aSize', new THREE.BufferAttribute(sizes, 1));
        geometry.setAttribute('aMeta', new THREE.BufferAttribute(this.meta, 3));
        this.particleGeometry = geometry;

        this.uniforms = {
            uTime: { value: 0 },
            uPixelRatio: { value: Math.min(window.devicePixelRatio || 1, 1.5) },
            uSizeScale: { value: this.computeSizeScale() },
            uHeadEnergy: { value: 0 },
            uFaceCoreEnergy: { value: 0.4 },
            uChestCoreEnergy: { value: 0.35 },
            uAudioLevel: { value: 0 },
            uErrorFlash: { value: 0 },
            uGlowPass: { value: 0 },
        };

        const glowUniforms = {
            ...this.uniforms,
            uGlowPass: { value: 1 },
        };

        const glowMaterial = new THREE.ShaderMaterial({
            uniforms: glowUniforms,
            vertexShader: VERTEX_SHADER,
            fragmentShader: FRAGMENT_SHADER,
            transparent: true,
            depthWrite: false,
            depthTest: false,
            blending: THREE.AdditiveBlending,
        });

        const material = new THREE.ShaderMaterial({
            uniforms: this.uniforms,
            vertexShader: VERTEX_SHADER,
            fragmentShader: FRAGMENT_SHADER,
            transparent: true,
            depthWrite: false,
            blending: THREE.AdditiveBlending,
        });

        this.glowSystem = new THREE.Points(geometry, glowMaterial);
        this.particleSystem = new THREE.Points(geometry, material);
        this.scene.add(this.glowSystem);
        this.scene.add(this.particleSystem);
        this.log(`Initialized ${n} particles (holographic humanoid bust)`);
    }

    computeSizeScale() {
        // Mirrors THREE's built-in sizeAttenuation formula so gl_PointSize stays predictable
        // in pixels regardless of the auto-framed camera distance.
        const fovRad = THREE.MathUtils.degToRad(this.camera.fov);
        return this.renderer.domElement.clientHeight / (2 * Math.tan(fovRad / 2));
    }

    randomStartPosition() {
        const mode = Math.random();
        if (mode < 0.3) {
            const side = Math.random() < 0.5 ? 'left' : 'right';
            return new THREE.Vector3(
                side === 'left' ? -450 : 450,
                (Math.random() - 0.5) * 900,
                (Math.random() - 0.5) * 450
            );
        } else if (mode < 0.6) {
            return new THREE.Vector3(
                (Math.random() - 0.5) * 700,
                (Math.random() - 0.5) * 700,
                -850 + Math.random() * 400
            );
        } else {
            return new THREE.Vector3(
                (Math.random() - 0.5) * 900,
                (Math.random() - 0.5) * 900,
                (Math.random() - 0.5) * 900
            );
        }
    }

    // ──────── Animation Loop ────────

    animate() {
        if (!this.isRunning) return;
        if (!this.isPaused) {
            this.update();
            this.render();
            this.monitorPerformance();
        }
        requestAnimationFrame(() => this.animate());
    }

    update() {
        const now = performance.now();
        const wallDt = Math.max(0, (now - this.lastUpdateTime) / 1000);
        this.lastUpdateTime = now;
        // Assembly timing follows real time, while physics remains capped after a stalled frame.
        const speed = this.config.animationSpeedMultiplier;
        const dt = Math.min(0.05, wallDt) * speed;
        this.elapsedTime += wallDt * speed * 1000;
        this.frameCount++;

        this.updateEnvelopes(dt);

        if (this.currentState === 'ASSEMBLING') {
            this.simulateAssembling(dt);
        } else {
            this.simulateSettled(dt);
        }

        this.particleGeometry.attributes.position.needsUpdate = true;

        // Frontal view stays the primary view — only a very small organic sway, no
        // continuous spin (per design: this is a face-forward holographic bust).
        this.particleSystem.rotation.y = Math.sin(this.elapsedTime * 0.00015) * THREE.MathUtils.degToRad(3);
        this.glowSystem.rotation.y = this.particleSystem.rotation.y;
    }

    /** Decays one-shot envelopes and eases the state-driven uniforms toward their targets. */
    updateEnvelopes(dt) {
        this.burstT = Math.max(0, this.burstT - dt * 1.4);
        this.errorFlash = Math.max(0, this.errorFlash - dt * 2.0);

        let headTarget = 0, faceTarget = 0.4, chestTarget = 0.35;
        switch (this.currentState) {
            case 'THINKING':
                headTarget = 1.0; faceTarget = 0.85; break;
            case 'LISTENING':
                headTarget = 0.35; faceTarget = 0.65; break;
            case 'SPEAKING':
                headTarget = 0.15; faceTarget = 0.5 + this.audioLevel * 0.5; break;
            default:
                break;
        }
        chestTarget = Math.min(1, chestTarget + this.burstT * 1.1);
        faceTarget = Math.min(1, faceTarget + this.burstT * 0.4);

        const k = Math.min(1, dt * 3);
        this.headEnergyCur += (headTarget - this.headEnergyCur) * k;
        this.faceCoreEnergyCur += (faceTarget - this.faceCoreEnergyCur) * k;
        this.chestCoreEnergyCur += (chestTarget - this.chestCoreEnergyCur) * k;

        this.uniforms.uTime.value = this.elapsedTime * 0.001;
        this.uniforms.uHeadEnergy.value = this.headEnergyCur;
        this.uniforms.uFaceCoreEnergy.value = this.faceCoreEnergyCur;
        this.uniforms.uChestCoreEnergy.value = this.chestCoreEnergyCur;
        this.uniforms.uAudioLevel.value = this.audioLevel;
        this.uniforms.uErrorFlash.value = this.errorFlash;
    }

    /** Hierarchical build-up: each region eases in within its own REGION_WINDOW slice. */
    simulateAssembling(dt) {
        const progress = Math.min(1, this.elapsedTime / this.config.assemblyDurationMs);
        const pos = this.positions, start = this.startPositions, target = this.targetPositions, meta = this.meta;
        let allDone = true;

        for (let i = 0; i < this.particleCountActual; i++) {
            const idx = i * 3;
            const region = meta[idx];
            const seed = meta[idx + 2];
            const regionWindow = REGION_WINDOW[region] || [0, 1];
            const jitter = (seed - 0.5) * 0.08;
            // The stochastic start offset must never prevent the global 100% frame from
            // completing (especially for core regions whose nominal window already ends at 1).
            const local = progress >= 1
                ? 1
                : Math.max(0, Math.min(1, (progress - (regionWindow[0] + jitter)) / (regionWindow[1] - regionWindow[0])));
            if (local < 1) allDone = false;
            const eased = easeInOutCubic(local);

            const noiseAmount = (1 - eased) * 26;
            const nx = Math.sin(seed * 900 + i) * noiseAmount;
            const ny = Math.cos(seed * 700 + i) * noiseAmount;

            pos[idx] = start[idx] + (target[idx] - start[idx]) * eased + nx;
            pos[idx + 1] = start[idx + 1] + (target[idx + 1] - start[idx + 1]) * eased + ny;
            pos[idx + 2] = start[idx + 2] + (target[idx + 2] - start[idx + 2]) * eased;
        }

        if (progress >= 1 && allDone && !this.assemblyCompleteLogged) {
            this.assemblyCompleteLogged = true;
            this.log('Assembly completed');
            this.currentState = 'IDLE';
            this.syncReferenceVisual();
            window.particleInterface?.setState('IDLE');
        }
    }

    /** Baseline motion for IDLE/LISTENING/THINKING/SPEAKING/SUCCESS/ERROR. */
    simulateSettled(dt) {
        const pos = this.positions, target = this.targetPositions, meta = this.meta;
        const L = this.layout;
        const t = this.elapsedTime;
        const state = this.currentState;
        const thinkSpin = state === 'THINKING' ? dt * 0.6 : 0;

        for (let i = 0; i < this.particleCountActual; i++) {
            const idx = i * 3;
            const region = meta[idx];
            const seed = meta[idx + 2];

            // Gentle correction back toward the settled shape (recovers from any perturbation).
            pos[idx] += (target[idx] - pos[idx]) * 0.03;
            pos[idx + 1] += (target[idx + 1] - pos[idx + 1]) * 0.03;
            pos[idx + 2] += (target[idx + 2] - pos[idx + 2]) * 0.03;

            const breathAmp = region === REGION.AMBIENT ? 0.15 : 0.45;
            pos[idx + 1] += Math.sin(t * 0.0008 + seed * 6.2831) * breathAmp * dt;

            if (region === REGION.AMBIENT) {
                // Slow, irregular free wander — some particles drift out and back in.
                pos[idx] += Math.sin(t * 0.00025 + seed * 12.0) * 4 * dt;
                pos[idx + 1] += Math.cos(t * 0.0002 + seed * 9.0) * 3 * dt;
                pos[idx + 2] += Math.sin(t * 0.00018 + seed * 5.0) * 3 * dt;
                continue;
            }

            if (thinkSpin && (region === REGION.HEAD || region === REGION.FACE)) {
                const dx = pos[idx] - 0, dz = pos[idx + 2] - 0;
                const angle = Math.atan2(dz, dx) + thinkSpin * (0.3 + seed * 0.4);
                const radius = Math.sqrt(dx * dx + dz * dz);
                pos[idx] = radius * Math.cos(angle);
                pos[idx + 2] = radius * Math.sin(angle);
            }

            if (state === 'SPEAKING' && this.audioLevel > 0.02 &&
                (region === REGION.FACE || region === REGION.NECK || region === REGION.CHEST)) {
                const wave = Math.sin(t * 0.012 + seed * 6.2831) * this.audioLevel * 3.5;
                pos[idx + 1] += wave * dt;
            }

            if (state === 'ERROR' && this.errorFlash > 0.05 && (region === REGION.HEAD || region === REGION.FACE)) {
                pos[idx] += (Math.random() - 0.5) * this.errorFlash * 1.5;
                pos[idx + 1] += (Math.random() - 0.5) * this.errorFlash * 1.5;
            }

            if (this.burstT > 0.01 &&
                (region === REGION.CHEST || region === REGION.SHOULDER_L || region === REGION.SHOULDER_R || region === REGION.FLOW)) {
                const dx = pos[idx] - 0, dy = pos[idx + 1] - L.chestCoreY, dz = pos[idx + 2] - L.chestCoreZ;
                const len = Math.sqrt(dx * dx + dy * dy + dz * dz) || 1;
                const push = this.burstT * 4;
                pos[idx] += (dx / len) * push;
                pos[idx + 1] += (dy / len) * push;
                pos[idx + 2] += (dz / len) * push;
            }
        }
    }

    playSuccessAnimation() {
        // Brief cyan/white pulse from the chest core outward; decays in updateEnvelopes()/
        // simulateSettled(). Auto-return to IDLE is handled by ParticleAssistantController.
        this.burstT = 1.0;
    }

    playErrorAnimation() {
        // Brief particle instability + warm/red flash on the face; decays automatically.
        // Auto-return to IDLE is handled by ParticleAssistantController.
        this.errorFlash = 1.0;
    }

    // ──────── Rendering ────────

    render() {
        this.renderer.render(this.scene, this.camera);
        this.updateDebugInfo();
    }

    updateDebugInfo() {
        const info = document.getElementById('debugInfo');
        if (!info) return;
        if (!this.config.debug) {
            if (info.textContent) info.textContent = '';
            return;
        }
        if (this.frameCount % 30 !== 0) return;
        const now = performance.now();
        const fps = Math.round(30000 / Math.max(1, now - this.lastFrameTime));
        this.lastFrameTime = now;
        info.textContent = `FPS: ${fps} | State: ${this.currentState} | Particles: ${this.config.particleCount} | Quality: ${this.config.quality}`;
    }

    // ──────── Utilities ────────

    /**
     * One-shot adaptive downgrade: if AUTO-quality sustains a low framerate for ~2s,
     * drop to the next-lower particle-count tier once and rebuild. Not re-evaluated
     * continuously to avoid visible thrashing.
     */
    monitorPerformance() {
        const now = performance.now();
        const dt = now - this.perfLastTime;
        this.perfLastTime = now;
        this.perfHistory.push(dt);
        if (this.perfHistory.length < 120) return;

        const avgMs = this.perfHistory.reduce((a, b) => a + b, 0) / this.perfHistory.length;
        this.perfHistory = [];
        const avgFps = 1000 / avgMs;

        if (this.config.quality === 'AUTO' && !this.autoDowngraded &&
            avgFps < 40 && this.config.particleCount > ParticleScene.QUALITY_PRESETS.LOW) {
            this.autoDowngraded = true;
            const newCount = Math.max(
                ParticleScene.QUALITY_PRESETS.LOW,
                Math.floor(this.config.particleCount * 0.6)
            );
            this.log(`Low FPS (${avgFps.toFixed(1)}) → reducing particle count ${this.config.particleCount} → ${newCount}`);
            this.config.particleCount = newCount;
            this.generateTargetGeometry();
            this.initializeParticles(true);
        }
    }

    onWindowResize() {
        const w = window.innerWidth;
        const h = window.innerHeight;
        this.camera.aspect = w / h;
        this.camera.updateProjectionMatrix();
        this.renderer.setSize(w, h);
        if (this.uniforms) {
            this.uniforms.uPixelRatio.value = Math.min(window.devicePixelRatio || 1, 1.5);
            this.uniforms.uSizeScale.value = this.computeSizeScale();
        }
    }

    log(msg) {
        if (window.particleInterface) {
            window.particleInterface.log(msg);
        } else {
            console.log(msg);
        }
    }
}
