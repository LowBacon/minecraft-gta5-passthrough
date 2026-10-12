#include "compositor.h"
#include "gpu_host.h"
#include "blocks/block_mapping.hpp"
#include "blocks/block_renderer.hpp"
#include <windows.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <reshade.hpp>

// what ReShade's Add-ons tab shows for this add-on
extern "C" __declspec(dllexport) const char *NAME = "MCPassthrough";
extern "C" __declspec(dllexport) const char *DESCRIPTION = "Minecraft x GTA V passthrough by LowBacon: draws Minecraft into GTA V's picture.";

using namespace reshade::api;

namespace
{
	constexpr const wchar_t *kMappingName = L"Local\\MCPassthroughFrame";
	constexpr uint32_t kMagic = 0x5450434D; // "MCPT"
	constexpr int kHeader = 4096;
	constexpr int kSlotDesc = 256;
	constexpr int kSlotDescBytes = 128;
	constexpr int kGpuDesc = 1024;               // + 32 * slot: u32 GPU generation, u32 set (for slots flagged 64)
	constexpr int kSceneDesc = 2048;             // + 64 * slot: the native block mesh the world layer left out (FrameExporter)
	constexpr uint32_t kSceneMagic = 0x4353434D; // "MCSC"
	constexpr int32_t kFlagAvatar = 32;          // the player alone in layers 3 (colour) and 4 (depth), not in the world layer
	constexpr uint32_t kMaxGpuW = 3840, kMaxGpuH = 2160; // GPU frames aren't bound by the slots' size
	constexpr const char *kEffect = "MCPassthrough.fx";

	std::atomic<bool> g_registered{false};
	std::atomic<bool> g_active{false};
	std::atomic<float> g_hostNear{0.15f};
	std::atomic<float> g_hostFar{10000.0f};
	std::atomic<uint32_t> g_bbWidth{0}, g_bbHeight{0};
	std::atomic<bool> g_cameraLocked{false};
	std::atomic<float> g_lookLight{-1.0f}, g_lookBias{-1.0f}, g_lookSlope{-1.0f};
	std::atomic<float> g_shakeX{0.0f}, g_shakeY{0.0f}, g_shakeRoll{0.0f}, g_portalWarp{0.0f};
	float g_savedLight = -1.0f, g_savedBias = -1.0f, g_savedSlope = -1.0f;

	struct Pose
	{
		float yaw = 0, pitch = 0, roll = 0, fov = 70;
		double x = 0, y = 0, z = 0;
		bool valid = false;
		double px = 0, py = 0, pz = 0; // the player's feet (Steve), Minecraft coordinates
		bool hasPlayer = false;
	};
	std::mutex g_poseLock;
	// GTA's recent camera poses, newest last. The script reads the camera GTA is about to render (the next frame),
	// while the picture being presented was rendered with the one before: re-project to that (g_poseLag back, or the
	// effect's "Pose lag": Legacy measured 0; Enhanced's DirectX 12 may present a frame or two later).
	constexpr unsigned kPoses = 8;
	Pose g_hostPoses[kPoses];
	unsigned g_hostPoseCount = 0;
	std::atomic<int> g_poseLag{-1}; // set by the script (F9, director): overrides the effect's slider; -1: the slider
	Pose g_mcPose;
	bool g_mcFirstPerson = false;
	std::atomic<float> g_cursorX{0.0f}, g_cursorY{0.0f};
	std::atomic<bool> g_cursorOn{false};

	HANDLE g_mapping = nullptr;
	const uint8_t *g_view = nullptr;
	volatile LONG *g_control = nullptr; // the header, writable (null: read-only access): +48 = the slot being copied
	constexpr int64_t kMaxLayerBytes = int64_t(7680) * 4320 * 4; // no layer is bigger than an 8K frame
	int32_t g_maxW = 0, g_maxH = 0;
	int32_t g_slots = 0;
	int64_t g_stride = 0;
	int64_t g_lastPublish = -1;
	DWORD g_nextOpenAttempt = 0;

	struct Layer
	{
		resource tex = {0};
		resource_view srv = {0};
	};
	// The five planes, in the slot's (and the GPU sets') order.
	enum Plane : uint32_t
	{
		kWorld,
		kDepth,
		kOverlay,
		kAvatar,
		kAvatarDepth,
		kPlaneCount
	};
	constexpr const char *kSemantics[kPlaneCount] = {"MCWORLD", "MCDEPTH", "MCOVERLAY", "MCAVATAR", "MCAVATARDEPTH"};
	constexpr format kFormats[kPlaneCount] = {format::r8g8b8a8_unorm, format::r32_float, format::r8g8b8a8_unorm, format::r8g8b8a8_unorm, format::r32_float};
	// What the effect samples: bound once (ReShade 6.8's update_texture_bindings waits for the whole GPU to go idle, so
	// rebinding every Minecraft frame stalled GTA each time). A frame reaches these only by a GPU copy, queued once the
	// frame is known whole and ahead of the effects on the same queue, so a torn frame never reaches the screen.
	Layer g_layers[kPlaneCount];
	// Slot frames on D3D11 land here first (written in place), then are copied whole into g_layers.
	Layer g_staging[kPlaneCount];
	// Slot frames on D3D12: copied into an upload buffer, checked, then copied on the GPU (no synchronous upload).
	struct Upload
	{
		resource buffer = {0};
		uint64_t completion = 0;
	};
	Upload g_uploads[3];
	uint32_t g_rowPitch = 0;
	bool g_d3d12 = false;
	bool g_mcAvatar = false; // the shown frame has the avatar layers
	uint32_t g_width = 0, g_height = 0;
	bool g_hasFrame = false;
	// Minecraft stopped publishing (crashed, frozen, its window minimized) for this long: GTA's picture goes untouched
	constexpr int64_t kStaleNanos = 1500000000;
	int64_t g_publishNanos = 0; // when the newest shown frame was published (Minecraft's nanoTime = this QPC clock)
	// GPU frames (D3D12, GTA V Enhanced): Minecraft writes into textures shared from here; copies are fenced
	gpu::Host g_gpu;
	fence g_copyFence = {0};
	uint64_t g_copyValue = 0;
	std::atomic<int> g_statGpuFrames{0};
	std::atomic<bool> g_gpuActive{false};
	// Native blocks: Minecraft's baked block mesh, drawn here with GTA's presented camera (blocks/)
	mcpt::blocks::Reader g_blockReader;
	mcpt::blocks::Mailbox g_blockAnim, g_blockLight;
	mcpt::blocks::Renderer g_blocks;
	mcpt::blocks::AckWriter g_blockAck;
	mcpt::blocks::Scene g_scene; // what the shown frame's world layer left out
	uint64_t g_blockBound = ~0ull; // the block targets' generation bound to the effect
	bool g_blocksReported = false;
	uint64_t g_blocksDiagnostic = 0;
	std::atomic<bool> g_statBlocks{false};
	std::atomic<uint32_t> g_statBlockVertices{0};
	// diagnostics
	std::atomic<int> g_statFps{0}, g_statAgeMs{-1}, g_statTorn{0};
	std::atomic<bool> g_statStale{false};
	// the script hands a pose every game frame; none for this long while GTA keeps presenting = the script isn't
	// running (GTA's pause menu and map stop ScriptHookV scripts): GTA's picture shows untouched, never a stale overlay
	constexpr int64_t kScriptGoneNanos = 250000000;
	std::atomic<int64_t> g_lastPoseNanos{0};
	std::atomic<bool> g_statScriptPaused{false};
	int g_framesThisSecond = 0;
	int64_t g_secondFrom = 0;

