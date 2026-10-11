#include "gpu_host.h"
#include <windows.h>
#include <d3d12.h>
#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>

using namespace reshade::api;

namespace gpu
{
	namespace
	{
		constexpr wchar_t kMappingName[] = L"Local\\MCPassthroughGpuFrame";
		constexpr size_t kBytes = 4096;
		constexpr uint32_t kMagic = 0x5447434D, kRequestMagic = 0x5147434D, kVersion = 1;
		constexpr size_t kPlaneBytes = 32, kHostStatus = 76, kPreferred = 96, kRequest = 128, kAcks = 256;
		constexpr uint32_t kStatusFailed = 2, kMaxW = 3840, kMaxH = 2160;

		template <typename T>
		void put(uint8_t *view, size_t offset, T value)
		{
			std::memcpy(view + offset, &value, sizeof(T));
		}

		template <typename T>
		T get(const uint8_t *view, size_t offset)
		{
			T value;
			std::memcpy(&value, view + offset, sizeof(T));
			return value;
		}

		void log(reshade::log::level level, const char *text)
		{
			reshade::log::message(level, text);
		}

		void name(wchar_t *out, size_t n, uint32_t pid, uint32_t generation, const wchar_t *suffix)
		{
			std::swprintf(out, n, L"Local\\MCPassthroughGpu_%u_%u_%s", pid, generation, suffix);
		}
	}

