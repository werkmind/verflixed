/**
 * Canvas port of Android VerflixedIntroView + BrandSting.
 * Perspective-projected 3D V + deep original sting (ogg, WebAudio fallback).
 */
window.VfIntro = (() => {
  const DEFAULT_DURATION_MS = 3400;
  const HOLD_AFTER_MS = 220;

  const FACE_TOP = [183, 219, 255];
  const FACE_MID = [47, 128, 255];
  const SIDE_DARK = [8, 26, 64];
  const SIDE_DEEP = [3, 8, 20];
  const SIDE_LIGHT = [28, 98, 200];
  const ACCENT_SOFT = [143, 182, 232];
  const ATMO_CORE = [12, 24, 52];
  const ATMO_MID = [6, 12, 28];
  const ATMO_EDGE = [1, 3, 8];
  const LIGHT = norm3(0.32, -0.72, 0.62);
  const DUST = [
    -1.4, -0.8, 0.6, 1.2, -0.4, -0.5, -0.9, 0.7, 0.9,
    1.5, 0.3, 0.2, -1.1, 0.1, -0.8, 0.4, -1.1, 0.7,
    0.8, 0.9, -0.3, -0.3, -0.6, 1.1, 1.6, -0.9, 0.4,
    -1.6, 0.5, 0.1, 0.2, 1.0, -0.9, -0.6, -1.2, -0.2,
  ];

  function bezierEase(x1, y1, x2, y2) {
    return (t) => {
      let x = t;
      for (let i = 0; i < 6; i++) {
        const cx = 3 * x1;
        const bx = 3 * (x2 - x1) - cx;
        const ax = 1 - cx - bx;
        const d = (3 * ax * x + 2 * bx) * x + cx;
        if (Math.abs(d) < 1e-6) break;
        x -= (((ax * x + bx) * x + cx) * x - t) / d;
      }
      const cy = 3 * y1;
      const by = 3 * (y2 - y1) - cy;
      const ay = 1 - cy - by;
      return ((ay * x + by) * x + cy) * x;
    };
  }

  const easeOut = bezierEase(0.16, 1, 0.3, 1);
  const easeInOut = bezierEase(0.4, 0, 0.2, 1);

  function lerp(a, b, t) {
    return a + (b - a) * t;
  }

  function lerpColor(from, to, t) {
    const k = Math.max(0, Math.min(1, t));
    return [
      Math.round(lerp(from[0], to[0], k)),
      Math.round(lerp(from[1], to[1], k)),
      Math.round(lerp(from[2], to[2], k)),
    ];
  }

  function rgba(c, a) {
    return `rgba(${c[0]},${c[1]},${c[2]},${a})`;
  }

  function rgb(c) {
    return `rgb(${c[0]},${c[1]},${c[2]})`;
  }

  function norm3(x, y, z) {
    const len = Math.hypot(x, y, z) || 1;
    return { x: x / len, y: y / len, z: z / len };
  }

  function rotate(p, rotX, rotY) {
    const cy = Math.cos(rotY);
    const sy = Math.sin(rotY);
    const x1 = p.x * cy + p.z * sy;
    const z1 = -p.x * sy + p.z * cy;
    const cx = Math.cos(rotX);
    const sx = Math.sin(rotX);
    return { x: x1, y: p.y * cx - z1 * sx, z: p.y * sx + z1 * cx };
  }

  function project(p, focal, camZ) {
    const d = focal / Math.max(0.35, camZ + p.z);
    return { x: p.x * d, y: p.y * d };
  }

  function normal(a, b, c) {
    const ux = b.x - a.x;
    const uy = b.y - a.y;
    const uz = b.z - a.z;
    const vx = c.x - a.x;
    const vy = c.y - a.y;
    const vz = c.z - a.z;
    return norm3(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx);
  }

  function outline(size) {
    const halfW = size * 0.62;
    const top = -size * 0.6;
    const bottom = size * 0.62;
    const thick = size * 0.3;
    return [
      { x: -halfW, y: top },
      { x: -halfW + thick, y: top },
      { x: 0, y: bottom - thick * 0.55 },
      { x: halfW - thick, y: top },
      { x: halfW, y: top },
      { x: thick * 0.3, y: bottom },
      { x: -thick * 0.3, y: bottom },
    ];
  }

  function buildFaces(size, half) {
    const ring = outline(size);
    const at = (i, z) => ({ x: ring[i].x, y: ring[i].y, z });
    const faces = [];
    const front = (a, b, c, d) => faces.push({ a, b, c, d, kind: "front" });
    const back = (a, b, c, d) => faces.push({ a, b, c, d, kind: "back" });
    front(at(0, half), at(1, half), at(2, half), at(2, half));
    front(at(0, half), at(2, half), at(6, half), at(6, half));
    front(at(2, half), at(3, half), at(4, half), at(5, half));
    front(at(2, half), at(5, half), at(6, half), at(6, half));
    back(at(0, -half), at(6, -half), at(2, -half), at(1, -half));
    back(at(2, -half), at(6, -half), at(5, -half), at(4, -half));
    back(at(2, -half), at(4, -half), at(3, -half), at(3, -half));
    for (let i = 0; i < ring.length; i++) {
      const j = (i + 1) % ring.length;
      faces.push({
        a: at(i, half),
        b: at(j, half),
        c: at(j, -half),
        d: at(i, -half),
        kind: "side",
      });
    }
    return faces;
  }

  function shadeColor(kind, shade) {
    const base = kind === "front" ? FACE_MID : kind === "back" ? SIDE_DEEP : SIDE_DARK;
    const hi = kind === "front" ? FACE_TOP : kind === "back" ? SIDE_DARK : SIDE_LIGHT;
    return lerpColor(base, hi, shade);
  }

  function drawSolidV(ctx, size, rotX, rotY, camZ, focal, alpha, reflect) {
    const half = size * 0.34;
    const faces = buildFaces(size, half);
    const lit = [];
    for (const face of faces) {
      const r0 = rotate(face.a, rotX, rotY);
      const r1 = rotate(face.b, rotX, rotY);
      const r2 = rotate(face.c, rotX, rotY);
      const r3 = rotate(face.d, rotX, rotY);
      const n = normal(r0, r1, r2);
      if (n.z <= 0.02 && !reflect) continue;
      const shade = Math.max(0, Math.min(1, 0.18 + 0.82 * Math.max(0, n.x * LIGHT.x + n.y * LIGHT.y + n.z * LIGHT.z)));
      lit.push({
        p0: project(r0, focal, camZ),
        p1: project(r1, focal, camZ),
        p2: project(r2, focal, camZ),
        p3: project(r3, focal, camZ),
        z: (r0.z + r1.z + r2.z + r3.z) * 0.25,
        shade,
        kind: face.kind,
      });
    }
    lit.sort((a, b) => a.z - b.z);
    const fade = reflect ? 0.22 : 1;
    const ySign = reflect ? -1 : 1;
    const yOff = reflect ? size * 1.55 : 0;
    for (const f of lit) {
      ctx.beginPath();
      ctx.moveTo(f.p0.x, yOff + f.p0.y * ySign);
      ctx.lineTo(f.p1.x, yOff + f.p1.y * ySign);
      ctx.lineTo(f.p2.x, yOff + f.p2.y * ySign);
      ctx.lineTo(f.p3.x, yOff + f.p3.y * ySign);
      ctx.closePath();
      ctx.fillStyle = rgb(shadeColor(f.kind, f.shade));
      ctx.globalAlpha = alpha * fade;
      ctx.fill();
    }
    ctx.globalAlpha = 1;
    if (!reflect && alpha > 0.4) {
      ctx.save();
      ctx.translate(0, size * 0.02);
      drawSolidV(ctx, size, rotX, rotY, camZ + 0.15, focal, alpha * 0.18, true);
      ctx.restore();
    }
  }

  function drawAtmosphere(ctx, w, h, k, shaftK) {
    if (k <= 0) return;
    const g = ctx.createRadialGradient(w / 2, h * 0.4, 0, w / 2, h * 0.4, h * 1.15);
    g.addColorStop(0, rgba(ATMO_CORE, 0.86 * k));
    g.addColorStop(0.52, rgba(ATMO_MID, 0.86 * k));
    g.addColorStop(1, rgba(ATMO_EDGE, 0.86 * k));
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, w, h);
    if (shaftK > 0) {
      const sg = ctx.createLinearGradient(w * 0.28, 0, w * 0.72, h);
      sg.addColorStop(0.2, "rgba(47,128,255,0)");
      sg.addColorStop(0.5, `rgba(47,128,255,${0.14 * shaftK})`);
      sg.addColorStop(0.8, "rgba(47,128,255,0)");
      ctx.fillStyle = sg;
      ctx.fillRect(w * 0.22, 0, w * 0.56, h * 0.72);
    }
    const hg = ctx.createLinearGradient(0, h * 0.58, 0, h * 0.96);
    hg.addColorStop(0, "rgba(47,128,255,0)");
    hg.addColorStop(0.48, `rgba(47,128,255,${0.2 * k})`);
    hg.addColorStop(1, "rgba(47,128,255,0)");
    ctx.fillStyle = hg;
    ctx.fillRect(0, h * 0.52, w, h * 0.48);
  }

  function drawFrame(canvas, progress, opts) {
    const ctx = canvas.getContext("2d");
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    if (!w || !h) return;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = "#000";
    ctx.fillRect(0, 0, w, h);

    const compact = !!opts.compact;
    const wordmark = opts.wordmark || "VERFLIXED";
    const tagline = opts.tagline || "Serien & Filme";
    const seg = (from, to) => {
      if (to <= from) return progress >= to ? 1 : 0;
      return Math.max(0, Math.min(1, (progress - from) / (to - from)));
    };

    drawAtmosphere(ctx, w, h, easeOut(seg(0, 0.48)), easeInOut(seg(0.08, 0.55)));

    const markSize = Math.min(w, h) * (compact ? 0.3 : 0.34);
    const cx = w / 2;
    const cy = h / 2 - markSize * (compact ? 0.14 : 0.2);
    const flyIn = easeOut(seg(0, 0.5));
    const settle = easeOut(seg(0.82, 1));
    const depth = 1 - flyIn;
    const rotY = 0.98 * depth + 0.2;
    const rotX = 0.42 * depth + 0.11;
    const camZ = 3.55 - 1.35 * flyIn;
    const focal = markSize * 1.22;
    const scale = 0.78 + 0.26 * flyIn - 0.03 * settle * Math.sin(settle * Math.PI);
    const markAlpha = Math.min(1, seg(0.02, 0.28) * 1.25);

    const floorA = 0.28 * flyIn;
    if (floorA > 0) {
      const rw = markSize * (1.85 + 0.25 * Math.sin(rotY));
      const rh = markSize * 0.28;
      const fg = ctx.createRadialGradient(cx, cy + markSize * 0.92, 0, cx, cy + markSize * 0.92, rw);
      fg.addColorStop(0, `rgba(58,140,255,${floorA})`);
      fg.addColorStop(1, "rgba(58,140,255,0)");
      ctx.fillStyle = fg;
      ctx.beginPath();
      ctx.ellipse(cx, cy + markSize * 0.78, rw, rh, 0, 0, Math.PI * 2);
      ctx.fill();
    }

    for (let i = 0; i < DUST.length; i += 3) {
      const p = project(
        rotate({ x: DUST[i] * markSize, y: DUST[i + 1] * markSize, z: DUST[i + 2] * markSize }, rotX, rotY),
        focal,
        camZ,
      );
      const twinkle = 0.45 + 0.55 * Math.abs(Math.sin(progress * 6 + i));
      ctx.fillStyle = `rgba(255,255,255,${0.35 * flyIn * twinkle})`;
      ctx.beginPath();
      ctx.arc(cx + p.x, cy + p.y, 1.4 + 1.1 * twinkle, 0, Math.PI * 2);
      ctx.fill();
    }

    const glowA = 0.5 * flyIn * (0.5 + 0.5 * easeInOut(seg(0.28, 0.68)));
    if (glowA > 0) {
      const r = markSize * (2.7 + 0.4 * flyIn);
      const gg = ctx.createRadialGradient(cx, cy, 0, cx, cy, r);
      gg.addColorStop(0, `rgba(47,128,255,${glowA})`);
      gg.addColorStop(0.42, `rgba(47,128,255,${glowA * 0.32})`);
      gg.addColorStop(1, "rgba(47,128,255,0)");
      ctx.fillStyle = gg;
      ctx.beginPath();
      ctx.arc(cx, cy, r, 0, Math.PI * 2);
      ctx.fill();
    }

    ctx.save();
    ctx.translate(cx, cy);
    ctx.scale(scale, scale);
    drawSolidV(ctx, markSize, rotX, rotY, camZ, focal, markAlpha, false);

    const sweepK = seg(0.46, 0.68);
    if (sweepK > 0 && sweepK < 1) {
      const eased = easeInOut(sweepK);
      const ring = outline(markSize);
      ctx.beginPath();
      ring.forEach((p, i) => {
        const pr = project(rotate({ x: p.x, y: p.y, z: markSize * 0.34 }, rotX, rotY), focal, camZ);
        if (i === 0) ctx.moveTo(pr.x, pr.y);
        else ctx.lineTo(pr.x, pr.y);
      });
      ctx.closePath();
      ctx.save();
      ctx.clip();
      const bandW = markSize * 0.9;
      const x = -markSize * 1.8 + eased * markSize * 3.6;
      const sg = ctx.createLinearGradient(x - bandW, 0, x + bandW, 0);
      sg.addColorStop(0, "rgba(242,248,255,0)");
      sg.addColorStop(0.5, `rgba(242,248,255,${0.78 * Math.sin(sweepK * Math.PI)})`);
      sg.addColorStop(1, "rgba(242,248,255,0)");
      ctx.fillStyle = sg;
      ctx.fillRect(x - bandW, -markSize * 1.8, bandW * 2, markSize * 3.6);
      ctx.restore();
    }
    ctx.restore();

    const textSize = markSize * (compact ? 0.32 : 0.38);
    ctx.font = `700 ${textSize}px "Segoe UI", ui-sans-serif, system-ui, sans-serif`;
    ctx.textBaseline = "alphabetic";
    const tracking = textSize * 0.26;
    const letters = [...wordmark];
    const widths = letters.map((ch) => ctx.measureText(ch).width);
    const total = widths.reduce((a, b) => a + b, 0) + tracking * (letters.length - 1);
    let x = cx - total / 2;
    const baseline = cy + markSize * 1.22;
    letters.forEach((ch, i) => {
      const start = 0.6 + (i / letters.length) * 0.26;
      const k = easeOut(seg(start, start + 0.18));
      if (k > 0) {
        ctx.save();
        ctx.globalAlpha = Math.min(1, k * 1.25);
        ctx.fillStyle = "#fff";
        const rise = (1 - k) * textSize * 0.45;
        const mid = x + widths[i] / 2;
        ctx.translate(mid, baseline + rise);
        ctx.scale(1, 0.88 + 0.12 * k);
        ctx.fillText(ch, -widths[i] / 2, 0);
        ctx.restore();
      }
      x += widths[i] + tracking;
    });

    if (!compact) {
      const tk = easeOut(seg(0.78, 0.96));
      if (tk > 0) {
        ctx.globalAlpha = 0.78 * tk;
        ctx.fillStyle = rgb(ACCENT_SOFT);
        ctx.font = `400 ${textSize * 0.46}px "Segoe UI", ui-sans-serif, system-ui, sans-serif`;
        ctx.textAlign = "center";
        ctx.fillText(tagline, cx, baseline + textSize * (1.12 - 0.22 * tk));
        ctx.textAlign = "start";
        ctx.globalAlpha = 1;
      }
    }
  }

  const playing = new WeakMap();

  /** Rendered 3D opener that ships with the app (audio baked in). */
  const VIDEO_IDS = { splashIntro: "splashIntroVideo", playerIntro: "playerIntroVideo" };

  function videoFor(canvas) {
    const id = canvas && VIDEO_IDS[canvas.id];
    return id ? document.getElementById(id) : null;
  }

  function stopVideo(video) {
    if (!video) return;
    try {
      video.pause();
      video.currentTime = 0;
    } catch (_) {}
    video.classList.remove("active");
  }

  function playVideo(video) {
    return new Promise((resolve, reject) => {
      let settled = false;
      const done = (ok) => {
        if (settled) return;
        settled = true;
        video.removeEventListener("ended", onEnd);
        video.removeEventListener("error", onErr);
        ok ? resolve() : reject(new Error("intro video failed"));
      };
      const onEnd = () => done(true);
      const onErr = () => done(false);
      video.addEventListener("ended", onEnd);
      video.addEventListener("error", onErr);
      try {
        video.currentTime = 0;
        video.muted = false;
        video.volume = 1;
        video.classList.add("active");
        const p = video.play();
        if (p && p.catch) {
          p.catch(() => {
            // Autoplay with audio blocked (browser/PWA): keep the picture,
            // run it muted and let the WebAudio sting carry the sound.
            video.muted = true;
            sting();
            const retry = video.play();
            if (retry && retry.catch) retry.catch(() => done(false));
          });
        }
      } catch (_) {
        done(false);
      }
      // Safety net if 'ended' never fires (codec stall).
      setTimeout(() => done(true), DEFAULT_DURATION_MS + 1200);
    });
  }

  function stop(canvas) {
    stopVideo(videoFor(canvas));
    const rec = canvas && playing.get(canvas);
    if (rec?.raf) cancelAnimationFrame(rec.raf);
    if (canvas) playing.delete(canvas);
  }

  function playCanvas(canvas, opts = {}) {
    if (!canvas) return Promise.resolve();
    stop(canvas);
    const duration = opts.durationMs || DEFAULT_DURATION_MS;
    const start = performance.now();
    return new Promise((resolve) => {
      const rec = { raf: 0 };
      playing.set(canvas, rec);
      const tick = (now) => {
        const t = Math.max(0, Math.min(1, (now - start) / duration));
        drawFrame(canvas, t, opts);
        if (t < 1 && playing.get(canvas) === rec) {
          rec.raf = requestAnimationFrame(tick);
        } else {
          drawFrame(canvas, 1, opts);
          resolve();
        }
      };
      rec.raf = requestAnimationFrame(tick);
    });
  }

  /**
   * Prefer the rendered 3D opener (its audio is baked in). Only if the video
   * cannot play do we fall back to the canvas animation + synth sting.
   */
  async function play(canvas, opts = {}) {
    const video = videoFor(canvas);
    if (video && !opts.forceCanvas) {
      try {
        await playVideo(video);
        return;
      } catch (_) {
        stopVideo(video);
      }
    }
    sting();
    await playCanvas(canvas, opts);
  }

  let audioEl = null;
  let audioCtx = null;

  function sting() {
    try {
      if (audioEl) {
        try {
          audioEl.pause();
          audioEl.currentTime = 0;
        } catch (_) {}
      }
      audioEl = new Audio("splash_tudum.ogg");
      audioEl.volume = 1;
      const playP = audioEl.play();
      if (playP && playP.catch) playP.catch(() => synthSting());
      return;
    } catch (_) {
      synthSting();
    }
  }

  function synthSting() {
    try {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return;
      if (!audioCtx || audioCtx.state === "closed") audioCtx = new Ctx();
      const ctx = audioCtx;
      if (ctx.state === "suspended") ctx.resume().catch(() => {});
      const now = ctx.currentTime;
      const master = ctx.createGain();
      master.gain.value = 0.95;
      const lp = ctx.createBiquadFilter();
      lp.type = "lowpass";
      lp.frequency.value = 1400;
      const hp = ctx.createBiquadFilter();
      hp.type = "highpass";
      hp.frequency.value = 22;
      master.connect(hp);
      hp.connect(lp);
      lp.connect(ctx.destination);

      function tone(t, freq, dur, gain, type) {
        const osc = ctx.createOscillator();
        const g = ctx.createGain();
        osc.type = type;
        osc.frequency.setValueAtTime(freq, t);
        osc.frequency.exponentialRampToValueAtTime(Math.max(18, freq * 0.88), t + dur);
        g.gain.setValueAtTime(0.0001, t);
        g.gain.exponentialRampToValueAtTime(gain, t + 0.01);
        g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
        osc.connect(g);
        g.connect(master);
        osc.start(t);
        osc.stop(t + dur + 0.02);
      }

      function hit(t, sub, body, gain, decay) {
        tone(t, sub, decay * 3.2, gain * 0.72, "sine");
        tone(t, body, decay * 2.4, gain * 0.38, "sine");
        tone(t, body * 1.5, decay * 1.4, gain * 0.1, "triangle");
        tone(t, 180, 0.04, gain * 0.16, "sine");
      }

      const noise = ctx.createBufferSource();
      const buf = ctx.createBuffer(1, Math.floor(ctx.sampleRate * 0.6), ctx.sampleRate);
      const data = buf.getChannelData(0);
      let b = 0;
      for (let i = 0; i < data.length; i++) {
        b = Math.max(-1, Math.min(1, b + (Math.random() * 2 - 1) * 0.03));
        data[i] = b;
      }
      noise.buffer = buf;
      const ng = ctx.createGain();
      const nf = ctx.createBiquadFilter();
      nf.type = "lowpass";
      nf.frequency.setValueAtTime(220, now);
      nf.frequency.exponentialRampToValueAtTime(80, now + 0.5);
      ng.gain.setValueAtTime(0.0001, now);
      ng.gain.exponentialRampToValueAtTime(0.14, now + 0.08);
      ng.gain.exponentialRampToValueAtTime(0.0001, now + 0.55);
      noise.connect(nf);
      nf.connect(ng);
      ng.connect(master);
      noise.start(now);
      noise.stop(now + 0.6);

      hit(now + 0.5, 46.25, 92.5, 0.95, 0.3);
      hit(now + 0.78, 34.65, 69.3, 1.1, 0.58);
    } catch (_) {}
  }

  return {
    DEFAULT_DURATION_MS,
    HOLD_AFTER_MS,
    play,
    stop,
    sting,
    drawFrame,
  };
})();
