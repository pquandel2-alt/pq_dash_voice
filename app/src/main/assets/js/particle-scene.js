/**
 * ParticleScene: WebGL-rendered 3D particle system forming a futuristic AI head.
 *
 * States:
 * - IDLE: subtle pulsing, breathing motion
 * - ASSEMBLING: particles build up from chaos into form
 * - LISTENING: reactive to microphone input
 * - THINKING: processing animation (brain area)
 * - SPEAKING: mouth region follows TTS audio level
 * - SUCCESS: positive energy burst, then IDLE
 * - ERROR: warning animation
 *
 * Performance: targets 60fps with adaptive particle count (see configure()).
 */
class ParticleScene {
    static QUALITY_PRESETS = { LOW: 6000, MEDIUM: 12000, HIGH: 18000 };

    constructor(canvasElement) {
        this.canvas = canvasElement;
        this.isRunning = false;
        this.isPaused = false;

        // Three.js setup
        this.scene = new THREE.Scene();
        this.scene.background = new THREE.Color(0x000000);

        const w = window.innerWidth;
        const h = window.innerHeight;
        this.camera = new THREE.PerspectiveCamera(45, w / h, 0.1, 10000);
        this.camera.position.z = 500;

        this.renderer = new THREE.WebGLRenderer({ canvas: canvasElement, antialias: true, alpha: false });
        this.renderer.setSize(w, h);
        this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5)); // adaptive

        this.renderer.domElement.addEventListener('webglcontextlost', (e) => {
            e.preventDefault();
            this.isRunning = false;
            this.log('WebGL context lost');
        }, false);
        this.renderer.domElement.addEventListener('webglcontextrestored', () => {
            this.log('WebGL context restored');
            this.start();
        }, false);

        // State
        this.currentState = 'IDLE';
        this.audioLevel = 0;
        this.elapsedTime = 0;
        this.frameCount = 0;

        // Particles
        this.particles = [];
        this.particleGeometry = null;
        this.particleSystem = null;

        // Configuration (overridden by configure() before start())
        this.config = {
            quality: 'AUTO',
            particleCount: ParticleScene.QUALITY_PRESETS.MEDIUM,
            assemblyDurationMs: 3000,
            animationSpeedMultiplier: 1.0,
            assemblyEnabled: true,
            idleScale: 1.0,
            headRadius: 80,
        };

        // Performance monitoring (one-shot AUTO-quality downgrade if FPS is poor)
        this.perfHistory = [];
        this.perfLastTime = performance.now();
        this.autoDowngraded = false;
        this.lastFrameTime = performance.now();

        // Handle resize
        window.addEventListener('resize', () => this.onWindowResize());
    }

    /** Muss vor start() aufgerufen werden. opts: {quality, animationSpeedMultiplier, assemblyEnabled} */
    configure(opts = {}) {
        const quality = opts.quality || 'AUTO';
        this.config.quality = quality;
        this.config.particleCount = quality === 'AUTO'
            ? this.autoDetectParticleCount()
            : (ParticleScene.QUALITY_PRESETS[quality] || ParticleScene.QUALITY_PRESETS.MEDIUM);
        this.config.animationSpeedMultiplier = opts.animationSpeedMultiplier > 0 ? opts.animationSpeedMultiplier : 1.0;
        this.config.assemblyEnabled = opts.assemblyEnabled !== false;
        this.log(`Particle quality: ${quality} (${this.config.particleCount} particles), ` +
            `speed=${this.config.animationSpeedMultiplier}x, assembly=${this.config.assemblyEnabled}`);
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
        this.generateTargetGeometry();
        this.initializeParticles();
        this.animate();
    }

    pause() {
        this.isPaused = true;
        this.log('ParticleScene paused');
    }

    resume() {
        if (!this.isRunning) return;
        this.isPaused = false;
        this.log('ParticleScene resumed');
        this.animate();
    }

    reset() {
        this.log('Screensaver state → ASSEMBLING (scene reset)');
        this.elapsedTime = 0;
        this.frameCount = 0;
        this.audioLevel = 0;
        this.assemblyCompleteLogged = false;
        if (this.particles.length === 0) {
            this.initializeParticles();
        } else {
            // Fresh spawn positions every time — never just re-fade an already-assembled figure.
            this.particles.forEach(p => {
                p.startPosition = this.randomStartPosition();
                p.position.copy(p.startPosition);
                p.progress = 0;
                p.age = Math.random() * 1000;
            });
        }
    }

    setState(stateName) {
        if (this.currentState === stateName) return;
        this.currentState = stateName;
        this.log(`Particle state: ${stateName}`);

        if (stateName === 'ASSEMBLING' && !this.config.assemblyEnabled) {
            // Assembly-animation disabled in settings → snap straight to the finished figure.
            this.particles.forEach(p => p.position.copy(p.targetPosition));
            this.currentState = 'IDLE';
            this.log('Assembly completed (instant, animation disabled)');
            window.particleInterface?.setState('IDLE');
            return;
        }

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
        // Affects particle motion in LISTENING and SPEAKING states
    }

    // ──────── Private: Initialization ────────

    generateTargetGeometry() {
        /**
         * Generate target positions for the particles to form a humanoid AI head.
         * A procedural point cloud approximating:
         * - Head dome (sphere)
         * - Face features (eye sockets, nose, mouth)
         * - Neck/shoulder outline
         */

        const targetPoints = [];

        // Head dome: upper hemisphere
        const headSegments = 40;
        const headRings = 30;
        for (let i = 0; i < headRings; i++) {
            const phi = (Math.PI * i) / headRings;
            for (let j = 0; j < headSegments; j++) {
                const theta = (2 * Math.PI * j) / headSegments;
                const x = this.config.headRadius * Math.sin(phi) * Math.cos(theta);
                const y = this.config.headRadius * Math.cos(phi);
                const z = this.config.headRadius * Math.sin(phi) * Math.sin(theta);
                targetPoints.push(new THREE.Vector3(x, y, z));
            }
        }

        // Face details: eye sockets (simplified)
        const eyeOffsetY = 30;
        const eyeOffsetZ = 50;
        const eyeRadius = 12;
        for (let side of [-1, 1]) {
            for (let i = 0; i < 100; i++) {
                const angle = (2 * Math.PI * i) / 100;
                const x = side * (this.config.headRadius * 0.4);
                const y = eyeOffsetY + eyeRadius * Math.cos(angle);
                const z = eyeOffsetZ + eyeRadius * Math.sin(angle);
                targetPoints.push(new THREE.Vector3(x, y, z));
            }
        }

        // Neck/shoulder: tapered cone
        const neckSegments = 30;
        for (let hgt = 0; hgt < 60; hgt += 2) {
            const progress = hgt / 60;
            const radius = this.config.headRadius * 0.3 * (1 - progress);
            for (let i = 0; i < neckSegments; i++) {
                const theta = (2 * Math.PI * i) / neckSegments;
                const x = radius * Math.cos(theta);
                const y = -hgt * 2;
                const z = radius * Math.sin(theta);
                targetPoints.push(new THREE.Vector3(x, y, z));
            }
        }

        // Pad to target count if needed
        while (targetPoints.length < this.config.particleCount) {
            const existing = targetPoints[Math.floor(Math.random() * targetPoints.length)];
            const jitter = 3;
            targetPoints.push(
                new THREE.Vector3(
                    existing.x + (Math.random() - 0.5) * jitter,
                    existing.y + (Math.random() - 0.5) * jitter,
                    existing.z + (Math.random() - 0.5) * jitter
                )
            );
        }

        this.targetGeometry = targetPoints.slice(0, this.config.particleCount);
    }

    initializeParticles() {
        // Clear old system
        if (this.particleSystem) {
            this.scene.remove(this.particleSystem);
            this.particleSystem.geometry.dispose();
            this.particleSystem.material.dispose();
        }
        this.particles = [];

        const positions = new Float32Array(this.config.particleCount * 3);
        const colors = new Float32Array(this.config.particleCount * 3);

        for (let i = 0; i < this.config.particleCount; i++) {
            const idx = i * 3;

            // Random start position (spawn from edges/depth/cloud)
            const startPos = this.randomStartPosition();
            positions[idx] = startPos.x;
            positions[idx + 1] = startPos.y;
            positions[idx + 2] = startPos.z;

            // Color: cyan/white glow
            const hue = 0.5 + Math.random() * 0.1; // cyan
            const color = new THREE.Color();
            color.setHSL(hue, 0.8, 0.6);
            colors[idx] = color.r;
            colors[idx + 1] = color.g;
            colors[idx + 2] = color.b;

            // Particle data
            this.particles.push({
                position: new THREE.Vector3(startPos.x, startPos.y, startPos.z),
                startPosition: startPos,
                targetPosition: this.targetGeometry[i],
                velocity: new THREE.Vector3(
                    (Math.random() - 0.5) * 2,
                    (Math.random() - 0.5) * 2,
                    (Math.random() - 0.5) * 2
                ),
                mass: 1.0,
                progress: 0, // 0 = at start, 1 = at target
                age: Math.random() * 1000, // offset animation start
                noiseSeed: Math.random(),
            });
        }

        const geometry = new THREE.BufferGeometry();
        geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
        geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));

        const material = new THREE.PointsMaterial({
            size: 2.0,
            sizeAttenuation: true,
            vertexColors: true,
            transparent: true,
            opacity: 0.9,
        });

        this.particleSystem = new THREE.Points(geometry, material);
        this.scene.add(this.particleSystem);
        this.log(`Initialized ${this.config.particleCount} particles`);
    }

    randomStartPosition() {
        /**
         * Spawn from edges/depth:
         * - Screen edges (left/right/top/bottom)
         * - Behind camera (depth)
         * - Random cloud around origin
         */
        const mode = Math.random();

        if (mode < 0.3) {
            // Screen edge
            const side = Math.random() < 0.5 ? 'left' : 'right';
            return new THREE.Vector3(
                side === 'left' ? -400 : 400,
                (Math.random() - 0.5) * 800,
                (Math.random() - 0.5) * 400
            );
        } else if (mode < 0.6) {
            // Depth (behind camera)
            return new THREE.Vector3(
                (Math.random() - 0.5) * 600,
                (Math.random() - 0.5) * 600,
                -800 + Math.random() * 400
            );
        } else {
            // Random cloud
            return new THREE.Vector3(
                (Math.random() - 0.5) * 800,
                (Math.random() - 0.5) * 800,
                (Math.random() - 0.5) * 800
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
        const deltaTime = 0.016 * this.config.animationSpeedMultiplier; // ~60fps, speed-scaled
        this.elapsedTime += deltaTime * 1000; // ms
        this.frameCount++;

        // Update particle positions
        switch (this.currentState) {
            case 'ASSEMBLING':
                this.updateAssembly(deltaTime);
                break;
            case 'LISTENING':
                this.updateListening(deltaTime);
                break;
            case 'THINKING':
                this.updateThinking(deltaTime);
                break;
            case 'SPEAKING':
                this.updateSpeaking(deltaTime);
                break;
            default: // IDLE, SUCCESS, ERROR
                this.updateIdle(deltaTime);
        }

        // Update geometry
        const positions = this.particleSystem.geometry.attributes.position.array;
        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];
            const idx = i * 3;
            positions[idx] = p.position.x;
            positions[idx + 1] = p.position.y;
            positions[idx + 2] = p.position.z;
        }
        this.particleSystem.geometry.attributes.position.needsUpdate = true;

        // Rotate scene slightly for visual interest
        this.particleSystem.rotation.y += 0.0003 * this.config.animationSpeedMultiplier;
    }

    updateAssembly(deltaTime) {
        /**
         * ASSEMBLING state: particles flow from start to target position.
         * Different per-particle delays create a cascading assembly effect.
         */
        const assemblyProgress = Math.min(1, this.elapsedTime / this.config.assemblyDurationMs);

        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];
            const individualDelay = p.noiseSeed; // 0..1
            const staggeredProgress = Math.max(0, assemblyProgress - individualDelay * 0.5);

            if (staggeredProgress > 0) {
                // Easing: start slow, accelerate, ease into place
                const eased = this.easeInOutCubic(staggeredProgress);

                // Interpolate toward target
                p.position.lerpVectors(p.startPosition, p.targetPosition, eased);

                // Add some noise for organic (non-linear) motion
                const noiseAmount = (1 - eased) * 30;
                p.position.x += Math.sin(p.age * 0.002 + i) * noiseAmount;
                p.position.y += Math.cos(p.age * 0.0025 + i) * noiseAmount;

                p.age++;
            }

            p.progress = staggeredProgress;
        }

        if (assemblyProgress >= 1 && !this.assemblyCompleteLogged) {
            this.assemblyCompleteLogged = true;
            this.log('Assembly completed');
            this.currentState = 'IDLE';
            window.particleInterface?.setState('IDLE');
        }
    }

    updateListening(deltaTime) {
        /**
         * LISTENING: particles subtly react to audio input (microphone level).
         * Affected particles: face/mouth area primarily.
         */
        const mouthThreshold = -50; // Y coordinate for mouth area

        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];

            // Base idle motion
            const baseMotion = Math.sin(this.elapsedTime * 0.002 + i * 0.1) * 2;
            p.position.y += baseMotion * deltaTime;

            // Audio-reactive mouth/face
            if (p.position.y < mouthThreshold) {
                const audioMotion = this.audioLevel * 15;
                p.position.y += audioMotion * Math.sin(this.elapsedTime * 0.01 + i);
            }

            p.age++;
        }
    }

    updateThinking(deltaTime) {
        /**
         * THINKING: head/brain area pulsates with energy.
         * Creates rotating/spiraling effect in upper portion.
         */
        const brainCenterY = 60;
        const brainRadius = 100;

        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];

            const dy = p.position.y - brainCenterY;
            const distToBrain = Math.sqrt(
                Math.pow(p.position.x, 2) + Math.pow(dy, 2) + Math.pow(p.position.z, 2)
            );

            if (distToBrain < brainRadius * 1.2) {
                const angle = Math.atan2(p.position.z, p.position.x);
                const radius = Math.sqrt(Math.pow(p.position.x, 2) + Math.pow(p.position.z, 2));

                const newAngle = angle + (this.elapsedTime * 0.004);
                p.position.x = radius * Math.cos(newAngle);
                p.position.z = radius * Math.sin(newAngle);

                const pulse = Math.sin(this.elapsedTime * 0.005) * 5;
                p.position.x *= 1 + pulse * 0.02;
                p.position.z *= 1 + pulse * 0.02;
            }

            p.age++;
        }
    }

    updateSpeaking(deltaTime) {
        /**
         * SPEAKING: mouth region responds to TTS audio level.
         */
        const mouthY = -40;
        const mouthRadius = 50;

        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];

            if (Math.abs(p.position.y - mouthY) < mouthRadius * 1.5) {
                const scale = 1 + this.audioLevel * 0.3;
                p.position.x *= scale;
                p.position.z *= scale;
                p.position.y += this.audioLevel * 2 * deltaTime;
            }

            p.age++;
        }
    }

    updateIdle(deltaTime) {
        /**
         * IDLE: subtle, natural motion (breathing, slight drift).
         */
        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];

            const breath = Math.sin(this.elapsedTime * 0.0008 + i * 0.01) * 0.5;

            const toTarget = new THREE.Vector3().subVectors(p.targetPosition, p.position);
            toTarget.multiplyScalar(0.001); // small correction back to the settled figure

            p.position.add(toTarget);
            p.position.y += breath * deltaTime;

            p.age++;
        }
    }

    playSuccessAnimation() {
        // Brief positive burst; auto-return to IDLE is handled by ParticleAssistantController.
    }

    playErrorAnimation() {
        // Warning flash; auto-return to IDLE is handled by ParticleAssistantController.
    }

    // ──────── Rendering ────────

    render() {
        this.renderer.render(this.scene, this.camera);
        this.updateDebugInfo();
    }

    updateDebugInfo() {
        if (this.frameCount % 30 !== 0) return; // Update every 30 frames
        const fps = Math.round(1 / (performance.now() - this.lastFrameTime) * 1000);
        this.lastFrameTime = performance.now();

        const info = document.getElementById('debugInfo');
        if (info) {
            info.textContent = `FPS: ${fps} | State: ${this.currentState} | Particles: ${this.config.particleCount} | Quality: ${this.config.quality}`;
        }
    }

    // ──────── Utilities ────────

    easeInOutCubic(t) {
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

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
        if (this.perfHistory.length < 120) return; // ~2s at 60fps

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
            this.initializeParticles();
        }
    }

    onWindowResize() {
        const w = window.innerWidth;
        const h = window.innerHeight;
        this.camera.aspect = w / h;
        this.camera.updateProjectionMatrix();
        this.renderer.setSize(w, h);
    }

    log(msg) {
        if (window.particleInterface) {
            window.particleInterface.log(msg);
        } else {
            console.log(msg);
        }
    }
}