	int64_t now_nanos()
	{
		static const double freq = [] { LARGE_INTEGER f; QueryPerformanceFrequency(&f); return double(f.QuadPart); }();
		LARGE_INTEGER c;
		QueryPerformanceCounter(&c);
		return int64_t(double(c.QuadPart) / freq * 1e9);
	}
	float g_mcNear = 0.05f, g_mcFar = 2048.0f;
	int32_t g_mcFlags = 7;

	template <typename T>
	T read(const uint8_t *p)
	{
		T v;
		std::memcpy(&v, p, sizeof(T));
		return v;
	}

	bool open_mapping()
	{
		if (g_view != nullptr)
			return true;
		if (GetTickCount() < g_nextOpenAttempt)
			return false;
		g_nextOpenAttempt = GetTickCount() + 1000;
		// read + write: the header's +48 says which slot this copies (Minecraft never writes into that one meanwhile)
		g_mapping = OpenFileMappingW(FILE_MAP_READ | FILE_MAP_WRITE, FALSE, kMappingName);
		if (g_mapping == nullptr)
			g_mapping = OpenFileMappingW(FILE_MAP_READ, FALSE, kMappingName);
		if (g_mapping == nullptr)
			return false;
		const auto *header = static_cast<const uint8_t *>(MapViewOfFile(g_mapping, FILE_MAP_READ, 0, 0, kHeader));
		// a layout this add-on understands, and sizes that can't send a read past the mapping
		const bool valid = header != nullptr && read<uint32_t>(header) == kMagic && read<int32_t>(header + 8) == kHeader &&
			read<int32_t>(header + 12) >= 1 && read<int32_t>(header + 12) <= 8 && read<int64_t>(header + 16) > 0 &&
			read<int64_t>(header + 16) <= kMaxLayerBytes * 5 && read<int32_t>(header + 24) > 0 && read<int32_t>(header + 28) > 0 &&
			int64_t(read<int32_t>(header + 24)) * read<int32_t>(header + 28) * 4 * 3 <= read<int64_t>(header + 16);
		if (!valid)
		{
			if (header != nullptr)
				UnmapViewOfFile(header);
			CloseHandle(g_mapping);
			g_mapping = nullptr;
			return false;
		}
		g_slots = read<int32_t>(header + 12);
		g_stride = read<int64_t>(header + 16);
		g_maxW = read<int32_t>(header + 24);
		g_maxH = read<int32_t>(header + 28);
		UnmapViewOfFile(header);
		g_view = static_cast<const uint8_t *>(MapViewOfFile(g_mapping, FILE_MAP_READ, 0, 0, static_cast<SIZE_T>(kHeader + g_stride * g_slots)));
		if (g_view == nullptr)
		{
			CloseHandle(g_mapping);
			g_mapping = nullptr;
			return false;
		}
		g_control = static_cast<volatile LONG *>(MapViewOfFile(g_mapping, FILE_MAP_WRITE, 0, 0, kHeader)); // null if read-only
		reshade::log::message(reshade::log::level::info, "MCPassthrough: connected to Minecraft's frame export");
		return true;
	}

	void destroy_layer(device *dev, Layer &layer)
	{
		if (layer.srv.handle != 0)
			dev->destroy_resource_view(layer.srv);
		if (layer.tex.handle != 0)
			dev->destroy_resource(layer.tex);
		layer = Layer();
	}

	void destroy_layers(device *dev)
	{
		for (uint32_t p = 0; p < kPlaneCount; ++p)
		{
			destroy_layer(dev, g_layers[p]);
			destroy_layer(dev, g_staging[p]);
		}
		for (Upload &u : g_uploads)
		{
			if (u.buffer.handle != 0)
				dev->destroy_resource(u.buffer);
			u = Upload();
		}
		g_width = g_height = g_rowPitch = 0;
		g_hasFrame = false;
	}

	bool create_layer(device *dev, Layer &layer, uint32_t w, uint32_t h, format fmt)
	{
		if (!dev->create_resource(resource_desc(w, h, 1, 1, fmt, 1, memory_heap::default_,
									  resource_usage::shader_resource | resource_usage::copy_dest | resource_usage::copy_source),
				nullptr, resource_usage::shader_resource, &layer.tex))
			return false;
		return dev->create_resource_view(layer.tex, resource_usage::shader_resource, resource_view_desc(fmt), &layer.srv);
	}

