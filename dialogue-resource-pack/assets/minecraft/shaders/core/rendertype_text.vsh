#version 150

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    if (abs(Color.a - 0.98039216) < 0.001) gl_Position.z = -0.999 * gl_Position.w;
    if (abs(Color.a - 0.96862745) < 0.001) gl_Position.z = -0.9998 * gl_Position.w;
    if (abs(Color.a - 0.97647059) < 0.001) gl_Position.z = -0.9999 * gl_Position.w;
    if (abs(Color.a - 0.97254902) < 0.001) gl_Position.z = -0.99995 * gl_Position.w;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
}
