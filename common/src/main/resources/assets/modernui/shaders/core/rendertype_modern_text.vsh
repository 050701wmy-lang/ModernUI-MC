#version 330
// This file is part of Modern UI. Licensed under LGPL-3.0-or-later.
// Keep the stage interface paired with ModernUI's fragments. Resource packs
// can add outputs to minecraft:core/text which Vulkan's rebinder compacts away
// on the fragment side, producing different varying locations.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#ifndef IS_GUI
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#endif

in vec3 Position;
in vec4 Color;
in vec2 UV0;
#ifndef IS_GUI
in ivec2 UV2;
uniform sampler2D Sampler2;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#endif
out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
#ifdef IS_GUI
    vertexColor = Color;
#else
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
#endif
}
