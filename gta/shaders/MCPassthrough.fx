// Composites Minecraft (passthrough mod) into GTA V. The MCPassthrough add-on (MCPassthrough.asi) uploads
// Minecraft's latest frame into the MCWORLD / MCDEPTH / MCOVERLAY textures and sets the plane uniforms.
//
// world: premultiplied RGBA, drawn where Minecraft is nearer than GTA's depth buffer (both in metres).
// overlay: Minecraft's hand, HUD and screens, always on top.
// avatar (third person): the player alone, skin, armour and all, shown as Minecraft drew them (camera-relative, never
// re-projected: re-projection is what tore the head and left copies behind), depth-tested against GTA and the world.
// The avatar layer follows EldenCraft's EcAvatar (MIT, Copyright (c) 2026 Adam Grzegorzewski, github.com/SocketByte/EldenCraft).
#include "ReShade.fxh"

texture MCWorldTex : MCWORLD;
texture MCDepthTex : MCDEPTH;
texture MCOverlayTex : MCOVERLAY;
sampler sWorld { Texture = MCWorldTex; AddressU = CLAMP; AddressV = CLAMP; };
sampler sDepth { Texture = MCDepthTex; MinFilter = POINT; MagFilter = POINT; AddressU = CLAMP; AddressV = CLAMP; };
sampler sOverlay { Texture = MCOverlayTex; AddressU = CLAMP; AddressV = CLAMP; };
texture MCAvatarTex : MCAVATAR;
texture MCAvatarDepthTex : MCAVATARDEPTH;
sampler sAvatar { Texture = MCAvatarTex; AddressU = CLAMP; AddressV = CLAMP; };
sampler sAvatarDepth { Texture = MCAvatarDepthTex; MinFilter = POINT; MagFilter = POINT; AddressU = CLAMP; AddressV = CLAMP; };
// Set by the add-on: the shown frame has the avatar layer (then the world layer has no player in it).
uniform bool AvatarReady = false;

// x = near, y = far, z = flags (1: [0,1] depth, 2: rows bottom-up, 4: reversed Z). Set by the add-on.
uniform float3 McPlanes = float3(0.05, 2048.0, 7.0);
// GTA's camera near/far clip. Set by the add-on.
uniform float2 HostPlanes = float2(0.15, 10000.0);

// Set true by the add-on only while it has uploaded a Minecraft frame; until then GTA passes through untouched.
uniform bool McActive = false;

uniform bool HostReversedZ < ui_label = "GTA depth is reversed"; > = true;
uniform float DepthBias < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.005; ui_label = "Depth bias (m)";
	ui_tooltip = "How far behind GTA's surface Minecraft may still show (blocks resting on the ground)."; > = 0.04;
uniform float SlopeBias < ui_type = "drag"; ui_min = 0.0; ui_max = 40.0; ui_step = 0.1; ui_label = "Depth bias per grazing slope";
	ui_tooltip = "Extra depth bias where GTA's surface is seen at a grazing angle (its depth changes fast down the screen): Minecraft ground laid into GTA's (lava, netherrack) still shows far away."; > = 0.0;
uniform float MaxBias < ui_type = "drag"; ui_min = 0.1; ui_max = 10.0; ui_step = 0.1; ui_label = "Largest depth bias (m)"; > = 3.0;
uniform float HostDepthScale < ui_type = "drag"; ui_min = 0.5; ui_max = 2.0; ui_step = 0.001; ui_label = "GTA depth scale";
	ui_tooltip = "Calibration: multiplies GTA's linearised depth."; > = 1.0;
uniform float LightMatch < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Match GTA lighting";
	ui_tooltip = "Relight Minecraft by the (blurred) GTA picture around it: darker in shade, tinted by nearby light."; > = 0.7;
uniform float LightReference < ui_type = "drag"; ui_min = 0.1; ui_max = 1.0; ui_step = 0.01; ui_label = "Neutral brightness";
	ui_tooltip = "GTA brightness at which Minecraft keeps its own colours."; > = 0.42;