	void bind_layers(effect_runtime *runtime)
	{
		for (uint32_t p = 0; p < kPlaneCount; ++p)
			runtime->update_texture_bindings(kSemantics[p], g_layers[p].srv, g_layers[p].srv);
	}

	/// The layers for frames of this size: created (and bound) only when the size changes.
	bool allocate_layers(effect_runtime *runtime, uint32_t w, uint32_t h)
	{
		if (w == g_width && h == g_height && g_layers[0].tex.handle != 0)
			return true;
		device *dev = runtime->get_device();
		if (g_width != 0)
			runtime->get_command_queue()->wait_idle(); // only a resize waits: the effects may still read the old layers
		destroy_layers(dev);
		for (uint32_t p = 0; p < kPlaneCount; ++p)
			if (!create_layer(dev, g_layers[p], w, h, kFormats[p]) || (!g_d3d12 && !create_layer(dev, g_staging[p], w, h, kFormats[p])))
			{
				destroy_layers(dev);
				return false;
			}
		g_width = w;
		g_height = h;
		g_rowPitch = (w * 4 + 255u) & ~255u;
		bind_layers(runtime);
		return true;
	}

	/// What a slot's descriptor says about its frame, read under the slot's seqlock.
	struct Frame
	{
		int64_t seq = 0, publishNanos = 0;
		uint64_t mcFrame = 0;
		uint32_t w = 0, h = 0, gpuGeneration = 0, gpuSet = 0;
		int32_t flags = 0;
		bool gpu = false, avatar = false, firstPerson = false;
		float nearPlane = 0, farPlane = 0;
		Pose pose;
		mcpt::blocks::Scene scene;
	};

	bool read_frame(int32_t slot, Frame &f)
	{
		const uint8_t *desc = g_view + kSlotDesc + kSlotDescBytes * slot;
		f.seq = read<int64_t>(desc);
		if (f.seq & 1)
			return false;
		f.mcFrame = uint64_t(read<int64_t>(desc + 8));
		f.w = read<uint32_t>(desc + 24);
		f.h = read<uint32_t>(desc + 28);
		f.nearPlane = read<float>(desc + 32);
		f.farPlane = read<float>(desc + 36);
		f.pose.fov = read<float>(desc + 40);
		f.flags = read<int32_t>(desc + 44);
		f.pose.x = read<double>(desc + 48);
		f.pose.y = read<double>(desc + 56);
		f.pose.z = read<double>(desc + 64);
		f.pose.yaw = read<float>(desc + 72);
		f.pose.pitch = read<float>(desc + 76);
		f.pose.roll = read<float>(desc + 80);
		f.firstPerson = read<int32_t>(desc + 84) != 0;
		f.publishNanos = read<int64_t>(desc + 96);
		// where Steve stood in this frame (an older mod leaves these 0: no Steve correction then)
		f.pose.px = read<double>(desc + 104);
		f.pose.py = read<double>(desc + 112);
		f.pose.pz = read<double>(desc + 120);
		f.pose.hasPlayer = f.pose.px != 0.0 || f.pose.py != 0.0 || f.pose.pz != 0.0;
		f.pose.valid = true;
		f.gpu = (f.flags & int32_t(gpu::kFlag)) != 0;
		f.avatar = (f.flags & kFlagAvatar) != 0;
		const uint8_t *gpuDesc = g_view + kGpuDesc + 32 * slot;
		f.gpuGeneration = read<uint32_t>(gpuDesc);
		f.gpuSet = read<uint32_t>(gpuDesc + 4);
		const uint8_t *scene = g_view + kSceneDesc + 64 * slot;
		f.scene = {};
		if (read<uint32_t>(scene) == kSceneMagic && read<uint32_t>(scene + 4) == 1)
		{
			f.scene.mesh_revision = read<uint64_t>(scene + 8);
			f.scene.atlas_revision = read<uint64_t>(scene + 16);
			f.scene.mesh_session = read<uint64_t>(scene + 24);
			f.scene.producer = read<uint32_t>(g_view + 44);
			if (!f.scene.mesh_revision || !f.scene.atlas_revision || !f.scene.mesh_session || !f.scene.producer)
				f.scene = {};
		}
		return read<int64_t>(desc) == f.seq; // every field above belongs to one publication
	}

