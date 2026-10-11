# Third-party notices

## EldenCraft

Adapted from EldenCraft (https://github.com/SocketByte/EldenCraft):

- the GPU frame transport, from its ECGT v1 shared-texture transport: `mc/src/client/java/dev/rehan/passthrough/client/GpuTransport.java`,
  `gta/src/gpu_host.h`, `gta/src/gpu_host.cpp`;
- the avatar layer (the player drawn alone and shown camera-relative), from its HostAvatarRenderer, SceneLevelRendererMixin
  and the EcAvatar sample of EldenCraftPassthrough.fx: `mc/src/client/java/dev/rehan/passthrough/client/HostAvatarRenderer.java`,
  `mc/src/client/java/dev/rehan/passthrough/client/mixin/AvatarLayerMixin.java`, the avatar part of `gta/shaders/MCPassthrough.fx`.

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