uniform float LightTint < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Light colour"; > = 0.3;
uniform float LightBlur < ui_type = "drag"; ui_min = 1.0; ui_max = 6.0; ui_step = 0.1; ui_label = "Light blur (mip)";
	ui_tooltip = "How wide an area of GTA's picture lights each Minecraft pixel. Low values let the shapes of what's behind a block show through it (a see-through look)."; > = 4.6;
uniform float GradeMatch < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Match GTA colour grade";
	ui_tooltip = "Give Minecraft the colour of GTA's whole picture (a red Nether night, an orange sunset), as GTA's own grading does to its world."; > = 0.55;
uniform float HazeStart < ui_type = "drag"; ui_min = 0.0; ui_max = 200.0; ui_step = 1.0; ui_label = "Haze start (m)"; > = 40.0;
uniform float HazeDistance < ui_type = "drag"; ui_min = 10.0; ui_max = 1000.0; ui_step = 1.0; ui_label = "Haze distance (m)";
	ui_tooltip = "Far away, Minecraft takes on the colour of GTA's air (GTA's haze and fog don't reach Minecraft otherwise)."; > = 220.0;
uniform float HazeStrength < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Haze strength"; > = 0.6;
uniform bool CrispPixels < ui_label = "Crisp Minecraft pixels";
	ui_tooltip = "Scale Minecraft's pixel art up sharp (each texel flat) instead of blurring it."; > = true;
uniform float EdgeSoftness < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Soften Minecraft's edges";
	ui_tooltip = "Anti-alias where Minecraft meets GTA (GTA's own anti-aliasing ran before Minecraft was added). High values blend edge pixels into GTA, which reads as a see-through rim."; > = 0.15;
uniform float ContactShadow < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Contact shadows";
	ui_tooltip = "Darken GTA's surfaces right next to Minecraft's (under mobs' feet, around blocks)."; > = 0.5;
uniform float ContactRadius < ui_type = "drag"; ui_min = 0.1; ui_max = 3.0; ui_step = 0.05; ui_label = "Contact shadow size (m)"; > = 0.7;
uniform float BloomStrength < ui_type = "drag"; ui_min = 0.0; ui_max = 2.0; ui_step = 0.01; ui_label = "Minecraft glow";
	ui_tooltip = "Bright Minecraft things (lava, fire, the portal) glow over GTA's picture, as GTA's own lights do."; > = 0.55;
uniform float BloomThreshold < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Glow threshold"; > = 0.62;
// Set by the add-on: the camera shake, applied to the finished picture so GTA and Minecraft shake together
// (x, y: fraction of the screen height; z: roll, radians), and the nether portal's warp (0..1) while passing through.
uniform float3 Shake = float3(0.0, 0.0, 0.0);
uniform float PortalWarp = 0.0;
uniform float Timer < source = "timer"; >;
uniform int DebugView < ui_type = "combo"; ui_items = "Composite\0GTA depth (1 m bands)\0Minecraft depth (1 m bands)\0Depth difference\0Avatar layer (red: none this frame)\0Avatar depth test (green: shown, red: behind GTA)\0Avatar vs world (blue: avatar nearer, yellow: world nearer)\0"; > = 0;
uniform bool Reproject < ui_label = "Re-project to GTA's camera"; ui_tooltip = "Rotate Minecraft's (slightly older) frame onto GTA's current camera."; > = true;
uniform float PosePrediction < ui_type = "drag"; ui_min = -2.0; ui_max = 3.0; ui_step = 0.05; ui_label = "Pose prediction (frames)";
	ui_tooltip = "Extrapolate GTA's camera rotation by this many frames before re-projecting."; > = 0.0;
uniform int PoseLag < ui_type = "slider"; ui_min = 0; ui_max = 4; ui_label = "Pose lag (frames)";
	ui_tooltip = "How many frames after the script reads GTA's camera that picture is shown. If Minecraft's blocks swim or double while you turn, try 1 or 2 (GTA V Enhanced's DirectX 12 may need it); keep the steadiest."; > = 0;
uniform bool MoveSteve < ui_label = "Move Steve with the player";
	ui_tooltip = "Steve's pixels follow GTA's player by how far it has moved since Minecraft drew him (no Steve left behind, or doubled, while walking or driving)."; > = true;

