package dev.herdertower.phone.orb

/**
 * The Mac orb's Metal shader (`src/orb/orb.metal`) in AGSL: a hollow,
 * glass-like cup of liquid, raymarched; colour comes from how much material
 * each view ray passes through. The `scene` and `present` passes are one
 * here (AGSL has no feedback texture, so the slight motion trail is left
 * out): the opaque picture is turned into colour over transparency the same
 * way, so on our background it looks as on the Mac's.
 */
object OrbShader {
    const val FOCAL = 1.32
    const val CAM_Z = 4.0

    /** `liquid()` as straight-line code, exactly as `liquid_msl()` writes it. */
    fun liquidFunction(): String {
        fun num(v: Double) = if (v % 1.0 == 0.0) "%.1f".format(java.util.Locale.ROOT, v) else v.toString()
        fun coeff(k: Int) = "sh[${k shr 2}].${"xyzw"[k and 3]}"
        val lines = mutableListOf("float liquid(float3 n) {", "  float h = 0.0; float re = 1.0; float im = 0.0; float t; float q; float q1; float q2;")
        var k = 0
        var qmm = 1.0
        for (m in 0..Modes.LMAX) {
            if (m > 0) {
                lines += "  t = re * n.x - im * n.y; im = re * n.y + im * n.x; re = t;"
                qmm *= (2 * m - 1).toDouble()
            }
            lines += "  q1 = 0.0; q2 = 0.0;"
            for (l in m..Modes.LMAX) {
                lines += if (l == m) {
                    "  q = ${num(qmm)};"
                } else {
                    val a = (2 * l - 1).toDouble() / (l - m)
                    val b = (l + m - 1).toDouble() / (l - m)
                    "  q = ${num(a)} * n.z * q1 - ${num(b)} * q2;"
                }
                lines += "  q2 = q1; q1 = q;"
                if (l < 2) continue
                if (m == 0) {
                    lines += "  h += ${coeff(k)} * q;"
                    k += 1
                } else {
                    lines += "  h += q * (${coeff(k)} * re + ${coeff(k + 1)} * im);"
                    k += 2
                }
            }
        }
        lines += "  return h;"
        lines += "}"
        return lines.joinToString("\n")
    }

    /** The Mac marches up to 160 steps; a device whose AGSL compiler refuses that gets fewer, longer ones. */
    val STEP_LADDER = intArrayOf(160, 140, 120, 100, 80, 64)

    val SOURCE: String get() = source(160)

    fun source(steps: Int): String = "const int STEPS = $steps;\nconst float MIN_STEP = ${"%.5f".format(java.util.Locale.ROOT, 0.004 * 160.0 / steps)};\n" + BODY

