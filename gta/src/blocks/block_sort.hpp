// Back-to-front order of the translucent triangles (water, glass, ice) along the camera's forward axis. Adapted from
// EldenCraft's block_sort.hpp (MIT, Copyright (c) 2026 Adam Grzegorzewski).
#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <span>
#include <utility>
#include <vector>

namespace mcpt::blocks
{
	using TriangleCenter = std::array<double, 3>;
	inline bool sort_view_changed(const std::array<float, 3> &a, const std::array<float, 3> &b)
	{
		double dot = 0;
		for (unsigned c = 0; c < 3; ++c)
			dot += double(a[c]) * b[c];
		return dot < .99999;
	}
	// The eye's translation subtracts the same depth from every triangle: only the forward axis changes this order.
	inline void translucent_order(std::span<const TriangleCenter> centers, const std::array<float, 3> &forward, std::uint32_t first,
		std::vector<std::pair<double, std::uint32_t>> &work, std::vector<std::uint32_t> &indices)
	{
		work.resize(centers.size());
		indices.resize(centers.size() * 3);
		for (std::size_t t = 0; t < centers.size(); ++t)
		{
			double depth = 0;
			for (unsigned c = 0; c < 3; ++c)
				depth += centers[t][c] * forward[c];
			work[t] = {depth, static_cast<std::uint32_t>(t)};
		}
		// the original stable order is the triangle number: an explicit tie break allows an allocation-free std::sort
		std::sort(work.begin(), work.end(), [](const auto &a, const auto &b) { return a.first > b.first || (a.first == b.first && a.second < b.second); });
		for (std::size_t t = 0; t < work.size(); ++t)
			for (unsigned v = 0; v < 3; ++v)
				indices[t * 3 + v] = first + work[t].second * 3 + v;
	}
}