// Set by the add-on: how far Steve moved since Minecraft's frame (Minecraft camera space), his middle as drawn there
// (w = 1 while there is a Steve to move), and the world's up there.
uniform float3 SteveDelta = float3(0.0, 0.0, 0.0);
uniform float4 SteveAt = float4(0.0, 0.0, 0.0, 0.0);
uniform float3 SteveUp = float3(0.0, 1.0, 0.0);
// Set by the add-on: GTA's cursor (uv) and z = 1 while a Minecraft screen (inventory, the creative menu, chat) has the
// mouse: drawn over that screen, which is composited after GTA drew its own cursor.
uniform float3 McCursor = float3(0.0, 0.0, 0.0);

// Set by the add-on: rows of R_mc^T R_gta (GTA camera ray -> Minecraft camera ray), and
// x = tan(GTA vertical fov / 2), y = tan(Minecraft vertical fov / 2), z = Minecraft aspect.
uniform float3 WarpRow0 = float3(1.0, 0.0, 0.0);
uniform float3 WarpRow1 = float3(0.0, 1.0, 0.0);
uniform float3 WarpRow2 = float3(0.0, 0.0, 1.0);
uniform float3 WarpTan = float3(0.7, 0.7, 1.7777);
// Set by the add-on: GTA's camera position relative to Minecraft's, in Minecraft's camera space.
uniform float3 WarpT = float3(0.0, 0.0, 0.0);

// GTA's picture at quarter size with mips: its blurred levels stand in for the light around each pixel.
texture GtaLightTex { Width = BUFFER_WIDTH / 4; Height = BUFFER_HEIGHT / 4; Format = RGBA8; MipLevels = 7; };
sampler sGtaLight { Texture = GtaLightTex; AddressU = CLAMP; AddressV = CLAMP; };

// The composited world (GTA + Minecraft, no overlay) and, per pixel: Minecraft's coverage, its depth and GTA's.
texture CompositeTex { Width = BUFFER_WIDTH; Height = BUFFER_HEIGHT; Format = RGBA8; };
sampler sComposite { Texture = CompositeTex; AddressU = CLAMP; AddressV = CLAMP; };
texture McInfoTex { Width = BUFFER_WIDTH; Height = BUFFER_HEIGHT; Format = RGBA16F; };
sampler sMcInfo { Texture = McInfoTex; MinFilter = POINT; MagFilter = POINT; AddressU = CLAMP; AddressV = CLAMP; };
// Minecraft's bright parts at quarter size with mips (the glow).
texture McBrightTex { Width = BUFFER_WIDTH / 4; Height = BUFFER_HEIGHT / 4; Format = RGBA16F; MipLevels = 6; };
sampler sMcBright { Texture = McBrightTex; AddressU = CLAMP; AddressV = CLAMP; };

float luma(float3 c)
{
	return dot(c, float3(0.2126, 0.7152, 0.0722));
}

float4 PS_Light(float4 pos : SV_Position, float2 uv : TEXCOORD) : SV_Target
{
	return float4(tex2D(ReShade::BackBuffer, uv).rgb, 1.0);
}

