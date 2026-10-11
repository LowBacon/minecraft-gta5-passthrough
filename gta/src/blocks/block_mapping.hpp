// Readers of Minecraft's block mesh mappings and the writer of the host's acknowledgement. Adapted from EldenCraft's
// block_mapping.hpp (MIT, Copyright (c) 2026 Adam Grzegorzewski).
#pragma once
#include "block_protocol.hpp"
#include <windows.h>
#include <array>
#include <atomic>

namespace mcpt::blocks
{
	class Mailbox
	{
		HANDLE mapping_{}, process_{};
		const std::uint8_t *view_{};
		std::uint32_t pid_{};
		std::uint64_t retry_{};
		Header cached_{};
		static std::uint64_t sequence(const std::uint8_t *p)
		{
			const auto value = *reinterpret_cast<const volatile std::uint64_t *>(p + 8);
			std::atomic_thread_fence(std::memory_order_seq_cst);
			return value;
		}

	public:
		Header header{};
		std::vector<std::uint8_t> payload;
		Mailbox() = default;
		Mailbox(const Mailbox &) = delete;
		Mailbox &operator=(const Mailbox &) = delete;
		~Mailbox() { close(); }
		// An odd or torn publication doesn't invalidate an earlier coherent one: keep it within its deadline, while its producer lives.
		bool retained(std::uint64_t now, std::uint32_t host) const
		{
			return process_ && pid_ == header.pid && WaitForSingleObject(process_, 0) == WAIT_TIMEOUT && header.fresh(now, host) &&
				   cached_.same_content(header);
		}
		void close()
		{
			if (view_)
				UnmapViewOfFile(view_);
			if (mapping_)
				CloseHandle(mapping_);
			if (process_)
				CloseHandle(process_);
			view_ = nullptr;
			mapping_ = process_ = nullptr;
			pid_ = 0;
			cached_ = {};
			header = {};
			payload.clear();
		}
		bool poll(const wchar_t *name, std::size_t capacity, std::uint32_t magic, std::uint64_t now, std::uint32_t host)
		{
			// release the named mapping even when a dead producer left an old, inactive or half-written header, or its
			// replacement couldn't get a fresh mapping while this reader keeps the old one alive
			if (process_ && WaitForSingleObject(process_, 0) != WAIT_TIMEOUT)
			{
				close();
				return false;
			}
			if (!view_)
			{
				if (now < retry_)
					return false;
				retry_ = now + 1000;
				mapping_ = OpenFileMappingW(FILE_MAP_READ, FALSE, name);
				if (!mapping_)
					return false;
				view_ = static_cast<const std::uint8_t *>(MapViewOfFile(mapping_, FILE_MAP_READ, 0, 0, capacity));
				if (!view_)
				{
					close();
					return false;
				}
			}
			const auto before = sequence(view_);
			if (!before || (before & 1))
			{
				if (!process_ && now >= retry_)
					close();
				return false;
			}
			std::array<std::uint8_t, header_bytes> copy{};
			std::memcpy(copy.data(), view_, copy.size());
			if (sequence(view_) != before)
				return false;
			Header next;
			if (!decode_header(copy, magic, next) || next.sequence != before)
			{
				close();
				return false;
			}
			if (next.pid != pid_)
			{
				if (process_)
					CloseHandle(process_);
				process_ = OpenProcess(SYNCHRONIZE, FALSE, next.pid);
				pid_ = next.pid;
			}
			if (!process_ || WaitForSingleObject(process_, 0) != WAIT_TIMEOUT)
			{
				close();
				return false;
			}
			// the heartbeat may advance while the header is copied: a pre-read timestamp mustn't revoke a coherent revision
			if (!next.fresh(GetTickCount64(), host))
			{
				header = next;
				return false;
			}
			const bool changed = !cached_.same_content(next);
			std::vector<std::uint8_t> incoming;
			if (changed)
			{
				incoming.resize(next.bytes);
				std::memcpy(incoming.data(), view_ + header_bytes, next.bytes);
			}
			std::atomic_thread_fence(std::memory_order_seq_cst);
			if (sequence(view_) != before)
				return false;
			if (changed)
			{
				if (magic == mesh_magic && !validate_vertices(incoming, next))
				{
					close();
					return false;
				}
				payload.swap(incoming);
				cached_ = next;
			}
			header = next;
			return true;
		}
	};