	void Host::poll(effect_runtime *runtime, uint64_t nowMs)
	{
		if (disabled_ || runtime->get_device()->get_api() != device_api::d3d12)
			return;
		if (view_ == nullptr)
		{
			if (nowMs < nextAttempt_)
				return;
			nextAttempt_ = nowMs + 1000;
			// open-or-create: whichever process starts first owns the zeroed page
			mapping_ = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, DWORD(kBytes), kMappingName);
			if (mapping_ == nullptr)
				return;
			view_ = static_cast<uint8_t *>(MapViewOfFile(mapping_, FILE_MAP_ALL_ACCESS, 0, 0, kBytes));
			if (view_ == nullptr)
			{
				CloseHandle(mapping_);
				mapping_ = nullptr;
				return;
			}
		}
		// GTA's back buffer, so Minecraft can render at full resolution (no CPU copy means no size budget)
		uint32_t bw = 0, bh = 0;
		runtime->get_screenshot_width_and_height(&bw, &bh);
		if (bw > kMaxW || bh > kMaxH)
			bw = bh = 0;
		put<uint32_t>(view_, kPreferred, bw);
		put<uint32_t>(view_, kPreferred + 4, bh);
		// the request, validated on a copy
		uint8_t copy[kBytes];
		std::memcpy(copy, view_, kBytes);
		if (get<uint32_t>(copy, kRequest) != kRequestMagic)
			return;
		const uint32_t w = get<uint32_t>(copy, kRequest + 4), h = get<uint32_t>(copy, kRequest + 8), pid = get<uint32_t>(copy, kRequest + 12);
		const uint32_t status = get<uint32_t>(copy, kRequest + 16), statusGeneration = get<uint32_t>(copy, kRequest + 20);
		if (pid == 0 || w < 1 || h < 1 || w > kMaxW || h > kMaxH)
			return;
		if (generation_ != 0 && pid == guestPid_ && status == kStatusFailed && statusGeneration == generation_)
		{
			log(reshade::log::level::warning, "MCPassthrough GPU frames: Minecraft couldn't import the shared textures; shared-memory frames stay.");
			release(runtime);
			disabled_ = true;
			put<uint32_t>(view_, kHostStatus, kStatusFailed);
			return;
		}
		if (generation_ != 0 && pid == guestPid_ && w == width_ && h == height_)
			return;
		if (nowMs < nextAttempt_)
			return;
		nextAttempt_ = nowMs + 1000;
		create(runtime, w, h, pid);
	}

	bool Host::create(effect_runtime *runtime, uint32_t width, uint32_t height, uint32_t guestPid)
	{
		release(runtime);
		auto *device = reinterpret_cast<ID3D12Device *>(runtime->get_device()->get_native());
		if (device == nullptr)
			return false;
		device->AddRef();
		device_ = device;
		static uint32_t counter = 0;
		const uint32_t generation = (GetTickCount() ^ (++counter << 24)) | 1u;
		const uint32_t pid = GetCurrentProcessId();
		D3D12_HEAP_PROPERTIES heap = {};
		heap.Type = D3D12_HEAP_TYPE_DEFAULT;
		uint64_t planeBytes[kPlanes] = {};
		auto fail = [&](const char *what, HRESULT hr) {
			char message[192];
			std::snprintf(message, sizeof(message), "MCPassthrough GPU frames: %s failed (0x%08lx); shared-memory frames stay.", what, static_cast<unsigned long>(hr));
			log(reshade::log::level::warning, message);
			release(runtime);
			disabled_ = true;
			put<uint32_t>(view_, kHostStatus, kStatusFailed);
			return false;
		};
		for (uint32_t set = 0; set < kSets; ++set)
			for (uint32_t plane = 0; plane < kPlanes; ++plane)
			{
				D3D12_RESOURCE_DESC desc = {};
				desc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
				desc.Width = width;
				desc.Height = height;
				desc.DepthOrArraySize = 1;
				desc.MipLevels = 1;
				desc.Format = is_depth_plane(plane) ? DXGI_FORMAT_R32_FLOAT : DXGI_FORMAT_R8G8B8A8_UNORM;
				desc.SampleDesc.Count = 1;
				desc.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
				// simultaneous access: OpenGL writes and this queue copies without cross-API barriers, ordered by the fence
				desc.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET | D3D12_RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS;
				ID3D12Resource *texture = nullptr;
				HRESULT hr = device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_SHARED, &desc, D3D12_RESOURCE_STATE_COMMON, nullptr,
					__uuidof(ID3D12Resource), reinterpret_cast<void **>(&texture));
				HANDLE handle = nullptr;
				wchar_t textureName[96], suffix[16];
				std::swprintf(suffix, 16, L"%u_%u", set, plane);
				name(textureName, 96, pid, generation, suffix);
				if (SUCCEEDED(hr))
					hr = device->CreateSharedHandle(texture, nullptr, GENERIC_ALL, textureName, &handle);
				if (FAILED(hr))
				{
					if (texture != nullptr)
						texture->Release();
					return fail("shared texture creation", hr);
				}
				textures_[set][plane] = texture;
				handles_[set][plane] = handle;
				planeBytes[plane] = device->GetResourceAllocationInfo(0, 1, &desc).SizeInBytes;
			}
		HRESULT hr = device->CreateFence(0, D3D12_FENCE_FLAG_SHARED, __uuidof(ID3D12Fence), reinterpret_cast<void **>(&ready_));
		wchar_t fenceName[96];
		name(fenceName, 96, pid, generation, L"ready");
		if (SUCCEEDED(hr))
			hr = device->CreateSharedHandle(ready_, nullptr, GENERIC_ALL, fenceName, &readyHandle_);
		if (FAILED(hr))
			return fail("shared fence creation", hr);
		// the block, magic last: Minecraft never imports a half-written description
		put<uint32_t>(view_, 0, 0);
		std::atomic_thread_fence(std::memory_order_seq_cst);
		put<uint32_t>(view_, 4, kVersion);
		put<uint32_t>(view_, 8, pid);
		put<uint32_t>(view_, 12, generation);
		put<uint32_t>(view_, 16, width);
		put<uint32_t>(view_, 20, height);
		put<uint32_t>(view_, 24, kSets);
		put<uint32_t>(view_, 28, kPlanes);
		put<uint32_t>(view_, kHostStatus, 0);
		for (uint32_t plane = 0; plane < kPlanes; ++plane)
			put<uint64_t>(view_, kPlaneBytes + plane * 8, planeBytes[plane]);
		for (uint32_t set = 0; set < kSets; ++set)
			put<uint64_t>(view_, kAcks + set * 8, 0);
		std::atomic_thread_fence(std::memory_order_seq_cst);
		put<uint32_t>(view_, 0, kMagic);
		generation_ = generation;
		width_ = width;
		height_ = height;
		guestPid_ = guestPid;
		char message[160];
		std::snprintf(message, sizeof(message), "MCPassthrough GPU frames: %ux%u shared textures ready (generation %u); no CPU readback.", width, height, generation);
		log(reshade::log::level::info, message);
		return true;
	}

	bool Host::ready(uint32_t generation, uint32_t set, uint32_t width, uint32_t height, uint64_t frame) const
	{
		return generation_ != 0 && ready_ != nullptr && generation == generation_ && set < kSets && width == width_ && height == height_ &&
			   ready_->GetCompletedValue() >= frame;
	}

	resource Host::plane(uint32_t set, uint32_t plane) const
	{
		if (set >= kSets || plane >= kPlanes || textures_[set][plane] == nullptr)
			return {0};
		return {reinterpret_cast<uint64_t>(textures_[set][plane])};
	}

	void Host::ack(uint32_t set, uint64_t frame)
	{
		if (view_ == nullptr || set >= kSets)
			return;
		auto *slot = reinterpret_cast<volatile uint64_t *>(view_ + kAcks + set * 8);
		if (*slot < frame)
			*slot = frame;
		std::atomic_thread_fence(std::memory_order_seq_cst);
	}

	void Host::retire(uint64_t completedFence)
	{
		if (completedFence == UINT64_MAX)
			return; // D3D12's way of saying the device was removed
		pending_.erase(std::remove_if(pending_.begin(), pending_.end(), [&](const Pending &p) {
			if (p.fence > completedFence)
				return false;
			ack(p.set, p.frame);
			return true;
		}), pending_.end());
	}

	void Host::release(effect_runtime *runtime)
	{
		if (view_ != nullptr)
		{
			put<uint32_t>(view_, 0, 0);
			std::atomic_thread_fence(std::memory_order_seq_cst);
		}
		// only on a resize or shutdown: the GPU may still be reading the old sets
		if ((ready_ != nullptr || textures_[0][0] != nullptr) && runtime != nullptr)
			runtime->get_command_queue()->wait_idle();
		for (auto &set : textures_)
			for (auto &texture : set)
			{
				if (texture != nullptr)
					texture->Release();
				texture = nullptr;
			}
		for (auto &set : handles_)
			for (auto &handle : set)
			{
				if (handle != nullptr)
					CloseHandle(handle);
				handle = nullptr;
			}
		if (ready_ != nullptr)
			ready_->Release();
		if (readyHandle_ != nullptr)
			CloseHandle(readyHandle_);
		ready_ = nullptr;
		readyHandle_ = nullptr;
		if (device_ != nullptr)
			device_->Release();
		device_ = nullptr;
		generation_ = width_ = height_ = guestPid_ = 0;
		pending_.clear();
	}

	void Host::destroy(effect_runtime *runtime)
	{
		release(runtime);
		if (view_ != nullptr)
			UnmapViewOfFile(view_);
		if (mapping_ != nullptr)
			CloseHandle(mapping_);
		view_ = nullptr;
		mapping_ = nullptr;
	}
}