/// Minecraft renders at full daylight, so treat its colour as albedo and light it with GTA's local light; then give it
/// GTA's colour grade (the whole picture's colour) and, far away, GTA's haze (fade into the picture around it).
float3 relight(float3 c, float a, float2 uv, float zm)
{
	// the light here: GTA's picture blurred well past any single thing behind this pixel (a sky or a wall right behind a
	// block mustn't show in it: that reads as the block being see-through), its brightness local, its colour from a wider
	// area still, and both kept within a narrow range for the same reason
	const float3 L = tex2Dlod(sGtaLight, float4(uv, 0, LightBlur)).rgb;
	const float3 W = tex2Dlod(sGtaLight, float4(uv, 0, min(LightBlur + 1.5, 6.0))).rgb;
	const float lum = luma(L);
	const float gain = clamp(lum / LightReference, 0.6, 1.2);
	const float3 tint = clamp(lerp(1.0, W / max(luma(W), 1e-3), LightTint), 0.75, 1.25);
	c = lerp(c, c * gain * tint, LightMatch);
	// the picture's grade, gentled (GTA's own grade keeps some colour in everything) and kept off lights (bright
	// things keep their colour: fire, lava, the portal)
	const float3 M = tex2Dlod(sGtaLight, float4(0.5, 0.5, 0, 6.0)).rgb;
	const float3 grade = clamp(lerp(1.0, M / max(luma(M), 1e-3), 0.6), 0.6, 1.6);
	c = lerp(c, c * grade, GradeMatch * (1.0 - saturate((max(c.r, max(c.g, c.b)) - 0.45) * 2.5)));
	// far away, GTA's haze: towards the colour of GTA's whole picture (its air), never towards what's behind the block,
	// which would make far blocks see-through (premultiplied: the haze only where Minecraft covers)
	const float h = saturate((1.0 - exp(-max(zm - HazeStart, 0.0) / HazeDistance)) * HazeStrength);
	return lerp(c, M * a, h);
}

/// Minecraft's picture is pixel art, scaled up and re-projected: sample it crisp (each texel flat, blended only across
/// about one screen pixel at its edge) instead of smearing every texel over its neighbours. `scale`: texels per screen pixel.
float4 sample_crisp(sampler s, float2 uv, float2 size, float scale)
{
	const float2 t = uv * size - 0.5;
	const float2 f = frac(t);
	const float2 a = saturate((f - 0.5) / max(scale, 1e-3) + 0.5);
	return tex2Dlod(s, float4((floor(t) + a + 0.5) / size, 0, 0));
}

float mc_linear(float d)
{
	const float n = McPlanes.x, f = McPlanes.y;
	return d <= 0.0 ? 1e9 : n * f / (n + d * (f - n));
}

float host_linear(float d)
{
	const float n = HostPlanes.x, f = HostPlanes.y;
	float z;
	if (HostReversedZ)
		z = d > 0.0 ? n * f / (n + d * (f - n)) : 1e9;
	else
		z = d < 1.0 ? n * f / (f - d * (f - n)) : 1e9;
	return z * HostDepthScale;
}

float3 bands(float z)
{
	const float t = frac(z);
	return lerp(float3(0.1, 0.1, 0.1), float3(1.0, 0.85, 0.3), step(0.5, t)) * saturate(1.5 - z / 100.0);
}

/// Whether a point in Minecraft's camera space is Steve (as Minecraft drew him): inside an upright 1.85 m x 0.55 m
/// capsule-ish box round his middle, so the block under his feet or next to him isn't.
bool is_steve(float3 q)
{
	const float3 d = q - SteveAt.xyz;
	const float along = dot(d, SteveUp);
	return MoveSteve && SteveAt.w > 0.0 && abs(along) < 0.92 && length(d - SteveUp * along) < 0.55;
}

/// The point `z` metres along this GTA ray, seen from the camera Minecraft rendered with (rotation + how far the camera
/// moved since, minus `shift`: how far the thing moved itself): false if Minecraft didn't see it, else where it lands
/// in Minecraft's picture (n), how far it is from that camera (zp) and how far Minecraft's surface there is (zs).
bool mc_at(float3 ray, float z, float3 shift, float2 mcScale, out float2 n, out float zp, out float zs)
{
	const float3 p = ray * z;
	const float3 pm = float3(dot(WarpRow0, p), dot(WarpRow1, p), dot(WarpRow2, p)) + WarpT - shift;
	zp = -pm.z;
	n = pm.xy / max(zp, 1e-3) / mcScale;
	const bool seen = zp >= 1e-3 && all(abs(n) <= 1.0);
	zs = seen ? mc_linear(tex2Dlod(sDepth, float4(n * 0.5 + 0.5, 0, 0)).r) : 1e9;
	return seen;
}

