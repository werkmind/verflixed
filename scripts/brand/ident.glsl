#version 410 core

// ---------------------------------------------------------------------------
// VERFLIXED startup ident — raymarched liquid-glass opener.
//
// Two enormous translucent ribbons flow past the camera, tighten into a tunnel,
// then sweep outward so the negative space between them lands on the brand V,
// which the remaining glass retracts into. Everything is rounded and organic:
// the ribbons are stadium cross-sections swept along sums of sines, the mark is
// two rounded capsules joined by a smooth minimum. There are no crystalline
// shards anywhere in the scene by construction.
//
// Output is linear HDR. Tonemapping, bloom and dithering happen in
// render_ident.py so the glow is built from unclipped highlights.
// ---------------------------------------------------------------------------

out vec4 fragColor;

uniform vec2  uRes;     // full (supersampled) output size
uniform vec2  uOrigin;  // pixel offset of the tile being rendered
uniform float uT;       // seconds since the first frame

// @TIMELINE — replaced with #defines generated from timeline.py

const float PI = 3.14159265359;

// Palette. Linear-light, derived from the app tokens (sv_accent #2F80FF,
// sv_accent_hover #5AA8FF) so the ident and the UI share one blue.
const vec3 ROYAL   = vec3(0.021, 0.132, 0.780);
const vec3 ELECTRIC= vec3(0.048, 0.320, 1.000);
const vec3 ICE     = vec3(0.560, 0.780, 1.000);
const vec3 MIDNIGHT= vec3(0.006, 0.020, 0.062);

const int   MAX_LAYERS   = 3;    // how many glass surfaces a ray may pass through
const int   STEPS_OUT    = 108;
const int   STEPS_IN     = 44;
const float MAX_DIST     = 46.0;
const float SURF         = 0.0018;
const float IOR          = 1.46;
const float BIG          = 60.0;

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

float sat(float x) { return clamp(x, 0.0, 1.0); }

float seg(float t, float a, float b) {
    return b <= a ? (t >= b ? 1.0 : 0.0) : sat((t - a) / (b - a));
}

float smootherstep(float x) { return x * x * x * (x * (x * 6.0 - 15.0) + 10.0); }

float easeOut(float x) { return 1.0 - pow(1.0 - x, 3.0); }

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

// Distance travelled down the tunnel. Nearly still at first, speed peaking late
// ("gradually becomes faster and more energetic"), then decelerating onto the
// mark so the impact lands on a camera that has already settled.
float travelAt(float t) {
    float k = seg(t, T_MOVE_IN, T_MOVE_OUT);
    return TRAVEL_LEN * smootherstep(pow(k, 1.22));
}

// 0 before the structures part, 1 once the V has resolved.
float resolveAt(float t) { return smootherstep(seg(t, T_EXIT_IN, T_IMPACT)); }

// How present the solid mark is. Grows out of the parting glass.
float markAt(float t) { return smootherstep(seg(t, T_IMPACT - 0.42, T_IMPACT + 0.30)); }

// Half-width of the gap between the two ribbons. Tight through the tunnel,
// flung wide open on the exit.
float gapAt(float t) {
    float open = 1.0 - smootherstep(seg(t, T_EMERGE_IN, T_TUNNEL_PEAK));
    float g = mix(0.72, 2.55, open);
    return g + 7.0 * resolveAt(t);
}

// The ribbons rise as they sweep out of frame.
float liftAt(float t) { return 4.6 * resolveAt(t); }

// Positive erosion dissolves the ribbons once the mark has taken over.
float erodeAt(float t) { return 5.5 * smootherstep(seg(t, T_IMPACT - 0.20, T_IMPACT + 0.65)); }

// Global light level. Opens from near-black, then the surrounding energy falls
// away at the end while the key stays on the mark.
float envGainAt(float t) {
    float wake = mix(0.055, 1.0, smootherstep(seg(t, 0.05, 1.85)));
    float fade = 1.0 - 0.72 * smootherstep(seg(t, T_FADE_IN, T_DUR));
    return wake * fade;
}