	/// A slot frame (no GPU sharing) into the layers: written somewhere private first, checked whole, then copied on the GPU.
	bool upload_slot_frame(effect_runtime *runtime, command_list *cmd, int32_t slot, const Frame &f, uint32_t planes)
	{
		device *dev = runtime->get_device();
		command_queue *queue = runtime->get_command_queue();
		const uint8_t *base = g_view + kHeader + g_stride * slot;
		const size_t layer = size_t(f.w) * f.h * 4;
		const uint8_t *desc = g_view + kSlotDesc + kSlotDescBytes * slot;
		if (!g_d3d12)
		{
			// D3D11 (Legacy): UpdateSubresource into the staging layers (the driver takes the bytes during the call)
			for (uint32_t p = 0; p < planes; ++p)
			{
				subresource_data data;
				data.data = const_cast<uint8_t *>(base + p * layer);
				data.row_pitch = f.w * 4;
				data.slice_pitch = static_cast<uint32_t>(layer);
				dev->update_texture_region(data, g_staging[p].tex, 0);
			}
			if (read<int64_t>(desc) != f.seq)
				return false; // Minecraft rewrote the slot meanwhile: the layers keep the last whole frame
			for (uint32_t p = 0; p < planes; ++p)
			{
				cmd->barrier(g_layers[p].tex, resource_usage::shader_resource, resource_usage::copy_dest);
				cmd->copy_texture_region(g_staging[p].tex, 0, nullptr, g_layers[p].tex, 0, nullptr);
				cmd->barrier(g_layers[p].tex, resource_usage::copy_dest, resource_usage::shader_resource);
			}
			queue->flush_immediate_command_list();
			return true;
		}
		// D3D12: one of three upload buffers whose last copy completed (none free: drop this frame, never wait)
		const uint64_t completed = dev->get_completed_fence_value(g_copyFence);
		Upload *free = nullptr;
		for (Upload &u : g_uploads)
			if (u.completion <= completed)
			{
				free = &u;
				break;
			}
		if (free == nullptr)
			return false;
		const uint64_t planeBytes = uint64_t(g_rowPitch) * f.h;
		if (free->buffer.handle == 0 &&
			!dev->create_resource(resource_desc(planeBytes * kPlaneCount, memory_heap::upload, resource_usage::copy_source), nullptr, resource_usage::cpu_access,
				&free->buffer))
			return false;
		void *mapped = nullptr;
		if (!dev->map_buffer_region(free->buffer, 0, planeBytes * planes, map_access::write_only, &mapped))
			return false;
		for (uint32_t p = 0; p < planes; ++p)
			for (uint32_t y = 0; y < f.h; ++y)
				std::memcpy(static_cast<uint8_t *>(mapped) + p * planeBytes + uint64_t(y) * g_rowPitch, base + p * layer + size_t(y) * f.w * 4, size_t(f.w) * 4);
		dev->unmap_buffer_region(free->buffer);
		if (read<int64_t>(desc) != f.seq)
			return false; // torn: nothing of it is submitted
		for (uint32_t p = 0; p < planes; ++p)
		{
			cmd->barrier(g_layers[p].tex, resource_usage::shader_resource, resource_usage::copy_dest);
			cmd->copy_buffer_to_texture(free->buffer, p * planeBytes, g_rowPitch / 4, f.h, g_layers[p].tex, 0);
			cmd->barrier(g_layers[p].tex, resource_usage::copy_dest, resource_usage::shader_resource);
		}
		queue->flush_immediate_command_list();
		if (!queue->signal(g_copyFence, ++g_copyValue))
			return false;
		free->completion = g_copyValue;
		return true;
	}

	/// Copies the newest published Minecraft frame into the layers, if there is one not shown yet.
	void upload(effect_runtime *runtime)
	{
		const int64_t published = read<int64_t>(g_view + 32);
		if (published == g_lastPublish)
			return;
		const int32_t slot = read<int32_t>(g_view + 40);
		if (slot < 0 || slot >= g_slots)
			return;
		// claim the slot first (Minecraft never writes into the one named at +48), then read it coherently
		if (g_control != nullptr)
			InterlockedExchange(g_control + 12, slot);
		struct Release
		{
			~Release()
			{
				if (g_control != nullptr)
					InterlockedExchange(g_control + 12, -1);
			}
		} release;
		Frame f;
		if (!read_frame(slot, f))
		{
			++g_statTorn;
			return;
		}
		if (f.w == 0 || f.h == 0)
			return;
		if (f.gpu ? (f.w > kMaxGpuW || f.h > kMaxGpuH)
				  : (int64_t(f.w) > g_maxW || int64_t(f.h) > g_maxH || int64_t(f.w) * f.h * 4 * (f.avatar ? 5 : 3) > g_stride))
			return; // not a frame this mapping (or the shared textures) can hold: never read past it
		if (!(f.pose.fov > 1.0f && f.pose.fov < 179.0f) || !(f.nearPlane > 0.0f) || !(f.farPlane > f.nearPlane) || !std::isfinite(f.pose.x) ||
			!std::isfinite(f.pose.y) || !std::isfinite(f.pose.z))
			return; // a pose that can't be re-projected: keep the last good frame
		device *dev = runtime->get_device();
		if (!allocate_layers(runtime, f.w, f.h))
			return;
		if (g_copyFence.handle == 0 && !dev->create_fence(0, fence_flags::none, &g_copyFence))
			return;
		command_queue *queue = runtime->get_command_queue();
		command_list *cmd = queue->get_immediate_command_list();
		if (cmd == nullptr)
			return;
		const uint32_t planes = f.avatar ? 5 : 3;
		if (f.gpu)
		{
			// a GPU frame: Minecraft's planes are in the shared set named here; copy them texture to texture once
			// Minecraft's GPU finished them. The set is Minecraft's to reuse only once this copy completed.
			if (!g_gpu.ready(f.gpuGeneration, f.gpuSet, f.w, f.h, f.mcFrame))
				return; // not finished on Minecraft's side yet: next present
			for (uint32_t plane = 0; plane < planes; ++plane)
			{
				// the shared source is a simultaneous-access texture in COMMON (promoted implicitly): only ours needs barriers
				cmd->barrier(g_layers[plane].tex, resource_usage::shader_resource, resource_usage::copy_dest);
				cmd->copy_texture_region(g_gpu.plane(f.gpuSet, plane), 0, nullptr, g_layers[plane].tex, 0, nullptr);
				cmd->barrier(g_layers[plane].tex, resource_usage::copy_dest, resource_usage::shader_resource);
			}
			queue->flush_immediate_command_list();
			if (!queue->signal(g_copyFence, ++g_copyValue))
				return;
			g_gpu.noteCopy(f.gpuSet, f.mcFrame, g_copyValue); // the set goes back to Minecraft once this copy completed
			++g_statGpuFrames;
		}
		else if (!upload_slot_frame(runtime, cmd, slot, f, planes))
		{
			++g_statTorn; // torn or no free upload buffer: the screen keeps the last whole frame, retried next present
			return;
		}
		g_lastPublish = published;
		g_publishNanos = f.publishNanos;
		++g_framesThisSecond;
		g_mcNear = f.nearPlane;
		g_mcFar = f.farPlane;
		g_mcFlags = f.flags;
		g_mcAvatar = f.avatar;
		g_mcPose = f.pose;
		g_mcFirstPerson = f.firstPerson;
		g_scene = f.scene;
		g_hasFrame = true;
	}