/// March this GTA pixel's ray (in GTA's current camera) from near to far through Minecraft's frame. Where it goes from
/// in front of Minecraft's surface to behind it, bisect to the crossing; that's where Minecraft is seen only if the ray
/// really meets the surface there. If it's still far behind it, the ray only passed behind something (a thin thing in
/// front, like Steve or what he holds), and marching goes on: otherwise everything behind such things would show them
/// again, smeared along the camera's motion. steve: only Steve counts (moved by `shift`); else everything but Steve
/// as drawn (he's somewhere else now).
bool march(float2 uv, float zFar, float3 shift, bool steve, out float2 muv, out float zm)
{
	muv = 0.0;
	zm = 1e9;
	const float3 ray = float3((uv.x * 2.0 - 1.0) * WarpTan.x * BUFFER_WIDTH * BUFFER_RCP_HEIGHT, (1.0 - uv.y * 2.0) * WarpTan.x, -1.0);
	const float2 mcScale = float2(WarpTan.y * WarpTan.z, WarpTan.y);
	const float zNear = 0.2;
	float zFront = zNear * 0.5; // the last point known to be in front of Minecraft (or not seen by it)
	[loop] for (int i = 0; i < 32; ++i)
	{
		const float z = zNear * pow(zFar / zNear, i / 31.0);
		float2 n;
		float zp, zs;
		if (!mc_at(ray, z, shift, mcScale, n, zp, zs) || zs > zp + 0.03)
		{
			zFront = z;
			continue;
		}
		// crossed Minecraft's surface somewhere in (zFront, z]: bisect to it
		float za = zFront, zb = z;
		[loop] for (int k = 0; k < 6; ++k)
		{
			const float zmid = 0.5 * (za + zb);
			float2 nm;
			float zpm, zsm;
			if (mc_at(ray, zmid, shift, mcScale, nm, zpm, zsm) && zsm <= zpm + 0.03)
			{
				zb = zmid;
				n = nm;
				zp = zpm;
				zs = zsm;
			}
			else
				za = zmid;
		}
		if (zp - zs > 0.12 + 0.02 * zp)
		{
			zFront = z; // passed behind something: not a surface this ray meets
			continue;
		}
		// what it met, in Minecraft's camera space: Steve, or the world
		if (is_steve(float3(n * mcScale, -1.0) * zs) != steve)
			return false; // the world pass at Steve's old place: GTA shows there until Minecraft draws it again
		muv = n * 0.5 + 0.5;
		zm = zb;
		return true;
	}
	return false;
}

