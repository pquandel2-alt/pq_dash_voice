/**
 * CanvasParticleScene is the Android-WebView-safe renderer for the particle assistant.
 * It consumes the same original procedural geometry used by the native Android view and
 * animates its points without WebGL. This keeps older/Huawei WebViews from turning the
 * screensaver black when Chromium loses or refuses the GPU context.
 */
class CanvasParticleScene {
    constructor(canvasElement) {
        this.canvas = canvasElement;
        this.ctx = canvasElement.getContext('2d', { alpha: false });
        if (!this.ctx) throw new Error('Canvas 2D context unavailable');

        this.referenceImage = null;
        this.geometryData = null;
        this.particles = [];
        this.currentState = 'IDLE';
        this.audioLevel = 0;
        this.animationSpeedMultiplier = 1;
        this.assemblyEnabled = true;
        this.assemblyDurationMs = 3200;
        this.assemblyStartedAt = 0;
        this.isRunning = false;
        this.isPaused = false;
        this.rafId = 0;
        this.resize();
        window.addEventListener('resize', () => this.resize());
    }

    setReferenceImage(imageElement) {
        this.referenceImage = imageElement;
        imageElement.style.display = 'none';
    }

    async prepare() {
        const response = await fetch('avatar-target.json');
        if (!response.ok) throw new Error(`Canvas geometry load failed (${response.status})`);
        this.geometryData = (await response.json()).particles;
        this.sampleReference();
    }

    configure(opts = {}) {
        this.animationSpeedMultiplier = opts.animationSpeedMultiplier > 0
            ? opts.animationSpeedMultiplier : 1;
        this.assemblyEnabled = opts.assemblyEnabled !== false;
        this.debug = opts.debug === true;
    }

    resize() {
        // One canvas pixel per CSS pixel keeps Huawei/MatePad WebViews comfortably fast.
        this.width = Math.max(1, window.innerWidth);
        this.height = Math.max(1, window.innerHeight);
        this.canvas.width = this.width;
        this.canvas.height = this.height;
        this.canvas.style.width = '100%';
        this.canvas.style.height = '100%';
        if (this.referenceImage?.naturalWidth) this.sampleReference();
    }

    start() {
        if (!this.geometryData && !this.referenceImage?.naturalWidth) {
            throw new Error('Particle geometry unavailable');
        }
        this.sampleReference();
        this.isRunning = true;
        this.isPaused = false;
        this.canvas.style.display = 'block';
        this.lastFrameAt = performance.now();
        this.animate(this.lastFrameAt);
        console.info(`Canvas particle renderer started (${this.particles.length} particles)`);
    }

    sampleReference() {
        if (this.geometryData) {
            this.particles = this.geometryData.map(p => {
                const angle = Math.random() * Math.PI * 2;
                const radius = Math.sqrt(Math.random()) * Math.max(this.width, this.height) * 0.62;
                return {
                    tx: p.x * this.width,
                    ty: p.y * this.height,
                    r: p.color[0], g: p.color[1], b: p.color[2],
                    brightness: p.brightness,
                    phase: p.phase,
                    drift: p.drift,
                    size: Math.max(0.7, p.radius),
                    x: this.width * 0.5 + Math.cos(angle) * radius,
                    y: this.height * 0.5 + Math.sin(angle) * radius,
                    sx: 0, sy: 0,
                };
            });
            this.centerX = this.width * 0.5;
            this.centerY = this.height * 0.52;
            return;
        }
        const image = this.referenceImage;
        if (!image?.naturalWidth) return;

        const source = document.createElement('canvas');
        source.width = image.naturalWidth;
        source.height = image.naturalHeight;
        const sourceCtx = source.getContext('2d', { willReadFrequently: true });
        sourceCtx.drawImage(image, 0, 0);
        const pixels = sourceCtx.getImageData(0, 0, source.width, source.height).data;

        const candidates = [];
        const sampleStep = 3;
        let brightMinX = source.width, brightMaxX = 0;
        let brightMinY = source.height, brightMaxY = 0;

        for (let sy = 0; sy < source.height; sy += sampleStep) {
            for (let sx = 0; sx < source.width; sx += sampleStep) {
                const index = (sy * source.width + sx) * 4;
                const r = pixels[index];
                const g = pixels[index + 1];
                const b = pixels[index + 2];
                const brightness = Math.max(r, g, b);
                // The reference background is nearly black; retain only its luminous structure.
                if (brightness < 34 || r + g + b < 82) continue;
                if (brightness > 82) {
                    brightMinX = Math.min(brightMinX, sx);
                    brightMaxX = Math.max(brightMaxX, sx);
                    brightMinY = Math.min(brightMinY, sy);
                    brightMaxY = Math.max(brightMaxY, sy);
                }
                candidates.push({
                    sourceX: sx,
                    sourceY: sy,
                    r, g, b,
                    brightness: brightness / 255,
                });
            }
        }

        // Frame the luminous humanoid, not the large black border of the portrait artwork.
        const brightW = Math.max(1, brightMaxX - brightMinX);
        const brightH = Math.max(1, brightMaxY - brightMinY);
        const fitScale = Math.min(this.width * 0.84 / brightW, this.height * 0.92 / brightH);
        const brightCenterX = (brightMinX + brightMaxX) * 0.5;
        const brightCenterY = (brightMinY + brightMaxY) * 0.5;
        for (const p of candidates) {
            p.tx = this.width * 0.5 + (p.sourceX - brightCenterX) * fitScale;
            p.ty = this.height * 0.5 + (p.sourceY - brightCenterY) * fitScale;
        }

        const maxParticles = 5200;
        const stride = Math.max(1, candidates.length / maxParticles);
        const particles = [];
        for (let cursor = Math.random() * stride; cursor < candidates.length; cursor += stride) {
            const p = candidates[Math.floor(cursor)];
            const angle = Math.random() * Math.PI * 2;
            const radius = Math.sqrt(Math.random()) * Math.max(this.width, this.height) * 0.62;
            particles.push({
                ...p,
                x: this.width * 0.5 + Math.cos(angle) * radius,
                y: this.height * 0.5 + Math.sin(angle) * radius,
                sx: 0,
                sy: 0,
                phase: Math.random() * Math.PI * 2,
                drift: 0.45 + Math.random() * 1.35,
                size: 0.7 + p.brightness * 1.65 + Math.random() * 0.45,
            });
        }
        this.particles = particles;
        this.centerX = this.width * 0.5;
        this.centerY = this.height * 0.52;
    }