	/// Camera-to-world rotation, as Minecraft builds it: rotationYXZ(pi - yaw, -pitch, roll) (camera looks down -z).
	void camera_rotation(const Pose &p, float m[3][3])
	{
		const float d2r = 3.14159265f / 180.0f;
		const float a = 3.14159265f - p.yaw * d2r, b = -p.pitch * d2r, c = p.roll * d2r;
		const float ca = std::cos(a), sa = std::sin(a), cb = std::cos(b), sb = std::sin(b), cc = std::cos(c), sc = std::sin(c);
		// Ry(a) * Rx(b) * Rz(c)
		const float ry[3][3] = {{ca, 0, sa}, {0, 1, 0}, {-sa, 0, ca}};
		const float rx[3][3] = {{1, 0, 0}, {0, cb, -sb}, {0, sb, cb}};
		const float rz[3][3] = {{cc, -sc, 0}, {sc, cc, 0}, {0, 0, 1}};
		float t[3][3] = {};
		for (int i = 0; i < 3; ++i)
			for (int j = 0; j < 3; ++j)
				for (int k = 0; k < 3; ++k)
					t[i][j] += ry[i][k] * rx[k][j];
		for (int i = 0; i < 3; ++i)
			for (int j = 0; j < 3; ++j)
			{
				m[i][j] = 0;
				for (int k = 0; k < 3; ++k)
					m[i][j] += t[i][k] * rz[k][j];
			}
	}

	/// Rows of R_mc^T * R_host: turns a ray in GTA's camera space into Minecraft's camera space.
	void warp_matrix(const Pose &host, const Pose &mc, float out[3][3])
	{
		float rh[3][3], rm[3][3];
		camera_rotation(host, rh);
		camera_rotation(mc, rm);
		for (int i = 0; i < 3; ++i)
			for (int j = 0; j < 3; ++j)
			{
				out[i][j] = 0;
				for (int k = 0; k < 3; ++k)
					out[i][j] += rm[k][i] * rh[k][j];
			}
	}

	/// The camera of the GTA frame being presented, for the native block pass (Minecraft coordinates).
	mcpt::blocks::HostCamera block_camera(const Pose &p, uint32_t w, uint32_t h)
	{
		float m[3][3];
		camera_rotation(p, m);
		mcpt::blocks::HostCamera c;
		c.position = {p.x, p.y, p.z};
		c.right = {m[0][0], m[1][0], m[2][0]};
		c.up = {m[0][1], m[1][1], m[2][1]};
		c.forward = {-m[0][2], -m[1][2], -m[2][2]};
		c.fov = p.fov * 3.14159265f / 180.0f;
		c.aspect = h ? float(w) / float(h) : 0.0f;
		c.near_plane = g_hostNear.load();
		c.far_plane = g_hostFar.load();
		return c;
	}

	/// GTA's depth buffer as ReShade's generic depth add-on selected it (the effect's McHostDepthTex), checked for use.
	resource_view host_depth_view(effect_runtime *runtime, float aspect, const char *&reason)
	{
		reason = "the effect has no McHostDepthTex";
		const effect_texture_variable variable = runtime->find_texture_variable(kEffect, "McHostDepthTex");
		if (variable.handle == 0)
			return {0};
		resource_view depth = {0}, depthSrgb = {0};
		runtime->get_texture_binding(variable, &depth, &depthSrgb);
		reason = "no GTA depth buffer bound yet";
		if (depth.handle == 0)
			return {0};
		device *dev = runtime->get_device();
		const resource res = dev->get_resource_from_view(depth);
		if (res.handle == 0)
			return {0};
		const resource_desc desc = dev->get_resource_desc(res);
		const resource_view_desc view = dev->get_resource_view_desc(depth);
		reason = "GTA depth buffer size/format rejected";
		if (desc.type != resource_type::texture_2d || desc.texture.samples != 1 || desc.texture.width < 64 || desc.texture.height < 64)
			return {0};
		if (view.format != format::r32_float && view.format != format::r24_unorm_x8_uint && view.format != format::r32_float_x8_uint &&
			view.format != format::r16_unorm)
			return {0};
		const double depthAspect = double(desc.texture.width) / desc.texture.height;
		reason = "GTA depth buffer aspect differs from the picture's";
		if (std::abs(depthAspect - aspect) > .03 * aspect)
			return {0};
		reason = nullptr;
		return depth;
	}

