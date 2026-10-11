// GPU frames test, against the DEV Minecraft client only (mc\gradlew.bat runClient, with something playing the host on
// the link, e.g. host\pose_test.ps1): plays the add-on's side of the shared-texture transport (gpu_host.cpp) with plain
// D3D12. It shares three sets of five textures (or three: an older add-on) and a fence at the size Minecraft asks for,
// waits for Minecraft's GPU frames (slot flag 64) and their fence, copies each frame's colour and depth (and, with flag
// 32, the avatar colour) back to check them (saving the first as gpu_frame.bmp / gpu_avatar.bmp), and acknowledges each
// set as the add-on does. With five planes, third-person frames must carry the avatar layer; with three, none may.
//   gpu_frames_test.exe [seconds] [planes: 5 or 3]
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_4.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace
{
	constexpr uint32_t kMagic = 0x5447434D, kRequestMagic = 0x5147434D, kSets = 3, kMaxPlanes = 5;

	template <typename T>
	T get(const volatile uint8_t *p)
	{
		T v;
		std::memcpy(&v, const_cast<const uint8_t *>(p), sizeof(T));
		return v;
	}

	template <typename T>
	void put(volatile uint8_t *p, T v)
	{
		std::memcpy(const_cast<uint8_t *>(p), &v, sizeof(T));
	}

	bool check(HRESULT hr, const char *what)
	{
		if (FAILED(hr))
			std::printf("FAIL %s: 0x%08lx\n", what, static_cast<unsigned long>(hr));
		return SUCCEEDED(hr);
	}

	void save_bmp(const char *path, const uint8_t *rgba, uint32_t w, uint32_t h, uint32_t pitch)
	{
		FILE *f = std::fopen(path, "wb");
		if (!f)
			return;
		const uint32_t row = w * 3, padded = (row + 3) & ~3u, size = 54 + padded * h;
		uint8_t header[54] = {'B', 'M'};
		std::memcpy(header + 2, &size, 4);
		header[10] = 54;
		header[14] = 40;
		std::memcpy(header + 18, &w, 4);
		std::memcpy(header + 22, &h, 4);
		header[26] = 1;
		header[28] = 24;
		std::fwrite(header, 1, 54, f);
		std::vector<uint8_t> line(padded, 0);
		for (uint32_t y = 0; y < h; ++y) // Minecraft's rows are bottom-up, as BMP's are
		{
			const uint8_t *src = rgba + size_t(y) * pitch;
			for (uint32_t x = 0; x < w; ++x)
			{
				const uint8_t a = src[x * 4 + 3];
				line[x * 3 + 0] = a ? src[x * 4 + 2] : 60; // BGR; empty pixels dark blue-grey
				line[x * 3 + 1] = a ? src[x * 4 + 1] : 40;
				line[x * 3 + 2] = a ? src[x * 4 + 0] : 40;
			}
			std::fwrite(line.data(), 1, padded, f);
		}
		std::fclose(f);
	}
}

