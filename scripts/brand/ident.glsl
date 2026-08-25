#version 410 core

// ---------------------------------------------------------------------------
// VERFLIXED startup ident.
//
// The journey is a vast terraced sculpture whose contours are nested copies of
// the brand chevron, so every curve in the film is already the V. The camera
// starts buried among the outermost steps in near-darkness and climbs inward
// toward the summit. On the impact the summit splits, and the two folded,
// edge-lit glass blades of the mark rise out of the gap.
//
// Two materials, deliberately: the sculpture is a matte translucent resin with
// deep crevice shadows, and the mark is thin edge-lit glass.
//
// Output is linear HDR. Tonemapping, bloom and dithering happen in
// render_ident.py so the glow is built from unclipped highlights.
// ---------------------------------------------------------------------------

out vec4 fragColor;

uniform vec2  uRes;     // full (supersampled) output size
uniform vec2  uOrigin;  // pixel offset of the tile being rendered
uniform float uT;       // seconds since the first frame

// The line below is replaced with #defines generated from timeline.py.
// @TIMELINE

const float PI = 3.14159265359;

// Palette, linear-light, anchored on the app's accent (#2F80FF).
const vec3 RESIN     = vec3(0.045, 0.255, 1.000);
const vec3 RESIN_DIM = vec3(0.004, 0.022, 0.115);
const vec3 ICE       = vec3(0.660, 0.840, 1.000);
const vec3 ELECTRIC  = vec3(0.055, 0.340, 1.000);

const int   RINGS     = 11;
const float RING_STEP = 1.030;   // how much each contour grows outward
const float RING_DROP = 0.760;   // and how far it falls

const int   STEPS     = 132;
const float MAX_DIST  = 60.0;
const float SURF      = 0.0022;

const float MAT_RESIN = 0.0;
const float MAT_GLASS = 1.0;

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

float sat(float x) { return clamp(x, 0.0, 1.0); }

float seg(float t, float a, float b) {
    return b <= a ? (t >= b ? 1.0 : 0.0) : sat((t - a) / (b - a));
}

float smootherstep(float x) { return x * x * x * (x * (x * 6.0 - 15.0) + 10.0); }

float smin(float a, float b, float k) {
    float h = sat(0.5 + 0.5 * (b - a) / k);
    return mix(b, a, h) - k * h * (1.0 - h);
}

float smax(float a, float b, float k) { return -smin(-a, -b, k); }

mat2 rot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, -s, s, c);
}

// ---------------------------------------------------------------------------
// choreography
// ---------------------------------------------------------------------------

// The climb from the outer steps to the summit. Slow at first, quickest late,
// decelerating so the camera has already settled when the mark lands.
float climbAt(float t) {
    return smootherstep(pow(seg(t, T_MOVE_IN, T_MOVE_OUT), 1.20));
}

// The summit parts and the sculpture sinks away.
float partAt(float t)  { return smootherstep(seg(t, T_EXIT_IN, T_IMPACT + 0.30)); }
float sinkAt(float t)  { return smootherstep(seg(t, T_EXIT_IN + 0.30, T_DUR)); }

// The mark assembles out of the gap and is complete on the impact.
float markAt(float t)  { return smootherstep(seg(t, T_IMPACT - 0.80, T_IMPACT)); }

// A small overshoot so the V physically lands on the beat.
float settleAt(float t) {
    float k = seg(t, T_IMPACT - 0.10, T_IMPACT + 0.60);
    if (k <= 0.0 || k >= 1.0) return 1.0;
    return 1.0 + 0.038 * sin(k * PI * 1.6) * (1.0 - k);
}

// Global light level: wakes out of blackness, then falls away at the end while
// the mark keeps its own light.
float envGainAt(float t) {
    float wake = mix(0.020, 1.0, smootherstep(seg(t, 0.10, 1.90)));
    return wake * (1.0 - 0.94 * smootherstep(seg(t, T_FADE_IN, T_DUR - 0.10)));
}

// ---------------------------------------------------------------------------
// the brand chevron — the one curve everything in the film is built from
// ---------------------------------------------------------------------------