	/// The native block pass: prepare the newest mesh, draw the revision the shown frame left out with GTA's presented
	/// camera, and acknowledge what's held so Minecraft can leave its chunks out. Every frame (also while off, to revoke).
	void update_blocks(effect_runtime *runtime, command_list *cmd, bool on, const Pose &host)
	{
		using namespace mcpt::blocks;
		const uint32_t self = GetCurrentProcessId();
		uint64_t now = GetTickCount64();
		// poll even while off, so a dead producer's mapping is released and can't block the next one
		g_blockReader.poll(now, self);
		const bool animPolled = g_blockAnim.poll(anim_name, anim_capacity, anim_magic, now, self);
		const bool lightPolled = g_blockLight.poll(light_name, light_capacity, light_magic, now, self);
		now = GetTickCount64();
		bool ready = false, acked = false;
		const char *reason = "composition off";
		do
		{
			if (!on || !host.valid)
				break;
			reason = "no fresh mesh from Minecraft";
			if (!g_blockReader.retained(now, self))
				break;
			reason = "the effect lacks the native block textures";
			if (runtime->find_texture_variable(kEffect, "McBlockColorTex").handle == 0 || runtime->find_uniform_variable(kEffect, "BlocksReady").handle == 0)
				break;
			uint32_t w = 0, h = 0;
			runtime->get_screenshot_width_and_height(&w, &h);
			const HostCamera camera = block_camera(host, w, h);
			const resource_view depth = host_depth_view(runtime, camera.aspect, reason);
			if (depth.handle == 0)
				break;
			HostDepth depthParams;
			bool reversed = true;
			if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "HostReversedZ"); v.handle != 0)
				runtime->get_uniform_value_bool(v, &reversed, 1);
			depthParams.mode = reversed ? 2 : 1;
			if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "HostDepthScale"); v.handle != 0)
				runtime->get_uniform_value_float(v, &depthParams.scale, 1);
			if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "DepthBias"); v.handle != 0)
				runtime->get_uniform_value_float(v, &depthParams.bias, 1);
			const Header &mesh = g_blockReader.mesh.header;
			if ((lightPolled || g_blockLight.retained(now, self)) && mesh.same_context(g_blockLight.header) &&
				mesh.atlas_revision == g_blockLight.header.atlas_revision)
				g_blocks.lighting(runtime, g_blockLight.header, g_blockLight.payload);
			const bool prepared = g_blocks.prepare(runtime, mesh, g_blockReader.mesh.payload, g_blockReader.atlas.header, g_blockReader.atlas.payload, g_scene, camera);
			const char *preparation = prepared ? nullptr : g_blocks.failure_reason();
			const Header *animation = (animPolled || g_blockAnim.retained(now, self)) ? &g_blockAnim.header : nullptr;
			// a busy candidate upload must not hide the exact older revision the shown frame names
			if (mesh.session == g_scene.mesh_session && mesh.pid == g_scene.producer)
				ready = g_blocks.render(runtime, cmd, camera, g_scene, mesh, w, h, depth, depthParams, nullptr, animation, g_blockAnim.payload);
			reason = ready ? "drawn" : g_scene.mesh_revision ? (g_blocks.failure_reason() ? g_blocks.failure_reason() : "waiting for the mesh")
								  : "waiting for Minecraft to leave its chunks out";
			if (!ready && preparation)
				reason = preparation;
			// acknowledge only an intact, prepared pair in this producer's context; never an unprepared candidate
			const Header *held = g_blocks.latest_header();
			const bool awaiting = held && (!g_scene.mesh_revision || g_scene.mesh_revision != held->revision || g_scene.atlas_revision != held->atlas_revision ||
											  g_scene.mesh_session != held->session);
			if (held && held->same_context(mesh) && g_blocks.color_view().handle && g_blocks.depth_view().handle && (ready || awaiting))
			{
				g_blockAck.publish(*held, now);
				acked = true;
			}
		} while (false);
		if (!acked)
			g_blockAck.clear();
		if (g_blocks.color_view().handle != 0 && g_blocks.targets_generation() != g_blockBound)
		{
			// only when the targets were (re)created: binding waits for the GPU
			runtime->update_texture_bindings("MCBLOCKCOLOR", g_blocks.color_view(), g_blocks.color_view());
			runtime->update_texture_bindings("MCBLOCKDEPTH", g_blocks.depth_view(), g_blocks.depth_view());
			g_blockBound = g_blocks.targets_generation();
		}
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "BlocksReady"); v.handle != 0)
			runtime->set_uniform_value_bool(v, ready);
		g_statBlocks = ready;
		g_statBlockVertices = ready ? g_blockReader.mesh.header.count : 0;
		if (ready != g_blocksReported)
		{
			g_blocksReported = ready;
			char message[256];
			std::snprintf(message, sizeof(message), "MCPassthrough native blocks: %s (%s)", ready ? "on" : "off", reason);
			reshade::log::message(reshade::log::level::info, message);
		}
		if (on && now - g_blocksDiagnostic >= 5000)
		{
			g_blocksDiagnostic = now;
			char message[384];
			std::snprintf(message, sizeof(message), "MCPassthrough native blocks: %s; mesh %llu (%u vertices), shown frame names %llu, atlas %llu", reason,
				static_cast<unsigned long long>(g_blockReader.mesh.header.revision), g_blockReader.mesh.header.count,
				static_cast<unsigned long long>(g_scene.mesh_revision), static_cast<unsigned long long>(g_blockReader.atlas.header.revision));
			reshade::log::message(reshade::log::level::info, message);
		}
	}

	/// Reload MCPassthrough.fx when the file changes (ReShade doesn't watch it), so tweaks don't need a GTA restart.
	void watch_effect_file(effect_runtime *runtime)
	{
		static DWORD next = 0;
		static FILETIME last = {};
		static wchar_t path[MAX_PATH] = {};
		if (GetTickCount() < next)
			return;
		next = GetTickCount() + 1000;
		if (path[0] == 0)
		{
			GetModuleFileNameW(nullptr, path, MAX_PATH);
			wchar_t *slash = wcsrchr(path, L'\\');
			if (slash)
				wcscpy_s(slash + 1, MAX_PATH - (slash + 1 - path), L"reshade-shaders\\Shaders\\MCPassthrough.fx");
		}
		WIN32_FILE_ATTRIBUTE_DATA info;
		if (!GetFileAttributesExW(path, GetFileExInfoStandard, &info))
			return;
		if (last.dwLowDateTime != 0 && CompareFileTime(&info.ftLastWriteTime, &last) != 0)
			runtime->reload_effect_next_frame(kEffect);
		last = info.ftLastWriteTime;
	}

	void on_present(effect_runtime *runtime)
	{
		watch_effect_file(runtime); // every frame, even when the effect failed to compile
	}

	void on_begin_effects(effect_runtime *runtime, command_list *cmd, resource_view, resource_view)
	{
		g_d3d12 = runtime->get_device()->get_api() == device_api::d3d12;
		uint32_t bw = 0, bh = 0;
		runtime->get_screenshot_width_and_height(&bw, &bh);
		g_bbWidth = bw;
		g_bbHeight = bh;
		// GPU frames: answer Minecraft's texture request (D3D12 only), and hand back every set whose copy has completed
		g_gpu.poll(runtime, GetTickCount64());
		if (g_copyFence.handle != 0)
			g_gpu.retire(runtime->get_device()->get_completed_fence_value(g_copyFence));
		g_gpuActive = g_gpu.active();
		bool on = g_active && open_mapping();
		if (on)
			upload(runtime);
		const int64_t now = now_nanos();
		const bool stale = g_hasFrame && now - g_publishNanos > kStaleNanos;
		const bool scriptPaused = now - g_lastPoseNanos.load() > kScriptGoneNanos;
		g_statScriptPaused = scriptPaused;
		on = on && g_hasFrame && !stale && !scriptPaused;
		g_statStale = stale;
		g_statAgeMs = g_hasFrame ? int((now - g_publishNanos) / 1000000) : -1;
		if (now - g_secondFrom >= 1000000000)
		{
			g_statFps = g_secondFrom == 0 ? 0 : int(g_framesThisSecond * 1e9 / double(now - g_secondFrom) + 0.5);
			g_framesThisSecond = 0;
			g_secondFrom = now;
		}
		// The technique stays enabled (preset); McActive gates it, so GTA passes through untouched until a
		// Minecraft frame is here. (Toggling techniques from inside this callback crashes ReShade.)
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "McActive"); v.handle != 0)
			runtime->set_uniform_value_bool(v, on);

		// the camera of the GTA frame being presented: the pose lag back (Enhanced presents a frame later), extrapolated
		// by the effect's PosePrediction frames
		int uiLag = 0;
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "PoseLag"); v.handle != 0)
			runtime->get_uniform_value_int(v, &uiLag, 1);
		Pose host, prev;
		{
			std::lock_guard<std::mutex> lock(g_poseLock);
			const int want = g_poseLag.load() >= 0 ? g_poseLag.load() : uiLag;
			const unsigned lag = unsigned(std::clamp(want, 0, int(kPoses) - 2));
			if (g_hostPoseCount > lag)
				host = g_hostPoses[(g_hostPoseCount - 1 - lag) % kPoses];
			if (g_hostPoseCount > lag + 1)
				prev = g_hostPoses[(g_hostPoseCount - 2 - lag) % kPoses];
		}
		float predict = 0.0f;
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "PosePrediction"); v.handle != 0)
			runtime->get_uniform_value_float(v, &predict, 1);
		if (host.valid && prev.valid && predict != 0.0f)
		{
			auto delta = [](float a, float b) { float d = std::fmod(a - b + 540.0f, 360.0f) - 180.0f; return d; };
			host.yaw += delta(host.yaw, prev.yaw) * predict;
			host.pitch += (host.pitch - prev.pitch) * predict;
			host.roll += (host.roll - prev.roll) * predict;
			host.x += (host.x - prev.x) * predict;
			host.y += (host.y - prev.y) * predict;
			host.z += (host.z - prev.z) * predict;
		}
		// the flight chase cam composites Minecraft's frame as rendered: its blocks with Minecraft's camera then
		const Pose blockPose = g_cameraLocked ? g_mcPose : host;
		if (cmd == runtime->get_command_queue()->get_immediate_command_list())
			update_blocks(runtime, cmd, on, blockPose);
		if (!on)
			return;
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "McPlanes"); v.handle != 0)
			runtime->set_uniform_value_float(v, g_mcNear, g_mcFar, float(g_mcFlags));
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "AvatarReady"); v.handle != 0)
			runtime->set_uniform_value_bool(v, g_mcAvatar && !g_mcFirstPerson);
		// a scene's look overrides the preset's light matching and depth bias; the preset's values come back after
		auto look = [&](const char *name, float want, float &saved) {
			const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, name);
			if (v.handle == 0)
				return;
			if (want >= 0.0f)
			{
				if (saved < 0.0f)
					runtime->get_uniform_value_float(v, &saved, 1);
				runtime->set_uniform_value_float(v, want);
			}
			else if (saved >= 0.0f)
			{
				runtime->set_uniform_value_float(v, saved);
				saved = -1.0f;
			}
		};
		look("LightMatch", g_lookLight.load(), g_savedLight);
		look("DepthBias", g_lookBias.load(), g_savedBias);
		look("SlopeBias", g_lookSlope.load(), g_savedSlope);
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "Shake"); v.handle != 0)
			runtime->set_uniform_value_float(v, g_shakeX.load(), g_shakeY.load(), g_shakeRoll.load());
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "PortalWarp"); v.handle != 0)
			runtime->set_uniform_value_float(v, g_portalWarp.load());
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "HostPlanes"); v.handle != 0)
			runtime->set_uniform_value_float(v, g_hostNear.load(), g_hostFar.load());
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "McCursor"); v.handle != 0)
			runtime->set_uniform_value_float(v, g_cursorX.load(), g_cursorY.load(), g_cursorOn.load() ? 1.0f : 0.0f);

		// Re-projection from Minecraft's pose to GTA's presented one.
		const bool warp = host.valid && g_mcPose.valid && !g_cameraLocked;
		float m[3][3] = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
		float t[3] = {0, 0, 0};
		float steveDelta[3] = {0, 0, 0}, steveAt[4] = {0, 0, 0, 0}, steveUp[3] = {0, 1, 0};
		if (warp)
		{
			warp_matrix(host, g_mcPose, m);
			// T = R_mc^T (host position - Minecraft's camera position), in Minecraft's camera space
			float rm[3][3];
			camera_rotation(g_mcPose, rm);
			auto to_mc = [&rm](const double w[3], float out[3]) {
				for (int i = 0; i < 3; ++i)
					out[i] = float(rm[0][i] * w[0] + rm[1][i] * w[1] + rm[2][i] * w[2]);
			};
			const double d[3] = {host.x - g_mcPose.x, host.y - g_mcPose.y, host.z - g_mcPose.z};
			to_mc(d, t);
			// Steve moved with GTA's player since Minecraft drew him: his pixels move by that (in Minecraft's camera
			// space), not with the camera's re-projection. Third person only, and only while he's part of the world
			// layer (the avatar layer has its own); a jump of metres is a teleport, not motion.
			if (host.hasPlayer && g_mcPose.hasPlayer && !g_mcFirstPerson && !g_mcAvatar)
			{
				const double moved[3] = {host.px - g_mcPose.px, host.py - g_mcPose.py, host.pz - g_mcPose.pz};
				if (moved[0] * moved[0] + moved[1] * moved[1] + moved[2] * moved[2] < 6.0 * 6.0)
				{
					to_mc(moved, steveDelta);
					// his middle as drawn, and the world's up, in Minecraft's camera space
					const double middle[3] = {g_mcPose.px - g_mcPose.x, g_mcPose.py + 0.95 - g_mcPose.y, g_mcPose.pz - g_mcPose.z};
					to_mc(middle, steveAt);
					steveAt[3] = 1.0f;
					const double up[3] = {0.0, 1.0, 0.0};
					to_mc(up, steveUp);
				}
			}
		}
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "SteveDelta"); v.handle != 0)
			runtime->set_uniform_value_float(v, steveDelta[0], steveDelta[1], steveDelta[2]);
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "SteveAt"); v.handle != 0)
			runtime->set_uniform_value_float(v, steveAt[0], steveAt[1], steveAt[2], steveAt[3]);
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "SteveUp"); v.handle != 0)
			runtime->set_uniform_value_float(v, steveUp[0], steveUp[1], steveUp[2]);
		const char *rows[3] = {"WarpRow0", "WarpRow1", "WarpRow2"};
		for (int i = 0; i < 3; ++i)
			if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, rows[i]); v.handle != 0)
				runtime->set_uniform_value_float(v, m[i][0], m[i][1], m[i][2]);
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "WarpT"); v.handle != 0)
			runtime->set_uniform_value_float(v, t[0], t[1], t[2]);
		const float d2r = 3.14159265f / 180.0f;
		// GTA's view always from GTA's own fov: Minecraft's frame can be wider (overscan), even when it isn't re-projected
		const float tanHost = std::tan((host.valid ? host.fov : g_mcPose.fov) * d2r * 0.5f), tanMc = std::tan(g_mcPose.fov * d2r * 0.5f);
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, "WarpTan"); v.handle != 0)
			runtime->set_uniform_value_float(v, tanHost, tanMc, float(g_width) / float(g_height));
	}

	void on_reloaded_effects(effect_runtime *runtime)
	{
		if (g_width != 0)
			bind_layers(runtime);
		g_blockBound = ~0ull; // rebound by the next block pass
	}

	void on_destroy_effect_runtime(effect_runtime *runtime)
	{
		g_blockAck.clear();
		g_blocks.destroy(runtime);
		g_blockBound = ~0ull;
		g_gpu.destroy(runtime);
		runtime->get_command_queue()->wait_idle();
		if (g_copyFence.handle != 0)
		{
			runtime->get_device()->destroy_fence(g_copyFence);
			g_copyFence = {0};
		}
		destroy_layers(runtime->get_device());
		g_lastPublish = -1;
	}
}