void PS_Composite(float4 pos : SV_Position, float2 uv : TEXCOORD, out float4 outColor : SV_Target0, out float4 outInfo : SV_Target1)
{
	const float3 host = tex2D(ReShade::BackBuffer, uv).rgb;
	outInfo = 0.0;
	outColor = float4(host, 1.0);
	if (!McActive)
		return;
	const float2 ouv = float2(uv.x, 1.0 - uv.y); // Minecraft's rows are bottom-up

	// Where this GTA pixel's view ray lands in Minecraft's frame.
	// without re-projection: the same view, scaled into Minecraft's (wider, by any overscan) frame
	float2 muv = (ouv - 0.5) * (WarpTan.x / max(WarpTan.y, 1e-3)) + 0.5;
	bool inside = true;
	const float zh = host_linear(tex2Dlod(ReShade::DepthBuffer, float4(uv, 0, 0)).x);
	// how far behind GTA's surface Minecraft may still show: more where that surface is seen at a grazing angle
	const float allow = min(DepthBias + SlopeBias * abs(ddy(zh)), max(MaxBias, DepthBias));
	float zm = 1e9;
	if (Reproject)
	{
		// Steve first, moved by how far he went since Minecraft drew him; then the world (the camera's motion only)
		const float zFar = max(min(zh + allow, 400.0), 0.5);
		inside = false;
		if (MoveSteve && SteveAt.w > 0.0)
		{
			// only rays that come near him (in Minecraft's camera space, the ray is the line WarpT - SteveDelta + t R ray)
			const float3 ray = float3((uv.x * 2.0 - 1.0) * WarpTan.x * BUFFER_WIDTH * BUFFER_RCP_HEIGHT, (1.0 - uv.y * 2.0) * WarpTan.x, -1.0);
			const float3 d = normalize(float3(dot(WarpRow0, ray), dot(WarpRow1, ray), dot(WarpRow2, ray)));
			const float3 o = SteveAt.xyz - (WarpT - SteveDelta);
			if (length(o - d * dot(o, d)) < 1.4)
				inside = march(uv, zFar, SteveDelta, true, muv, zm);
		}
		if (!inside)
			inside = march(uv, zFar, float3(0.0, 0.0, 0.0), false, muv, zm);
	}
	// Minecraft texels per screen pixel here (its frame is scaled to GTA's picture, wider by any overscan)
	const float2 mcSize = float2(tex2Dsize(sWorld, 0));
	const float texelsPerPixel = mcSize.y / BUFFER_HEIGHT * (WarpTan.x / max(WarpTan.y, 1e-3));
	float4 world = float4(0.0, 0.0, 0.0, 0.0);
	if (inside)
		world = CrispPixels ? sample_crisp(sWorld, muv, mcSize, texelsPerPixel) : tex2D(sWorld, muv);
	if (!Reproject)
		zm = mc_linear(tex2D(sDepth, muv).r);

	outInfo = float4(0.0, 0.0, zh, 0.0);
	if (DebugView == 1)
	{
		outColor = float4(bands(zh), 1.0);
		return;
	}
	if (DebugView == 2)
	{
		outColor = float4(world.a > 0.0 ? bands(zm) : host * 0.3, 1.0);
		return;
	}
	if (DebugView == 3)
	{
		outColor = float4(world.a > 0.0 ? float3(saturate((zh - zm) * 0.5 + 0.5), saturate(-(zh - zm) * 0.5 + 0.5), 0.0) : host * 0.3, 1.0);
		return;
	}

	const float visible = zm < zh + allow ? 1.0 : 0.0;
	const float cover = world.a * visible;
	const float3 worldLit = relight(world.rgb, world.a, uv, zm) * visible;

	// the player's own layer, as Minecraft drew it: only GTA's current lens is applied (its frame can be wider)
	float4 avatar = 0.0;
	float za = 1e9;
	if (AvatarReady)
	{
		const float2 auv = (ouv - 0.5) * (WarpTan.x / max(WarpTan.y, 1e-3)) + 0.5;
		if (all(auv >= 0.0) && all(auv <= 1.0))
		{
			const float d = tex2Dlod(sAvatarDepth, float4(auv, 0, 0)).r;
			if (d > 0.0) // reversed Z: 0 is nothing drawn
			{
				za = mc_linear(d);
				avatar = CrispPixels ? sample_crisp(sAvatar, auv, mcSize, texelsPerPixel) : tex2D(sAvatar, auv);
			}
		}
	}
	if (DebugView == 4)
	{
		outColor = float4(avatar.a > 0.0 ? avatar.rgb : host * 0.3 + (AvatarReady ? 0.0 : float3(0.4, 0.0, 0.0)), 1.0);
		return;
	}
	if (DebugView == 5)
	{
		outColor = float4(avatar.a > 0.0 ? (za < zh + allow ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0)) * saturate(za / 8.0) : host * 0.3, 1.0);
		return;
	}
	if (DebugView == 6)
	{
		outColor = float4(avatar.a > 0.0 ? (cover <= 0.0 ? float3(0.5, 0.5, 0.5) : za <= zm ? float3(0.0, 0.3, 1.0) : float3(1.0, 0.9, 0.0)) : host * 0.3, 1.0);
		return;
	}
	const float aCover = za < zh + allow ? avatar.a : 0.0;
	if (aCover > 0.0)
	{
		const float3 avatarLit = relight(avatar.rgb, avatar.a, uv, za);
		// nearest first: the player in front of the world, or a block between the camera and the player
		const float3 c = za <= zm || cover <= 0.0 ? avatarLit + (worldLit + host * (1.0 - cover)) * (1.0 - aCover)
			: worldLit + (avatarLit + host * (1.0 - aCover)) * (1.0 - cover);
		const float both = 1.0 - (1.0 - cover) * (1.0 - aCover);
		outColor = float4(c, 1.0);
		// w: how much of this is the player (no glow on them: armour and skin aren't lights)
		outInfo = float4(both, cover > 0.0 ? min(zm, za) : za, zh, za <= zm || cover <= 0.0 ? aCover : 0.0);
		return;
	}
	outColor = float4(worldLit + host * (1.0 - cover), 1.0);
	outInfo = float4(cover, cover > 0.0 ? zm : 0.0, zh, 0.0);
}

