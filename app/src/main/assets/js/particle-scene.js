/**
 * ParticleScene: WebGL-rendered 3D particle system forming an abstract holographic
 * humanoid AI bust (head + neck + shoulders + upper chest).
 *
 * Architecture (see REGION below): dense head surface + open topographic head flow,
 * embedded face-core volumes, organic neck/shoulder/chest filaments, chest beacon and
 * body-local ambient particles. No explicit outline, tube or line objects are used.
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
    HEAD_SURFACE: 0,
    HEAD_FLOW: 1,
    HEAD_RIM: 2,
    FACE_STRUCTURE: 2, // compatibility alias for unchanged state-animation branches
    FACE_CORE_OUTER: 3,
    FACE_CORE_INNER: 4,
    NECK_FLOW: 5,
    SHOULDER_FLOW: 6,
    CHEST_FLOW: 7,
    CHEST_CORE: 8,
    AMBIENT: 9,
    SHOULDER_RIM: 10,
    SIDE_TRAIL: 11,
    HEAD_RIM_MAIN: 12,
    HEAD_RIM_INNER: 13,
    HEAD_BACK: 14,
    HEAD_MID: 15,
    HEAD_FRONT: 16,
};

// Assembly is hierarchical: each region starts/finishes moving into place within its own
// window (fraction of the total assembly duration), so the figure builds up in stages
// (ambient haze → rough silhouette → face/neck detail → energy lines → glowing cores)
// instead of every particle moving in lockstep.
const REGION_WINDOW = {
    [REGION.AMBIENT]: [0.00, 0.32],
    [REGION.HEAD_SURFACE]: [0.08, 0.50],
    [REGION.HEAD_FLOW]: [0.22, 0.62],
    [REGION.NECK_FLOW]: [0.38, 0.72],
    [REGION.SHOULDER_FLOW]: [0.40, 0.76],
    [REGION.HEAD_RIM]: [0.10, 0.52],
    [REGION.CHEST_FLOW]: [0.55, 0.88],
    [REGION.FACE_CORE_OUTER]: [0.62, 0.94],
    [REGION.FACE_CORE_INNER]: [0.78, 1.00],
    [REGION.CHEST_CORE]: [0.80, 1.00],
    [REGION.SHOULDER_RIM]: [0.40, 0.78],
    [REGION.SIDE_TRAIL]: [0.00, 0.34],
    [REGION.HEAD_RIM_MAIN]: [0.12, 0.52],
    [REGION.HEAD_RIM_INNER]: [0.16, 0.54],
    [REGION.HEAD_BACK]: [0.08, 0.46],
    [REGION.HEAD_MID]: [0.12, 0.50],
    [REGION.HEAD_FRONT]: [0.18, 0.56],
};

// Head profile: half-width (x) / half-depth (z) as a fraction of the maximum, keyed by
// normalized height h (1 = crown, -1 = chin). This is what replaces the old constant-radius
// sphere — width/depth now genuinely depend on height, giving a skull → temple → cheekbone →
// jaw → chin taper instead of a ball.
const HEAD_PROFILE = [
    { h: 1.00, rx: 0.46, rz: 0.46 },
    { h: 0.92, rx: 0.84, rz: 0.76 },
    { h: 0.64, rx: 1.00, rz: 0.98 },
    { h: 0.08, rx: 1.00, rz: 1.00 },
    { h: -0.40, rx: 0.98, rz: 0.96 },
    { h: -0.60, rx: 0.93, rz: 0.88 },
    { h: -0.80, rx: 0.85, rz: 0.76 },
    { h: -0.92, rx: 0.68, rz: 0.60 },
    { h: -1.00, rx: 0.54, rz: 0.48 },
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
    c.setHSL(0.54 + (Math.random() - 0.5) * 0.035, 0.88 + Math.random() * 0.11, 0.50 + Math.random() * 0.13);
    return c;
}
function colorFlow() {
    const c = new THREE.Color();
    c.setHSL(0.52 + (Math.random() - 0.5) * 0.025, 0.86 + Math.random() * 0.13, 0.55 + Math.random() * 0.13);
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
attribute float aAlpha;
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
    float alpha = 0.35 + 0.65 * aAlpha;

    if (vRegion == 3.0 || vRegion == 4.0) { // FACE CORE, outer + inner
        float pulse = 0.7 + 0.3 * sin(uTime * 1.3 + seed * 6.2831);
        float inner = vRegion == 4.0 ? 1.0 : 0.0;
        size *= (1.0 + uFaceCoreEnergy * (0.48 + inner * 0.42)) * (0.82 + pulse * 0.18);
        alpha *= (0.48 + inner * 0.18) + 0.20 * pulse;
    } else if (vRegion == 8.0) { // CHEST_CORE
        float pulse = 0.7 + 0.3 * sin(uTime * 1.0 + seed * 6.2831 + 1.5);
        size *= (1.0 + uChestCoreEnergy * 0.8) * pulse;
        alpha *= 0.45 + 0.45 * pulse;
    } else if (vRegion == 1.0 || vRegion == 5.0 || vRegion == 6.0 || vRegion == 7.0 || vRegion == 10.0 || vRegion == 11.0) {
        float wave = sin(pathT * 12.0 - uTime * 1.8 + seed * 6.2831);
        float glow = smoothstep(0.5, 1.0, wave);
        alpha *= 0.72 + 0.28 * glow;
        size *= 0.82 + 0.42 * glow;
    } else if (vRegion == 9.0) { // AMBIENT
        alpha *= 0.28 * twinkle;
    } else {
        size *= (1.0 + uHeadEnergy * 0.18) * twinkle;
        alpha *= 0.62 + 0.28 * twinkle;
    }
    if (vRegion == 0.0) alpha *= 0.56;
    if (vRegion == 1.0) alpha *= 1.28;
    if (vRegion == 2.0) alpha *= 0.72;
    if (vRegion == 12.0) alpha *= 1.10;
    if (vRegion == 13.0) alpha *= 1.02;
    if (vRegion == 14.0) { alpha *= 0.32; size *= 0.84; }
    if (vRegion == 15.0) { alpha *= 0.58; size *= 0.92; }
    if (vRegion == 16.0) alpha *= 0.82;
    if (vRegion == 6.0) {
        float endFade = 1.0 - smoothstep(0.76, 1.0, pathT) * 0.82;
        alpha *= endFade;
    }
    // A handful of intentionally large points act only as translucent energy haze.
    if (aSize > 10.0) alpha *= 0.11;

    float frontWeight = smoothstep(-45.0, 58.0, position.z);
    if (vRegion != 9.0) alpha *= 0.68 + 0.32 * frontWeight;
    if (vRegion == 7.0) alpha *= smoothstep(-94.0, -45.0, position.y);

    alpha *= 0.75 + 0.25 * uAudioLevel;
    if (uGlowPass > 0.5) {
        float glowScale = vRegion == 9.0 ? 2.1 : (vRegion == 3.0 || vRegion == 4.0 || vRegion == 8.0 ? 5.0 : 3.2);
        size *= glowScale;
        float glowAlpha = 0.048;
        if (vRegion == 0.0 || vRegion == 1.0) glowAlpha = 0.12;
        if (vRegion == 2.0) { glowAlpha = 0.13; size *= 1.70; }
        if (vRegion == 12.0) { glowAlpha = 0.14; size *= 0.96; }
        if (vRegion == 13.0) { glowAlpha = 0.10; size *= 0.78; }
        if (vRegion == 14.0) { glowAlpha = 0.035; size *= 1.10; }
        if (vRegion == 15.0) { glowAlpha = 0.070; size *= 1.08; }
        if (vRegion == 16.0) { glowAlpha = 0.105; size *= 1.05; }
        if (vRegion == 6.0) glowAlpha = 0.074;
        if (vRegion == 10.0) glowAlpha = 0.105;
        if (vRegion == 11.0) glowAlpha = 0.035;
        if (vRegion == 3.0 || vRegion == 4.0 || vRegion == 8.0) glowAlpha = 0.13;
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

    float rim = (vRegion == 2.0 || vRegion == 12.0 || vRegion == 13.0) ? 1.0 : 0.0;
    float hotStrength = (vRegion == 3.0 || vRegion == 4.0) ? 0.08 : mix(0.62, 0.18, rim);
    vec3 col = vColor + hot * hotStrength;
    if (vRegion == 0.0 || vRegion == 1.0 || vRegion == 2.0 || vRegion == 12.0 ||
        vRegion == 13.0 || vRegion == 14.0 || vRegion == 15.0 || vRegion == 16.0) {
        col = mix(col, vec3(1.0, 0.28, 0.16), uErrorFlash * 0.55);
    }
    gl_FragColor = vec4(col, alpha);
}
`;

class ParticleScene {
    static QUALITY_PRESETS = { LOW: 11000, MEDIUM: 14500, HIGH: 18000 };

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
        this.geometrySeed = 0x5f3759df;
        this.sharedGeometryData = null;
        this.geometryMode = 'PROCEDURAL_FALLBACK'; // set by prepare(): REFERENCE_TARGET | PROCEDURAL_FALLBACK

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

    /**
     * Loads the shared target field used by the native APK too, preferring the
     * reference-analysis-derived field (dev tools/avatar_reference/) over the
     * hand-authored procedural one. Never fetches the reference image itself at
     * runtime — only pre-generated geometry data. If neither JSON field loads,
     * this does NOT throw: sharedGeometryData stays null and
     * generateTargetGeometry() falls back to the fully in-JS procedural
     * generators (genHeadSurface() etc.) as the last-resort geometry source.
     */
    async prepare() {
        const candidates = [
            { url: 'avatar-target.json', mode: 'REFERENCE_TARGET' },
            { url: 'avatar-geometry.json', mode: 'PROCEDURAL_FALLBACK' },
        ];
        for (const candidate of candidates) {
            try {
                const response = await fetch(candidate.url, { cache: 'no-store' });
                if (!response.ok) continue;
                const data = await response.json();
                if (!Array.isArray(data?.particles) || !data.particles.length) continue;
                this.sharedGeometryData = data;
                this.geometryMode = candidate.mode;
                this.log(`Geometry source: ${candidate.mode} (${candidate.url}, ${data.particles.length} points)`);
                return;
            } catch (error) {
                this.log(`Geometry source ${candidate.url} failed: ${error?.message || error}`);
            }
        }
        this.geometryMode = 'PROCEDURAL_FALLBACK';
        this.log('No shared geometry field available — using in-JS procedural fallback');
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

    syncReferenceVisual() {
        this.canvas.style.opacity = '1';
        if (this.glowSystem) this.glowSystem.visible = true;
    }

    /** Deterministic setup-time PRNG: previews and device builds produce the same sculpture. */
    random() {
        this.geometrySeed = (1664525 * this.geometrySeed + 1013904223) >>> 0;
        return this.geometrySeed / 4294967296;
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
        // The paused RAF chain stays alive, so starting another chain here would double-render.
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
        const worldHeight = 400 * s;
        const yWorld = normalizedY => (0.5 - normalizedY) * worldHeight;
        const headTopY = yWorld(0.180);
        const headBottomY = yWorld(0.475);
        const headHalfHeight = (headTopY - headBottomY) / 2;
        const headCenterY = (headTopY + headBottomY) / 2;
        const neckTopY = headBottomY;
        const neckBottomY = yWorld(0.555);
        const shoulderCenterY = yWorld(0.625);
        const chestTopY = yWorld(0.535);
        const chestBottomY = yWorld(0.835);

        return {
            s,
            headCenterY,
            headHalfHeight,
            headHalfWidthMax: 46.72 * s,
            headHalfDepthMax: 16 * s,
            headTopY,
            headBottomY,
            neckTopY,
            neckBottomY,
            neckHalfWidth: 16 * s,
            neckHalfDepth: 10 * s,
            shoulderCenterY,
            shoulderReachX: 109.82 * s,
            shoulderDropY: 46 * s,
            chestTopY,
            chestBottomY,
            chestHalfWidthTop: 95 * s,
            chestHalfWidthBottom: 35 * s,
            chestHalfDepth: 18 * s,
            chestCoreY: yWorld(0.720),
            chestCoreZ: 8 * s,
            faceCoreY: yWorld(0.340),
            faceCoreZ: 8 * s,
        };
    }

    /** Frames the complete normalized 16:10 target field; WebGL and native pixels now align. */
    frameCamera() {
        const fieldHeight = 400 * this.layout.s;
        const fovRad = THREE.MathUtils.degToRad(this.camera.fov);
        const distance = fieldHeight / (2 * Math.tan(fovRad / 2));
        this.camera.position.set(0, 0, THREE.MathUtils.clamp(distance, 260, 1400));
        this.camera.updateProjectionMatrix();
    }

    /**
     * Builds the full point cloud (plain array, setup-time only) across all regions, then
     * hands off to initializeParticles() to pack it into typed arrays / GPU buffers.
     */
    generateTargetGeometry() {
        if (this.sharedGeometryData?.particles?.length) {
            this.generateSharedTargetGeometry();
            return;
        }
        const L = this.layout;
        const budget = this.config.particleCount;
        this.geometrySeed = 0x5f3759df;
        const pts = [];

        const headSurfaceBudget = Math.round(budget * 0.12);
        const headRimBudget = Math.round(budget * 0.058);
        const headFlowBudget = Math.round(budget * 0.29);
        const faceStructureBudget = Math.round(budget * 0.025);
        const faceCoreBudget = Math.round(budget * 0.14);
        const neckBudget = Math.round(budget * 0.09);
        const shoulderBudget = Math.round(budget * 0.13);
        const chestBudget = Math.round(budget * 0.11);
        const chestCoreBudget = Math.round(budget * 0.015);
        const used = headSurfaceBudget + headRimBudget + headFlowBudget + faceStructureBudget + faceCoreBudget +
            neckBudget + shoulderBudget + chestBudget + chestCoreBudget;
        const ambientBudget = budget - used;

        this.genHeadSurface(pts, headSurfaceBudget, L);
        this.genHeadRim(pts, headRimBudget, L);
        this.genHeadFlow(pts, headFlowBudget, L);
        this.genFaceEnergy(pts, faceStructureBudget, L);
        this.genNeck(pts, neckBudget, L);
        this.genShoulder(pts, Math.round(shoulderBudget / 2), L, -1);
        this.genShoulder(pts, shoulderBudget - Math.round(shoulderBudget / 2), L, 1);
        this.genChest(pts, chestBudget, L);
        this.genFlowPaths(pts, Math.round(budget * 0.045), L);
        this.genFaceCore(pts, faceCoreBudget, L);
        this.genVolumetricCluster(pts, chestCoreBudget, REGION.CHEST_CORE, 0, L.chestCoreY, L.chestCoreZ, 5 * L.s, 7 * L.s, 4 * L.s, colorChestCore, 1.65 * L.s);
        this.genAmbient(pts, Math.max(0, ambientBudget - Math.round(budget * 0.045)), L);

        // Pad/trim to the exact configured budget (rounding remainders only).
        while (pts.length < budget) {
            const src = pts[Math.floor(this.random() * pts.length)];
            pts.push({ ...src, x: src.x + (this.random() - 0.5) * 3, y: src.y + (this.random() - 0.5) * 3, z: src.z + (this.random() - 0.5) * 3 });
        }
        this.targetPoints = pts.slice(0, budget);
    }

    /** Converts normalized 16:10 target-field coordinates into shallow 2.5D world points. */
    generateSharedTargetGeometry() {
        const source = this.sharedGeometryData.particles;
        const requested = Math.min(this.config.particleCount, source.length);
        // Downsampling must not visually change the sculpture. Sparse quality levels keep
        // exactly the same target field and receive only a bounded point-size compensation.
        const densityCompensation = Math.min(1.5, Math.sqrt(source.length / requested));
        const worldHeight = 400 * this.layout.s;
        const worldWidth = worldHeight * 1.6;
        const regionId = particle => {
            switch (particle.region) {
                case 'headShell': return REGION.HEAD_SURFACE;
                case 'headBack': return REGION.HEAD_BACK;
                case 'headMid': return REGION.HEAD_MID;
                case 'headFront': return REGION.HEAD_FRONT;
                case 'headBand': return REGION.HEAD_FLOW;
                case 'headRimInner': return REGION.HEAD_RIM_INNER;
                case 'headRimMain': return REGION.HEAD_RIM_MAIN;
                case 'headRimHalo': return REGION.HEAD_RIM;
                case 'faceCore': return particle.brightness > 0.72 ? REGION.FACE_CORE_INNER : REGION.FACE_CORE_OUTER;
                case 'neckEnergy': return REGION.NECK_FLOW;
                case 'shoulderBand':
                case 'shoulderSurface': return REGION.SHOULDER_FLOW;
                case 'shoulderRim': return REGION.SHOULDER_RIM;
                case 'chestBand': return REGION.CHEST_FLOW;
                case 'chestCore': return REGION.CHEST_CORE;
                case 'sideTrail': return REGION.SIDE_TRAIL;
                default: return REGION.AMBIENT;
            }
        };
        this.targetPoints = new Array(requested);
        for (let i = 0; i < requested; i++) {
            const particle = source[Math.floor(i * source.length / requested)];
            this.targetPoints[i] = {
                x: (particle.targetX - 0.5) * worldWidth,
                y: (0.5 - particle.targetY) * worldHeight,
                z: particle.targetZ * worldHeight,
                region: regionId(particle),
                color: new THREE.Color(particle.color[0] / 255, particle.color[1] / 255, particle.color[2] / 255),
                size: particle.baseSize * 2.08 * densityCompensation * this.layout.s * (
                    particle.region === 'faceCore' ? 0.98 :
                        particle.region === 'chestCore' ? 1.30 :
                        particle.region === 'headBand' ? 1.18 :
                            (particle.region === 'shoulderBand' || particle.region === 'neckEnergy' ||
                                particle.region === 'chestBand') ? 1.20 : 1.0
                ),
                baseAlpha: particle.baseAlpha,
                pathT: particle.flowT,
                seed: particle.seed / source.length,
            };
        }
        this.config.particleCount = requested;
    }

    genHeadSurface(pts, budget, L) {
        // A dense, shallow surface cloud establishes volume. There is deliberately no
        // silhouette pass: the edge emerges only where many differently offset layers meet.
        for (let i = 0; i < budget; i++) {
            const h = 1 - 2 * this.random();
            const prof = headProfileAt(h);
            const theta = (-0.92 + this.random() * 1.84) * Math.PI / 2;
            const interior = 0.72 + Math.sqrt(this.random()) * 0.28;
            const xNorm = Math.sin(theta);
            const edgeNoise = (this.random() - 0.5) * 3.2 * L.s;
            const x = xNorm * prof.rx * L.headHalfWidthMax * interior + edgeNoise;
            const zSurface = Math.cos(theta) * prof.rz * L.headHalfDepthMax;
            const z = zSurface * (0.78 + 0.22 * interior) + (this.random() - 0.5) * 6 * L.s;
            const y = L.headCenterY + h * L.headHalfHeight +
                Math.sin(theta * 2.1 + h * 4.0) * 1.4 * L.s + (this.random() - 0.5) * 2.2 * L.s;
            const color = colorCyanStructure();
            if (Math.abs(h + 0.05) < 0.50 && Math.abs(xNorm) < 0.58) color.multiplyScalar(0.48);
            pts.push({
                x, y, z,
                region: REGION.HEAD_SURFACE,
                color,
                size: (0.82 + this.random() * 0.72) * L.s,
                pathT: h * 0.5 + 0.5,
            });
        }
    }

    /** Irregular topographic filaments across the frontal skull; open and softly broken. */
    genHeadFlow(pts, budget, L) {
        const layers = 62;
        const perLayer = Math.max(18, Math.floor(budget / layers));
        for (let layer = 0; layer < layers; layer++) {
            const baseU = layer / (layers - 1);
            const h = 1 - 2 * baseU + Math.sin(layer * 1.71) * 0.009;
            const prof = headProfileAt(h);
            const asym = Math.sin(layer * 2.37) * 0.018;
            const edgeInsetL = 0.04 + this.random() * 0.12;
            const edgeInsetR = 0.04 + this.random() * 0.12;
            for (let j = 0; j < perLayer; j++) {
                if (this.random() < 0.075) continue;
                const t = j / (perLayer - 1);
                const xNorm = -1 + edgeInsetL + t * (2 - edgeInsetL - edgeInsetR);
                const curveY = Math.cos(xNorm * Math.PI * 0.72) * (1.5 + 1.2 * Math.sin(layer * 0.63)) * L.s;
                const x = (xNorm + asym * (1 - Math.abs(xNorm))) * prof.rx * L.headHalfWidthMax;
                const z = Math.sqrt(Math.max(0.02, 1 - xNorm * xNorm)) * prof.rz * L.headHalfDepthMax +
                    (this.random() - 0.5) * 2.8 * L.s;
                const color = colorFlow();
                if (Math.abs(h + 0.05) < 0.48 && Math.abs(xNorm) < 0.62) color.multiplyScalar(0.38);
                pts.push({
                    x: x + (this.random() - 0.5) * 1.0 * L.s,
                    y: L.headCenterY + h * L.headHalfHeight + curveY + (this.random() - 0.5) * 0.9 * L.s,
                    z,
                    region: REGION.HEAD_FLOW,
                    color,
                    size: (1.06 + this.random() * 0.48) * L.s,
                    pathT: t,
                });
            }
        }
    }

    /** Glowing neon rim tracing the head silhouette: three-layer glow (core, main, halo). */
    genHeadRim(pts, budget, L) {
        const rimCoreBudget = Math.round(budget * 0.15);
        const rimMainBudget = Math.round(budget * 0.60);
        const rimHaloBudget = budget - rimCoreBudget - rimMainBudget;

        const layerCount = 56;

        // RIM_CORE: tiny, almost-white, directly on the profile edge
        const corePerLayer = Math.max(1, Math.floor(rimCoreBudget / layerCount));
        for (let layer = 0; layer < layerCount; layer++) {
            const h = 1 - 2 * (layer / (layerCount - 1));
            const prof = headProfileAt(h);
            const theta = (this.random() - 0.5) * 0.32 * Math.PI;
            const xNorm = Math.sin(theta);
            const rimOffset = (0.92 + this.random() * 0.08) * (prof.rx * L.headHalfWidthMax);
            const x = xNorm * rimOffset;
            const z = Math.cos(theta) * prof.rz * L.headHalfDepthMax * (0.98 + this.random() * 0.04);
            const color = new THREE.Color().setHSL(0.54, 0.95, 0.85 + this.random() * 0.15);
            pts.push({
                x, y: L.headCenterY + h * L.headHalfHeight + (this.random() - 0.5) * 1.2 * L.s, z,
                region: REGION.HEAD_RIM, color,
                size: (0.28 + this.random() * 0.15) * L.s,
                pathT: layer / (layerCount - 1),
            });
        }

        // RIM_MAIN: electric cyan, outer halo edge
        const mainPerLayer = Math.max(3, Math.floor(rimMainBudget / layerCount));
        for (let layer = 0; layer < layerCount; layer++) {
            const h = 1 - 2 * (layer / (layerCount - 1));
            const prof = headProfileAt(h);
            for (let i = 0; i < mainPerLayer; i++) {
                if (this.random() < 0.12) continue;
                const theta = (this.random() - 0.5) * 1.2 * Math.PI;
                const xNorm = Math.sin(theta);
                const radiusVariance = 1.08 + this.random() * 0.22;
                const rimOffset = radiusVariance * (prof.rx * L.headHalfWidthMax);
                const x = xNorm * rimOffset + (this.random() - 0.5) * 1.8 * L.s;
                const z = Math.cos(theta) * prof.rz * L.headHalfDepthMax * (0.98 + this.random() * 0.06) + (this.random() - 0.5) * 2.1 * L.s;
                const color = colorCyanStructure();
                color.multiplyScalar(0.92 + this.random() * 0.12);
                pts.push({
                    x, y: L.headCenterY + h * L.headHalfHeight + (this.random() - 0.5) * 1.5 * L.s, z,
                    region: REGION.HEAD_RIM, color,
                    size: (0.52 + this.random() * 0.38) * L.s,
                    pathT: i / Math.max(1, mainPerLayer - 1),
                });
            }
        }

        // RIM_HALO: larger, transparent, soft outer glow
        const haloPerLayer = Math.max(2, Math.floor(rimHaloBudget / layerCount));
        for (let layer = 0; layer < layerCount; layer++) {
            const h = 1 - 2 * (layer / (layerCount - 1));
            const prof = headProfileAt(h);
            for (let i = 0; i < haloPerLayer; i++) {
                if (this.random() < 0.18) continue;
                const theta = (this.random() - 0.5) * 1.6 * Math.PI;
                const xNorm = Math.sin(theta);
                const radiusVariance = 1.24 + this.random() * 0.36;
                const rimOffset = radiusVariance * (prof.rx * L.headHalfWidthMax);
                const x = xNorm * rimOffset + (this.random() - 0.5) * 3.2 * L.s;
                const z = Math.cos(theta) * prof.rz * L.headHalfDepthMax * (0.96 + this.random() * 0.08) + (this.random() - 0.5) * 3.6 * L.s;
                const color = colorCyanStructure();
                color.multiplyScalar(0.58 + this.random() * 0.18);
                pts.push({
                    x, y: L.headCenterY + h * L.headHalfHeight + (this.random() - 0.5) * 2.0 * L.s, z,
                    region: REGION.HEAD_RIM, color,
                    size: (0.84 + this.random() * 0.52) * L.s,
                    pathT: i / Math.max(1, haloPerLayer - 1),
                    baseAlpha: 0.52 + this.random() * 0.28,
                });
            }
        }
    }

    /** Embedded pear-shaped volumetric energy, layered white-yellow → gold → amber → inner hot spot. */
    genFaceCore(pts, budget, L) {
        const outerBudget = Math.round(budget * 0.26);
        const midBudget = Math.round(budget * 0.32);
        const innerBudget = Math.round(budget * 0.28);
        const hotBudget = budget - outerBudget - midBudget - innerBudget;

        const addLayer = (count, scale, region, intensityMin, sizeScale = 1.0) => {
            for (let i = 0; i < count; i++) {
                const ny = this.random() * 2 - 1;
                const profileWidth = (0.64 + 0.36 * (1 - ny * ny)) * (ny > 0 ? 0.78 + 0.22 * (1 - ny) : 1.0);
                const angle = this.random() * Math.PI * 2;
                const radius = Math.cbrt(this.random());
                const x = Math.cos(angle) * radius * 32 * scale * profileWidth * L.s + (this.random() - 0.5) * 2 * L.s;
                const y = L.faceCoreY + ny * 42 * scale * L.s + Math.sin(angle * 2.0) * 1.8 * L.s + (this.random() - 0.5) * 1.5 * L.s;
                const depth = Math.sin(angle) * radius;
                const z = L.faceCoreZ - 4 * L.s + depth * 10 * scale * L.s + (1 - radius) * 5 * L.s + (this.random() - 0.5) * 1.2 * L.s;
                const intensity = intensityMin + (1 - radius) * (1 - intensityMin);
                const color = colorFaceCore(intensity);
                if (region === REGION.FACE_CORE_INNER && intensity > 0.88) {
                    color.lerp(new THREE.Color(0xfff9e6), 0.38);
                }
                pts.push({
                    x, y, z, region, color,
                    size: ((0.88 + this.random() * 0.72) * sizeScale) * L.s,
                    pathT: ny * 0.5 + 0.5,
                    baseAlpha: 0.72 + this.random() * 0.22,
                });
            }
        };

        // Outer layer: amber/orange diffuse cloud
        addLayer(outerBudget, 1.0, REGION.FACE_CORE_OUTER, 0.12, 1.1);
        // Middle layer: warmer orange/gold transition
        addLayer(midBudget, 0.68, REGION.FACE_CORE_OUTER, 0.38, 0.95);
        // Inner layer: gold/white transition
        addLayer(innerBudget, 0.32, REGION.FACE_CORE_INNER, 0.68, 0.82);
        // Hot center: tiny bright white hot spots
        addLayer(hotBudget, 0.08, REGION.FACE_CORE_INNER, 0.92, 0.55);

        // Subtle radiant accent halo (embedded in head, not floating)
        for (const halo of [
            { size: 48, color: 0xff8822, z: -6, intensity: 0.5 },
            { size: 32, color: 0xffaa33, z: -2, intensity: 0.65 },
            { size: 18, color: 0xffdd66, z: 2, intensity: 0.82 },
        ]) {
            pts.push({
                x: 0, y: L.faceCoreY, z: L.faceCoreZ + halo.z * L.s,
                region: REGION.FACE_CORE_OUTER,
                color: colorFaceCore(halo.intensity),
                size: halo.size * L.s,
                pathT: 0.5,
                baseAlpha: 0.45,
            });
        }

        // Warm filaments that bind the core to the cyan face surface
        const filamentCount = Math.max(8, Math.floor(budget * 0.032));
        for (let i = 0; i < filamentCount; i++) {
            const t = i / Math.max(1, filamentCount - 1);
            const wiggle = Math.sin(t * Math.PI * 3.8) * 8 * (1 - Math.abs(t - 0.5)) * L.s;
            pts.push({
                x: wiggle + (this.random() - 0.5) * 1.5 * L.s,
                y: L.faceCoreY + (t - 0.5) * 48 * L.s,
                z: L.faceCoreZ + 2 * L.s + (this.random() - 0.5) * 1.2 * L.s,
                region: REGION.FACE_CORE_INNER,
                color: colorFaceCore(0.76 + this.random() * 0.24),
                size: (1.08 + this.random() * 0.24) * L.s,
                pathT: t,
                baseAlpha: 0.68,
            });
        }
    }

    /** Sparse open facial energy wisps; no brows, mouth, mask edge or closed feature loops. */
    genFaceEnergy(pts, budget, L) {
        const strands = 11;
        const perStrand = Math.max(8, Math.floor(budget / strands));
        for (let strand = 0; strand < strands; strand++) {
            const n = strand / (strands - 1);
            const side = n < 0.5 ? -1 : 1;
            const spread = Math.abs(n - 0.5) * 2;
            const curve = new THREE.CatmullRomCurve3([
                new THREE.Vector3(side * (4 + spread * 18) * L.s, L.headCenterY + 46 * L.s, 43 * L.s),
                new THREE.Vector3(side * (10 + spread * 24) * L.s, L.headCenterY + 20 * L.s, 49 * L.s),
                new THREE.Vector3(side * (7 + spread * 29) * L.s, L.headCenterY - 7 * L.s, 51 * L.s),
                new THREE.Vector3(side * (11 + spread * 23) * L.s, L.headCenterY - 34 * L.s, 46 * L.s),
                new THREE.Vector3(side * (6 + spread * 10) * L.s, L.headCenterY - 56 * L.s, 40 * L.s),
            ]);
            const sampled = curve.getSpacedPoints(perStrand - 1);
            sampled.forEach((p, i) => {
                if ((i + strand * 3) % 17 === 0) return;
                const color = colorFlow();
                color.multiplyScalar(0.55 + (1 - spread) * 0.18);
                pts.push({
                    x: p.x + Math.sin(i * 0.55 + strand) * 0.7 * L.s,
                    y: p.y + Math.cos(i * 0.39 + strand * 1.7) * 0.7 * L.s,
                    z: p.z + Math.sin(strand * 2.1) * 1.5 * L.s,
                    region: REGION.FACE_STRUCTURE,
                    color,
                    size: (0.72 + this.random() * 0.35) * L.s,
                    pathT: i / Math.max(1, sampled.length - 1),
                });
            });
        }
    }

    genNeck(pts, budget, L) {
        const strands = 16;
        const perStrand = Math.max(8, Math.round(budget / strands));
        for (let strand = 0; strand < strands; strand++) {
            const xNorm = (strand / (strands - 1)) * 2 - 1;
            const absX = Math.abs(xNorm);
            const phase = strand * 1.83;
            const sideDrift = Math.sin(phase) * 2.4 * L.s;
            const curve = new THREE.CatmullRomCurve3([
                new THREE.Vector3(xNorm * (10 + absX * 6) * L.s, L.neckTopY + 5 * L.s, 41 * L.s),
                new THREE.Vector3(xNorm * (15 - absX * 3) * L.s + sideDrift, L.neckTopY - 24 * L.s, 43 * L.s),
                new THREE.Vector3(xNorm * (10 + absX * 8) * L.s - sideDrift * 0.6, L.neckBottomY + 20 * L.s, 42 * L.s),
                new THREE.Vector3(xNorm * (24 + absX * 12) * L.s, L.chestCoreY + 7 * L.s, 43 * L.s),
                new THREE.Vector3(xNorm * 5 * L.s, L.chestCoreY, L.chestCoreZ),
            ]);
            const sampled = curve.getSpacedPoints(perStrand - 1);
            sampled.forEach((p, i) => {
                const warm = absX < 0.34;
                pts.push({
                    x: p.x + Math.sin(i * 0.31 + phase) * 0.65 * L.s,
                    y: p.y + Math.sin(phase + i * 0.27) * 0.8 * L.s,
                    z: p.z + Math.cos(phase + i * 0.2) * 1.4 * L.s,
                    region: REGION.NECK_FLOW,
                    color: warm ? colorNeckEnergy() : colorCyanStructure(),
                    size: (warm ? 1.0 : 1.2) * L.s,
                    pathT: i / (perStrand - 1),
                });
            });
        }
    }

    genShoulder(pts, budget, L, side) {
        const layers = 14;
        const perLayer = Math.max(12, Math.round(budget / layers));
        for (let layer = 0; layer < layers; layer++) {
            const n = layer / (layers - 1);
            const asym = side * Math.sin(layer * 2.17 + (side > 0 ? 0.7 : 0)) * 4.2 * L.s;
            const yOffset = (n - 0.5) * 17 * L.s + Math.sin(layer * 1.47) * 1.6 * L.s;
            const zOffset = (n - 0.5) * 13 * L.s;
            const curve = new THREE.CatmullRomCurve3([
                new THREE.Vector3(side * (20 + n * 8) * L.s, L.neckBottomY + 22 * L.s + yOffset * 0.25, 39 * L.s + zOffset),
                new THREE.Vector3(side * (39 + n * 10) * L.s, L.shoulderCenterY + (25 - n * 4) * L.s + yOffset * 0.45, 41 * L.s + zOffset),
                new THREE.Vector3(side * (74 + n * 12) * L.s + asym, L.shoulderCenterY + (18 - n * 8) * L.s + yOffset, 35 * L.s + zOffset),
                new THREE.Vector3(side * (108 + n * 13) * L.s, L.shoulderCenterY + (5 - n * 12) * L.s + yOffset, 25 * L.s + zOffset * 0.7),
                new THREE.Vector3(side * (L.shoulderReachX - n * 18 * L.s + Math.sin(layer * 1.9) * 5 * L.s), L.shoulderCenterY - (18 + n * 15) * L.s + yOffset * 0.5, 12 * L.s + zOffset * 0.3),
            ]);
            const sampled = curve.getSpacedPoints(perLayer - 1);
            sampled.forEach((p, i) => {
                const color = colorCyanStructure();
                color.multiplyScalar(0.58 + this.random() * 0.25);
                pts.push({
                    x: p.x + Math.sin(i * 0.73 + layer) * 0.45 * L.s,
                    y: p.y + Math.cos(i * 0.51 + layer * 1.3) * 0.45 * L.s,
                    z: p.z,
                    region: REGION.SHOULDER_FLOW,
                    color,
                    size: (1.02 + this.random() * 0.48) * L.s,
                    pathT: i / (perLayer - 1),
                });
            });
        }
    }

    genChest(pts, budget, L) {
        const bandsPerSide = 18;
        const perBand = Math.max(12, Math.floor((budget * 0.68) / (bandsPerSide * 2)));
        for (const side of [-1, 1]) {
            for (let lane = 0; lane < bandsPerSide; lane++) {
                const n = lane / (bandsPerSide - 1);
                const asym = Math.sin(lane * 1.91 + side * 0.8) * 2.2 * L.s;
                const curve = new THREE.CatmullRomCurve3([
                    new THREE.Vector3(side * (5 + n * 35) * L.s, L.chestCoreY - (3 + n * 26) * L.s, L.chestCoreZ + 1 * L.s),
                    new THREE.Vector3(side * (25 + n * 25) * L.s + asym, L.chestCoreY + (4 - n * 19) * L.s, 44 * L.s),
                    new THREE.Vector3(side * (54 + n * 27) * L.s, L.shoulderCenterY + (4 - n * 20) * L.s, 38 * L.s),
                    new THREE.Vector3(side * (88 + n * 27) * L.s - asym, L.shoulderCenterY + (10 - n * 29) * L.s, 29 * L.s),
                    new THREE.Vector3(side * (119 + n * 9) * L.s, L.shoulderCenterY - (9 + n * 28) * L.s, 17 * L.s),
                ]);
                const sampled = curve.getSpacedPoints(perBand - 1);
                sampled.forEach((p, i) => pts.push({
                    x: p.x + Math.sin(lane * 1.9 + i * 0.7) * 0.38 * L.s,
                    y: p.y + Math.cos(lane * 1.4 + i * 0.6) * 0.38 * L.s,
                    z: p.z,
                    region: REGION.CHEST_FLOW,
                    color: colorFlow(),
                    size: (1.02 + this.random() * 0.48) * L.s,
                    pathT: i / (perBand - 1),
                }));
            }
        }

        // Downward torso field: short, bowed vertical filaments fade before a common edge.
        const lowerCount = Math.max(0, budget - bandsPerSide * 2 * perBand);
        const torsoStrands = 26;
        const perTorso = Math.max(8, Math.floor(lowerCount / torsoStrands));
        for (let strand = 0; strand < torsoStrands; strand++) {
            const xNorm = (strand / (torsoStrands - 1)) * 2 - 1;
            const curve = new THREE.CatmullRomCurve3([
                new THREE.Vector3(xNorm * 78 * L.s, L.shoulderCenterY - 13 * L.s, 31 * L.s),
                new THREE.Vector3(xNorm * 63 * L.s, L.chestCoreY - 17 * L.s, 39 * L.s),
                new THREE.Vector3(xNorm * 53 * L.s, L.chestCoreY - 48 * L.s, 32 * L.s),
                new THREE.Vector3(xNorm * 38 * L.s, L.chestBottomY + (this.random() * 12 - 2) * L.s, 22 * L.s),
            ]);
            const sampled = curve.getSpacedPoints(perTorso - 1);
            sampled.forEach((p, i) => {
                if (this.random() < 0.08 + i / perTorso * 0.12) return;
                pts.push({
                    x: p.x + Math.sin(i * 0.47 + strand * 1.3) * 0.7 * L.s,
                    y: p.y + Math.cos(i * 0.31 + strand) * 0.7 * L.s,
                    z: p.z + Math.sin(strand * 0.8) * 3.5 * L.s,
                    region: REGION.CHEST_FLOW,
                    color: colorCyanStructure(),
                    size: (0.84 + this.random() * 0.42) * L.s,
                    pathT: i / Math.max(1, sampled.length - 1),
                });
            });
        }
    }

    addFlowPath(pts, keypoints, count, L, colorFn = colorFlow, size = 1.1, region = REGION.CHEST_FLOW) {
        const curve = new THREE.CatmullRomCurve3(keypoints.map(p => new THREE.Vector3(p[0], p[1], p[2])));
        const sampled = curve.getSpacedPoints(Math.max(2, count - 1));
        sampled.forEach((p, i) => {
            pts.push({
                x: p.x,
                y: p.y,
                z: p.z,
                region,
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
            // Warm/cyan neural filaments weave from jaw through neck into the sternum.
            for (let lane = 0; lane < 6; lane++) {
                const offset = lane * 2.4;
                pathSpecs.push({ points: [
                    [side * (9 + offset) * s, L.headCenterY - L.headHalfHeight * (0.53 + lane * 0.025), 44 * s],
                    [side * (18 + lane) * s, L.neckTopY - 7 * s, 43 * s],
                    [side * (8 + lane * 1.2) * s, L.neckTopY - 35 * s, 45 * s],
                    [side * (20 - lane * 1.8) * s, L.neckBottomY + 7 * s, 43 * s],
                    [side * (3 + lane * 0.5) * s, L.chestCoreY, L.chestCoreZ],
                ], color: lane < 3 ? colorNeckEnergy : colorFlow, size: 0.95 + lane * 0.035, region: REGION.NECK_FLOW });
            }

            // Short accents reinforce the energy axis without becoming radial spokes.
            for (let lane = 0; lane < 4; lane++) {
                const n = lane / 3;
                pathSpecs.push({ points: [
                    [side * (2 + lane) * s, L.chestCoreY, L.chestCoreZ + 2 * s],
                    [side * (19 + lane * 4) * s, L.chestCoreY + (8 + lane * 3) * s, 45 * s],
                    [side * (52 + lane * 9) * s, L.shoulderCenterY + (10 - lane * 2) * s, 39 * s],
                    [side * (96 + n * 24) * s, L.shoulderCenterY - (3 + lane * 5) * s, 25 * s],
                ], color: colorFlow, size: 1.0, region: REGION.CHEST_FLOW });
            }
        }

        const perPath = Math.max(8, Math.round(budget / pathSpecs.length));
        for (const spec of pathSpecs) {
            this.addFlowPath(pts, spec.points, perPath, L, spec.color, spec.size, spec.region);
        }
    }

    /** Soft volumetric particle blob (multiple depth layers) — not a solid sphere/circle. */
    genVolumetricCluster(pts, budget, region, cx, cy, cz, rx, ry, rz, colorFn, baseSize) {
        for (let i = 0; i < budget; i++) {
            // Uniform-in-volume-ish sampling: cube root of a random radius fraction + random
            // direction gives more points toward the center falling off softly outward.
            const dir = new THREE.Vector3(this.random() - 0.5, this.random() - 0.5, this.random() - 0.5).normalize();
            const r = Math.cbrt(this.random());
            const x = cx + dir.x * rx * r;
            const y = cy + dir.y * ry * r;
            const z = cz + dir.z * rz * r;
            pts.push({ x, y, z, region, color: colorFn(1 - r), size: baseSize * (1.3 - 0.5 * r), pathT: -1 });
        }
    }

    genAmbient(pts, budget, L) {
        const s = L.s;
        for (let i = 0; i < budget; i++) {
            const zone = this.random();
            const angle = this.random() * Math.PI * 2;
            let cx = 0, cy = L.headCenterY, rx = 112 * s, ry = 124 * s;
            if (zone > 0.48) {
                cx = (this.random() < 0.5 ? -1 : 1) * 76 * s;
                cy = L.shoulderCenterY + 12 * s;
                rx = 93 * s;
                ry = 68 * s;
            }
            const radius = 0.72 + Math.pow(this.random(), 0.7) * 0.68;
            pts.push({
                x: cx + Math.cos(angle) * radius * rx + (this.random() - 0.5) * 10 * s,
                y: cy + Math.sin(angle) * radius * ry + (this.random() - 0.5) * 10 * s,
                z: (this.random() - 0.5) * 86 * s,
                region: REGION.AMBIENT,
                color: colorAmbient(),
                size: (0.65 + this.random() * 1.05) * s,
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
        const alphas = new Float32Array(n);

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
            this.meta[idx + 2] = p.seed ?? this.random();

            colors[idx] = p.color.r;
            colors[idx + 1] = p.color.g;
            colors[idx + 2] = p.color.b;
            sizes[i] = p.size;
            alphas[i] = p.baseAlpha ?? 1;
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
        geometry.setAttribute('aAlpha', new THREE.BufferAttribute(alphas, 1));
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
        this.particleSystem.rotation.y = Math.sin(this.elapsedTime * 0.00015) * THREE.MathUtils.degToRad(1.2);
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
                headTarget = 0.42; faceTarget = 1.0; break;
            case 'SPEAKING':
                headTarget = 0.22; faceTarget = 0.58 + this.audioLevel * 0.42; break;
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

            const flowRegion = region === REGION.HEAD_FLOW || region === REGION.NECK_FLOW ||
                region === REGION.SHOULDER_FLOW || region === REGION.CHEST_FLOW;
            const motionAmp = flowRegion ? 1.35 : (region === REGION.AMBIENT ? 0.2 : 0.72);
            const phase = seed * 6.2831;
            const desiredX = target[idx] + Math.sin(t * 0.00072 + phase) * motionAmp;
            const desiredY = target[idx + 1] + Math.sin(t * 0.00093 + phase * 1.37) * motionAmp * 0.72;
            const desiredZ = target[idx + 2] + Math.cos(t * 0.00064 + phase * 0.81) * motionAmp * 0.8;
            pos[idx] += (desiredX - pos[idx]) * 0.065;
            pos[idx + 1] += (desiredY - pos[idx + 1]) * 0.065;
            pos[idx + 2] += (desiredZ - pos[idx + 2]) * 0.065;

            if (region === REGION.AMBIENT) {
                // Slow, irregular free wander — some particles drift out and back in.
                pos[idx] += Math.sin(t * 0.00025 + seed * 12.0) * 4 * dt;
                pos[idx + 1] += Math.cos(t * 0.0002 + seed * 9.0) * 3 * dt;
                pos[idx + 2] += Math.sin(t * 0.00018 + seed * 5.0) * 3 * dt;
                continue;
            }

            if (thinkSpin && (region === REGION.HEAD_SURFACE || region === REGION.HEAD_BACK ||
                region === REGION.HEAD_MID || region === REGION.HEAD_FRONT || region === REGION.HEAD_FLOW ||
                region === REGION.FACE_STRUCTURE || region === REGION.HEAD_RIM_MAIN || region === REGION.HEAD_RIM_INNER)) {
                const dx = pos[idx] - 0, dz = pos[idx + 2] - 0;
                const angle = Math.atan2(dz, dx) + thinkSpin * (0.3 + seed * 0.4);
                const radius = Math.sqrt(dx * dx + dz * dz);
                pos[idx] = radius * Math.cos(angle);
                pos[idx + 2] = radius * Math.sin(angle);
            }

            if (state === 'SPEAKING' && this.audioLevel > 0.02 &&
                (region === REGION.FACE_STRUCTURE || region === REGION.HEAD_RIM_MAIN ||
                    region === REGION.HEAD_RIM_INNER || region === REGION.FACE_CORE_OUTER ||
                    region === REGION.FACE_CORE_INNER || region === REGION.NECK_FLOW || region === REGION.CHEST_FLOW)) {
                const wave = Math.sin(t * 0.012 + seed * 6.2831) * this.audioLevel * 3.5;
                pos[idx + 1] += wave * dt;
            }

            if (state === 'ERROR' && this.errorFlash > 0.05 &&
                (region === REGION.HEAD_SURFACE || region === REGION.HEAD_BACK || region === REGION.HEAD_MID ||
                    region === REGION.HEAD_FRONT || region === REGION.HEAD_FLOW ||
                    region === REGION.FACE_STRUCTURE || region === REGION.HEAD_RIM_MAIN || region === REGION.HEAD_RIM_INNER)) {
                pos[idx] += (Math.random() - 0.5) * this.errorFlash * 1.5;
                pos[idx + 1] += (Math.random() - 0.5) * this.errorFlash * 1.5;
            }

            if (this.burstT > 0.01 &&
                (region === REGION.CHEST_FLOW || region === REGION.SHOULDER_FLOW || region === REGION.NECK_FLOW)) {
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
