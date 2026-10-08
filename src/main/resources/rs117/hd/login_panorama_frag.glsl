#version 330

#include <uniforms/global.glsl>

// Injected at shader-compile time by LoginScreenBackdropRenderer.initializeShaders, kept in sync with
// PanoramaCapture.NUM_FACES.
#include PANORAMA_NUM_FACES

// Captured horizon faces from PanoramaCapture.java, stored as layers of a single texture array (layer i was
// captured facing yawTargetForDirection(i) there) - not a true 6-face cubemap, see PanoramaCapture's class
// comment for why only the horizon ring is captured (no camera position/zoom setter and gameplay-clamped pitch
// in RuneLite's camera API rule out a full cube).
uniform sampler2DArray faceTextures;

// Half the real captured field of view (horizontal == vertical, since each face is cropped square), in radians -
// read back from what PanoramaCapture recorded at capture time (see computeCapturedHalfFovRadians there). Each
// face is still centered FACE_ANGLE_STEP apart (that's how the camera was steered) - PANORAMA_NUM_FACES needs to
// be high enough that this real FOV actually overlaps with the spacing, or there's a gap of real un-photographed
// space between neighbouring faces that no amount of blending can turn into continuous content.
uniform float capturedHalfFovRadians;

in vec2 fScreenPos;

out vec4 FragColor;

// PI/HALF_PI already come from utils/constants.glsl, pulled in transitively via uniforms/global.glsl.
#define TWO_PI (2.0 * PI)
#define FACE_ANGLE_STEP (TWO_PI / float(PANORAMA_NUM_FACES))

// Expressed as a multiple of capturedHalfFovRadians beyond the real content's edge - the face's own content
// fades out to VOID_COLOR over this much extra angle, rather than either stretching the edge pixel across the
// whole gap (blurry smear) or hard-cutting straight to the next face's own stretched edge (jarring seam). With
// enough faces and real overlap, this should rarely even trigger - it's a safety net for whatever residual gap
// is left, not the primary way neighbouring faces meet.
#define GAP_FADE_WIDTH 0.6
#define VOID_COLOR vec3(0.04, 0.05, 0.07)

void main() {
	vec4 nearWorld = uboGlobal.invProjectionMatrix * vec4(fScreenPos, -1.0, 1.0);
	vec4 farWorld = uboGlobal.invProjectionMatrix * vec4(fScreenPos, 1.0, 1.0);
	vec3 dir = normalize(farWorld.xyz / farWorld.w - nearWorld.xyz / nearWorld.w);

	float yaw = atan(dir.x, dir.z);
	if (yaw < 0.0)
		yaw += TWO_PI;

	// Nearest face CENTER, not an angle-step sector floor.
	float faceF = yaw / FACE_ANGLE_STEP;
	int faceRaw = int(floor(faceF + 0.5));
	// GLSL's % can return negative results for negative operands (unlike mod()), so wrap manually rather than
	// relying on faceRaw already being non-negative.
	int face = faceRaw % PANORAMA_NUM_FACES;
	if (face < 0)
		face += PANORAMA_NUM_FACES;
	float faceCenterYaw = float(faceRaw) * FACE_ANGLE_STEP;

	// Rotate dir into this face's own local space, where its capture direction sits at local +Z (yaw=0) - undoes
	// the face's own yaw so a standard gnomonic/perspective projection can be applied below. Matches how
	// PanoramaCapture.yawTargetForDirection related dir.x = atan-numerator to dir.z = atan-denominator.
	float c = cos(faceCenterYaw), s = sin(faceCenterYaw);
	vec3 localDir = vec3(dir.x * c - dir.z * s, dir.y, dir.x * s + dir.z * c);

	// True rectilinear/perspective (gnomonic) projection onto the face's capture plane at localDir.z = 1 - the
	// capture is a standard perspective screenshot, where screen position is linear in tan(angle) from the
	// capture direction, not in the angle itself. localDir.z is always positive here since this is always the
	// nearest face to dir.
	float tanHalfFov = tan(capturedHalfFovRadians);
	float uTan = (localDir.x / localDir.z) / tanHalfFov;
	float vTan = (localDir.y / localDir.z) / tanHalfFov;

	if (vTan < -1.0) {
		// Above the captured range - plain sky-ish gradient rather than the real procedural sky for now, to
		// avoid depending on this standalone login-screen pass fully replicating the live sky system's uniform
		// state (weather, sun angle, etc.) just to avoid a wrong-looking sky here.
		vec3 skyColor = mix(vec3(0.55, 0.72, 0.95), vec3(0.15, 0.35, 0.75), clamp(-vTan - 1.0, 0.0, 1.0));
		FragColor = vec4(skyColor, 1.0);
		return;
	}

	vec2 uv = vec2(clamp(uTan, -1.0, 1.0), clamp(vTan, -1.0, 1.0)) * 0.5 + 0.5;
	vec3 color = texture(faceTextures, vec3(uv, float(face))).rgb;

	// Fade out toward the void colour the further outside the real content a pixel falls, on whichever axis is
	// further out - softens both the horizontal gap between faces and the ground below the vertical range into
	// a plain dark fade instead of a stretched/frozen edge pixel or a hard seam against the next face.
	float gapDistance = max(abs(uTan), abs(vTan)) - 1.0;
	float fade = smoothstep(0.0, GAP_FADE_WIDTH, gapDistance);
	color = mix(color, VOID_COLOR, fade);

	FragColor = vec4(color, 1.0);
}
