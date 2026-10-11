// Draws Minecraft's native block mesh with the presented host camera (D3D12, and D3D11 for GTA V Legacy and the tests).
// Adapted from EldenCraft's block_renderer (MIT, Copyright (c) 2026 Adam Grzegorzewski, github.com/SocketByte/EldenCraft).
#pragma once
#include "block_protocol.hpp"
#include <reshade.hpp>
#include <memory>
#include <span>

namespace mcpt::blocks
{
	// All methods run on the runtime's render thread. render() needs the queue's immediate command list, outside any render
	// pass; it submits and fences its commands.
	class Renderer
	{
	public:
		explicit Renderer(bool details = false);
		~Renderer(); // destroy(runtime) first, before the device or runtime goes away
		Renderer(const Renderer &) = delete;
		Renderer &operator=(const Renderer &) = delete;
		// Uploads a mesh and its atlas (each revision once; never waits for the GPU).
		bool prepare(reshade::api::effect_runtime *, const Header &mesh, std::span<const std::uint8_t> vertices, const Header &atlas,
			std::span<const std::uint8_t> pixels, const Scene &shown, const HostCamera &camera);
		bool lighting(reshade::api::effect_runtime *, const Header &, std::span<const std::uint8_t>);
		// Draws the revision `shown` names. `animation`: an optional fresh MCPassthroughBlockAnim publication, copied into the
		// atlas first when it names that atlas revision.
		bool render(reshade::api::effect_runtime *, reshade::api::command_list *, const HostCamera &, const Scene &shown, const Header &producer,
			std::uint32_t width, std::uint32_t height, reshade::api::resource_view host_depth, const HostDepth &depth, Renderer *destination = nullptr,
			const Header *animation = nullptr, std::span<const std::uint8_t> animation_payload = {});
		void destroy(reshade::api::effect_runtime *);
		// Allocated in prepare(), so the acknowledgement can bootstrap. Show them only after render() returned true this frame.
		reshade::api::resource_view color_view() const;
		reshade::api::resource_view depth_view() const;
		// Advances whenever the views above are destroyed (a recreated view can reuse the same handle).
		std::uint64_t targets_generation() const;
		// The newest upload submitted to the queue render() uses.
		const Header *latest_header() const;
		// Why the last call failed (static text), or nullptr.
		const char *failure_reason() const;

	private:
		struct Impl;
		std::unique_ptr<Impl> impl_;
	};
}
