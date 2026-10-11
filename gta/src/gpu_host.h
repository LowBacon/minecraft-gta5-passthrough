// Zero-copy Minecraft frames (host side): the add-on shares three sets of five D3D12 textures (world colour,
// world depth, overlay, avatar colour, avatar depth) and a fence; Minecraft
// writes its planes into a free set on the GPU, signals the fence with its frame number and publishes the slot with
// flag 64. The add-on copies the set into its own textures once the fence reaches that frame, and acknowledges the set
// only when that copy has completed. No pixel crosses the CPU. Layout: mc/.../GpuTransport.java.
// Adapted from EldenCraft's ECGT v1 host (MIT, Copyright (c) 2026 Adam Grzegorzewski, github.com/SocketByte/EldenCraft).
#pragma once
#include <reshade.hpp>
#include <array>
#include <cstdint>
#include <vector>

struct ID3D12Device;
struct ID3D12Resource;
struct ID3D12Fence;

namespace gpu
{
	constexpr uint32_t kSets = 3, kPlanes = 5, kFlag = 64;
	/// Planes 1 and 4 are depth (R32F), the others colour (RGBA8).
	constexpr bool is_depth_plane(uint32_t plane) { return plane == 1 || plane == 4; }

	class Host
	{
		void *mapping_ = nullptr;
		uint8_t *view_ = nullptr;
		ID3D12Device *device_ = nullptr;
		std::array<std::array<ID3D12Resource *, kPlanes>, kSets> textures_{};
		std::array<std::array<void *, kPlanes>, kSets> handles_{};
		ID3D12Fence *ready_ = nullptr;
		void *readyHandle_ = nullptr;
		uint32_t generation_ = 0, width_ = 0, height_ = 0, guestPid_ = 0;
		uint64_t nextAttempt_ = 0;
		bool disabled_ = false;
		struct Pending
		{
			uint32_t set;
			uint64_t frame, fence;
		};
		std::vector<Pending> pending_; // host copies in flight: their sets stay unacknowledged until the copy completed
		void release(reshade::api::effect_runtime *runtime);
		bool create(reshade::api::effect_runtime *runtime, uint32_t width, uint32_t height, uint32_t guestPid);
		void ack(uint32_t set, uint64_t frame);

	public:
		Host() = default;
		Host(const Host &) = delete;
		Host &operator=(const Host &) = delete;
		/// Opens the control mapping, publishes GTA's back-buffer size and answers Minecraft's size request. D3D12 only.
		void poll(reshade::api::effect_runtime *runtime, uint64_t nowMs);
		/// The slot names this generation and set at this size, and Minecraft's GPU has finished writing that frame.
		bool ready(uint32_t generation, uint32_t set, uint32_t width, uint32_t height, uint64_t frame) const;
		reshade::api::resource plane(uint32_t set, uint32_t plane) const;
		/// A copy of `set` holding `frame` was submitted ahead of `fence` on the add-on's copy fence.
		void noteCopy(uint32_t set, uint64_t frame, uint64_t fence) { pending_.push_back({set, frame, fence}); }
		/// Acknowledge every set whose copy has completed on the GPU.
		void retire(uint64_t completedFence);
		void destroy(reshade::api::effect_runtime *runtime);
		bool active() const { return generation_ != 0; }
	};
}