/// Minecraft's bright parts (lava, fire, the portal, blazes): what glows.
float4 PS_Bright(float4 pos : SV_Position, float2 uv : TEXCOORD) : SV_Target
{
	const float4 info = tex2D(sMcInfo, uv);
	const float cover = info.x * (1.0 - info.w);
	const float3 c = tex2D(sComposite, uv).rgb;
	const float l = luma(c);
	return float4(c * (cover * saturate((l - BloomThreshold) / max(1.0 - BloomThreshold, 1e-3)) / max(l, 1e-3)), 1.0);
}

/// The finished picture: the world (shaken as one: GTA and Minecraft together), Minecraft's edges softened, contact
/// shadows on GTA's surfaces next to Minecraft's, Minecraft's glow, the portal's warp, and Minecraft's hand and HUD on
/// top (neither shaken nor warped).
float3 PS_Final(float4 pos : SV_Position, float2 uv : TEXCOORD) : SV_Target
{
	if (!McActive)
		return tex2D(ReShade::BackBuffer, uv).rgb;
	const float aspect = BUFFER_WIDTH * BUFFER_RCP_HEIGHT;
	const float t = Timer * 0.001;
	// shake: rotate and move the picture a little, zoomed in just enough that no edge shows
	const float amount = length(Shake.xy) + abs(Shake.z) * 0.5;
	const float zoom = 1.0 + amount * 2.2 + PortalWarp * 0.06;
	float2 d = (uv - 0.5) * float2(aspect, 1.0) / zoom;
	// the portal: a swirl and waves, strongest in the middle
	if (PortalWarp > 0.0)
	{
		const float r = length(d);
		const float swirl = PortalWarp * 1.1 * exp(-r * 2.2) * sin(t * 1.7 + 1.0);
		const float cs = cos(swirl), sn = sin(swirl);
		d = float2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
		d += PortalWarp * 0.014 * float2(sin(d.y * 38.0 + t * 7.0), cos(d.x * 31.0 + t * 6.0));
	}
	const float cr = cos(Shake.z), sr = sin(Shake.z);
	d = float2(d.x * cr - d.y * sr, d.x * sr + d.y * cr) + Shake.xy;
	const float2 wuv = d / float2(aspect, 1.0) + 0.5;

	const float2 px = BUFFER_PIXEL_SIZE;
	float3 color = tex2D(sComposite, wuv).rgb;
	const float4 info = tex2D(sMcInfo, wuv);
	// soften where Minecraft meets GTA (GTA's anti-aliasing never saw Minecraft)
	if (EdgeSoftness > 0.0)
	{
		const float c1 = tex2D(sMcInfo, wuv + float2(px.x, 0)).x, c2 = tex2D(sMcInfo, wuv - float2(px.x, 0)).x;
		const float c3 = tex2D(sMcInfo, wuv + float2(0, px.y)).x, c4 = tex2D(sMcInfo, wuv - float2(0, px.y)).x;
		const float edge = saturate(abs(c1 - info.x) + abs(c2 - info.x) + abs(c3 - info.x) + abs(c4 - info.x));
		if (edge > 0.0)
		{
			const float3 n = tex2D(sComposite, wuv + float2(px.x, 0)).rgb + tex2D(sComposite, wuv - float2(px.x, 0)).rgb
				+ tex2D(sComposite, wuv + float2(0, px.y)).rgb + tex2D(sComposite, wuv - float2(0, px.y)).rgb;
			color = lerp(color, (color * 2.0 + n) / 6.0, edge * EdgeSoftness);
		}
	}
	// contact shadows: GTA surface pixels with Minecraft surfaces close by (in depth), mostly above them
	if (ContactShadow > 0.0 && info.x < 0.5 && info.z < 200.0)
	{
		const float zh = info.z;
		const float r = clamp(ContactRadius / max(zh, 0.5) * BUFFER_HEIGHT / (2.0 * WarpTan.x), 2.0, 48.0);
		float occ = 0.0, weight = 0.0;
		[unroll] for (int i = 0; i < 10; ++i)
		{
			const float a = i * 2.39996 + 0.6; // golden-angle spiral
			const float rr = r * sqrt((i + 0.5) / 10.0);
			const float2 o = float2(cos(a), sin(a)) * rr;
			const float4 sm = tex2Dlod(sMcInfo, float4(wuv + o * px, 0, 0));
			const float w = o.y < 0.0 ? 1.0 : 0.35; // things stand on the ground: their shadow is below them
			weight += w;
			if (sm.x > 0.5 && abs(sm.y - zh) < ContactRadius * 1.5)
				occ += w * (1.0 - rr / (r + 1.0));
		}
		color *= 1.0 - ContactShadow * saturate(occ / max(weight, 1e-3) * 2.2);
	}
	// Minecraft's glow
	if (BloomStrength > 0.0)
	{
		const float3 b = tex2Dlod(sMcBright, float4(wuv, 0, 1.0)).rgb * 0.5 + tex2Dlod(sMcBright, float4(wuv, 0, 2.5)).rgb * 0.8
			+ tex2Dlod(sMcBright, float4(wuv, 0, 4.0)).rgb;
		color += b * BloomStrength;
	}
	// the portal's purple
	if (PortalWarp > 0.0)
	{
		const float r = length((uv - 0.5) * float2(aspect, 1.0));
		const float3 purple = color * float3(0.72, 0.38, 1.25) + float3(0.12, 0.0, 0.22) * (0.6 + 0.4 * sin(t * 3.0 + r * 9.0));
		color = lerp(color, purple, saturate(PortalWarp * (0.55 + r * 0.6)));
	}
	// hand, HUD and screens: 2D over Minecraft's whole window, which is GTA's picture: 1:1, never shaken
	const float2 ouv = float2(uv.x, 1.0 - uv.y);
	const float2 oSize = float2(tex2Dsize(sOverlay, 0));
	const float4 overlay = CrispPixels ? sample_crisp(sOverlay, ouv, oSize, oSize.y / BUFFER_HEIGHT) : tex2D(sOverlay, ouv);
	float3 result = overlay.rgb + saturate(color) * (1.0 - overlay.a);
	if (McCursor.z > 0.5)
	{
		// GTA's cursor over Minecraft's screen: an arrow, white with a black edge, its tip at the cursor (1080p-sized)
		const float2 p = (uv - McCursor.xy) * BUFFER_SCREEN_SIZE * (1080.0 * BUFFER_RCP_HEIGHT);
		const bool edge = p.x >= -1.5 && p.y >= -1.5 && p.y <= 22.0 && p.x <= p.y * 0.62 + 1.8;
		const bool fill = p.x >= 0.0 && p.y >= 0.0 && p.y <= 19.5 && p.x <= p.y * 0.62 - 0.4;
		if (edge)
			result = fill ? float3(1.0, 1.0, 1.0) : float3(0.0, 0.0, 0.0);
	}
	return result;
}

technique MCPassthrough < ui_tooltip = "Minecraft passthrough: enabled automatically while the Minecraft link is up."; >
{
	pass Light
	{
		VertexShader = PostProcessVS;
		PixelShader = PS_Light;
		RenderTarget = GtaLightTex;
	}
	pass Composite
	{
		VertexShader = PostProcessVS;
		PixelShader = PS_Composite;
		RenderTarget0 = CompositeTex;
		RenderTarget1 = McInfoTex;
	}
	pass Bright
	{
		VertexShader = PostProcessVS;
		PixelShader = PS_Bright;
		RenderTarget = McBrightTex;
	}
	pass Final
	{
		VertexShader = PostProcessVS;
		PixelShader = PS_Final;
	}
}