float sdTaperedSeg2(vec2 p, vec2 a, vec2 b, float ra, float rb) {
    vec2 pa = p - a, ba = b - a;
    float h = sat(dot(pa, ba) / dot(ba, ba));
    return length(pa - ba * h) - mix(ra, rb, h);
}

// Filled chevron, rounded and tapered. Reproduces the outline the app draws.
float chevron(vec2 p) {
    const float RA = 0.150, RB = 0.082;
    float dL = sdTaperedSeg2(p, vec2(-0.47, 0.62), vec2(-0.050, -0.505), RA, RB);
    float dR = sdTaperedSeg2(p, vec2( 0.47, 0.62), vec2( 0.050, -0.505), RA, RB);
    return smax(smin(dL, dR, 0.038), p.y - 0.62, 0.040);
}

// ---------------------------------------------------------------------------
// the sculpture
// ---------------------------------------------------------------------------

// Nested offsets of the chevron, each one a step lower — a stepped pyramid
// whose summit is the mark itself, seen from among its outermost terraces.
float sculpture(vec3 p) {
    float part = partAt(uT);
    float sink = sinkAt(uT);

    // The innermost terraces split left and right to open the gap the mark
    // rises through.
    vec2 q = p.xz;
    q.x -= sign(q.x) * part * 2.30;

    float c = chevron(q / TERR_SCALE) * TERR_SCALE;
    float top0 = TERR_TOP - sink * 9.0;

    float d = 1e9;
    for (int i = 0; i < RINGS; i++) {
        float fi = float(i);
        float outline = c - fi * RING_STEP;
        float top = top0 - fi * RING_DROP;
        // Each plate reaches well below its own face so the stack reads solid.
        float lo = top - 2.60;
        const float r = 0.085;
        vec2 w = vec2(outline + r, abs(p.y - (top + lo) * 0.5) - (top - lo) * 0.5 + r);
        d = min(d, min(max(w.x, w.y), 0.0) + length(max(w, 0.0)) - r);
    }

    // Fine contour grooves cut into the faces — the detail that makes the
    // sculpture read as machined rather than as a smooth ramp.
    float g = abs(mod(c - 0.34, RING_STEP) - RING_STEP * 0.5) - 0.055;
    return smax(d, -g - 0.13, 0.07);
}

// ---------------------------------------------------------------------------
// the mark — two folded, edge-lit glass blades
// ---------------------------------------------------------------------------

// One petal. Widest around two thirds up, tapering to the tip and to the foot,
// with a shallow crease along its length so each blade shows two facets.
float bladeRight(vec3 q, float grow) {
    vec2 A = vec2(0.020, -0.560);
    vec2 B = vec2(0.620,  0.745);
    vec2 ab = B - A;
    float L = length(ab);
    vec2 dir = ab / L;
    vec2 rel = q.xy - A;

    float v = sat(dot(rel, dir) / L);
    float u = dot(rel, vec2(-dir.y, dir.x));

    float hw = 0.300 * pow(sin(PI * pow(v, 0.80)), 0.60) + 0.012;
    float fold = 0.105 * (1.0 - sat(abs(u) / hw));
    float sheet = abs(q.z - fold) - 0.028;
    float strip = max(abs(u) - hw, abs(v - 0.5) * L - L * 0.5);

    return smax(strip, sheet, 0.032) + (1.0 - grow) * 0.09;
}

float blades(vec3 p) {
    float grow = markAt(uT);
    if (grow <= 0.0) return 1e9;

    p /= MARK_SCALE * settleAt(uT);

    // The blades fold in from the sides as they arrive.
    float open = (1.0 - grow) * 0.85;

    vec3 l = p;
    l.x = -l.x;                       // mirror, then build one blade twice
    l.z += 0.055;
    l.xz = rot(0.26 + open) * l.xz;

    vec3 r = p;
    r.z -= 0.055;
    r.xz = rot(-0.26 - open) * r.xz;

    float d = min(bladeRight(l, grow), bladeRight(r, grow));
    return d * MARK_SCALE * settleAt(uT);
}

