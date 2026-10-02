# Fabric 26.2 / native Blaze3D Vulkan port

This branch targets Minecraft 26.2, Java 25, Fabric Loader 0.19.3+,
Fabric API 0.158.0+ and Forge Config API Port 26.2.1+.
The standalone ModernUI core remains at 3.13.0; Arc3D remains at 2026.2.0.
Forge and NeoForge targets have not been ported on this branch.

## Build

```powershell
.\gradlew.bat -PfabricOnly :ModernUI-Fabric:build
```

Install `fabric/build/libs/ModernUI-Fabric-26.2-3.13.0.7-SNAPSHOT-universal.jar`.
The ordinary jar does not contain the required ModernUI runtime.
Neo Loom uses the upstream 1.17 snapshot coordinate (resolved to 1.17.11
during verification); Gradle is 9.5.1.

## Native Vulkan integration

- Arc3D borrows Minecraft's Vulkan device, graphics queue and VMA allocator.
  It advertises only the base device features Minecraft actually enables.
- UI rendering copies the Arc3D RGBA8 image into a Minecraft-owned texture
  entirely on the GPU. No CPU readback or replacement of private texture handles
  is needed. This adds a full UI image copy whenever the UI layer is composed.
- Submission order, image barriers and layout restoration bridge the two
  command encoders. Minecraft's deferred destruction queue retains the Arc3D
  source until the copy has completed. Minecraft owns the destination texture.
- Native Vulkan alpha-mask font atlases store white RGB and mask alpha in RGBA8,
  because Blaze3D does not expose component swizzling. This preserves vanilla
  and SDF shader semantics, at four bytes per glyph pixel. The atlas dimension
  limit is reduced to 4096 to preserve the previous 64 MiB memory ceiling.
- UI composition uses premultiplied alpha, including on OpenGL and VulkanMod.
- VulkanMod remains an optional compile-only integration, not a runtime dependency.

## Verification

`:ModernUI-Fabric:build` passed, including compilation, the test task and
access widener validation. There are no dedicated GPU regression tests.
`git diff --check` passed.
The user's Wynncraft vulkan run on 2026-10-02 initialized the native Vulkan
device and ModernUI renderer on an RTX 4060 Laptop GPU, with ImmediatelyFast
and Vitrail present. It subsequently failed compiling the GUI text pipeline
because a shader declared `Globals` without a corresponding pipeline layout.
The normal/SDF text snippets and tooltip pipeline now declare `Globals`, as
Minecraft's own pipelines do. In-game verification of this fix is pending.

Check the main menu, ModernUI settings screen, transparent edges, tooltips,
Chinese text, emoji, world text, resizing and resource reloads. Confirm the log
reports the Vulkan backend; Minecraft can fall back to OpenGL if Vulkan fails.

Development launch commands, for future debugging:

```powershell
.\gradlew.bat -PfabricOnly -PgraphicsBackend=vulkan -PvulkanValidation :ModernUI-Fabric:runClient
.\gradlew.bat -PfabricOnly -PgraphicsBackend=opengl :ModernUI-Fabric:runClient
```

## Upstream synchronization

`origin` is `050701wmy-lang/ModernUI-MC`; `upstream` is `BloCamLimb/ModernUI-MC`.
Keep the fork's `master` aligned with upstream and keep this port on
`fabric-26.2-vulkan`:

```powershell
git fetch upstream
git switch master
git merge --ff-only upstream/master
git push origin master
git switch fabric-26.2-vulkan
git merge upstream/master
```

Resolve any version/API conflicts on the port branch, rebuild and test before
pushing it. Never force-push the upstream repository.

## Attribution

Base: BloCamLimb/ModernUI-MC `4724428a0be612accbc596025e2620ab3e6522fb`.
The GUI, text, shader and build migration draws from
[drangonmc/ModernUI-Fabric26.2](https://github.com/drangonmc/ModernUI-Fabric26.2),
commit `73cac73`, under the retained LGPL-3.0-or-later license. That port's
documentation credits Chino081/ModernUI-MC commits `249c928` and `8c34833`.
The native Blaze3D Vulkan bridge, native font atlas handling and premultiplied
composition fixes are additional changes on this branch.