// ---------------------------------------------------------------------------
// signed distance fields
// ---------------------------------------------------------------------------

float sdSegment2(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    return length(pa - ba * sat(dot(pa, ba) / dot(ba, ba)));
}

// Rounded stadium cross-section — the ribbons' profile. No corners exist.
float sdStadium2(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

// The brand chevron, built from two rounded capsules so every join is organic.
// Control points reproduce the outline used by VerflixedIntroView so the ident
// resolves onto exactly the mark the app draws everywhere else.
float markCross(vec2 p) {
    float r = 0.135;
    float dL = sdSegment2(p, vec2(-0.47, 0.62), vec2(0.0, -0.48)) - r;
    float dR = sdSegment2(p, vec2( 0.47, 0.62), vec2(0.0, -0.48)) - r;
    float d = smin(dL, dR, 0.075);
    return smax(d, p.y - 0.62, 0.055);  // softly flattened arm tops
}

float sdMark(vec3 p) {
    p /= MARK_SCALE;
    const float bev = 0.085;
    vec2 w = vec2(markCross(p.xy) + bev, abs(p.z) - (0.26 - bev));
    float d = min(max(w.x, w.y), 0.0) + length(max(w, 0.0)) - bev;
    return d * MARK_SCALE;
}

// One flowing ribbon. `s` is -1 for the left structure, +1 for the right.
float ribbon(vec3 p, float s, float gap, float lift, float erode) {
    float z = p.z;
    // Sums of sines only: continuous and intentional, never a chaotic sim.
    float ox = s * gap
             + 0.62 * sin(z * 0.155 + s * 1.70)
             + 0.30 * sin(z * 0.062 - 0.90 + s * 0.40);
    float oy = 0.72 * sin(z * 0.111 + s * 2.35)
             + 0.34 * sin(z * 0.047 + 1.25)
             + s * lift;
    float roll = 0.42 * sin(z * 0.088 + s * 0.85) + s * 0.10;

    vec2 q = rot(roll) * (p.xy - vec2(ox, oy));
    float th = 0.62 + 0.26 * sin(z * 0.190 + s * 2.00);
    float hh = 3.40 + 1.30 * sin(z * 0.071 + s * 1.10);
    return sdStadium2(q, vec2(th, hh), th * 0.94) + erode;
}

// Half-plane running along one outer arm of the V, positive inside the wedge.
// Carving both ribbons with it is what turns the gap between them into the mark.
float armPlane(vec2 p, float s) {
    vec2 top = vec2(s * 0.62, 0.60) * MARK_SCALE + vec2(0.0, MARK_Y);
    vec2 dir = normalize(vec2(s * 0.09, -0.62) * MARK_SCALE + vec2(0.0, MARK_Y) - top);
    vec2 n = vec2(-s * dir.y, s * dir.x);
    return dot(p - top, n);
}

float map(vec3 p) {
    float gap    = gapAt(uT);
    float lift   = liftAt(uT);
    float erode  = erodeAt(uT);
    float clipK  = resolveAt(uT);

    float dl = ribbon(p, -1.0, gap, lift, erode);
    float dr = ribbon(p,  1.0, gap, lift, erode);

    // Fade the wedge in by pushing the constraint out of range beforehand.
    float slack = (1.0 - clipK) * BIG;
    dl = smax(dl, armPlane(p.xy, -1.0) - slack, 0.16);
    dr = smax(dr, armPlane(p.xy,  1.0) - slack, 0.16);

    float d = min(dl, dr);

    float dm = sdMark(p - vec3(0.0, MARK_Y, MARK_Z)) + (1.0 - markAt(uT)) * BIG;
    return smin(d, dm, 0.55);
}

vec3 mapNormal(vec3 p) {
    // Tetrahedral gradient: four taps instead of six.
    const vec2 e = vec2(1.0, -1.0) * 0.0012;
    return normalize(
        e.xyy * map(p + e.xyy) + e.yyx * map(p + e.yyx) +
        e.yxy * map(p + e.yxy) + e.xxx * map(p + e.xxx));
}

// The cross-section varies along z, so the field is not strictly Lipschitz;
// under-relaxing the step keeps the march from tunnelling through thin edges.
float marchOut(vec3 ro, vec3 rd, out bool hit) {
    float t = 0.006;
    hit = false;
    for (int i = 0; i < STEPS_OUT; i++) {
        float d = map(ro + rd * t);
        if (d < SURF) { hit = true; break; }
        t += max(d * 0.55, 0.0016);
        if (t > MAX_DIST) break;
    }
    return t;
}

float marchIn(vec3 ro, vec3 rd) {
    float t = 0.004;
    for (int i = 0; i < STEPS_IN; i++) {
        float d = -map(ro + rd * t);
        if (d < SURF) break;
        t += max(d * 0.60, 0.0016);
        if (t > 14.0) break;
    }
    return t;
}

// ---------------------------------------------------------------------------
// lighting
// ---------------------------------------------------------------------------

const vec3 KEY_DIR  = normalize(vec3(-0.42,  0.74, -0.52));
const vec3 RIM_DIR  = normalize(vec3( 0.68,  0.30,  0.62));
const vec3 FILL_DIR = normalize(vec3( 0.05, -0.86,  0.42));

// Dark studio: a broad cool key, a tighter rim and a low fill. Deliberately
// featureless apart from the blobs — nothing here can read as a star or particle.
vec3 env(vec3 d) {
    float up = d.y * 0.5 + 0.5;
    vec3 c = mix(vec3(0.0016, 0.0040, 0.0125), MIDNIGHT * 2.4, up * up);
    c += ICE   * 1.85 * pow(max(dot(d, KEY_DIR),  0.0), 24.0);
    c += vec3(0.10, 0.30, 0.95) * 0.80 * pow(max(dot(d, RIM_DIR),  0.0), 10.0);
    c += ROYAL * 0.55 * pow(max(dot(d, FILL_DIR), 0.0), 3.5);
    return c;
}

// Light living inside the material rather than sliding across its surface:
// broad wavefronts travelling down the tunnel, and — once the mark exists — a
// single luminous pass from left to right through the V.
float energyField(vec3 p) {
    float travelling = pow(sin(p.z * 0.42 - uT * 3.1) * 0.5 + 0.5, 7.0)
                     + 0.6 * pow(sin(p.z * 0.17 + uT * 1.4) * 0.5 + 0.5, 5.0);
    travelling *= 1.0 - markAt(uT);

    float k = seg(uT, T_SWEEP_IN, T_SWEEP_OUT);
    float sweep = 0.0;
    if (k > 0.0 && k < 1.0) {
        float x = mix(-2.6, 2.6, smootherstep(k));
        sweep = exp(-pow((p.x - x) / 0.34, 2.0)) * sin(k * PI) * 2.6 * markAt(uT);
    }
    return travelling + sweep;
}

// ---------------------------------------------------------------------------
// camera
// ---------------------------------------------------------------------------

void camera(float t, out vec3 ro, out vec3 fwd, out vec3 right, out vec3 up, out float focal) {
    float z = travelAt(t);
    // Drift unwinds completely by the impact so the logo lands dead centre.
    float free = 1.0 - smootherstep(seg(t, T_IMPACT - 1.05, T_IMPACT + 0.20));
    float x = (0.60 * sin(z * 0.100 + 0.70) + 0.24 * sin(z * 0.041)) * free;
    float y = (0.44 * sin(z * 0.083 + 2.10) - 0.16) * free;
    ro = vec3(x, y, z);

    vec3 target = mix(vec3(x * 0.35, y * 0.35, z + 5.0),
                      vec3(0.0, MARK_Y, MARK_Z),
                      smootherstep(seg(t, T_EXIT_IN - 0.5, T_IMPACT)));
    fwd = normalize(target - ro);
    float roll = 0.085 * sin(z * 0.070 + 1.4) * free;
    up = normalize(vec3(sin(roll), cos(roll), 0.0));
    right = normalize(cross(fwd, up));
    up = cross(right, fwd);

    // Wide and immersive inside the tunnel, tightening for a flattering logo.
    focal = mix(0.92, 1.78, smootherstep(seg(t, T_EXIT_IN - 0.3, T_IMPACT + 0.25)));
}

// ---------------------------------------------------------------------------
// shading
// ---------------------------------------------------------------------------

vec3 shadeBackground(vec3 rd) { return env(rd) * envGainAt(uT); }

vec3 render(vec3 ro, vec3 rd) {
    float gain = envGainAt(uT);
    vec3 acc = vec3(0.0);
    vec3 tint = vec3(1.0);
    float travelled = 0.0;

    for (int layer = 0; layer < MAX_LAYERS; layer++) {
        bool hit;
        float t = marchOut(ro, rd, hit);
        travelled += min(t, MAX_DIST);
        if (!hit) {
            acc += tint * shadeBackground(rd);
            break;
        }

        vec3 p = ro + rd * t;
        vec3 n = mapNormal(p);
        if (dot(n, rd) > 0.0) n = -n;

        float cosI = sat(dot(-rd, n));
        float F = 0.045 + 0.955 * pow(1.0 - cosI, 5.0);

        // Reflected environment plus a tight specular — the soft edge highlights.
        vec3 refl = env(reflect(rd, n)) * gain;
        float spec = pow(max(dot(reflect(rd, n), KEY_DIR), 0.0), 220.0);
        acc += tint * (F * refl + ICE * spec * 2.4 * gain);

        // Into the material.
        vec3 rin = refract(rd, n, 1.0 / IOR);
        if (dot(rin, rin) < 1e-6) rin = reflect(rd, n);   // total internal reflection

        float inner = marchIn(p + rin * 0.004, rin);
        vec3 p2 = p + rin * inner;
        vec3 n2 = mapNormal(p2);
        if (dot(n2, rin) < 0.0) n2 = -n2;

        // Thick glass stays dark and deeply blue; thin edges pass ice-blue light.
        vec3 absorb = exp(-vec3(2.35, 1.05, 0.42) * inner);

        // Energy read at the midpoint of the internal path, weighted by how much
        // material the ray crossed, so it glows from within rather than on top.
        float e = energyField(mix(p, p2, 0.5)) * min(inner, 2.2);
        acc += tint * (1.0 - F) * mix(ROYAL, ELECTRIC, 0.6) * e * 0.42 * gain;

        // Cheap caustic: light that leaves the glass aimed at the key focuses.
        float caustic = pow(max(dot(rin, KEY_DIR), 0.0), 40.0);
        acc += tint * ICE * caustic * 0.55 * gain;

        vec3 rout = refract(rin, -n2, IOR);
        if (dot(rout, rout) < 1e-6) rout = reflect(rin, -n2);

        tint *= (1.0 - F) * absorb;
        if (max(tint.r, max(tint.g, tint.b)) < 0.006) break;

        ro = p2 + rout * 0.006;
        rd = rout;

        if (layer == MAX_LAYERS - 1) acc += tint * shadeBackground(rd);
    }

    // Very subtle volumetric haze so the deep blacks still have air in them.
    float haze = 1.0 - exp(-travelled * 0.014);
    acc += MIDNIGHT * haze * 0.55 * gain;
    return acc;
}

// ---------------------------------------------------------------------------

void main() {
    vec2 px = uOrigin + gl_FragCoord.xy;
    vec2 uv = (2.0 * px - uRes) / uRes.y;

    vec3 ro, fwd, right, up;
    float focal;
    camera(uT, ro, fwd, right, up, focal);

    vec3 rd = normalize(uv.x * right + uv.y * up + focal * fwd);
    vec3 col = render(ro, rd);

    // Gentle cinematic falloff; keeps the corners in true black.
    vec2 q = px / uRes;
    float vig = pow(16.0 * q.x * q.y * (1.0 - q.x) * (1.0 - q.y), 0.22);
    col *= mix(0.72, 1.0, vig);

    fragColor = vec4(col, 1.0);
}
