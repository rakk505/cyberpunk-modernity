package com.example.cyberdeck.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.core.Direction;

import org.joml.Vector3f;

/**
 * A single Bedrock cube compiled into six textured quads. Vertex layout and UV mapping mirror
 * TaCZ's {@code BedrockCubeBox}/{@code BedrockCubePerFace}, re-implemented against the Minecraft
 * 26.2 {@link VertexConsumer} API ({@code addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz)}).
 */
public final class BedrockCube {
    private final Polygon[] polygons = new Polygon[6];

    private BedrockCube() {}

    /** Box UV cube (single {@code uv:[u,v]} origin, faces laid out in the standard box unwrap). */
    public static BedrockCube box(float texOffX, float texOffY, float x, float y, float z,
                                  float width, float height, float depth, float delta,
                                  boolean mirror, float texWidth, float texHeight) {
        BedrockCube cube = new BedrockCube();
        float xEnd = x + width;
        float yEnd = y + height;
        float zEnd = z + depth;
        x -= delta;
        y -= delta;
        z -= delta;
        xEnd += delta;
        yEnd += delta;
        zEnd += delta;
        if (mirror) {
            float tmp = xEnd;
            xEnd = x;
            x = tmp;
        }
        Vertex v1 = new Vertex(x, y, z);
        Vertex v2 = new Vertex(xEnd, y, z);
        Vertex v3 = new Vertex(xEnd, yEnd, z);
        Vertex v4 = new Vertex(x, yEnd, z);
        Vertex v5 = new Vertex(x, y, zEnd);
        Vertex v6 = new Vertex(xEnd, y, zEnd);
        Vertex v7 = new Vertex(xEnd, yEnd, zEnd);
        Vertex v8 = new Vertex(x, yEnd, zEnd);

        int dx = (int) width;
        int dy = (int) height;
        int dz = (int) depth;
        float p1 = texOffX + dz;
        float p2 = texOffX + dz + dx;
        float p3 = texOffX + dz + dx + dx;
        float p4 = texOffX + dz + dx + dz;
        float p5 = texOffX + dz + dx + dz + dx;
        float p6 = texOffY + dz;
        float p7 = texOffY + dz + dy;
        float p8 = texOffY;
        float p9 = texOffX;

        cube.polygons[2] = Polygon.box(new Vertex[] {v6, v5, v1, v2}, p1, p8, p2, p6, texWidth, texHeight, mirror, Direction.DOWN);
        cube.polygons[3] = Polygon.box(new Vertex[] {v3, v4, v8, v7}, p2, p6, p3, p8, texWidth, texHeight, mirror, Direction.UP);
        cube.polygons[1] = Polygon.box(new Vertex[] {v1, v5, v8, v4}, p9, p6, p1, p7, texWidth, texHeight, mirror, Direction.WEST);
        cube.polygons[4] = Polygon.box(new Vertex[] {v2, v1, v4, v3}, p1, p6, p2, p7, texWidth, texHeight, mirror, Direction.NORTH);
        cube.polygons[0] = Polygon.box(new Vertex[] {v6, v2, v3, v7}, p2, p6, p4, p7, texWidth, texHeight, mirror, Direction.EAST);
        cube.polygons[5] = Polygon.box(new Vertex[] {v5, v6, v7, v8}, p4, p6, p5, p7, texWidth, texHeight, mirror, Direction.SOUTH);
        return cube;
    }

    /** Per-face UV cube (each face carries its own {@code uv}/{@code uv_size}). */
    public static BedrockCube perFace(float x, float y, float z, float width, float height,
                                      float depth, float delta, float texWidth, float texHeight,
                                      BedrockGeoData.FaceSet faces) {
        BedrockCube cube = new BedrockCube();
        float xEnd = x + width;
        float yEnd = y + height;
        float zEnd = z + depth;
        Vertex v1 = new Vertex(x -= delta, y -= delta, z -= delta);
        Vertex v2 = new Vertex(xEnd += delta, y, z);
        Vertex v3 = new Vertex(xEnd, yEnd += delta, z);
        Vertex v4 = new Vertex(x, yEnd, z);
        Vertex v5 = new Vertex(x, y, zEnd += delta);
        Vertex v6 = new Vertex(xEnd, y, zEnd);
        Vertex v7 = new Vertex(xEnd, yEnd, zEnd);
        Vertex v8 = new Vertex(x, yEnd, zEnd);

        cube.polygons[2] = Polygon.perFace(new Vertex[] {v6, v5, v1, v2}, texWidth, texHeight, Direction.DOWN, faces);
        cube.polygons[3] = Polygon.perFace(new Vertex[] {v3, v4, v8, v7}, texWidth, texHeight, Direction.UP, faces);
        cube.polygons[1] = Polygon.perFace(new Vertex[] {v1, v5, v8, v4}, texWidth, texHeight, Direction.WEST, faces);
        cube.polygons[4] = Polygon.perFace(new Vertex[] {v2, v1, v4, v3}, texWidth, texHeight, Direction.NORTH, faces);
        cube.polygons[0] = Polygon.perFace(new Vertex[] {v6, v2, v3, v7}, texWidth, texHeight, Direction.EAST, faces);
        cube.polygons[5] = Polygon.perFace(new Vertex[] {v5, v6, v7, v8}, texWidth, texHeight, Direction.SOUTH, faces);
        return cube;
    }