namespace compositor
{
	bool try_register(void *module)
	{
		if (g_registered)
			return true;
		if (!reshade::register_addon(module))
			return false;
		reshade::register_event<reshade::addon_event::reshade_begin_effects>(on_begin_effects);
		reshade::register_event<reshade::addon_event::reshade_present>(on_present);
		reshade::register_event<reshade::addon_event::reshade_reloaded_effects>(on_reloaded_effects);
		reshade::register_event<reshade::addon_event::destroy_effect_runtime>(on_destroy_effect_runtime);
		g_registered = true;
		reshade::log::message(reshade::log::level::info, "MCPassthrough: registered");
		return true;
	}

	void unregister(void *module)
	{
		if (g_registered.exchange(false))
			reshade::unregister_addon(module);
	}

	void set_active(bool active)
	{
		g_active = active;
	}

	void set_host_planes(float near_clip, float far_clip)
	{
		g_hostNear = near_clip;
		g_hostFar = far_clip;
	}

	void backbuffer_size(int &width, int &height)
	{
		width = int(g_bbWidth.load());
		height = int(g_bbHeight.load());
	}

	void set_camera_locked(bool locked)
	{
		g_cameraLocked = locked;
	}

	void set_screen_fx(float shake_x, float shake_y, float shake_roll, float portal_warp)
	{
		g_shakeX = shake_x;
		g_shakeY = shake_y;
		g_shakeRoll = shake_roll;
		g_portalWarp = portal_warp;
	}