	class Reader
	{
	public:
		Mailbox mesh, atlas;
		bool retained(std::uint64_t now, std::uint32_t host) const
		{
			return mesh.retained(now, host) && atlas.retained(now, host) && compatible(mesh.header, atlas.header);
		}
		bool poll(std::uint64_t now, std::uint32_t host)
		{
			const bool m = mesh.poll(mesh_name, mesh_capacity, mesh_magic, now, host);
			const bool a = atlas.poll(atlas_name, atlas_capacity, atlas_magic, now, host);
			return m && a && compatible(mesh.header, atlas.header);
		}
	};

	// The revision this host holds, for Minecraft (BlockMeshMailbox.acknowledgement): fresh for 250 ms.
	class AckWriter
	{
		HANDLE mapping_{}, writer_{};
		std::uint8_t *view_{};
		std::uint64_t sequence_{};

	public:
		AckWriter() = default;
		AckWriter(const AckWriter &) = delete;
		AckWriter &operator=(const AckWriter &) = delete;
		~AckWriter() { destroy(); }
		void destroy()
		{
			clear();
			if (view_)
				UnmapViewOfFile(view_);
			if (mapping_)
				CloseHandle(mapping_);
			if (writer_)
			{
				ReleaseMutex(writer_);
				CloseHandle(writer_);
			}
			view_ = nullptr;
			mapping_ = writer_ = nullptr;
		}
		void publish(const Header &h, std::uint64_t now)
		{
			if (!view_)
			{
				if (!writer_)
				{
					writer_ = CreateMutexW(nullptr, FALSE, L"Local\\MCPassthroughBlockMeshAck.Writer");
					if (!writer_)
						return;
					const auto result = WaitForSingleObject(writer_, 0);
					if (result != WAIT_OBJECT_0 && result != WAIT_ABANDONED)
					{
						CloseHandle(writer_);
						writer_ = nullptr;
						return;
					}
				}
				mapping_ = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, header_bytes, ack_name);
				if (!mapping_)
					return;
				view_ = static_cast<std::uint8_t *>(MapViewOfFile(mapping_, FILE_MAP_WRITE, 0, 0, header_bytes));
				if (!view_)
				{
					CloseHandle(mapping_);
					mapping_ = nullptr;
					return;
				}
			}
			std::array<std::uint8_t, header_bytes> data{};
			auto put = [&](std::size_t at, auto value) { std::memcpy(data.data() + at, &value, sizeof(value)); };
			put(0, ack_magic);
			put(4, std::uint32_t(1));
			put(16, h.pid);
			put(20, h.host_pid);
			put(24, h.epoch);
			put(32, h.map);
			put(36, std::uint32_t(1));
			put(40, h.session);
			put(48, now);
			put(56, h.revision);
			put(64, h.atlas_revision);
			put(96, h.anchor);
			const auto next = sequence_ + 2;
			InterlockedExchange64(reinterpret_cast<volatile LONG64 *>(view_ + 8), static_cast<LONG64>(next - 1));
			std::memcpy(view_, data.data(), 8);
			std::memcpy(view_ + 16, data.data() + 16, header_bytes - 16);
			InterlockedExchange64(reinterpret_cast<volatile LONG64 *>(view_ + 8), static_cast<LONG64>(next));
			sequence_ = next;
		}
		void clear()
		{
			if (!view_)
				return;
			const auto next = sequence_ + 2;
			InterlockedExchange64(reinterpret_cast<volatile LONG64 *>(view_ + 8), static_cast<LONG64>(next - 1));
			const std::uint32_t inactive = 0;
			std::memcpy(view_ + 36, &inactive, sizeof(inactive));
			InterlockedExchange64(reinterpret_cast<volatile LONG64 *>(view_ + 8), static_cast<LONG64>(next));
			sequence_ = next;
		}
	};

	// Dynamic decorations share the strict mesh layout, with a shorter lease and smaller count, never across a producer or session.
	inline bool detail_ready(const Header &mesh, const Header &atlas, const Header &resident, std::uint64_t now)
	{
		return mesh.same_context(resident) && compatible(mesh, atlas) && mesh.flags == 1 && atlas.flags == 1 && now >= mesh.stamp &&
			   now - mesh.stamp <= 250 && now >= atlas.stamp && now - atlas.stamp <= 250 && mesh.count <= 16380 && mesh.cutout == 0 &&
			   atlas.count <= 256 && atlas.stride == atlas.count * 10 + 1;
	}
}