    public void compile(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, int color) {
        for (Polygon polygon : polygons) {
            if (polygon == null) {
                continue;
            }
            float nx = polygon.normal.x();
            float ny = polygon.normal.y();
            float nz = polygon.normal.z();
            for (TexVertex vertex : polygon.vertices) {
                float px = vertex.x / 16.0f;
                float py = vertex.y / 16.0f;
                float pz = vertex.z / 16.0f;
                // Use the buffer's own pose-aware vertex builder (same path as vanilla
                // ItemInHandRenderer): it transforms position by pose.pose() and the normal by
                // pose.normal(), avoiding any manual-matrix mismatch with the submission buffer.
                consumer.addVertex(pose, px, py, pz)
                        .setColor(color)
                        .setUv(vertex.u, vertex.v)
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal(pose, nx, ny, nz);
            }
        }
    }

    private record Vertex(float x, float y, float z) {}

    private record TexVertex(float x, float y, float z, float u, float v) {}

    private static final class Polygon {
        final TexVertex[] vertices;
        final Vector3f normal;

        private Polygon(TexVertex[] vertices, Vector3f normal) {
            this.vertices = vertices;
            this.normal = normal;
        }

        static Polygon box(Vertex[] pos, float u1, float v1, float u2, float v2,
                           float texWidth, float texHeight, boolean mirror, Direction direction) {
            float minU = u1 / texWidth;
            float minV = v1 / texHeight;
            float maxU = u2 / texWidth;
            float maxV = v2 / texHeight;
            return build(pos, minU, minV, maxU, maxV, direction, mirror);
        }

        static Polygon perFace(Vertex[] pos, float texWidth, float texHeight, Direction direction,
                               BedrockGeoData.FaceSet faces) {
            BedrockGeoData.Face face = faces == null ? null : faces.get(direction.getName());
            if (face == null) {
                // Face omitted in the geometry: render nothing for it.
                return null;
            }
            float u1 = face.uv[0];
            float v1 = face.uv[1];
            float u2 = u1 + face.uvSize[0];
            float v2 = v1 + face.uvSize[1];
            float minU = u1 / texWidth;
            float minV = v1 / texHeight;
            float maxU = u2 / texWidth;
            float maxV = v2 / texHeight;
            return build(pos, minU, minV, maxU, maxV, direction, false);
        }

        private static Polygon build(Vertex[] pos, float minU, float minV, float maxU, float maxV,
                                     Direction direction, boolean mirror) {
            // Standard box-quad UV assignment: (maxU,minV),(minU,minV),(minU,maxV),(maxU,maxV).
            TexVertex[] verts = new TexVertex[] {
                    new TexVertex(pos[0].x(), pos[0].y(), pos[0].z(), maxU, minV),
                    new TexVertex(pos[1].x(), pos[1].y(), pos[1].z(), minU, minV),
                    new TexVertex(pos[2].x(), pos[2].y(), pos[2].z(), minU, maxV),
                    new TexVertex(pos[3].x(), pos[3].y(), pos[3].z(), maxU, maxV),
            };
            if (mirror) {
                // Flip U for mirrored faces.
                for (int i = 0; i < verts.length; i++) {
                    TexVertex tv = verts[i];
                    verts[i] = new TexVertex(tv.x(), tv.y(), tv.z(), (minU + maxU) - tv.u(), tv.v());
                }
            }
            Vector3f n = new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ());
            return new Polygon(verts, n);
        }
    }
}