    reset() {
        for (const p of this.particles) {
            const angle = Math.random() * Math.PI * 2;
            const radius = Math.sqrt(Math.random()) * Math.max(this.width, this.height) * 0.62;
            p.x = this.centerX + Math.cos(angle) * radius;
            p.y = this.centerY + Math.sin(angle) * radius;
            p.sx = p.x;
            p.sy = p.y;
        }
        this.assemblyStartedAt = performance.now();
    }

    setState(stateName) {
        this.currentState = stateName;
        if (stateName === 'ASSEMBLING') {
            if (!this.assemblyEnabled) {
                for (const p of this.particles) {
                    p.x = p.tx;
                    p.y = p.ty;
                }
                this.currentState = 'IDLE';
            } else {
                this.reset();
            }
        }
    }

    setAudioLevel(level) {
        this.audioLevel = Math.max(0, Math.min(1, Number(level) || 0));
    }

    pause() {
        this.isPaused = true;
    }

    resume() {
        if (!this.isRunning) return;
        this.isPaused = false;
        this.lastFrameAt = performance.now();
    }

    animate(now) {
        if (!this.isRunning) return;
        // 30 FPS is fluid for a screensaver and halves CPU load in software-rendered WebViews.
        if (!this.isPaused && now - this.lastFrameAt >= 33) {
            this.lastFrameAt = now;
            this.render(now);
        }
        this.rafId = requestAnimationFrame(next => this.animate(next));
    }

    render(now) {
        const ctx = this.ctx;
        ctx.fillStyle = '#000';
        ctx.fillRect(0, 0, this.width, this.height);

        const seconds = now * 0.001 * this.animationSpeedMultiplier;
        const breathe = 1 + Math.sin(seconds * 1.15) * 0.009;
        const speaking = this.currentState === 'SPEAKING' ? this.audioLevel : 0;
        let assembly = 1;
        if (this.currentState === 'ASSEMBLING') {
            const raw = Math.min(1, (now - this.assemblyStartedAt) /
                (this.assemblyDurationMs / this.animationSpeedMultiplier));
            assembly = raw < 0.5 ? 4 * raw * raw * raw : 1 - Math.pow(-2 * raw + 2, 3) / 2;
            if (raw >= 1) {
                this.currentState = 'IDLE';
                window.particleInterface?.setState('IDLE');
            }
        }

        for (const p of this.particles) {
            let px;
            let py;
            if (assembly < 1) {
                px = p.sx + (p.tx - p.sx) * assembly;
                py = p.sy + (p.ty - p.sy) * assembly;
            } else {
                const targetX = this.centerX + (p.tx - this.centerX) * breathe;
                const targetY = this.centerY + (p.ty - this.centerY) * breathe;
                px = targetX + Math.sin(seconds * (0.65 + p.drift * 0.16) + p.phase) * p.drift;
                py = targetY + Math.cos(seconds * (0.55 + p.drift * 0.13) + p.phase) * p.drift;
                p.x = px;
                p.y = py;
            }

            const pulse = 0.86 + 0.14 * Math.sin(seconds * 2.1 + p.phase) + speaking * 0.28;
            const alpha = Math.max(0.32, Math.min(1, p.brightness * pulse + 0.18));
            ctx.fillStyle = `rgba(${p.r},${p.g},${p.b},${alpha})`;
            const size = p.size * (1 + speaking * 0.22);
            ctx.fillRect(px - size * 0.5, py - size * 0.5, size, size);
        }
    }
}
