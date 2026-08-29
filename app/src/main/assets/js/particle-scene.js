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
 * Performance: targets 60fps with adaptive particle count.
 */
class ParticleScene {
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
        this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5)); // adaptive

        // State
        this.currentState = 'IDLE';
        this.audioLevel = 0;
        this.elapsedTime = 0;
        this.frameCount = 0;

        // Particles
        this.particles = [];
        this.particleGeometry = null;
        this.particleSystem = null;

        // Configuration
        this.config = {
            particleCount: 12000,
            assemblyDurationMs: 3000,
            idleScale: 1.0,
            headRadius: 80,
        };

        // Performance monitoring
        this.perfHistory = [];
        this.perfCheckInterval = 60; // frames between checks
        this.lastFrameTime = performance.now();

        // Handle resize
        window.addEventListener('resize', () => this.onWindowResize());
    }

    start() {
        if (this.isRunning) return;
        this.isRunning = true;
        this.isPaused = false;
        this.log('ParticleScene started');
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
        this.log('ParticleScene reset');
        this.elapsedTime = 0;
        this.frameCount = 0;
        this.audioLevel = 0;
        if (this.particles.length === 0) {
            this.initializeParticles();
        } else {
            // Reset particles to start positions
            this.particles.forEach(p => {
                p.progress = 0;
                p.position.copy(p.startPosition);
            });
        }
    }

    setState(stateName) {
        if (this.currentState === stateName) return;
        this.currentState = stateName;
        this.log(`Particle state: ${stateName}`);

        switch (stateName) {
            case 'ASSEMBLING':
                this.reset();
                break;
            case 'LISTENING':
                // Audio-reactive animation ready
                break;
            case 'THINKING':
                // Activate brain area pulsing
                break;
            case 'SPEAKING':
                // Audio level sync active
                break;
            case 'SUCCESS':
                this.playSuccessAnimation();
                break;
            case 'ERROR':
                this.playErrorAnimation();
                break;
            case 'IDLE':
                // Default subtle motion
                break;
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
         * Approx. 12000 points at roughly 0.5–2.0 unit spacing.
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
        for (let h = 0; h < 60; h += 2) {
            const progress = h / 60;
            const radius = this.config.headRadius * 0.3 * (1 - progress);
            for (let i = 0; i < neckSegments; i++) {
                const theta = (2 * Math.PI * i) / neckSegments;
                const x = radius * Math.cos(theta);
                const y = -h * 2;
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
        if (this.particleSystem) this.scene.remove(this.particleSystem);

        const positions = new Float32Array(this.config.particleCount * 3);
        const colors = new Float32Array(this.config.particleCount * 3);

        for (let i = 0; i < this.config.particleCount; i++) {
            const idx = i * 3;

            // Random start position (spawn from edges)
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
        const w = window.innerWidth;
        const h = window.innerHeight;

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
        const deltaTime = 0.016; // ~60fps
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
        this.particleSystem.rotation.y += 0.0003;
    }

    updateAssembly(deltaTime) {
        /**
         * ASSEMBLING state: particles flow from start to target position.
         * Different start delays create a cascading assembly effect.
         * Assembly takes ~3 seconds total.
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

                // Add some noise for organic motion
                const noiseAmount = (1 - eased) * 30;
                p.position.x += Math.sin(p.age * 0.002 + i) * noiseAmount;
                p.position.y += Math.cos(p.age * 0.0025 + i) * noiseAmount;

                p.age++;
            }

            p.progress = staggeredProgress;
        }

        // Notify when assembly completes
        if (assemblyProgress >= 0.99 && this.frameCount % 30 === 0) {
            if (this.frameCount > 30) {
                // Only once
                this.currentState = 'IDLE';
                window.particleInterface?.setState('IDLE');
            }
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

            // Distance from brain center
            const dy = p.position.y - brainCenterY;
            const distToBrain = Math.sqrt(
                Math.pow(p.position.x, 2) + Math.pow(dy, 2) + Math.pow(p.position.z, 2)
            );

            if (distToBrain < brainRadius * 1.2) {
                // Particles in brain region rotate
                const angle = Math.atan2(p.position.z, p.position.x);
                const radius = Math.sqrt(Math.pow(p.position.x, 2) + Math.pow(p.position.z, 2));

                const newAngle = angle + (this.elapsedTime * 0.004);
                p.position.x = radius * Math.cos(newAngle);
                p.position.z = radius * Math.sin(newAngle);

                // Pulsate radius
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
         * Simulates speech articulation with particle movement.
         */
        const mouthY = -40;
        const mouthRadius = 50;

        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];

            // Mouth area particles (check Y proximity)
            if (Math.abs(p.position.y - mouthY) < mouthRadius * 1.5) {
                // Scale based on audio level
                const scale = 1 + this.audioLevel * 0.3;
                p.position.x *= scale;
                p.position.z *= scale;

                // Slight upward motion during speech
                p.position.y += this.audioLevel * 2 * deltaTime;
            }

            p.age++;
        }
    }

    updateIdle(deltaTime) {
        /**
         * IDLE: subtle, natural motion (breathing, slight drift).
         * Very low energy to not distract.
         */
        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];

            // Breathing: gentle scale pulse
            const breath = Math.sin(this.elapsedTime * 0.0008 + i * 0.01) * 0.5;

            // Return to target if drifted
            const toTarget = new THREE.Vector3().subVectors(p.targetPosition, p.position);
            toTarget.multiplyScalar(0.001); // small correction

            p.position.add(toTarget);
            p.position.y += breath * deltaTime;

            p.age++;
        }
    }

    playSuccessAnimation() {
        // Brief positive burst (handled by state auto-return in controller)
    }

    playErrorAnimation() {
        // Warning flash (handled by state auto-return in controller)
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
            info.textContent = `FPS: ${fps} | State: ${this.currentState} | Particles: ${this.config.particleCount}`;
        }
    }

    // ──────── Utilities ────────

    easeInOutCubic(t) {
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    monitorPerformance() {
        // TODO: Implement adaptive particle count based on FPS
        // If FPS drops below 50, reduce particle count slightly
        // If FPS stable above 55, increase particle count
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