	void set_look(float light_match, float depth_bias, float slope_bias)
	{
		g_lookLight = light_match;
		g_lookBias = depth_bias;
		g_lookSlope = slope_bias;
	}

	void set_host_pose(float yaw, float pitch, float roll, float fov, double x, double y, double z)
	{
		std::lock_guard<std::mutex> lock(g_poseLock);
		g_hostPoses[g_hostPoseCount % kPoses] = {yaw, pitch, roll, fov, x, y, z, true};
		++g_hostPoseCount;
		g_lastPoseNanos = now_nanos();
	}

	void set_host_player(double x, double y, double z)
	{
		std::lock_guard<std::mutex> lock(g_poseLock);
		if (g_hostPoseCount == 0)
			return;
		Pose &p = g_hostPoses[(g_hostPoseCount - 1) % kPoses];
		p.px = x;
		p.py = y;
		p.pz = z;
		p.hasPlayer = true;
	}

	Stats stats()
	{
		Stats s;
		s.mcFps = g_statFps;
		s.frameAgeMs = g_statAgeMs;
		s.torn = g_statTorn;
		s.stale = g_statStale;
		s.width = int(g_width);
		s.height = int(g_height);
		s.poseLag = g_poseLag;
		s.gpuFrames = g_gpuActive.load();
		s.avatarLayer = g_mcAvatar;
		s.nativeBlocks = g_statBlocks.load();
		s.blockVertices = int(g_statBlockVertices.load());
		return s;
	}

	bool gpu_frames()
	{
		return g_gpuActive.load();
	}

	void set_cursor(float x, float y, bool visible)
	{
		g_cursorX = x;
		g_cursorY = y;
		g_cursorOn = visible;
	}

	void set_pose_lag(int frames)
	{
		g_poseLag = frames;
	}
}
