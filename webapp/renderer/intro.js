/**
 * Canvas port of Android VerflixedIntroView + BrandSting.
 * Fake-3D extruded V, Prime-like atmosphere, letter-by-letter wordmark.
 */
window.VfIntro = (() => {
  const DEFAULT_DURATION_MS = 2450;
  const HOLD_AFTER_MS = 260;

  const FACE_TOP = [159, 208, 255];
  const FACE_MID = [47, 128, 255];
  const FACE_BOTTOM = [16, 64, 143];
  const SIDE_DARK = [10, 30, 68];
  const SIDE_DEEP = [4, 11, 28];
  const SIDE_LIGHT = [27, 95, 196];
  const RIM_LIGHT = [220, 235, 255];
  const SWEEP_HOT = [232, 243, 255];
  const ACCENT_SOFT = [143, 182, 232];
  const ATMO_CORE = [11, 23, 48];
  const ATMO_MID = [6, 12, 28];
  const ATMO_EDGE = [2, 4, 9];

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

  function buildV(ctx, size) {
    const halfW = size * 0.62;
    const top = -size * 0.6;
    const bottom = size * 0.62;
    const thick = size * 0.3;
    ctx.beginPath();
    ctx.moveTo(-halfW, top);
    ctx.lineTo(-halfW + thick, top);
    ctx.lineTo(0, bottom - thick * 0.55);
    ctx.lineTo(halfW - thick, top);
    ctx.lineTo(halfW, top);
    ctx.lineTo(thick * 0.3, bottom);
    ctx.lineTo(-thick * 0.3, bottom);
    ctx.closePath();
  }

  function drawAtmosphere(ctx, w, h, k) {
    if (k <= 0) return;
    const g = ctx.createRadialGradient(w / 2, h * 0.42, 0, w / 2, h * 0.42, h * 1.05);
    g.addColorStop(0, rgba(ATMO_CORE, 0.78 * k));
    g.addColorStop(0.55, rgba(ATMO_MID, 0.78 * k));
    g.addColorStop(1, rgba(ATMO_EDGE, 0.78 * k));
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, w, h);
    const hg = ctx.createLinearGradient(0, h * 0.62, 0, h * 0.92);
    hg.addColorStop(0, "rgba(47,128,255,0)");
    hg.addColorStop(0.5, `rgba(47,128,255,${0.18 * k})`);
    hg.addColorStop(1, "rgba(47,128,255,0)");
    ctx.fillStyle = hg;
    ctx.fillRect(0, h * 0.55, w, h * 0.45);
  }

  function drawBackglow(ctx, cx, cy, markSize, flyIn, sweepK) {
    const glowAlpha = 0.47 * flyIn * (0.55 + 0.45 * easeInOut(sweepK));
    if (glowAlpha <= 0) return;
    const r = markSize * (2.5 + 0.5 * flyIn);
    const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, r);
    g.addColorStop(0, `rgba(47,128,255,${glowAlpha})`);
    g.addColorStop(0.45, `rgba(47,128,255,${glowAlpha * 0.35})`);
    g.addColorStop(1, "rgba(47,128,255,0)");
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    ctx.fill();
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

    drawAtmosphere(ctx, w, h, easeOut(seg(0, 0.5)));

    const markSize = Math.min(w, h) * (compact ? 0.3 : 0.34);
    const cx = w / 2;
    const cy = h / 2 - markSize * (compact ? 0.16 : 0.24);
    const flyIn = easeOut(seg(0, 0.42));
    const settle = easeOut(seg(0.78, 1));
    const depth = 1 - flyIn;
    const scale = 0.62 + 0.44 * flyIn - 0.06 * settle * Math.sin(settle * Math.PI);
    const rotY = -30 * depth;
    const markAlpha = Math.min(1, seg(0.02, 0.3) * 1.2);

    drawBackglow(ctx, cx, cy, markSize, flyIn, seg(0.3, 0.7));

    ctx.save();
    ctx.translate(cx, cy);
    ctx.transform(scale * (1 - Math.abs(rotY) / 150), 0, 0, scale, 0, 0);
    ctx.transform(1, -0.045 - 0.1 * depth, 0, 1, 0, 0);

    const extrude = markSize * (0.22 * depth + 0.085);
    const steps = 12;
    for (let i = steps; i >= 1; i--) {
      const k = i / steps;
      ctx.save();
      ctx.translate(extrude * k * 0.95, extrude * k * 0.75);
      buildV(ctx, markSize);
      const a = lerpColor(SIDE_DARK, SIDE_DEEP, k);
      const b = lerpColor(SIDE_LIGHT, SIDE_DARK, k);
      const lg = ctx.createLinearGradient(-markSize, -markSize, markSize, markSize);
      lg.addColorStop(0, rgb(a));
      lg.addColorStop(1, rgb(b));
      ctx.fillStyle = lg;
      ctx.globalAlpha = markAlpha * ((70 + 110 * (1 - k)) / 255);
      ctx.fill();
      ctx.restore();
    }

    ctx.save();
    ctx.translate(extrude * 0.5, extrude * 1.35);
    buildV(ctx, markSize);
    ctx.fillStyle = `rgba(0,0,0,${markAlpha * 0.35})`;
    ctx.fill();
    ctx.restore();

    buildV(ctx, markSize);
    const face = ctx.createLinearGradient(-markSize * 0.7, -markSize, markSize * 0.7, markSize);
    face.addColorStop(0, rgb(FACE_TOP));
    face.addColorStop(0.55, rgb(FACE_MID));
    face.addColorStop(1, rgb(FACE_BOTTOM));
    ctx.fillStyle = face;
    ctx.globalAlpha = markAlpha;
    ctx.fill();

    ctx.save();
    buildV(ctx, markSize);
    ctx.clip();
    ctx.translate(-extrude * 0.05, -extrude * 0.1);
    buildV(ctx, markSize);
    const rim = ctx.createLinearGradient(0, -markSize, 0, 0);
    rim.addColorStop(0, rgba(RIM_LIGHT, markAlpha * 0.47));
    rim.addColorStop(1, "rgba(220,235,255,0)");
    ctx.fillStyle = rim;
    ctx.globalAlpha = 1;
    ctx.fill();
    ctx.restore();

    const sweepK = seg(0.32, 0.66);
    if (sweepK > 0 && sweepK < 1) {
      const eased = easeInOut(sweepK);
      const bandW = markSize * 0.85;
      const x = -markSize * 1.7 + eased * markSize * 3.4;
      ctx.save();
      buildV(ctx, markSize);
      ctx.clip();
      const sg = ctx.createLinearGradient(x - bandW, 0, x + bandW, 0);
      sg.addColorStop(0, "rgba(232,243,255,0)");
      sg.addColorStop(0.5, rgba(SWEEP_HOT, 0.75 * Math.sin(sweepK * Math.PI)));
      sg.addColorStop(1, "rgba(232,243,255,0)");
      ctx.fillStyle = sg;
      ctx.fillRect(x - bandW, -markSize * 1.6, bandW * 2, markSize * 3.2);
      ctx.restore();
    }
    ctx.restore();

    const textSize = markSize * (compact ? 0.34 : 0.4);
    ctx.font = `700 ${textSize}px "Segoe UI", ui-sans-serif, system-ui, sans-serif`;
    ctx.textBaseline = "alphabetic";
    const tracking = textSize * 0.26;
    const letters = [...wordmark];
    const widths = letters.map((ch) => ctx.measureText(ch).width);
    const total = widths.reduce((a, b) => a + b, 0) + tracking * (letters.length - 1);
    let x = cx - total / 2;
    const baseline = cy + markSize * 1.16;
    letters.forEach((ch, i) => {
      const start = 0.44 + (i / letters.length) * 0.3;
      const k = easeOut(seg(start, start + 0.2));
      if (k > 0) {
        ctx.save();
        ctx.globalAlpha = Math.min(1, k * 1.25);
        ctx.fillStyle = "#fff";
        const rise = (1 - k) * textSize * 0.55;
        const mid = x + widths[i] / 2;
        ctx.translate(mid, baseline + rise);
        ctx.scale(1, 0.86 + 0.14 * k);
        ctx.fillText(ch, -widths[i] / 2, 0);
        ctx.restore();
      }
      x += widths[i] + tracking;
    });

    if (!compact) {
      const tk = easeOut(seg(0.7, 0.95));
      if (tk > 0) {
        ctx.globalAlpha = 0.78 * tk;
        ctx.fillStyle = rgb(ACCENT_SOFT);
        ctx.font = `400 ${textSize * 0.46}px "Segoe UI", ui-sans-serif, system-ui, sans-serif`;
        ctx.textAlign = "center";
        ctx.fillText(tagline, cx, baseline + textSize * (1.15 - 0.25 * tk));
        ctx.textAlign = "start";
        ctx.globalAlpha = 1;
      }
    }
  }

  const playing = new WeakMap();

  function stop(canvas) {
    const rec = canvas && playing.get(canvas);
    if (rec?.raf) cancelAnimationFrame(rec.raf);
    if (canvas) playing.delete(canvas);
  }

  function play(canvas, opts = {}) {
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
      const play = audioEl.play();
      if (play && play.catch) play.catch(() => synthSting());
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
      master.gain.value = 0.85;
      master.connect(ctx.destination);

      const whoosh = ctx.createOscillator();
      const whooshG = ctx.createGain();
      const whooshF = ctx.createBiquadFilter();
      whoosh.type = "sawtooth";
      whoosh.frequency.setValueAtTime(420, now);
      whoosh.frequency.exponentialRampToValueAtTime(90, now + 0.38);
      whooshF.type = "lowpass";
      whooshF.frequency.setValueAtTime(1800, now);
      whooshF.frequency.exponentialRampToValueAtTime(280, now + 0.4);
      whooshG.gain.setValueAtTime(0.0001, now);
      whooshG.gain.exponentialRampToValueAtTime(0.18, now + 0.05);
      whooshG.gain.exponentialRampToValueAtTime(0.0001, now + 0.42);
      whoosh.connect(whooshF);
      whooshF.connect(whooshG);
      whooshG.connect(master);
      whoosh.start(now);
      whoosh.stop(now + 0.45);

      function hit(t, freq, dur, gain) {
        const osc = ctx.createOscillator();
        const g = ctx.createGain();
        const f = ctx.createBiquadFilter();
        osc.type = "triangle";
        osc.frequency.setValueAtTime(freq, t);
        osc.frequency.exponentialRampToValueAtTime(freq * 0.42, t + dur);
        f.type = "lowpass";
        f.frequency.setValueAtTime(900, t);
        f.frequency.exponentialRampToValueAtTime(180, t + dur);
        g.gain.setValueAtTime(0.0001, t);
        g.gain.exponentialRampToValueAtTime(gain, t + 0.018);
        g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
        osc.connect(f);
        f.connect(g);
        g.connect(master);
        osc.start(t);
        osc.stop(t + dur + 0.02);
      }

      hit(now + 0.32, 98, 0.55, 0.42);
      hit(now + 0.58, 73, 0.85, 0.5);

      const shimmer = ctx.createOscillator();
      const shG = ctx.createGain();
      shimmer.type = "sine";
      shimmer.frequency.setValueAtTime(880, now + 0.55);
      shimmer.frequency.exponentialRampToValueAtTime(1320, now + 1.4);
      shG.gain.setValueAtTime(0.0001, now + 0.55);
      shG.gain.exponentialRampToValueAtTime(0.07, now + 0.7);
      shG.gain.exponentialRampToValueAtTime(0.0001, now + 1.8);
      shimmer.connect(shG);
      shG.connect(master);
      shimmer.start(now + 0.55);
      shimmer.stop(now + 1.85);
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
