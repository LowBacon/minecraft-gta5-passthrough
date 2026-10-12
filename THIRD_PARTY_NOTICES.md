# Third-party notices

The mod is by LowBacon (see [LICENSE](LICENSE)). It contains code adapted from the two projects below, both under the MIT
license, whose notices follow.

## universal-modder

This mod started from the Minecraft x GTA V passthrough example in universal-modder
(https://github.com/rehan-remade/universal-modder, `examples/minecraft-gta5-passthrough`) by Rehan: the first Fabric mod,
ScriptHookV script, ReShade add-on and effect, which this project has since extended and partly rewritten.

```
MIT License

Copyright (c) 2026 Rehan and universal-modder contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
OR OTHER DEALINGS IN THE SOFTWARE.
```

## EldenCraft

Adapted from EldenCraft (https://github.com/SocketByte/EldenCraft):

- the GPU frame transport, from its ECGT v1 shared-texture transport: `mc/src/client/java/dev/rehan/passthrough/client/GpuTransport.java`,
  `gta/src/gpu_host.h`, `gta/src/gpu_host.cpp`;
- the avatar layer (the player drawn alone and shown camera-relative), from its HostAvatarRenderer, SceneLevelRendererMixin
  and the EcAvatar sample of EldenCraftPassthrough.fx: `mc/src/client/java/dev/rehan/passthrough/client/HostAvatarRenderer.java`,
  `mc/src/client/java/dev/rehan/passthrough/client/mixin/AvatarLayerMixin.java`, the avatar part of `gta/shaders/MCPassthrough.fx`.
- the native blocks (block mesh baking, transport, acknowledgement, atlas/lightmap/animation, mining cracks and outlines, and the
  renderer that draws them with the host camera), from its BlockMeshClient, BlockMeshMailbox, BlockMeshProtocol, BlockMeshHandoff,
  BlockMeshChanges, BlockMeshCoverage, MeshPayload, BlockMeshAnimation, BlockMeshFluids, BlockDetails, BlockCrackGeometry, the
  BlockMesh*/BlockDetails mixins, and compositor block_protocol/block_mapping/block_shader/block_sort/block_renderer:
  those files in `mc/src/client/java/dev/rehan/passthrough/client/` and `.../client/mixin/`, and `gta/src/blocks/`.

```
MIT License

Copyright (c) 2026 Adam Grzegorzewski

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