// ---------------------------------------------------------------------------

float map(vec3 p) {
    return min(sculpture(p), blades(p - vec3(0.0, MARK_Y, 0.0)));
}

float materialAt(vec3 p) {
    return blades(p - vec3(0.0, MARK_Y, 0.0)) < sculpture(p) ? MAT_GLASS : MAT_RESIN;
}

vec3 mapNormal(vec3 p) {
    const vec2 e = vec2(1.0, -1.0) * 0.0014;
    return normalize(
        e.xyy * map(p + e.xyy) + e.yyx * map(p + e.yyx) +
        e.yxy * map(p + e.yxy) + e.xxx * map(p + e.xxx));
}

float march(vec3 ro, vec3 rd, out bool hit) {
    float t = 0.008;
    hit = false;
    for (int i = 0; i < STEPS; i++) {
        float d = map(ro + rd * t);
        if (d < SURF * max(t, 1.0)) { hit = true; break; }
        t += max(d * 0.68, 0.0022);
        if (t > MAX_DIST) break;
    }
    return t;
}

// Crevice darkening. This is what sells the terraces as a deep stack rather
// than a flat blue ramp.
float occlusion(vec3 p, vec3 n) {
    float occ = 0.0, sca = 1.0;
    for (int i = 0; i < 5; i++) {
        float h = 0.05 + 0.30 * float(i);
        occ += (h - map(p + n * h)) * sca;
        sca *= 0.72;
    }
    return sat(1.0 - 1.15 * occ);
}

float softShadow(vec3 ro, vec3 rd) {
    float res = 1.0, t = 0.09;
    for (int i = 0; i < 28; i++) {
        float h = map(ro + rd * t);
        res = min(res, 9.0 * h / t);
        if (res < 0.004 || t > 18.0) break;
        t += clamp(h, 0.05, 0.85);
    }
    return sat(res);
}

// ---------------------------------------------------------------------------
// lighting
// ---------------------------------------------------------------------------

const vec3 KEY_DIR = normalize(vec3(-0.46, 0.76, -0.46));
const vec3 RIM_DIR = normalize(vec3( 0.72, 0.24,  0.60));

// The frame's true black. The sources are never drawn directly — showing them
// would put a flare in shot and destroy the blacks the composition needs.
vec3 backdrop(vec3 d) {
    float up = d.y * 0.5 + 0.5;
    return mix(vec3(0.0004, 0.0010, 0.0032), vec3(0.0022, 0.0080, 0.0300), up * up);
}

// Matte translucent resin: wrapped diffuse, a cool sky term, deep occlusion and
// a little scatter through the thin plate edges.
vec3 shadeResin(vec3 p, vec3 n, vec3 rd, float gain) {
    float ndl = dot(n, KEY_DIR);
    float wrap = pow(sat(ndl * 0.5 + 0.5), 1.70);
    float sh = mix(1.0, softShadow(p + n * 0.05, KEY_DIR), 0.85);
    float occ = occlusion(p, n);

    vec3 c = RESIN * wrap * sh * 1.35;
    c += RESIN_DIM * (n.y * 0.5 + 0.5) * 2.2;                    // cool sky fill
    c += ELECTRIC * 0.30 * pow(sat(dot(n, RIM_DIR)), 3.0) * sh;  // opposite rim
    c *= occ;

    // Edge scatter — light bleeding through the thin lip of each plate.
    float fres = pow(1.0 - sat(dot(n, -rd)), 3.2);
    c += ICE * fres * 0.16;
    return c * gain;
}