    private val BODY: String by lazy {
        """
uniform float4 rot0;
uniform float4 rot1;
uniform float4 rot2;
uniform float4 center;
uniform shader liquidMap;
uniform shader boilMap;
uniform float4 maps;
uniform float4 shape;
uniform float4 wall;
uniform float4 p1;
uniform float4 p2;
uniform float4 res;
uniform float4 outU;
uniform float4 tint;
uniform float4 halo;

const float3 CAM = float3(0.0, 0.0, 4.0);

float3 ramp(float x) {
  float3 c = float3(223.0, 210.0, 194.0) / 255.0;
  c = mix(c, float3(193.0, 185.0, 179.0) / 255.0, clamp((x - 0.0) / 0.12, 0.0, 1.0));
  c = mix(c, float3(144.0, 161.0, 183.0) / 255.0, clamp((x - 0.12) / 0.16, 0.0, 1.0));
  c = mix(c, float3( 54.0, 105.0, 171.0) / 255.0, clamp((x - 0.28) / 0.20, 0.0, 1.0));
  c = mix(c, float3( 16.0,  53.0, 145.0) / 255.0, clamp((x - 0.48) / 0.14, 0.0, 1.0));
  c = mix(c, float3( 22.0,  41.0, 102.0) / 255.0, clamp((x - 0.62) / 0.14, 0.0, 1.0));
  c = mix(c, float3( 28.0,  39.0,  72.0) / 255.0, clamp((x - 0.76) / 0.12, 0.0, 1.0));
  c = mix(c, float3( 26.0,  28.0,  36.0) / 255.0, clamp((x - 0.88) / 0.12, 0.0, 1.0));
  return c;
}

float3 glowRamp(float x) {
  float3 c = float3(0.0);
  c = mix(c, float3( 10.0,  16.0,  38.0) / 255.0, clamp((x - 0.0) / 0.2, 0.0, 1.0));
  c = mix(c, float3( 24.0,  37.0,  70.0) / 255.0, clamp((x - 0.2) / 0.2, 0.0, 1.0));
  c = mix(c, float3( 47.0,  82.0, 145.0) / 255.0, clamp((x - 0.4) / 0.2, 0.0, 1.0));
  c = mix(c, float3(104.0, 140.0, 200.0) / 255.0, clamp((x - 0.6) / 0.2, 0.0, 1.0));
  c = mix(c, float3(165.0, 185.0, 217.0) / 255.0, clamp((x - 0.8) / 0.12, 0.0, 1.0));
  c = mix(c, float3(236.0, 228.0, 240.0) / 255.0, clamp((x - 0.92) / 0.08, 0.0, 1.0));
  return c;
}

float smax(float a, float b, float k) {
  float h = max(k - abs(a - b), 0.0) / k;
  return max(a, b) + h * h * k * 0.25;
}

float highlight(float3 rd, float3 n) {
  return pow(max(dot(reflect(rd, n), normalize(float3(0.7, -0.45, 0.6))), 0.0), 40.0);
}

// liquid(n) and boil(n) of the Mac's shader, looked up in maps of directions
// filled each frame on the CPU (OrbMaps): 16 bits in R (high) and G (low).
float2 lonLat(float3 n, float2 size) {
  return float2((atan(n.y, n.x) / 6.2831853 + 0.5) * size.x, acos(clamp(n.z, -1.0, 1.0)) / 3.1415927 * size.y);
}

float unpack(half4 c) {
  return (float(c.r) * 255.0 * 256.0 + float(c.g) * 255.0) / 65535.0 - 0.5;
}

float liquid(float3 n) { return unpack(liquidMap.eval(lonLat(n, maps.xy))); }
float boil(float3 n) { return unpack(boilMap.eval(lonLat(n, maps.zw))); }

float map(float3 p) {
  float3 w = p - center.xyz;
  float3 q = float3(dot(rot0.xyz, w), dot(rot1.xyz, w), dot(rot2.xyz, w));
  float r = max(length(w), 1e-5);
  float3 n = w / r;
  float bubble = boil(n);
  float wl = mix(wall.x, wall.y, pow(smoothstep(-shape.x, shape.y, q.z), wall.z));
  float shell = abs(r - shape.x - liquid(n) - 0.5 * bubble) - wl - 0.5 * bubble;
  return smax(shell, q.z - shape.y, shape.z);
}

float3 calcNormal(float3 p) {
  float2 e = float2(0.002, -0.002);
  return normalize(e.xyy * map(p + e.xyy) + e.yyx * map(p + e.yyx) +
                   e.yxy * map(p + e.yxy) + e.xxx * map(p + e.xxx));
}

float edgeAt(float3 p, float3 rd, float t) {
  return t < 0.0 ? 0.0 : pow(1.0 - abs(dot(calcNormal(p + rd * t), rd)), 6.0);
}

// The march adds up the distance spent inside the material. Where the ray
// first meets it (the highlight) and where it crosses a wall (the glow
// look's shining outlines) are noted, and their normals taken after the
// loop: the same values as the Mac's, at a size AGSL accepts.
float straightPath(float3 p, float3 rd, out float spec, inout float gEdge) {
  float len = 0.0;
  float t = 0.0;
  float hitT = -1.0;
  float e0 = -1.0; float e1 = -1.0; float e2 = -1.0; float e3 = -1.0; float e4 = -1.0; float e5 = -1.0;
  bool wasIn = false;
  spec = 0.0;
  for (int i = 0; i < STEPS; i++) {
    float d = map(p + rd * t);
    float s = clamp(abs(d) * p1.z, MIN_STEP, 0.25);
    float inside = clamp(0.5 - d / 0.008, 0.0, 1.0);
    if (inside > 0.0 && hitT < 0.0) hitT = t;
    if ((inside > 0.5) != wasIn) {
      wasIn = inside > 0.5;
      if (e0 < 0.0) { e0 = t; } else if (e1 < 0.0) { e1 = t; } else if (e2 < 0.0) { e2 = t; }
      else if (e3 < 0.0) { e3 = t; } else if (e4 < 0.0) { e4 = t; } else if (e5 < 0.0) { e5 = t; }
    }
    len += s * inside;
    t += s;
    float3 w = p + rd * t - center.xyz;
    if (dot(w, rd) > 0.0 && length(w) > center.w) break;
  }
  if (hitT >= 0.0 && p1.y > 0.0) spec = highlight(rd, calcNormal(p + rd * hitT));
  if (p2.z > 0.0) {
    gEdge += edgeAt(p, rd, e0) + edgeAt(p, rd, e1) + edgeAt(p, rd, e2) + edgeAt(p, rd, e3) + edgeAt(p, rd, e4) + edgeAt(p, rd, e5);
  }
  return len;
}

half4 main(float2 fragCoord) {
  float2 frag = float2(fragCoord.x, res.y - fragCoord.y);
  float2 uv = (frag + res.zw - 0.5 * res.xy) / min(res.x, res.y);
  float3 rd = normalize(float3(uv, -p1.w));
  float glow = p2.y;
  float3 bg = glow > 0.0 ? float3(0.0) : float3(223.0, 210.0, 194.0) / 255.0;
  float3 col = bg;
  float3 oc = CAM - center.xyz;
  float b = dot(oc, rd);
  float h = b * b - dot(oc, oc) + center.w * center.w;
  if (h > 0.0) {
    float spec;
    float gEdge = 0.0;
    float3 start = CAM + rd * max(-b - sqrt(h), 0.0);
    float len = straightPath(start, rd, spec, gEdge);
    if (glow > 0.0) {
      col = glowRamp(1.0 - exp(-len * p1.x * glow - gEdge * p2.z));
    } else {
      col = ramp(1.0 - exp(-len * p1.x));
    }
    col += spec * p1.y * float3(0.82, 0.88, 1.0);
    float x = 1.0 - exp(-len * p1.x);
    col = mix(col, tint.rgb, tint.w * smoothstep(0.0, 0.6, x));
  }
  if (halo.w > 0.0) {
    float dist = length(oc - rd * dot(oc, rd));
    float rim = shape.x + wall.y;
    float g = halo.w * exp(-max(dist - rim, 0.0) * 6.0) * smoothstep(rim - 0.2, rim + 0.02, dist);
    col = mix(col, halo.rgb, clamp(g, 0.0, 1.0));
  }
  // present: colour over transparency, the background becoming see-through.
  float a = 0.0;
  for (int i = 0; i < 3; i++) {
    float bc = i == 0 ? bg.r : (i == 1 ? bg.g : bg.b);
    float cc = i == 0 ? col.r : (i == 1 ? col.g : col.b);
    a = max(a, bc > 0.0 ? 1.0 - cc / bc : 0.0);
    a = max(a, bc < 1.0 ? (cc - bc) / (1.0 - bc) : 0.0);
  }
  a = clamp(a, 0.0, 1.0);
  float3 premul = max(col - bg * (1.0 - a), 0.0);
  return half4(half3(premul * outU.x), half(a * outU.x));
}
""".trimIndent()
    }
}