int main(int argc, char **argv)
{
	const int seconds = argc > 1 ? std::atoi(argv[1]) : 20;
	const uint32_t kPlanes = argc > 2 && std::atoi(argv[2]) == 3 ? 3 : 5;
	ID3D12Device *device = nullptr;
	if (!check(D3D12CreateDevice(nullptr, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&device)), "D3D12CreateDevice"))
		return 1;
	D3D12_COMMAND_QUEUE_DESC qd = {};
	qd.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
	ID3D12CommandQueue *queue = nullptr;
	ID3D12CommandAllocator *alloc = nullptr;
	ID3D12GraphicsCommandList *list = nullptr;
	ID3D12Fence *copyFence = nullptr;
	check(device->CreateCommandQueue(&qd, IID_PPV_ARGS(&queue)), "queue");
	check(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&alloc)), "allocator");
	check(device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, alloc, nullptr, IID_PPV_ARGS(&list)), "list");
	list->Close();
	check(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&copyFence)), "copy fence");
	HANDLE event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
	uint64_t copyValue = 0;

	HANDLE control = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, 4096, L"Local\\MCPassthroughGpuFrame");
	auto *ctl = static_cast<volatile uint8_t *>(MapViewOfFile(control, FILE_MAP_ALL_ACCESS, 0, 0, 4096));
	if (!ctl)
	{
		std::printf("FAIL control mapping\n");
		return 1;
	}
	put<uint32_t>(ctl + 96, 1920); // "GTA's back buffer"
	put<uint32_t>(ctl + 100, 1080);

	ID3D12Resource *textures[kSets][kMaxPlanes] = {};
	ID3D12Fence *ready = nullptr;
	uint32_t width = 0, height = 0, generation = 0;
	const uint32_t pid = GetCurrentProcessId();
	const volatile uint8_t *frames = nullptr;
	int64_t lastPublish = -1;
	int gpuFrames = 0, cpuFrames = 0, good = 0, empty = 0, avatarFrames = 0, avatarGood = 0;
	bool savedAvatar = false;
	double fenceWaitMs = 0.0;
	bool saved = false;
	const DWORD end = GetTickCount() + DWORD(seconds) * 1000;
	LARGE_INTEGER qpf;
	QueryPerformanceFrequency(&qpf);
	ID3D12Resource *readback[3] = {}; // world colour, world depth, avatar colour
	UINT64 readbackBytes[3] = {};
	D3D12_PLACED_SUBRESOURCE_FOOTPRINT footprint[2] = {};

	while (GetTickCount() < end)
	{
		// the request: (re)create shared textures of that size, block magic written last
		if (get<uint32_t>(ctl + 128) == kRequestMagic)
		{
			const uint32_t w = get<uint32_t>(ctl + 132), h = get<uint32_t>(ctl + 136);
			if (w && h && (w != width || h != height))
			{
				put<uint32_t>(ctl, 0);
				for (auto &set : textures)
					for (auto &t : set)
						if (t)
							t->Release(), t = nullptr;
				if (ready)
					ready->Release(), ready = nullptr;
				generation = (GetTickCount() << 1) | 1u;
				D3D12_HEAP_PROPERTIES heap = {D3D12_HEAP_TYPE_DEFAULT};
				for (uint32_t s = 0; s < kSets; ++s)
					for (uint32_t p = 0; p < kPlanes; ++p)
					{
						D3D12_RESOURCE_DESC d = {};
						d.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
						d.Width = w;
						d.Height = h;
						d.DepthOrArraySize = 1;
						d.MipLevels = 1;
						d.Format = p == 1 || p == 4 ? DXGI_FORMAT_R32_FLOAT : DXGI_FORMAT_R8G8B8A8_UNORM;
						d.SampleDesc.Count = 1;
						d.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET | D3D12_RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS;
						if (!check(device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_SHARED, &d, D3D12_RESOURCE_STATE_COMMON, nullptr,
								IID_PPV_ARGS(&textures[s][p])), "shared texture"))
							return 1;
						wchar_t name[96];
						std::swprintf(name, 96, L"Local\\MCPassthroughGpu_%u_%u_%u_%u", pid, generation, s, p);
						HANDLE handle = nullptr;
						if (!check(device->CreateSharedHandle(textures[s][p], nullptr, GENERIC_ALL, name, &handle), "shared handle"))
							return 1;
						put<uint64_t>(ctl + 32 + p * 8, device->GetResourceAllocationInfo(0, 1, &d).SizeInBytes);
						if (s == 0)
						{
							UINT64 total = 0;
							device->GetCopyableFootprints(&d, 0, 1, 0, &footprint[p == 1 || p == 4 ? 1 : 0], nullptr, nullptr, &total);
							if (p < 2)
								readbackBytes[p] = total;
							if (p == 0)
								readbackBytes[2] = total;
						}
					}
				check(device->CreateFence(0, D3D12_FENCE_FLAG_SHARED, IID_PPV_ARGS(&ready)), "shared fence");
				wchar_t fname[96];
				std::swprintf(fname, 96, L"Local\\MCPassthroughGpu_%u_%u_ready", pid, generation);
				HANDLE fh = nullptr;
				check(device->CreateSharedHandle(ready, nullptr, GENERIC_ALL, fname, &fh), "fence handle");
				for (int i = 0; i < 3; ++i)
				{
					if (readback[i])
						readback[i]->Release();
					D3D12_HEAP_PROPERTIES rh = {D3D12_HEAP_TYPE_READBACK};
					D3D12_RESOURCE_DESC bd = {};
					bd.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
					bd.Width = readbackBytes[i];
					bd.Height = 1;
					bd.DepthOrArraySize = 1;
					bd.MipLevels = 1;
					bd.SampleDesc.Count = 1;
					bd.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
					check(device->CreateCommittedResource(&rh, D3D12_HEAP_FLAG_NONE, &bd, D3D12_RESOURCE_STATE_COPY_DEST, nullptr, IID_PPV_ARGS(&readback[i])), "readback");
				}
				put<uint32_t>(ctl + 4, 1);
				put<uint32_t>(ctl + 8, pid);
				put<uint32_t>(ctl + 12, generation);
				put<uint32_t>(ctl + 16, w);
				put<uint32_t>(ctl + 20, h);
				put<uint32_t>(ctl + 24, kSets);
				put<uint32_t>(ctl + 28, kPlanes);
				put<uint32_t>(ctl + 76, 0);
				for (uint32_t s = 0; s < kSets; ++s)
					put<uint64_t>(ctl + 256 + s * 8, 0);
				MemoryBarrier();
				put<uint32_t>(ctl, kMagic);
				width = w;
				height = h;
				std::printf("shared %ux%u textures, generation %u\n", w, h, generation);
			}
		}
		if (!frames)
		{
			HANDLE m = OpenFileMappingW(FILE_MAP_READ, FALSE, L"Local\\MCPassthroughFrame");
			if (m)
				frames = static_cast<const volatile uint8_t *>(MapViewOfFile(m, FILE_MAP_READ, 0, 0, 4096));
		}
		if (frames && get<int64_t>(frames + 32) != lastPublish)
		{
			lastPublish = get<int64_t>(frames + 32);
			const int32_t slot = get<int32_t>(frames + 40);
			const volatile uint8_t *desc = frames + 256 + 128 * slot;
			const int32_t flags = get<int32_t>(desc + 44);
			if (!(flags & 64))
				++cpuFrames;
			else if (ready)
			{
				++gpuFrames;
				const uint32_t gen = get<uint32_t>(frames + 1024 + 32 * slot), set = get<uint32_t>(frames + 1024 + 32 * slot + 4);
				const uint64_t frame = uint64_t(get<int64_t>(desc + 8));
				if (gen == generation && set < kSets)
				{
					LARGE_INTEGER t0, t1;
					QueryPerformanceCounter(&t0);
					if (ready->GetCompletedValue() < frame)
					{
						ready->SetEventOnCompletion(frame, event);
						WaitForSingleObject(event, 1000);
					}
					QueryPerformanceCounter(&t1);
					fenceWaitMs += double(t1.QuadPart - t0.QuadPart) * 1000.0 / double(qpf.QuadPart);
					// copy colour and depth back
					alloc->Reset();
					list->Reset(alloc, nullptr);
					for (int i = 0; i < 2; ++i)
					{
						D3D12_TEXTURE_COPY_LOCATION dst = {readback[i], D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT};
						dst.PlacedFootprint = footprint[i];
						D3D12_TEXTURE_COPY_LOCATION src = {textures[set][i], D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX};
						src.SubresourceIndex = 0;
						list->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);
					}
					const bool avatar = (flags & 32) != 0 && kPlanes == 5;
					if (avatar)
					{
						D3D12_TEXTURE_COPY_LOCATION dst = {readback[2], D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT};
						dst.PlacedFootprint = footprint[0];
						D3D12_TEXTURE_COPY_LOCATION src = {textures[set][3], D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX};
						list->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);
					}
					list->Close();
					ID3D12CommandList *lists[] = {list};
					queue->ExecuteCommandLists(1, lists);
					queue->Signal(copyFence, ++copyValue);
					copyFence->SetEventOnCompletion(copyValue, event);
					WaitForSingleObject(event, 2000);
					put<uint64_t>(ctl + 256 + set * 8, frame); // the set goes back to Minecraft
					uint8_t *rgba = nullptr;
					float *depth = nullptr;
					D3D12_RANGE range = {0, SIZE_T(readbackBytes[0])};
					readback[0]->Map(0, &range, reinterpret_cast<void **>(&rgba));
					D3D12_RANGE drange = {0, SIZE_T(readbackBytes[1])};
					readback[1]->Map(0, &drange, reinterpret_cast<void **>(&depth));
					size_t drawn = 0, depthSet = 0, samples = 0;
					for (uint32_t y = 0; y < height; y += 8)
						for (uint32_t x = 0; x < width; x += 8)
						{
							++samples;
							drawn += rgba[size_t(y) * footprint[0].Footprint.RowPitch + x * 4 + 3] != 0;
							const float d = *reinterpret_cast<const float *>(reinterpret_cast<const uint8_t *>(depth) + size_t(y) * footprint[1].Footprint.RowPitch + x * 4);
							depthSet += d > 0.0f;
						}
					if (drawn > samples / 20 && depthSet > samples / 20)
						++good;
					else
						++empty;
					if (!saved && drawn > samples / 20)
					{
						saved = true;
						save_bmp("gpu_frame.bmp", rgba, width, height, footprint[0].Footprint.RowPitch);
						std::printf("frame %llu: %.1f%% colour, %.1f%% depth drawn (saved gpu_frame.bmp)\n", static_cast<unsigned long long>(frame),
							100.0 * drawn / samples, 100.0 * depthSet / samples);
					}
					readback[0]->Unmap(0, nullptr);
					readback[1]->Unmap(0, nullptr);
					if ((flags & 32) != 0)
						++avatarFrames;
					if (avatar)
					{
						uint8_t *body = nullptr;
						D3D12_RANGE arange = {0, SIZE_T(readbackBytes[2])};
						readback[2]->Map(0, &arange, reinterpret_cast<void **>(&body));
						size_t covered = 0;
						for (uint32_t y = 0; y < height; y += 4)
							for (uint32_t x = 0; x < width; x += 4)
								covered += body[size_t(y) * footprint[0].Footprint.RowPitch + x * 4 + 3] != 0;
						if (covered > 0)
							++avatarGood;
						if (!savedAvatar && covered > 0)
						{
							savedAvatar = true;
							save_bmp("gpu_avatar.bmp", body, width, height, footprint[0].Footprint.RowPitch);
							std::printf("frame %llu: avatar layer, %zu samples covered (saved gpu_avatar.bmp)\n", static_cast<unsigned long long>(frame), covered);
						}
						readback[2]->Unmap(0, nullptr);
					}
				}
			}
		}
		Sleep(1);
	}
	put<uint32_t>(ctl, 0); // gone: Minecraft falls back
	std::printf("GPU frames %d (good %d, empty %d), CPU frames %d, average fence wait %.2f ms; %u planes: avatar frames %d (drawn %d)\n", gpuFrames,
		good, empty, cpuFrames, gpuFrames ? fenceWaitMs / gpuFrames : 0.0, kPlanes, avatarFrames, avatarGood);
	const bool avatarOk = kPlanes == 5 ? avatarFrames > 0 && avatarGood == avatarFrames : avatarFrames == 0;
	std::printf(gpuFrames > 0 && good > 0 && empty == 0 && avatarOk ? "PASS\n" : "CHECK\n");
	return 0;
}