// Thin edge-lit glass. Almost all of the read is in the silhouette: the body
// stays near-black and the rim burns.
vec3 shadeGlass(vec3 p, vec3 n, vec3 rd, float gain) {
    float facing = sat(dot(n, -rd));
    float fres = pow(1.0 - facing, 2.0);

    // The crease splits each blade into a lit facet and a dark one.
    float facet = sat(dot(n, KEY_DIR));

    vec3 c = ELECTRIC * (0.05 + 0.75 * pow(facet, 1.4));
    c += ICE * pow(fres, 1.6) * 2.60;                    // the glowing rim
    c += ICE * pow(sat(dot(reflect(rd, n), KEY_DIR)), 90.0) * 3.2;

    // A single luminous pass travelling through the mark, left to right.
    float k = seg(uT, T_SWEEP_IN, T_SWEEP_OUT);
    if (k > 0.0 && k < 1.0) {
        float x = mix(-2.4, 2.4, smootherstep(k));
        c += ICE * exp(-pow((p.x - x) / 0.42, 2.0)) * sin(k * PI) * 1.30;
    }

    // The impact releases everything the climb has been gathering.
    c += ICE * 2.30 * exp(-pow((uT - T_IMPACT) / 0.14, 2.0));

    return c * max(gain, markAt(uT) * 0.96);
}

// ---------------------------------------------------------------------------
// camera
// ---------------------------------------------------------------------------

void camera(float t, out vec3 ro, out vec3 right, out vec3 up, out vec3 fwd, out float focal) {
    float k = climbAt(t);

    // Climb inward and upward: buried among the outer steps, ending level with
    // the summit.
    float radius = mix(12.60, 6.20, k);
    float height = mix(-6.30, 1.05, k);
    float angle  = mix(-1.05, 0.00, smootherstep(k));

    ro = vec3(sin(angle) * radius, height, -cos(angle) * radius);

    // Very close in, the camera looks along the terrace it is buried in; by the
    // end it is squared up on the summit.
    vec3 near = ro + vec3(sin(angle + 1.35), 0.30 - 0.55 * k, -cos(angle + 1.35)) * 6.0;
    vec3 far  = vec3(0.0, MARK_Y - 0.50, 0.0);
    vec3 target = mix(near, far, smootherstep(seg(t, T_EMERGE_OUT, T_IMPACT)));

    fwd = normalize(target - ro);
    float roll = 0.10 * sin(k * 3.1) * (1.0 - smootherstep(seg(t, T_EXIT_IN, T_IMPACT)));
    up = normalize(vec3(sin(roll), cos(roll), 0.0));
    right = normalize(cross(fwd, up));
    up = cross(right, fwd);

    focal = mix(0.86, 1.72, smootherstep(seg(t, T_EXIT_IN - 0.4, T_IMPACT + 0.2)));
}

// ---------------------------------------------------------------------------

vec3 render(vec3 ro, vec3 rd) {
    float gain = envGainAt(uT);
    vec3 acc = vec3(0.0);
    vec3 tint = vec3(1.0);

    // The blades are thin, so a ray may pass through several of them before it
    // reaches the sculpture behind — that overlap is most of the glass read.
    for (int layer = 0; layer < 3; layer++) {
        bool hit;
        float t = march(ro, rd, hit);
        if (!hit) {
            acc += tint * backdrop(rd) * gain;
            break;
        }

        vec3 p = ro + rd * t;
        vec3 n = mapNormal(p);
        if (dot(n, rd) > 0.0) n = -n;

        if (materialAt(p) == MAT_RESIN) {
            acc += tint * shadeResin(p, n, rd, gain);
            break;                       // the resin is opaque
        }

        acc += tint * shadeGlass(p, n, rd, gain);
        // Carry on through the sheet; what is behind still shows.
        tint *= 0.46;
        ro = p + rd * 0.055;
        if (max(tint.r, max(tint.g, tint.b)) < 0.02) break;
    }
    return acc;
}

void main() {
    vec2 px = uOrigin + gl_FragCoord.xy;
    vec2 uv = (2.0 * px - uRes) / uRes.y;

    vec3 ro, right, up, fwd;
    float focal;
    camera(uT, ro, right, up, fwd, focal);

    vec3 rd = normalize(uv.x * right + uv.y * up + focal * fwd);
    vec3 col = render(ro, rd);

    vec2 q = px / uRes;
    float vig = pow(16.0 * q.x * q.y * (1.0 - q.x) * (1.0 - q.y), 0.20);
    col *= mix(0.74, 1.0, vig);

    fragColor = vec4(col, 1.0);
}
